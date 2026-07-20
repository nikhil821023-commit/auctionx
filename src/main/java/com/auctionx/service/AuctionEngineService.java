package com.auctionx.service;

import com.auctionx.dto.*;
import com.auctionx.model.*;
import com.auctionx.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionEngineService implements AuctionTimerService.TimerExpiredCallback {

    private final PlayerRepository        playerRepository;
    private final TeamRepository          teamRepository;
    private final TournamentRepository    tournamentRepository;
    private final AuctionResultRepository resultRepository;
    private final AuctionTimerService     timerService;
    private final DashboardService        dashboardService;
    private final SimpMessagingTemplate   messagingTemplate;

    private final ConcurrentHashMap<Long, AuctionState>         activeAuctions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Map<Long, Integer>>   bidWarTracker  = new ConcurrentHashMap<>();

    // Add to AuctionEngineService fields:
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // ═══════════════════════════════════════════════════════════════════
    // 1. INIT AUCTION
    // ═══════════════════════════════════════════════════════════════════

    public AuctionState initAuction(Long tournamentId, AuctionSettingsDTO settings) {

        if (activeAuctions.containsKey(tournamentId)) {
            AuctionState existing = activeAuctions.get(tournamentId);
            if (existing != null && existing.getIsRunning().get()) {
                log.info("⚠️ Auction already running for tournament {}, reusing", tournamentId);
                broadcastAuctionState(tournamentId, existing, "AUCTION_ALREADY_RUNNING");
                return existing;
            }
        }

        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new RuntimeException("Tournament not found: " + tournamentId));

        List<Player> players = playerRepository
                .findByTournamentIdAndStatus(tournamentId, Player.PlayerStatus.AVAILABLE);

        if (players == null || players.isEmpty()) {
            throw new RuntimeException(
                    "No AVAILABLE players found for tournament " + tournamentId +
                            ". Please add players first."
            );
        }

        boolean allowTierOrder = settings.getAllowTierOrder()       != null ? settings.getAllowTierOrder()       : true;
        int     bidTimer       = settings.getBidTimerSeconds()      != null ? settings.getBidTimerSeconds()      : 30;
        int     resetTimer     = settings.getBidTimerResetSeconds() != null ? settings.getBidTimerResetSeconds() : 10;
        boolean autoSpin       = settings.getAutoSpin()             != null ? settings.getAutoSpin()             : true;
        int     pauseBetween   = settings.getPauseBetweenPlayers()  != null ? settings.getPauseBetweenPlayers()  : 5;
        double  bidIncrement   = tournament.getBidIncrement()       != null ? tournament.getBidIncrement()       : 10.0;

        if (allowTierOrder) {
            players = sortByTier(players);
        } else {
            Collections.shuffle(players);
        }

        AuctionState state = AuctionState.builder()
                .tournamentId(tournamentId)
                .remainingPlayers(new ArrayList<>(players))
                .unsoldPlayers(new ArrayList<>())
                .soldResults(new ArrayList<>())
                .totalTimerSeconds(bidTimer)
                .resetTimerSeconds(resetTimer)
                .autoSpin(autoSpin)
                .pauseBetweenPlayersSeconds(pauseBetween)
                .tierOrderEnabled(allowTierOrder)
                .bidIncrement(bidIncrement)
                .phase(AuctionState.AuctionPhase.IDLE)
                .bidMode(BidMode.ORGANIZER_CONTROLLED)
                .auctionStartedAt(LocalDateTime.now())
                .build();

        state.getIsRunning().set(true);
        state.getIsPaused().set(false);
        state.getIsBidding().set(false);
        state.getRemainingSeconds().set(0);

        activeAuctions.put(tournamentId, state);
        bidWarTracker.put(tournamentId, new ConcurrentHashMap<>());

        log.info("✅ Auction INITIALISED — tournament={} players={} timer={}s",
                tournamentId, players.size(), bidTimer);

        broadcastAuctionState(tournamentId, state, "AUCTION_INIT");
        return state;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 2. SPIN WHEEL
    // ═══════════════════════════════════════════════════════════════════

    public SpinResultDTO spinWheel(Long tournamentId) {
        AuctionState state = getState(tournamentId);

        if (state.getRemainingPlayers().isEmpty()) {
            if (!state.getUnsoldPlayers().isEmpty()) {
                return startReAuction(tournamentId);
            }
            completeAuction(tournamentId);
            throw new RuntimeException("No more players to auction");
        }

        if (state.getPhase() == AuctionState.AuctionPhase.BIDDING) {
            throw new RuntimeException("Bidding is still in progress");
        }

        state.setPhase(AuctionState.AuctionPhase.SPINNING);

        Random random = new Random();
        int index = random.nextInt(state.getRemainingPlayers().size());
        Player selectedPlayer = state.getRemainingPlayers().get(index);

        // Broadcast spinning animation data
        messagingTemplate.convertAndSend(
                "/topic/auction/" + tournamentId + "/spin",
                Map.of(
                        "event",          "WHEEL_SPINNING",
                        "wheelStopIndex", index,
                        "totalPlayers",   state.getRemainingPlayers().size(),
                        "playerNames",    state.getRemainingPlayers()
                                .stream().map(Player::getName)
                                .collect(Collectors.toList())
                )
        );

        state.setCurrentPlayer(selectedPlayer);
        state.setCurrentBid(selectedPlayer.getBasePrice());
        state.setBidHistory(new ArrayList<>());
        state.setCurrentHighBidderTeamId(null);
        state.setCurrentHighBidderName(null);
        state.setCurrentHighBidderTeamName(null);
        state.setCurrentHighBidderTeamColor(null);
        state.setPhase(AuctionState.AuctionPhase.PLAYER_REVEAL);

        bidWarTracker.get(tournamentId).clear();

        SpinResultDTO result = buildSpinResult(selectedPlayer, index,
                state.getRemainingPlayers().size());

        // Broadcast player reveal
        messagingTemplate.convertAndSend(
                "/topic/auction/" + tournamentId + "/spin",
                Map.of(
                        "event",  "PLAYER_REVEALED",
                        "player", result,
                        "phase",  state.getPhase()
                )
        );

        log.info("🎰 Player revealed: {} ({})", selectedPlayer.getName(), selectedPlayer.getTier());
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 3. START BIDDING
    // ═══════════════════════════════════════════════════════════════════

    public void startBidding(Long tournamentId) {
        AuctionState state = getState(tournamentId);

        if (state.getCurrentPlayer() == null) {
            throw new RuntimeException("No player selected — spin the wheel first");
        }

        state.setPhase(AuctionState.AuctionPhase.BIDDING);
        state.getIsBidding().set(true);
        state.getIsPaused().set(false);

        timerService.startTimer(tournamentId, state, this);
        broadcastAuctionState(tournamentId, state, "BIDDING_STARTED");

        log.info("🔔 Bidding STARTED for {} — Base: ₹{}",
                state.getCurrentPlayer().getName(), state.getCurrentBid());
    }

    // ═══════════════════════════════════════════════════════════════════
    // 4. PLACE BID
    // ═══════════════════════════════════════════════════════════════════

    public AuctionStateDTO placeBid(BidRequestDTO dto) {
        AuctionState state = getState(dto.getTournamentId());

        if (state.getPhase() != AuctionState.AuctionPhase.BIDDING) {
            throw new RuntimeException("Bidding is not currently open");
        }
        if (state.getIsPaused().get()) {
            throw new RuntimeException("Auction is paused");
        }

        // Resolve bid amount
        double bidAmount;
        if (Boolean.TRUE.equals(dto.getUseAutoIncrement())) {
            bidAmount = state.getCurrentBid() + state.getBidIncrement();
        } else if (dto.getBidAmount() != null) {
            bidAmount = dto.getBidAmount();
        } else {
            throw new RuntimeException("Bid amount is required");
        }

        if (bidAmount <= state.getCurrentBid()) {
            throw new RuntimeException(
                    "Bid ₹" + bidAmount + " must be higher than current ₹" + state.getCurrentBid());
        }

        Player player = state.getCurrentPlayer();
        if (player != null && bidAmount < player.getBasePrice()) {
            throw new RuntimeException(
                    "Bid ₹" + bidAmount + " is below base price ₹" + player.getBasePrice());
        }

        Team team = teamRepository.findById(dto.getTeamId())
                .orElseThrow(() -> new RuntimeException("Team not found"));

        if (team.getRemainingBudget() < bidAmount) {
            throw new RuntimeException(
                    "Insufficient budget. Remaining: ₹" + team.getRemainingBudget());
        }

        // Validate captain token for self-bid mode
        if (dto.getBidMode() == BidMode.CAPTAIN_SELF) {
            String expected = generateCaptainToken(dto.getTeamId(), dto.getTournamentId());
            if (!expected.equals(dto.getCaptainToken())) {
                throw new RuntimeException("Invalid captain token — unauthorized bid");
            }
        }

        // Record bid
        BidRecord record = BidRecord.builder()
                .teamId(dto.getTeamId())
                .teamName(team.getTeamName())
                .captainName(team.getCaptainName())
                .teamColor(team.getTeamColor())
                .amount(bidAmount)
                .timestamp(LocalDateTime.now())
                .timerSnapshotSeconds(state.getRemainingSeconds().get())
                .build();

        state.getBidHistory().add(record);
        state.setCurrentBid(bidAmount);
        state.setCurrentHighBidderTeamId(dto.getTeamId());
        state.setCurrentHighBidderName(team.getCaptainName());
        state.setCurrentHighBidderTeamName(team.getTeamName());
        state.setCurrentHighBidderTeamColor(team.getTeamColor());

        timerService.resetTimer(dto.getTournamentId(), state);

        boolean isBidWar = detectBidWar(dto.getTournamentId(), dto.getTeamId());

        AuctionStateDTO stateDTO = buildAuctionStateDTO(state, "BID_PLACED");
        stateDTO.setIsBidWar(isBidWar);
        if (isBidWar) {
            // Find the other team involved in the war
            String opponentName = state.getBidHistory().stream()
                    .filter(b -> !b.getTeamId().equals(dto.getTeamId()))
                    .reduce((first, second) -> second) // last bid from different team
                    .map(BidRecord::getTeamName)
                    .orElse("opponent");
            stateDTO.setAlertMessage(
                    team.getTeamName() + " vs " + opponentName);
        }

        messagingTemplate.convertAndSend("/topic/auction/" + dto.getTournamentId(), stateDTO);

        log.info("💰 [{}] Bid ₹{} by {} for {}",
                dto.getBidMode(), bidAmount, team.getCaptainName(),
                player != null ? player.getName() : "?");

        return stateDTO;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 5. TIMER EXPIRED → AUTO SOLD / UNSOLD
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void onTimerExpired(Long tournamentId) {
        AuctionState state = getState(tournamentId);
        if (state.getCurrentHighBidderTeamId() != null) {
            soldPlayer(tournamentId);
        } else {
            markUnsold(tournamentId);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 6. SOLD PLAYER
    // ═══════════════════════════════════════════════════════════════════

    public AuctionStateDTO soldPlayer(Long tournamentId) {
        AuctionState state = getState(tournamentId);
        timerService.cancelTimer(tournamentId);

        Player player      = state.getCurrentPlayer();
        Long winnerTeamId  = state.getCurrentHighBidderTeamId();
        Double soldPrice   = state.getCurrentBid();

        if (player == null || winnerTeamId == null) {
            throw new RuntimeException("No active bidding session to close");
        }

        Team winnerTeam = teamRepository.findById(winnerTeamId)
                .orElseThrow(() -> new RuntimeException("Winning team not found"));

        // Update player
        player.setStatus(Player.PlayerStatus.SOLD);
        player.setSoldPrice(soldPrice);
        player.setTeam(winnerTeam);
        playerRepository.save(player);

        // Update team budget
        winnerTeam.setSpentBudget(winnerTeam.getSpentBudget() + soldPrice);
        winnerTeam.setRemainingBudget(winnerTeam.getRemainingBudget() - soldPrice);
        teamRepository.save(winnerTeam);

        // Persist result
        AuctionResult result = AuctionResult.builder()
                .tournamentId(tournamentId)
                .playerId(player.getId())
                .playerName(player.getName())
                .playerRole(player.getRole())
                .playerPhotoPath(player.getPhotoPath())
                .playerTier(player.getTier() != null ? player.getTier().name() : "")
                .teamId(winnerTeam.getId())
                .teamName(winnerTeam.getTeamName())
                .captainName(winnerTeam.getCaptainName())
                .teamColor(winnerTeam.getTeamColor())
                .basePrice(player.getBasePrice())
                .soldPrice(soldPrice)
                .totalBids(state.getBidHistory().size())
                .status(AuctionResult.ResultStatus.SOLD)
                .soldAt(LocalDateTime.now())
                .build();

        // ✅ Save bid timeline as JSON when player is sold
        try {
            List<Map<String, Object>> timeline = state.getBidHistory()
                    .stream()
                    .map(b -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("teamName",  b.getTeamName());
                        entry.put("teamColor", b.getTeamColor() != null
                                ? b.getTeamColor() : "#888");
                        entry.put("amount",    b.getAmount());
                        entry.put("second",    b.getTimerSnapshotSeconds());
                        entry.put("isWinner",  b.getTeamId().equals(winnerTeamId));
                        return entry;
                    })
                    .collect(Collectors.toList());

            ObjectMapper mapper = new ObjectMapper();
            result.setBidTimeline(mapper.writeValueAsString(timeline));
            result.setOpeningBid(state.getBidHistory().isEmpty()
                    ? soldPrice : state.getBidHistory().get(0).getAmount());
            result.setFinalBid(soldPrice);

        } catch (Exception e) {
            log.warn("Could not serialize bid timeline: {}", e.getMessage());
        }



        resultRepository.save(result);
        state.getSoldResults().add(result);
        state.getRemainingPlayers().remove(player);
        state.setPhase(AuctionState.AuctionPhase.SOLD);
        state.getIsBidding().set(false);

        log.info("🔨 SOLD: {} → {} for ₹{}", player.getName(), winnerTeam.getTeamName(), soldPrice);

        // ✅ Build DTO BEFORE clearing — so team name/color are still in state
        AuctionStateDTO stateDTO = buildAuctionStateDTO(state, "PLAYER_SOLD");
        messagingTemplate.convertAndSend("/topic/auction/" + tournamentId, stateDTO);

        DashboardDTO dashboard = dashboardService.buildDashboard(tournamentId);
        messagingTemplate.convertAndSend("/topic/dashboard/" + tournamentId, dashboard);

        // Clear AFTER broadcast
        clearCurrentPlayer(state);

        if (state.getAutoSpin() && !state.getRemainingPlayers().isEmpty()) {
            scheduleAutoSpin(tournamentId, state.getPauseBetweenPlayersSeconds());
        }

        return stateDTO;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 7. MARK UNSOLD
    // ═══════════════════════════════════════════════════════════════════

    public AuctionStateDTO markUnsold(Long tournamentId) {
        AuctionState state = getState(tournamentId);
        timerService.cancelTimer(tournamentId);

        Player player = state.getCurrentPlayer();
        if (player == null) {
            throw new RuntimeException("No current player to mark unsold");
        }

        player.setStatus(Player.PlayerStatus.UNSOLD);
        playerRepository.save(player);

        AuctionResult result = AuctionResult.builder()
                .tournamentId(tournamentId)
                .playerId(player.getId())
                .playerName(player.getName())
                .playerRole(player.getRole())
                .playerTier(player.getTier() != null ? player.getTier().name() : "")
                .basePrice(player.getBasePrice())
                .soldPrice(0.0)
                .totalBids(0)
                .status(AuctionResult.ResultStatus.UNSOLD)
                .soldAt(LocalDateTime.now())
                .build();

        resultRepository.save(result);

        state.getRemainingPlayers().remove(player);
        state.getUnsoldPlayers().add(player);
        state.setPhase(AuctionState.AuctionPhase.UNSOLD);
        state.getIsBidding().set(false);

        log.info("❌ UNSOLD: {}", player.getName());

        AuctionStateDTO stateDTO = buildAuctionStateDTO(state, "PLAYER_UNSOLD");
        messagingTemplate.convertAndSend("/topic/auction/" + tournamentId, stateDTO);

        DashboardDTO dashboard = dashboardService.buildDashboard(tournamentId);
        messagingTemplate.convertAndSend("/topic/dashboard/" + tournamentId, dashboard);

        clearCurrentPlayer(state);

        if (state.getAutoSpin() && !state.getRemainingPlayers().isEmpty()) {
            scheduleAutoSpin(tournamentId, state.getPauseBetweenPlayersSeconds());
        }

        return stateDTO;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 8. PAUSE / RESUME
    // ═══════════════════════════════════════════════════════════════════

    public void pauseAuction(Long tournamentId) {
        AuctionState state = getState(tournamentId);
        state.getIsPaused().set(true);
        state.setPhase(AuctionState.AuctionPhase.PAUSED);
        timerService.pauseTimer(tournamentId, state);
        messagingTemplate.convertAndSend("/topic/auction/" + tournamentId,
                buildAuctionStateDTO(state, "AUCTION_PAUSED"));
        log.info("⏸ Auction PAUSED for tournament {}", tournamentId);
    }

    public void resumeAuction(Long tournamentId) {
        AuctionState state = getState(tournamentId);
        state.getIsPaused().set(false);
        state.setPhase(AuctionState.AuctionPhase.BIDDING);
        timerService.resumeTimer(tournamentId, state);
        messagingTemplate.convertAndSend("/topic/auction/" + tournamentId,
                buildAuctionStateDTO(state, "AUCTION_RESUMED"));
        log.info("▶ Auction RESUMED for tournament {}", tournamentId);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 9. RE-AUCTION
    // ═══════════════════════════════════════════════════════════════════

    public SpinResultDTO startReAuction(Long tournamentId) {
        AuctionState state = getState(tournamentId);

        if (state.getUnsoldPlayers().isEmpty()) {
            throw new RuntimeException("No unsold players to re-auction");
        }

        state.getUnsoldPlayers().forEach(p -> {
            p.setStatus(Player.PlayerStatus.AVAILABLE);
            playerRepository.save(p);
        });

        state.getRemainingPlayers().addAll(state.getUnsoldPlayers());
        state.getUnsoldPlayers().clear();
        state.setPhase(AuctionState.AuctionPhase.RE_AUCTION);

        messagingTemplate.convertAndSend("/topic/auction/" + tournamentId,
                Map.of(
                        "event",       "RE_AUCTION_STARTED",
                        "playerCount", state.getRemainingPlayers().size(),
                        "message",     "Re-auction starting for unsold players!"
                )
        );

        log.info("🔄 RE-AUCTION started — {} players", state.getRemainingPlayers().size());
        return spinWheel(tournamentId);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 10. COMPLETE AUCTION
    // ═══════════════════════════════════════════════════════════════════

    public void completeAuction(Long tournamentId) {
        AuctionState state = getState(tournamentId);
        timerService.cancelTimer(tournamentId);

        state.setPhase(AuctionState.AuctionPhase.COMPLETED);
        state.getIsRunning().set(false);

        tournamentRepository.findById(tournamentId).ifPresent(t -> {
            t.setStatus(Tournament.TournamentStatus.COMPLETED);
            tournamentRepository.save(t);
        });

        DashboardDTO finalDashboard = dashboardService.buildDashboard(tournamentId);

        messagingTemplate.convertAndSend("/topic/auction/" + tournamentId,
                Map.of(
                        "event",     "AUCTION_COMPLETED",
                        "message",   "🏆 Auction Complete!",
                        "dashboard", finalDashboard
                )
        );
        messagingTemplate.convertAndSend("/topic/dashboard/" + tournamentId, finalDashboard);

        log.info("🏆 AUCTION COMPLETED for tournament {}", tournamentId);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 11. BID MODE
    // ═══════════════════════════════════════════════════════════════════

    public void setBidMode(Long tournamentId, BidMode mode) {
        AuctionState state = getState(tournamentId);
        state.setBidMode(mode);
        messagingTemplate.convertAndSend(
                "/topic/auction/" + tournamentId,
                Map.of("event", "BID_MODE_CHANGED", "bidMode", mode.name())
        );
        log.info("Bid mode → {} for tournament {}", mode, tournamentId);
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    public AuctionState getState(Long tournamentId) {
        AuctionState state = activeAuctions.get(tournamentId);
        if (state == null) {
            throw new RuntimeException("No active auction for tournament " + tournamentId);
        }
        return state;
    }

    public String generateCaptainToken(Long teamId, Long tournamentId) {
        return Integer.toHexString(
                (teamId + "_" + tournamentId + "_auctionx").hashCode()
        );
    }

    // ✅ FIX: build DTO BEFORE clearing state — called before clearCurrentPlayer
    private AuctionStateDTO buildAuctionStateDTO(AuctionState state, String event) {
        Player p = state.getCurrentPlayer();

        List<BidRecord> recentBids = state.getBidHistory() != null
                ? state.getBidHistory().stream()
                .sorted(Comparator.comparing(BidRecord::getTimestamp).reversed())
                .limit(10)
                .collect(Collectors.toList())
                : List.of();

        return AuctionStateDTO.builder()
                .tournamentId(state.getTournamentId())
                .event(event)
                .currentPlayerId(p != null ? p.getId()           : null)
                .currentPlayerName(p != null ? p.getName()       : null)
                .currentPlayerRole(p != null ? p.getRole()       : null)
                .currentPlayerPhoto(p != null ? p.getPhotoPath() : null)
                .currentPlayerTier(p != null && p.getTier() != null
                        ? p.getTier().name() : null)
                .currentPlayerBasePrice(p != null ? p.getBasePrice() : null)
                .currentBid(state.getCurrentBid())
                .highBidderTeamId(state.getCurrentHighBidderTeamId())
                .highBidderCaptainName(state.getCurrentHighBidderName())
                .highBidderTeamName(state.getCurrentHighBidderTeamName())    // ✅ FIXED
                .highBidderTeamColor(state.getCurrentHighBidderTeamColor())  // ✅ FIXED
                .remainingSeconds(state.getRemainingSeconds().get())
                .totalTimerSeconds(state.getTotalTimerSeconds())
                .phase(state.getPhase())
                .recentBids(recentBids)
                .playersRemaining(state.getRemainingPlayers().size())
                .playersSold((int) state.getSoldResults().stream()
                        .filter(r -> r.getStatus() == AuctionResult.ResultStatus.SOLD)
                        .count())
                .playersUnsold(state.getUnsoldPlayers().size())
                .autoSpin(state.getAutoSpin())
                .isBidWar(false)
                .build();
    }

    // ✅ FIX: clear team name/color too
    private void clearCurrentPlayer(AuctionState state) {
        state.setCurrentPlayer(null);
        state.setCurrentBid(null);
        state.setCurrentHighBidderTeamId(null);
        state.setCurrentHighBidderName(null);
        state.setCurrentHighBidderTeamName(null);   // ✅ FIXED
        state.setCurrentHighBidderTeamColor(null);  // ✅ FIXED
        state.setBidHistory(new ArrayList<>());
    }

    private void broadcastAuctionState(Long tournamentId, AuctionState state, String event) {
        messagingTemplate.convertAndSend(
                "/topic/auction/" + tournamentId,
                buildAuctionStateDTO(state, event)
        );
    }

    private SpinResultDTO buildSpinResult(Player p, int index, int total) {
        return SpinResultDTO.builder()
                .playerId(p.getId())
                .playerName(p.getName())
                .playerRole(p.getRole())
                .playerPhotoPath(p.getPhotoPath())
                .playerTier(p.getTier() != null ? p.getTier().name() : "BRONZE")
                .basePrice(p.getBasePrice())
                .age(p.getAge())
                .nationality(p.getNationality())
                .matches(p.getMatches())
                .average(p.getAverage())
                .strikeRate(p.getStrikeRate())
                .wheelStopIndex(index)
                .totalPlayersLeft(total)
                .build();
    }

    private List<Player> sortByTier(List<Player> players) {
        Map<Player.PlayerTier, Integer> order = Map.of(
                Player.PlayerTier.PLATINUM, 0,
                Player.PlayerTier.GOLD,     1,
                Player.PlayerTier.SILVER,   2,
                Player.PlayerTier.BRONZE,   3
        );
        return players.stream()
                .sorted(Comparator.comparingInt(p -> order.getOrDefault(p.getTier(), 4)))
                .collect(Collectors.toList());
    }

    private boolean detectBidWar(Long tournamentId, Long teamId) {
        Map<Long, Integer> tracker = bidWarTracker.get(tournamentId);
        if (tracker == null) return false;
        tracker.merge(teamId, 1, Integer::sum);
        long teamsAbove2Bids = tracker.values().stream()
                .filter(count -> count >= 2).count();
        return teamsAbove2Bids >= 2;
    }

    private void scheduleAutoSpin(Long tournamentId, int delaySeconds) {
        new Thread(() -> {
            try {
                Thread.sleep(delaySeconds * 1000L);
                if (activeAuctions.containsKey(tournamentId)) {
                    spinWheel(tournamentId);
                    Thread.sleep(3000);
                    startBidding(tournamentId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
}