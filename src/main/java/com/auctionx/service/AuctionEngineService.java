package com.auctionx.service;

import com.auctionx.dto.*;
import com.auctionx.model.*;
import com.auctionx.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Core auction brain.
 * Controls spin → reveal → bid → sold/unsold → next player cycle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionEngineService implements AuctionTimerService.TimerExpiredCallback {

    private final PlayerRepository       playerRepository;
    private final TeamRepository         teamRepository;
    private final TournamentRepository   tournamentRepository;
    private final AuctionResultRepository resultRepository;
    private final AuctionTimerService    timerService;
    private final DashboardService       dashboardService;
    private final SimpMessagingTemplate  messagingTemplate;

    // Live auction states — one per tournament
    private final ConcurrentHashMap<Long, AuctionState> activeAuctions
            = new ConcurrentHashMap<>();

    // Bid war detection: teamId → recent bid count
    private final ConcurrentHashMap<Long, Map<Long, Integer>> bidWarTracker
            = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════════
    // 1. INITIALISE AUCTION (called from LobbyService.startAuction)
    // ═══════════════════════════════════════════════════════════════════

    public AuctionState initAuction(Long tournamentId, AuctionSettingsDTO settings) {

        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new RuntimeException("Tournament not found"));

        // Load all available players, sorted by tier if enabled
        List<Player> players = playerRepository
                .findByTournamentIdAndStatus(tournamentId, Player.PlayerStatus.AVAILABLE);

        if (settings.getAllowTierOrder() != null && settings.getAllowTierOrder()) {
            players = sortByTier(players);
        } else {
            Collections.shuffle(players); // random order
        }

        AuctionState state = AuctionState.builder()
                .tournamentId(tournamentId)
                .remainingPlayers(new ArrayList<>(players))
                .unsoldPlayers(new ArrayList<>())
                .soldResults(new ArrayList<>())
                .totalTimerSeconds(settings.getBidTimerSeconds() != null
                        ? settings.getBidTimerSeconds() : 30)
                .resetTimerSeconds(settings.getBidTimerResetSeconds() != null
                        ? settings.getBidTimerResetSeconds() : 10)
                .autoSpin(settings.getAutoSpin() != null ? settings.getAutoSpin() : true)
                .pauseBetweenPlayersSeconds(settings.getPauseBetweenPlayers() != null
                        ? settings.getPauseBetweenPlayers() : 5)
                .tierOrderEnabled(settings.getAllowTierOrder() != null
                        ? settings.getAllowTierOrder() : true)
                .bidIncrement(tournament.getBidIncrement() != null
                        ? tournament.getBidIncrement() : 10.0)
                .phase(AuctionState.AuctionPhase.IDLE)
                .auctionStartedAt(LocalDateTime.now())
                .build();

        state.getIsRunning().set(true);
        activeAuctions.put(tournamentId, state);
        bidWarTracker.put(tournamentId, new ConcurrentHashMap<>());

        log.info("🏏 Auction INITIALISED for tournament {} — {} players loaded",
                tournamentId, players.size());

        // Broadcast init event
        broadcastAuctionState(tournamentId, state, "AUCTION_INIT");

        return state;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 2. SPIN THE WHEEL
    // ═══════════════════════════════════════════════════════════════════

    public SpinResultDTO spinWheel(Long tournamentId) {
        AuctionState state = getState(tournamentId);

        if (state.getRemainingPlayers().isEmpty()) {
            // Check if there are unsold players to re-auction
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

        // Pick random player from remaining pool
        Random random = new Random();
        int index = random.nextInt(state.getRemainingPlayers().size());
        Player selectedPlayer = state.getRemainingPlayers().get(index);

        // Broadcast SPINNING event with wheel animation data
        messagingTemplate.convertAndSend(
                "/topic/auction/" + tournamentId + "/spin",
                Map.of(
                        "event",           "WHEEL_SPINNING",
                        "wheelStopIndex",  index,
                        "totalPlayers",    state.getRemainingPlayers().size(),
                        "playerNames",     state.getRemainingPlayers()
                                .stream().map(Player::getName)
                                .collect(Collectors.toList())
                )
        );

        // After spin, reveal player (small delay handled on frontend)
        state.setCurrentPlayer(selectedPlayer);
        state.setCurrentBid(selectedPlayer.getBasePrice());
        state.setBidHistory(new ArrayList<>());
        state.setCurrentHighBidderTeamId(null);
        state.setCurrentHighBidderName(null);
        state.setPhase(AuctionState.AuctionPhase.PLAYER_REVEAL);

        // Reset bid war tracker for this player
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

        log.info("🎰 Wheel spun — Player revealed: {} ({})",
                selectedPlayer.getName(), selectedPlayer.getTier());

        return result;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 3. START BIDDING (after player reveal)
    // ═══════════════════════════════════════════════════════════════════

    public void startBidding(Long tournamentId) {
        AuctionState state = getState(tournamentId);

        if (state.getCurrentPlayer() == null) {
            throw new RuntimeException("No player selected — spin the wheel first");
        }

        state.setPhase(AuctionState.AuctionPhase.BIDDING);
        state.getIsBidding().set(true);
        state.getIsPaused().set(false);

        // Start countdown timer
        timerService.startTimer(tournamentId, state, this);

        // Broadcast bidding opened
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

        // Calculate bid amount
        double bidAmount;
        if (dto.getUseAutoIncrement() != null && dto.getUseAutoIncrement()) {
            bidAmount = state.getCurrentBid() + state.getBidIncrement();
        } else {
            bidAmount = dto.getBidAmount();
        }

        // Validate bid
        if (bidAmount <= state.getCurrentBid()) {
            throw new RuntimeException("Bid must be higher than current bid: ₹" + state.getCurrentBid());
        }

        // Check team budget
        Team team = teamRepository.findById(dto.getTeamId())
                .orElseThrow(() -> new RuntimeException("Team not found"));

        if (team.getRemainingBudget() < bidAmount) {
            throw new RuntimeException("Insufficient budget. Remaining: ₹" + team.getRemainingBudget());
        }

        // Record the bid
        BidRecord bidRecord = BidRecord.builder()
                .teamId(dto.getTeamId())
                .teamName(dto.getTeamName())
                .captainName(dto.getCaptainName())
                .teamColor(dto.getTeamColor())
                .amount(bidAmount)
                .timestamp(LocalDateTime.now())
                .timerSnapshotSeconds(state.getRemainingSeconds().get())
                .build();

        state.getBidHistory().add(bidRecord);
        state.setCurrentBid(bidAmount);
        state.setCurrentHighBidderTeamId(dto.getTeamId());
        state.setCurrentHighBidderName(dto.getCaptainName());

        // Reset timer on new bid
        timerService.resetTimer(dto.getTournamentId(), state);

        // Bid war detection
        boolean isBidWar = detectBidWar(dto.getTournamentId(), dto.getTeamId());

        // Build broadcast payload
        AuctionStateDTO stateDTO = buildAuctionStateDTO(state, "BID_PLACED");
        stateDTO.setIsBidWar(isBidWar);
        if (isBidWar) {
            stateDTO.setAlertMessage("🔥 BID WAR! " + state.getCurrentHighBidderName() + " takes the lead!");
        }

        messagingTemplate.convertAndSend(
                "/topic/auction/" + dto.getTournamentId(),
                stateDTO
        );

        log.info("💰 Bid placed: ₹{} by {} for {}",
                bidAmount, dto.getCaptainName(), state.getCurrentPlayer().getName());

        return stateDTO;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 5. TIMER EXPIRED CALLBACK → AUTO SOLD
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void onTimerExpired(Long tournamentId) {
        AuctionState state = getState(tournamentId);

        if (state.getCurrentHighBidderTeamId() != null) {
            // Someone bid → SOLD
            soldPlayer(tournamentId);
        } else {
            // No bids → UNSOLD
            markUnsold(tournamentId);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 6. SOLD PLAYER
    // ═══════════════════════════════════════════════════════════════════

    public AuctionStateDTO soldPlayer(Long tournamentId) {
        AuctionState state = getState(tournamentId);
        timerService.cancelTimer(tournamentId);

        Player player = state.getCurrentPlayer();
        Long winnerTeamId = state.getCurrentHighBidderTeamId();
        Double soldPrice  = state.getCurrentBid();

        if (player == null || winnerTeamId == null) {
            throw new RuntimeException("No active bidding session to close");
        }

        Team winnerTeam = teamRepository.findById(winnerTeamId)
                .orElseThrow(() -> new RuntimeException("Winning team not found"));

        // ── Update Player ─────────────────────────────────────────────
        player.setStatus(Player.PlayerStatus.SOLD);
        player.setSoldPrice(soldPrice);
        player.setTeam(winnerTeam);
        playerRepository.save(player);

        // ── Update Team Budget ────────────────────────────────────────
        winnerTeam.setSpentBudget(winnerTeam.getSpentBudget() + soldPrice);
        winnerTeam.setRemainingBudget(winnerTeam.getRemainingBudget() - soldPrice);
        teamRepository.save(winnerTeam);

        // ── Persist Auction Result ────────────────────────────────────
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

        resultRepository.save(result);
        state.getSoldResults().add(result);

        // ── Remove from remaining pool ────────────────────────────────
        state.getRemainingPlayers().remove(player);

        // ── Update auction state ──────────────────────────────────────
        state.setPhase(AuctionState.AuctionPhase.SOLD);
        state.getIsBidding().set(false);

        log.info("🔨 SOLD: {} → {} for ₹{}", player.getName(),
                winnerTeam.getTeamName(), soldPrice);

        // Broadcast SOLD event
        AuctionStateDTO stateDTO = buildAuctionStateDTO(state, "PLAYER_SOLD");
        messagingTemplate.convertAndSend("/topic/auction/" + tournamentId, stateDTO);

        // Broadcast updated dashboard
        DashboardDTO dashboard = dashboardService.buildDashboard(tournamentId);
        messagingTemplate.convertAndSend("/topic/dashboard/" + tournamentId, dashboard);

        // Reset current player
        clearCurrentPlayer(state);

        // Auto-spin after pause if enabled
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

        // Persist unsold result
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
    // 9. RE-AUCTION UNSOLD PLAYERS
    // ═══════════════════════════════════════════════════════════════════

    public SpinResultDTO startReAuction(Long tournamentId) {
        AuctionState state = getState(tournamentId);

        if (state.getUnsoldPlayers().isEmpty()) {
            throw new RuntimeException("No unsold players to re-auction");
        }

        // Move unsold players back to remaining pool
        state.getUnsoldPlayers().forEach(p -> {
            p.setStatus(Player.PlayerStatus.AVAILABLE);
            playerRepository.save(p);
        });

        state.getRemainingPlayers().addAll(state.getUnsoldPlayers());
        state.getUnsoldPlayers().clear();
        state.setPhase(AuctionState.AuctionPhase.RE_AUCTION);

        messagingTemplate.convertAndSend("/topic/auction/" + tournamentId,
                Map.of(
                        "event",         "RE_AUCTION_STARTED",
                        "playerCount",   state.getRemainingPlayers().size(),
                        "message",       "Re-auction starting for unsold players!"
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

        // Update tournament status
        tournamentRepository.findById(tournamentId).ifPresent(t -> {
            t.setStatus(Tournament.TournamentStatus.COMPLETED);
            tournamentRepository.save(t);
        });

        // Build final dashboard
        DashboardDTO finalDashboard = dashboardService.buildDashboard(tournamentId);

        messagingTemplate.convertAndSend("/topic/auction/" + tournamentId,
                Map.of(
                        "event",     "AUCTION_COMPLETED",
                        "message",   "🏆 Auction Complete! All players have been auctioned.",
                        "dashboard", finalDashboard
                )
        );

        messagingTemplate.convertAndSend(
                "/topic/dashboard/" + tournamentId, finalDashboard);

        log.info("🏆 AUCTION COMPLETED for tournament {}", tournamentId);
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

    private void clearCurrentPlayer(AuctionState state) {
        state.setCurrentPlayer(null);
        state.setCurrentBid(null);
        state.setCurrentHighBidderTeamId(null);
        state.setCurrentHighBidderName(null);
        state.setBidHistory(new ArrayList<>());
    }

    private void broadcastAuctionState(Long tournamentId,
                                       AuctionState state, String event) {
        messagingTemplate.convertAndSend(
                "/topic/auction/" + tournamentId,
                buildAuctionStateDTO(state, event)
        );
    }

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
                .currentPlayerId(p != null ? p.getId()          : null)
                .currentPlayerName(p != null ? p.getName()      : null)
                .currentPlayerRole(p != null ? p.getRole()      : null)
                .currentPlayerPhoto(p != null ? p.getPhotoPath(): null)
                .currentPlayerTier(p != null && p.getTier() != null
                        ? p.getTier().name() : null)
                .currentPlayerBasePrice(p != null ? p.getBasePrice() : null)
                .currentBid(state.getCurrentBid())
                .highBidderTeamId(state.getCurrentHighBidderTeamId())
                .highBidderCaptainName(state.getCurrentHighBidderName())
                .remainingSeconds(state.getRemainingSeconds().get())
                .totalTimerSeconds(state.getTotalTimerSeconds())
                .phase(state.getPhase())
                .recentBids(recentBids)
                .playersRemaining(state.getRemainingPlayers().size())
                .playersSold(state.getSoldResults().stream()
                        .filter(r -> r.getStatus() == AuctionResult.ResultStatus.SOLD)
                        .collect(Collectors.toList()).size())
                .playersUnsold(state.getUnsoldPlayers().size())
                .isBidWar(false)
                .build();
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
                .sorted(Comparator.comparingInt(p ->
                        order.getOrDefault(p.getTier(), 4)))
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
                    Thread.sleep(3000); // 3s reveal pause
                    startBidding(tournamentId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
}