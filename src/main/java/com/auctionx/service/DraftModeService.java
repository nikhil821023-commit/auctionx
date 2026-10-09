package com.auctionx.service;

import com.auctionx.model.*;
import com.auctionx.repository.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Draft Mode Auction Engine
 * ─────────────────────────────────────────────────────────────────
 * Runs structured category-based rounds:
 *   Round 1 → Batsmen (top N by base price)
 *   Round 2 → Bowlers
 *   Round 3 → All-Rounders
 *   Round 4 → WK-Batsmen
 *   Round 5 → Remaining players
 *
 * Each round: players auctioned one by one via normal bidding.
 * Organizer can cap how many players per team per round.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DraftModeService {

    private final PlayerRepository     playerRepository;
    private final TeamRepository       teamRepository;
    private final TournamentRepository tournamentRepository;
    private final AuctionResultRepository resultRepository;
    private final SimpMessagingTemplate messagingTemplate;

    // ── In-memory draft sessions ──────────────────────────────────
    private final ConcurrentHashMap<Long, DraftSession> activeDrafts
            = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────
    // DRAFT SESSION MODEL
    // ─────────────────────────────────────────────────────────────
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DraftSession {
        private Long   tournamentId;
        private String tournamentName;

        private List<DraftRound> rounds;
        private int    currentRoundIndex;
        private int    currentPlayerIndex; // within round

        private DraftStatus status;
        private LocalDateTime startedAt;

        // Settings set by organizer
        private Integer maxPlayersPerTeamPerRound; // e.g. 1 = balanced
        private Integer bidTimerSeconds;
        private Boolean autoAdvance; // auto move to next player after sold

        // Track picks per team per round for balance enforcement
        // Map<teamId, Map<roundIndex, pickCount>>
        private Map<Long, Map<Integer, Integer>> teamPicksPerRound;

        public DraftRound currentRound() {
            if (currentRoundIndex >= rounds.size()) return null;
            return rounds.get(currentRoundIndex);
        }

        public Player currentPlayer() {
            DraftRound round = currentRound();
            if (round == null) return null;
            if (currentPlayerIndex >= round.getPlayers().size()) return null;
            return round.getPlayers().get(currentPlayerIndex);
        }

        public boolean isLastPlayerInRound() {
            DraftRound round = currentRound();
            if (round == null) return false;
            return currentPlayerIndex >= round.getPlayers().size() - 1;
        }

        public boolean isLastRound() {
            return currentRoundIndex >= rounds.size() - 1;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DraftRound {
        private int    roundNumber;
        private String roundName;     // "Batsmen Round", "Bowlers Round"
        private String roleFilter;    // "Batsman", "Bowler", etc. or "ALL"
        private String tierFilter;    // "PLATINUM", "GOLD", "ALL"
        private String icon;          // emoji
        private String colorHex;      // for UI

        private List<Player> players; // ordered by base price desc
        private List<DraftRoundResult> results; // sold/unsold outcomes

        private DraftRoundStatus status;
        private int totalPlayers;
        private int soldCount;
        private int unsoldCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DraftRoundResult {
        private Long   playerId;
        private String playerName;
        private String playerRole;
        private String playerTier;
        private String playerPhoto;
        private Double basePrice;
        private Double soldPrice;
        private String teamName;
        private String teamColor;
        private Long   teamId;
        private String status; // "SOLD" | "UNSOLD"
        private Integer totalBids;
    }

    public enum DraftStatus {
        SETUP, ACTIVE, ROUND_BREAK, COMPLETED
    }

    public enum DraftRoundStatus {
        PENDING, ACTIVE, COMPLETED
    }

    // ─────────────────────────────────────────────────────────────
    // 1. CONFIGURE AND START DRAFT
    // ─────────────────────────────────────────────────────────────

    /**
     * Organizer configures the draft rounds and starts.
     * roundConfigs: list of { roleFilter, tierFilter, playerCount, name }
     */
    public DraftSession startDraft(Long tournamentId,
                                   List<Map<String, Object>> roundConfigs,
                                   Integer maxPerTeamPerRound,
                                   Integer bidTimerSeconds,
                                   Boolean autoAdvance) {

        Tournament t = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new RuntimeException(
                        "Tournament not found"));

        List<Player> allPlayers = playerRepository
                .findByTournamentIdAndStatus(
                        tournamentId, Player.PlayerStatus.AVAILABLE);

        if (allPlayers.isEmpty()) {
            throw new RuntimeException(
                    "No available players found. Add players first.");
        }

        // ── Build rounds ──────────────────────────────────────────
        List<DraftRound> rounds = new ArrayList<>();
        Set<Long> assignedPlayerIds = new HashSet<>();

        // Default round configs if none provided
        if (roundConfigs == null || roundConfigs.isEmpty()) {
            roundConfigs = buildDefaultRoundConfigs();
        }

        int roundNum = 1;
        for (Map<String, Object> cfg : roundConfigs) {
            String role       = cfg.getOrDefault("roleFilter", "ALL").toString();
            String tier       = cfg.getOrDefault("tierFilter", "ALL").toString();
            String name       = cfg.getOrDefault("name",
                    buildRoundName(role, tier)).toString();
            int    maxPlayers = Integer.parseInt(
                    cfg.getOrDefault("playerCount", "999").toString());
            String icon       = cfg.getOrDefault("icon",
                    roleIcon(role)).toString();
            String color      = cfg.getOrDefault("color",
                    roleColor(role)).toString();

            // Filter players for this round
            List<Player> roundPlayers = allPlayers.stream()
                    .filter(p -> !assignedPlayerIds.contains(p.getId()))
                    .filter(p -> roleMatches(p.getRole(), role))
                    .filter(p -> tierMatches(p.getTier(), tier))
                    .sorted(Comparator.comparingDouble(
                                    (Player p) -> p.getBasePrice() != null
                                            ? p.getBasePrice() : 0)
                            .reversed()) // highest base price first
                    .limit(maxPlayers)
                    .collect(Collectors.toList());

            if (roundPlayers.isEmpty()) continue;

            roundPlayers.forEach(p -> assignedPlayerIds.add(p.getId()));

            DraftRound round = DraftRound.builder()
                    .roundNumber(roundNum++)
                    .roundName(name)
                    .roleFilter(role)
                    .tierFilter(tier)
                    .icon(icon)
                    .colorHex(color)
                    .players(roundPlayers)
                    .results(new ArrayList<>())
                    .status(DraftRoundStatus.PENDING)
                    .totalPlayers(roundPlayers.size())
                    .soldCount(0)
                    .unsoldCount(0)
                    .build();

            rounds.add(round);
        }

        if (rounds.isEmpty()) {
            throw new RuntimeException(
                    "No players matched the configured rounds. " +
                            "Check player roles are set correctly.");
        }

        // Mark first round ACTIVE
        rounds.get(0).setStatus(DraftRoundStatus.ACTIVE);

        // ── Build session ─────────────────────────────────────────
        DraftSession session = DraftSession.builder()
                .tournamentId(tournamentId)
                .tournamentName(t.getName())
                .rounds(rounds)
                .currentRoundIndex(0)
                .currentPlayerIndex(0)
                .status(DraftStatus.ACTIVE)
                .startedAt(LocalDateTime.now())
                .maxPlayersPerTeamPerRound(
                        maxPerTeamPerRound != null ? maxPerTeamPerRound : 0)
                .bidTimerSeconds(
                        bidTimerSeconds != null ? bidTimerSeconds : 30)
                .autoAdvance(autoAdvance != null ? autoAdvance : true)
                .teamPicksPerRound(new ConcurrentHashMap<>())
                .build();

        activeDrafts.put(tournamentId, session);

        log.info("🎯 Draft started for '{}' — {} rounds, {} total players",
                t.getName(), rounds.size(), assignedPlayerIds.size());

        broadcastDraftState(tournamentId, "DRAFT_STARTED");
        return session;
    }

    // ─────────────────────────────────────────────────────────────
    // 2. GET CURRENT STATE
    // ─────────────────────────────────────────────────────────────

    public Map<String, Object> getDraftState(Long tournamentId) {
        DraftSession session = getSession(tournamentId);
        return buildStatePayload(session);
    }

    // ─────────────────────────────────────────────────────────────
    // 3. RECORD SOLD IN DRAFT
    //    Called by AuctionEngine when a player is sold —
    //    updates the draft session tracking
    // ─────────────────────────────────────────────────────────────

    public void recordDraftSold(Long tournamentId,
                                Long playerId,
                                Long teamId,
                                Double soldPrice,
                                Integer totalBids) {
        DraftSession session = activeDrafts.get(tournamentId);
        if (session == null) return;

        DraftRound round = session.currentRound();
        if (round == null) return;

        Player player = round.getPlayers().stream()
                .filter(p -> p.getId().equals(playerId))
                .findFirst().orElse(null);

        Team team = teamRepository.findById(teamId).orElse(null);

        // Add result
        DraftRoundResult result = DraftRoundResult.builder()
                .playerId(playerId)
                .playerName(player != null ? player.getName() : "Unknown")
                .playerRole(player != null ? player.getRole() : "")
                .playerTier(player != null && player.getTier() != null
                        ? player.getTier().name() : "BRONZE")
                .playerPhoto(player != null ? player.getPhotoPath() : null)
                .basePrice(player != null ? player.getBasePrice() : 0)
                .soldPrice(soldPrice)
                .teamId(teamId)
                .teamName(team != null ? team.getTeamName() : "Unknown")
                .teamColor(team != null ? team.getTeamColor() : "#888")
                .status("SOLD")
                .totalBids(totalBids)
                .build();

        round.getResults().add(result);
        round.setSoldCount(round.getSoldCount() + 1);

        // Track team picks for balance enforcement
        session.getTeamPicksPerRound()
                .computeIfAbsent(teamId, k -> new ConcurrentHashMap<>())
                .merge(session.getCurrentRoundIndex(), 1, Integer::sum);

        advanceToNext(tournamentId, session);
    }

    // ─────────────────────────────────────────────────────────────
    // 4. RECORD UNSOLD IN DRAFT
    // ─────────────────────────────────────────────────────────────

    public void recordDraftUnsold(Long tournamentId, Long playerId) {
        DraftSession session = activeDrafts.get(tournamentId);
        if (session == null) return;

        DraftRound round = session.currentRound();
        if (round == null) return;

        Player player = round.getPlayers().stream()
                .filter(p -> p.getId().equals(playerId))
                .findFirst().orElse(null);

        DraftRoundResult result = DraftRoundResult.builder()
                .playerId(playerId)
                .playerName(player != null ? player.getName() : "Unknown")
                .playerRole(player != null ? player.getRole() : "")
                .playerTier(player != null && player.getTier() != null
                        ? player.getTier().name() : "BRONZE")
                .basePrice(player != null ? player.getBasePrice() : 0)
                .soldPrice(0.0)
                .status("UNSOLD")
                .totalBids(0)
                .build();

        round.getResults().add(result);
        round.setUnsoldCount(round.getUnsoldCount() + 1);

        advanceToNext(tournamentId, session);
    }

    // ─────────────────────────────────────────────────────────────
    // 5. ADVANCE TO NEXT PLAYER / ROUND
    // ─────────────────────────────────────────────────────────────

    private void advanceToNext(Long tournamentId,
                               DraftSession session) {

        if (session.isLastPlayerInRound()) {
            // Round complete
            DraftRound round = session.currentRound();
            round.setStatus(DraftRoundStatus.COMPLETED);

            if (session.isLastRound()) {
                // All rounds done — draft complete
                session.setStatus(DraftStatus.COMPLETED);
                broadcastDraftState(tournamentId, "DRAFT_COMPLETED");
                log.info("🏆 Draft COMPLETED for tournament {}",
                        tournamentId);
            } else {
                // Move to next round
                session.setCurrentRoundIndex(
                        session.getCurrentRoundIndex() + 1);
                session.setCurrentPlayerIndex(0);
                session.currentRound().setStatus(DraftRoundStatus.ACTIVE);
                session.setStatus(DraftStatus.ROUND_BREAK);

                broadcastDraftState(tournamentId, "ROUND_COMPLETED");

                log.info("📋 Round {} complete — moving to Round {}",
                        round.getRoundNumber(),
                        session.getCurrentRoundIndex() + 1);
            }
        } else {
            // Next player in same round
            session.setCurrentPlayerIndex(
                    session.getCurrentPlayerIndex() + 1);
            broadcastDraftState(tournamentId, "NEXT_PLAYER");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 6. ORGANIZER MANUALLY SKIPS TO NEXT ROUND
    // ─────────────────────────────────────────────────────────────

    public DraftSession skipToNextRound(Long tournamentId) {
        DraftSession session = getSession(tournamentId);

        if (session.isLastRound()) {
            session.setStatus(DraftStatus.COMPLETED);
            broadcastDraftState(tournamentId, "DRAFT_COMPLETED");
            return session;
        }

        session.currentRound().setStatus(DraftRoundStatus.COMPLETED);
        session.setCurrentRoundIndex(session.getCurrentRoundIndex() + 1);
        session.setCurrentPlayerIndex(0);
        session.currentRound().setStatus(DraftRoundStatus.ACTIVE);
        session.setStatus(DraftStatus.ACTIVE);

        broadcastDraftState(tournamentId, "ROUND_SKIPPED");
        return session;
    }

    // ─────────────────────────────────────────────────────────────
    // 7. CHECK TEAM PICK LIMIT
    // ─────────────────────────────────────────────────────────────

    public boolean canTeamPickInCurrentRound(Long tournamentId,
                                             Long teamId) {
        DraftSession session = activeDrafts.get(tournamentId);
        if (session == null) return true;
        if (session.getMaxPlayersPerTeamPerRound() == null
                || session.getMaxPlayersPerTeamPerRound() == 0) {
            return true; // no limit set
        }

        int picks = session.getTeamPicksPerRound()
                .getOrDefault(teamId, Map.of())
                .getOrDefault(session.getCurrentRoundIndex(), 0);

        return picks < session.getMaxPlayersPerTeamPerRound();
    }

    // ─────────────────────────────────────────────────────────────
    // ANALYTICS — draft summary
    // ─────────────────────────────────────────────────────────────

    public Map<String, Object> getDraftSummary(Long tournamentId) {
        DraftSession session = activeDrafts.get(tournamentId);
        if (session == null) {
            throw new RuntimeException("No draft session found");
        }

        List<Map<String, Object>> roundSummaries =
                session.getRounds().stream().map(round -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("roundNumber", round.getRoundNumber());
                    m.put("roundName",   round.getRoundName());
                    m.put("icon",        round.getIcon());
                    m.put("color",       round.getColorHex());
                    m.put("status",      round.getStatus().name());
                    m.put("totalPlayers",round.getTotalPlayers());
                    m.put("sold",        round.getSoldCount());
                    m.put("unsold",      round.getUnsoldCount());
                    m.put("results",     round.getResults());
                    m.put("totalValue",  round.getResults().stream()
                            .filter(r -> "SOLD".equals(r.getStatus()))
                            .mapToDouble(r -> r.getSoldPrice() != null
                                    ? r.getSoldPrice() : 0)
                            .sum());
                    return m;
                }).collect(Collectors.toList());

        int totalSold   = session.getRounds().stream()
                .mapToInt(DraftRound::getSoldCount).sum();
        int totalUnsold = session.getRounds().stream()
                .mapToInt(DraftRound::getUnsoldCount).sum();
        double totalValue = session.getRounds().stream()
                .flatMap(r -> r.getResults().stream())
                .filter(r -> "SOLD".equals(r.getStatus()))
                .mapToDouble(r -> r.getSoldPrice() != null
                        ? r.getSoldPrice() : 0)
                .sum();

        return Map.of(
                "tournamentId",   tournamentId,
                "tournamentName", session.getTournamentName(),
                "status",         session.getStatus().name(),
                "rounds",         roundSummaries,
                "totalRounds",    session.getRounds().size(),
                "totalSold",      totalSold,
                "totalUnsold",    totalUnsold,
                "totalValue",     Math.round(totalValue)
        );
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────

    private DraftSession getSession(Long tournamentId) {
        DraftSession s = activeDrafts.get(tournamentId);
        if (s == null) throw new RuntimeException(
                "No active draft for tournament " + tournamentId);
        return s;
    }

    private void broadcastDraftState(Long tournamentId, String event) {
        DraftSession session = activeDrafts.get(tournamentId);
        if (session == null) return;
        Map<String, Object> payload = buildStatePayload(session);
        payload.put("event", event);
        messagingTemplate.convertAndSend(
                "/topic/draft/" + tournamentId, payload);
    }

    private Map<String, Object> buildStatePayload(DraftSession s) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tournamentId",       s.getTournamentId());
        payload.put("tournamentName",     s.getTournamentName());
        payload.put("status",             s.getStatus().name());
        payload.put("currentRoundIndex",  s.getCurrentRoundIndex());
        payload.put("currentPlayerIndex", s.getCurrentPlayerIndex());
        payload.put("totalRounds",        s.getRounds().size());
        payload.put("bidTimerSeconds",    s.getBidTimerSeconds());

        // Round overview list (for sidebar)
        payload.put("roundOverview", s.getRounds().stream().map(r ->
                Map.of(
                        "roundNumber", r.getRoundNumber(),
                        "roundName",   r.getRoundName(),
                        "icon",        r.getIcon(),
                        "color",       r.getColorHex(),
                        "status",      r.getStatus().name(),
                        "totalPlayers",r.getTotalPlayers(),
                        "sold",        r.getSoldCount(),
                        "unsold",      r.getUnsoldCount()
                )
        ).collect(Collectors.toList()));

        // Current round detail
        DraftRound cur = s.currentRound();
        if (cur != null) {
            payload.put("currentRound", Map.of(
                    "roundNumber", cur.getRoundNumber(),
                    "roundName",   cur.getRoundName(),
                    "icon",        cur.getIcon(),
                    "color",       cur.getColorHex(),
                    "totalPlayers",cur.getTotalPlayers(),
                    "remaining",   cur.getTotalPlayers()
                            - cur.getSoldCount()
                            - cur.getUnsoldCount(),
                    "sold",        cur.getSoldCount(),
                    "results",     cur.getResults()
            ));
        }

        // Current player detail
        Player cp = s.currentPlayer();
        if (cp != null) {
            payload.put("currentPlayer", Map.of(
                    "id",          cp.getId(),
                    "name",        cp.getName(),
                    "role",        cp.getRole() != null ? cp.getRole() : "",
                    "tier",        cp.getTier() != null
                            ? cp.getTier().name() : "BRONZE",
                    "photo",       cp.getPhotoPath() != null
                            ? cp.getPhotoPath() : "",
                    "basePrice",   cp.getBasePrice() != null
                            ? cp.getBasePrice() : 0,
                    "nationality", cp.getNationality() != null
                            ? cp.getNationality() : "",
                    "matches",     cp.getMatches() != null
                            ? cp.getMatches() : 0,
                    "average",     cp.getAverage() != null
                            ? cp.getAverage() : 0,
                    "strikeRate",  cp.getStrikeRate() != null
                            ? cp.getStrikeRate() : 0
            ));
        }

        return payload;
    }

    private List<Map<String, Object>> buildDefaultRoundConfigs() {
        return List.of(
                Map.of("roleFilter","Batsman",     "name","Batsmen Round",
                        "icon","🏏", "color","#3b82f6"),
                Map.of("roleFilter","Bowler",      "name","Bowlers Round",
                        "icon","⚡", "color","#ef4444"),
                Map.of("roleFilter","All-Rounder", "name","All-Rounders Round",
                        "icon","🌟", "color","#c8ff00"),
                Map.of("roleFilter","WK-Batsman",  "name","Wicket-Keepers Round",
                        "icon","🧤", "color","#f59e0b"),
                Map.of("roleFilter","ALL",         "name","Remaining Players",
                        "icon","🎰", "color","#8b5cf6")
        );
    }

    private boolean roleMatches(String playerRole, String filter) {
        if ("ALL".equals(filter)) return true;
        if (playerRole == null)   return false;
        return playerRole.equalsIgnoreCase(filter)
                || playerRole.toLowerCase()
                .contains(filter.toLowerCase());
    }

    private boolean tierMatches(Player.PlayerTier playerTier,
                                String filter) {
        if ("ALL".equals(filter)) return true;
        if (playerTier == null)   return false;
        return playerTier.name().equalsIgnoreCase(filter);
    }

    private String buildRoundName(String role, String tier) {
        if ("ALL".equals(role) && "ALL".equals(tier))
            return "Open Round";
        if (!"ALL".equals(tier) && "ALL".equals(role))
            return tier.charAt(0)
                    + tier.substring(1).toLowerCase() + " Round";
        if ("ALL".equals(tier))
            return role + "s Round";
        return tier.charAt(0) + tier.substring(1).toLowerCase()
                + " " + role + "s";
    }

    private String roleIcon(String role) {
        return switch (role) {
            case "Batsman"     -> "🏏";
            case "Bowler"      -> "⚡";
            case "All-Rounder" -> "🌟";
            case "WK-Batsman"  -> "🧤";
            default            -> "🎰";
        };
    }

    private String roleColor(String role) {
        return switch (role) {
            case "Batsman"     -> "#3b82f6";
            case "Bowler"      -> "#ef4444";
            case "All-Rounder" -> "#c8ff00";
            case "WK-Batsman"  -> "#f59e0b";
            default            -> "#8b5cf6";
        };
    }
}