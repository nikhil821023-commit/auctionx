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
    private final ObjectMapper            objectMapper;

    private final ConcurrentHashMap<Long, AuctionState>         activeAuctions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Map<Long, Integer>>   bidWarTracker  = new ConcurrentHashMap<>();

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
                            ". Please add players first.");
        }

        boolean allowTierOrder = settings.getAllowTierOrder() != null ? settings.getAllowTierOrder() : true;
        int bidTimer = settings.getBidTimerSeconds() != null ? settings.getBidTimerSeconds() : 30;
        int resetTimer = settings.getBidTimerResetSeconds() != null ? settings.getBidTimerResetSeconds() : 10;
        boolean autoSpin = settings.getAutoSpin() != null ? settings.getAutoSpin() : true;
        int pauseBetween = settings.getPauseBetweenPlayers() != null ? settings.getPauseBetweenPlayers() : 5;
        double bidIncrement = tournament.getBidIncrement() != null ? tournament.getBidIncrement() : 10.0;

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

        messagingTemplate.convertAndSend(
                "/topic/auction/" + tournamentId + "/spin",
                Map.of(
                        "event", "WHEEL_SPINNING",
                        "wheelStopIndex", index,
                        "totalPlayers", state.getRemainingPlayers().size(),
                        "playerNames", state.getRemainingPlayers()
                                .stream()
                                .map(Player::getName)
                                .collect(Collectors.toList())
                )
        );

        state.setCurrentPlayer(selectedPlayer);

        // ✅ IMPORTANT: do not use any "baseline flag"
        // Real bid state is based on bidHistory being empty or not.
        state.setCurrentBid(null);
        state.setBidHistory(new ArrayList<>());
        state.setCurrentHighBidderTeamId(null);
        state.setCurrentHighBidderName(null);
        state.setCurrentHighBidderTeamName(null);
        state.setCurrentHighBidderTeamColor(null);
        state.setPhase(AuctionState.AuctionPhase.PLAYER_REVEAL);

        bidWarTracker.get(tournamentId).clear();

        SpinResultDTO result = buildSpinResult(selectedPlayer, index,
                state.getRemainingPlayers().size());

        messagingTemplate.convertAndSend(
                "/topic/auction/" + tournamentId + "/spin",
                Map.of(
                        "event", "PLAYER_REVEALED",
                        "player", result,
                        "phase", state.getPhase()
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

        // UI display should show base price before first real bid
        if (state.getCurrentBid() == null) {
            state.setCurrentBid(state.getCurrentPlayer().getBasePrice());
        }

        timerService.startTimer(tournamentId, state, this);
        broadcastAuctionState(tournamentId, state, "BIDDING_STARTED");

        log.info("🔔 Bidding STARTED for {} — Base: ₹{}",
                state.getCurrentPlayer().getName(), state.getCurrentPlayer().getBasePrice());
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

        Player player = state.getCurrentPlayer();
        if (player == null) {
            throw new RuntimeException("No player on auction block");
        }

        double basePrice = player.getBasePrice() != null ? player.getBasePrice() : 0;

        // First real bid is determined by bidHistory being empty.
        // currentBid can be base price only as a display value before first real bid.
        boolean isFirstBid = state.getBidHistory() == null || state.getBidHistory().isEmpty();

        Double currentBid = state.getCurrentBid() != null ? state.getCurrentBid() : basePrice;

        double bidAmount;
        if (Boolean.TRUE.equals(dto.getUseAutoIncrement())) {
            // auto-increment:
            // first real bid -> exactly base price
            // later bids -> current bid + increment
            bidAmount = isFirstBid ? basePrice : currentBid + state.getBidIncrement();
        } else if (dto.getBidAmount() != null) {
            bidAmount = dto.getBidAmount();
        } else {
            throw new RuntimeException("Bid amount is required");
        }

        // Validation:
        if (isFirstBid) {
            if (bidAmount < basePrice) {
                throw new RuntimeException(
                        "First bid must be at least base price ₹"
                                + (long) basePrice
                                + ". You bid ₹" + (long) bidAmount);
            }
        } else {
            if (bidAmount <= currentBid) {
                throw new RuntimeException(
                        "Bid ₹" + (long) bidAmount
                                + " must be higher than current bid ₹"
                                + (long) (double) currentBid
                                + ". Add at least ₹1 more.");
            }
        }

        // Budget check
        Team team = teamRepository.findById(dto.getTeamId())
                .orElseThrow(() -> new RuntimeException("Team not found"));

        double remaining = team.getRemainingBudget() != null ? team.getRemainingBudget() : 0;
        if (bidAmount > remaining) {
            throw new RuntimeException(
                    "Insufficient budget. Remaining: ₹" + (long) remaining
                            + ", Bid: ₹" + (long) bidAmount);
        }


        // ── Smart Budget Cap validation ───────────────────────────────────
// After the existing budget check, ADD this block:

// How many players does this team still need?
        int maxSquadSize = 11; // default — can come from tournament settings
        try {
            Tournament tourney = tournamentRepository
                    .findById(dto.getTournamentId())
                    .orElse(null);
            if (tourney != null && tourney.getMaxPlayersPerTeam() != null) {
                maxSquadSize = tourney.getMaxPlayersPerTeam();
            }
        } catch (Exception ignored) {}

// How many has this team already bought?
        long playersBought = resultRepository
                .countByTeamIdAndTournamentIdAndStatus(
                        dto.getTeamId(),
                        dto.getTournamentId(),
                        AuctionResult.ResultStatus.SOLD
                );

        long playersStillNeeded = maxSquadSize - playersBought;

// Reserve budget for remaining players (excluding current one)
// They need at least (playersStillNeeded - 1) more players at base price
        if (playersStillNeeded > 1) {
            double minBasePrice = player.getBasePrice() != null
                    ? player.getBasePrice() : 0;
            double reservedBudget = (playersStillNeeded - 1) * minBasePrice;
            double maxAllowedBid  = remaining - reservedBudget;

            if (bidAmount > maxAllowedBid) {
                throw new RuntimeException(
                        "Budget cap exceeded! You need "
                                + (playersStillNeeded - 1)
                                + " more players after this at ₹"
                                + (long) minBasePrice + " each."
                                + " Max you can bid: ₹" + (long) maxAllowedBid
                                + " (₹" + (long) reservedBudget
                                + " reserved for remaining squad)."
                );
            }
        }
// If playersStillNeeded <= 1, no reservation needed — spend freely

        // Validate captain token for self-bid mode
        if (dto.getBidMode() == BidMode.CAPTAIN_SELF) {
            String expected = generateCaptainToken(dto.getTeamId(), dto.getTournamentId());
            if (!expected.equals(dto.getCaptainToken())) {
                throw new RuntimeException("Invalid captain token — unauthorized bid");
            }
        }

        // Record real bid
        if (state.getBidHistory() == null) {
            state.setBidHistory(new ArrayList<>());
        }

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
            String opponentName = state.getBidHistory().stream()
                    .filter(b -> !b.getTeamId().equals(dto.getTeamId()))
                    .reduce((first, second) -> second)
                    .map(BidRecord::getTeamName)
                    .orElse("opponent");
            stateDTO.setAlertMessage(team.getTeamName() + " vs " + opponentName);
        }

        messagingTemplate.convertAndSend(
                "/topic/auction/" + dto.getTournamentId(),
                stateDTO
        );

        log.info("💰 [{}] Bid ₹{} by {} for {} (base ₹{}, firstBid={})",
                dto.getBidMode(), (long) bidAmount,
                team.getCaptainName(),
                player.getName(),
                (long) basePrice,
                isFirstBid);

        return stateDTO;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 5. TIMER EXPIRED → AUTO SOLD / UNSOLD
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void onTimerExpired(Long tournamentId) {
        AuctionState state = getState(tournamentId);

        // Sell only if at least one real bid happened
        if (state.getBidHistory() != null
                && !state.getBidHistory().isEmpty()
                && state.getCurrentHighBidderTeamId() != null) {
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

        Player player = state.getCurrentPlayer();
        Long winnerTeamId = state.getCurrentHighBidderTeamId();
        Double soldPrice = state.getCurrentBid();

        if (player == null) {
            throw new RuntimeException("No current player");
        }

        if (state.getBidHistory() == null
                || state.getBidHistory().isEmpty()
                || winnerTeamId == null) {
            throw new RuntimeException(
                    "No bids placed yet — at least one team must bid at base price ₹"
                            + (player.getBasePrice() != null
                            ? (long) (double) player.getBasePrice() : 0)
                            + " before selling.");
        }

        double basePrice = player.getBasePrice() != null ? player.getBasePrice() : 0;

        if (soldPrice == null || soldPrice < basePrice) {
            throw new RuntimeException(
                    "Sold price ₹" + (soldPrice != null ? (long) (double) soldPrice : 0)
                            + " cannot be less than base price ₹" + (long) basePrice);
        }

        Team winnerTeam = teamRepository.findById(winnerTeamId)
                .orElseThrow(() -> new RuntimeException("Winning team not found"));

        // Update player
        player.setStatus(Player.PlayerStatus.SOLD);
        player.setSoldPrice(soldPrice);
        player.setTeam(winnerTeam);
        playerRepository.save(player);

        // Update team budget
        winnerTeam.setSpentBudget(
                (winnerTeam.getSpentBudget() != null ? winnerTeam.getSpentBudget() : 0) + soldPrice);
        winnerTeam.setRemainingBudget(
                (winnerTeam.getRemainingBudget() != null ? winnerTeam.getRemainingBudget() : 0) - soldPrice);
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
                .basePrice(basePrice)
                .soldPrice(soldPrice)
                .totalBids(state.getBidHistory().size())
                .status(AuctionResult.ResultStatus.SOLD)
                .soldAt(LocalDateTime.now())
                .build();

        try {
            List<Map<String, Object>> timeline = state.getBidHistory()
                    .stream()
                    .map(b -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("teamName", b.getTeamName());
                        entry.put("teamColor", b.getTeamColor() != null ? b.getTeamColor() : "#888");
                        entry.put("amount", b.getAmount());
                        entry.put("second", b.getTimerSnapshotSeconds());
                        entry.put("isWinner", b.getTeamId().equals(winnerTeamId));
                        return entry;
                    })
                    .collect(Collectors.toList());

            result.setBidTimeline(objectMapper.writeValueAsString(timeline));
            result.setOpeningBid(state.getBidHistory().isEmpty()
                    ? soldPrice
                    : state.getBidHistory().get(0).getAmount());
            result.setFinalBid(soldPrice);
        } catch (Exception e) {
            log.warn("Could not serialize bid timeline: {}", e.getMessage());
        }

        resultRepository.save(result);

        state.getSoldResults().add(result);
        state.getRemainingPlayers().remove(player);
        state.setPhase(AuctionState.AuctionPhase.SOLD);
        state.getIsBidding().set(false);

        log.info("🔨 SOLD: {} → {} for ₹{} (base ₹{})",
                player.getName(), winnerTeam.getTeamName(),
                (long) (double) soldPrice, (long) basePrice);

        AuctionStateDTO stateDTO = buildAuctionStateDTO(state, "PLAYER_SOLD");
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

        log.info("❌ UNSOLD: {} (no bids)", player.getName());

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
                        "event", "RE_AUCTION_STARTED",
                        "playerCount", state.getRemainingPlayers().size(),
                        "message", "Re-auction starting for unsold players!"
                )
        );

        log.info("🔄 RE-AUCTION started — {} players",
                state.getRemainingPlayers().size());
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
                        "event", "AUCTION_COMPLETED",
                        "message", "🏆 Auction Complete!",
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
        return Integer.toHexString((teamId + "_" + tournamentId + "_auctionx").hashCode());
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
                .currentPlayerId(p != null ? p.getId() : null)
                .currentPlayerName(p != null ? p.getName() : null)
                .currentPlayerRole(p != null ? p.getRole() : null)
                .currentPlayerPhoto(p != null ? p.getPhotoPath() : null)
                .currentPlayerTier(p != null && p.getTier() != null ? p.getTier().name() : null)
                .currentPlayerBasePrice(p != null ? p.getBasePrice() : null)
                .currentBid(state.getCurrentBid())
                .highBidderTeamId(state.getCurrentHighBidderTeamId())
                .highBidderCaptainName(state.getCurrentHighBidderName())
                .highBidderTeamName(state.getCurrentHighBidderTeamName())
                .highBidderTeamColor(state.getCurrentHighBidderTeamColor())
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

    private void clearCurrentPlayer(AuctionState state) {
        state.setCurrentPlayer(null);
        state.setCurrentBid(null);
        state.setCurrentHighBidderTeamId(null);
        state.setCurrentHighBidderName(null);
        state.setCurrentHighBidderTeamName(null);
        state.setCurrentHighBidderTeamColor(null);
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
                Player.PlayerTier.GOLD, 1,
                Player.PlayerTier.SILVER, 2,
                Player.PlayerTier.BRONZE, 3
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
                .filter(count -> count >= 2)
                .count();

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