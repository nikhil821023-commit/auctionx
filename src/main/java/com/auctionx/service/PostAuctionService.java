package com.auctionx.service;

import com.auctionx.model.*;
import com.auctionx.repository.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.expression.common.ExpressionUtils.toLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class PostAuctionService {

    private final TournamentRepository    tournamentRepository;
    private final TeamRepository          teamRepository;
    private final PlayerRepository        playerRepository;
    private final AuctionResultRepository resultRepository;

    // ═══════════════════════════════════════════════════════════════
    // 1. FULL TOURNAMENT SUMMARY
    // ═══════════════════════════════════════════════════════════════
    public Map<String, Object> getTournamentSummary(Long tournamentId) {
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new RuntimeException("Tournament not found"));

        List<Team>   teams   = teamRepository.findByTournamentId(tournamentId);
        List<Player> players = playerRepository.findByTournamentId(tournamentId);
        List<AuctionResult> results = resultRepository.findByTournamentId(tournamentId);

        long sold   = players.stream()
                .filter(p -> p.getStatus() == Player.PlayerStatus.SOLD).count();
        long unsold = players.stream()
                .filter(p -> p.getStatus() == Player.PlayerStatus.UNSOLD).count();


        double totalMoneySpent = results.stream()
                .filter(r -> r.getStatus() == AuctionResult.ResultStatus.SOLD)
                .mapToDouble(AuctionResult::getSoldPrice)
                .sum();

        double highestBid = results.stream()
                .filter(r -> r.getStatus() == AuctionResult.ResultStatus.SOLD)
                .mapToDouble(AuctionResult::getSoldPrice)
                .max().orElse(0);

        AuctionResult mostExpensive = results.stream()
                .filter(r -> r.getStatus() == AuctionResult.ResultStatus.SOLD)
                .max(Comparator.comparingDouble(AuctionResult::getSoldPrice))
                .orElse(null);

        // Most active bidder — team that bought most players
        Map<String, Long> teamBuyCount = results.stream()
                .filter(r -> r.getStatus() == AuctionResult.ResultStatus.SOLD
                        && r.getTeamName() != null)
                .collect(Collectors.groupingBy(
                        AuctionResult::getTeamName, Collectors.counting()));

        String mostActiveBuyer = teamBuyCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("—");

        // Best value player — biggest (basePrice → soldPrice) ratio gap
        AuctionResult bestValue = results.stream()
                .filter(r -> r.getStatus() == AuctionResult.ResultStatus.SOLD
                        && r.getBasePrice() != null && r.getBasePrice() > 0)
                .min(Comparator.comparingDouble(r ->
                        r.getSoldPrice() / r.getBasePrice()))
                .orElse(null);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tournamentId",     tournamentId);
        summary.put("tournamentName",   tournament.getName());
        summary.put("sportType",        tournament.getSportType());
        summary.put("status",           tournament.getStatus());
        summary.put("totalTeams",       teams.size());
        summary.put("totalPlayers",     players.size());
        summary.put("playersSold",      sold);
        summary.put("playersUnsold",    unsold);
        summary.put("totalMoneySpent",  Math.round(totalMoneySpent));
        summary.put("highestBid",       Math.round(highestBid));
        summary.put("mostExpensivePlayer", mostExpensive != null ? Map.of(
                "name",     mostExpensive.getPlayerName(),
                "role",     mostExpensive.getPlayerRole() != null ? mostExpensive.getPlayerRole() : "",
                "price",    mostExpensive.getSoldPrice(),
                "team",     mostExpensive.getTeamName() != null ? mostExpensive.getTeamName() : "",
                "captain",  mostExpensive.getCaptainName() != null ? mostExpensive.getCaptainName() : ""
        ) : null);
        summary.put("bestValuePlayer", bestValue != null ? Map.of(
                "name",       bestValue.getPlayerName(),
                "basePrice",  bestValue.getBasePrice(),
                "soldPrice",  bestValue.getSoldPrice(),
                "team",       bestValue.getTeamName() != null ? bestValue.getTeamName() : ""
        ) : null);
        summary.put("mostActiveBuyer",  mostActiveBuyer);

        return summary;
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. ALL TEAM SQUADS
    // ═══════════════════════════════════════════════════════════════
    public List<Map<String, Object>> getAllSquads(Long tournamentId) {
        List<Team> teams = teamRepository.findByTournamentId(tournamentId);

        return teams.stream().map(team -> {
            List<AuctionResult> bought = resultRepository.findByTeamId(team.getId())
                    .stream()
                    .filter(r -> r.getTournamentId().equals(tournamentId)
                            && r.getStatus() == AuctionResult.ResultStatus.SOLD)
                    .sorted(Comparator.comparingDouble(AuctionResult::getSoldPrice).reversed())
                    .collect(Collectors.toList());

            double spent     = bought.stream().mapToDouble(AuctionResult::getSoldPrice).sum();
            double remaining = team.getTotalBudget() - spent;

            // Tier breakdown
            Map<String, Long> tierCount = bought.stream()
                    .collect(Collectors.groupingBy(
                            r -> r.getPlayerTier() != null ? r.getPlayerTier() : "BRONZE",
                            Collectors.counting()));

            // Role breakdown
            Map<String, Long> roleCount = bought.stream()
                    .collect(Collectors.groupingBy(
                            r -> r.getPlayerRole() != null ? r.getPlayerRole() : "Unknown",
                            Collectors.counting()));

            Map<String, Object> squad = new LinkedHashMap<>();
            squad.put("teamId",          team.getId());
            squad.put("teamName",        team.getTeamName());
            squad.put("captainName",     team.getCaptainName());
            squad.put("teamColor",       team.getTeamColor());
            squad.put("logoPath",        team.getLogoPath());
            squad.put("totalBudget",     team.getTotalBudget());
            squad.put("spentBudget",     Math.round(spent * 100.0) / 100.0);
            squad.put("remainingBudget", Math.round(remaining * 100.0) / 100.0);
            squad.put("budgetUsedPct",   team.getTotalBudget() > 0
                    ? Math.round((spent / team.getTotalBudget()) * 1000.0) / 10.0 : 0);
            squad.put("playerCount",     bought.size());
            squad.put("tierBreakdown",   tierCount);
            squad.put("roleBreakdown",   roleCount);
            squad.put("players",         bought.stream().map(r -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id",        r.getPlayerId());
                p.put("name",      r.getPlayerName());
                p.put("role",      r.getPlayerRole() != null ? r.getPlayerRole() : "—");
                p.put("tier",      r.getPlayerTier() != null ? r.getPlayerTier() : "BRONZE");
                p.put("photo",     r.getPlayerPhotoPath());
                p.put("basePrice", r.getBasePrice());
                p.put("soldPrice", r.getSoldPrice());
                p.put("profit",    r.getBasePrice() != null
                        ? r.getSoldPrice() - r.getBasePrice() : 0);
                return p;
            }).collect(Collectors.toList()));

            return squad;
        }).collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. SINGLE TEAM SQUAD
    // ═══════════════════════════════════════════════════════════════
    public Map<String, Object> getSquad(Long tournamentId, Long teamId) {
        return getAllSquads(tournamentId).stream()
                .filter(s -> teamId.equals(((Number) s.get("teamId")).longValue()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Squad not found"));
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. LEADERBOARD (teams ranked by strategy score)
    // ═══════════════════════════════════════════════════════════════
    // ═══════════════════════════════════════════════════════════════════
// 4. LEADERBOARD (teams ranked by strategy score)
// ═══════════════════════════════════════════════════════════════════
    public List<Map<String, Object>> getLeaderboard(Long tournamentId) {
        List<Map<String, Object>> squads = getAllSquads(tournamentId);

        return squads.stream().map(squad -> {

                    int    players  = toInt(squad.get("playerCount"));
                    double saved    = toDouble(squad.get("remainingBudget"));

                    // ✅ FIX: cast tierBreakdown safely — never use raw Map<?,?>
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tierBreakdown =
                            squad.get("tierBreakdown") instanceof Map
                                    ? (Map<String, Object>) squad.get("tierBreakdown")
                                    : Collections.emptyMap();

                    long platinum = toLong(tierBreakdown.get("PLATINUM"));
                    long gold     = toLong(tierBreakdown.get("GOLD"));
                    long silver   = toLong(tierBreakdown.get("SILVER"));

                    double score = players  * 40.0
                            + platinum * 25.0
                            + gold     * 15.0
                            + silver   *  8.0
                            + saved    *  0.005;

                    Map<String, Object> entry = new LinkedHashMap<>(squad);
                    entry.put("score", Math.round(score));
                    return entry;
                })
                .sorted(Comparator.comparingDouble(
                        s -> -toLong(s.get("score"))))
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. UNSOLD PLAYERS LIST
    // ═══════════════════════════════════════════════════════════════
    public List<Map<String, Object>> getUnsoldPlayers(Long tournamentId) {
        List<Player> unsoldPlayers = playerRepository.findByTournamentIdAndStatus(tournamentId, Player.PlayerStatus.UNSOLD);

        return unsoldPlayers.stream().map(p -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id",        p.getId());
                    map.put("name",      p.getName());
                    map.put("role",      p.getRole() != null      ? p.getRole()      : "—");
                    map.put("tier",      p.getTier() != null      ? p.getTier().name(): "BRONZE");
                    map.put("photo",     p.getPhotoPath());
                    map.put("basePrice", p.getBasePrice());
                    map.put("nationality", p.getNationality());
                    return map;
                })
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════
    // 6. CHART DATA
    // ═══════════════════════════════════════════════════════════════
    public Map<String, Object> getChartData(Long tournamentId) {
        List<Map<String, Object>> squads = getAllSquads(tournamentId);
        List<AuctionResult> results = resultRepository.findByTournamentId(tournamentId);

        // Budget utilisation per team
        List<Map<String, Object>> budgetChart = squads.stream().map(s ->
                Map.<String, Object>of(
                        "team",      s.get("teamName"),
                        "spent",     s.get("spentBudget"),
                        "remaining", s.get("remainingBudget"),
                        "pct",       s.get("budgetUsedPct"),
                        "color",     s.getOrDefault("teamColor", "#888")
                )
        ).collect(Collectors.toList());

        // Tier distribution across all sold players
        Map<Long, AuctionResult> latestSoldByPlayer = results.stream()
                .filter(r -> r.getStatus() == AuctionResult.ResultStatus.SOLD)
                .collect(Collectors.toMap(
                        AuctionResult::getPlayerId,
                        r -> r,
                        (existing, replacement) -> replacement  // keep latest
                ));

        List<AuctionResult> dedupedSold = new ArrayList<>(latestSoldByPlayer.values());

        // Now use dedupedSold instead of results for tier/role distribution:
        Map<String, Long> tierDist = dedupedSold.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getPlayerTier() != null ? r.getPlayerTier() : "BRONZE",
                        Collectors.counting()));

        Map<String, Long> roleDist = dedupedSold.stream()
                .filter(r -> r.getPlayerRole() != null)
                .collect(Collectors.groupingBy(
                        AuctionResult::getPlayerRole, Collectors.counting()));
        // Price progression — sold price over time (auction order)
        List<Map<String, Object>> priceProgress = results.stream()
                .filter(r -> r.getStatus() == AuctionResult.ResultStatus.SOLD
                        && r.getSoldAt() != null)
                .sorted(Comparator.comparing(AuctionResult::getSoldAt))
                .map(r -> Map.<String, Object>of(
                        "player", r.getPlayerName(),
                        "price",  r.getSoldPrice(),
                        "tier",   r.getPlayerTier() != null ? r.getPlayerTier() : "BRONZE",
                        "team",   r.getTeamName() != null ? r.getTeamName() : ""
                ))
                .collect(Collectors.toList());

        // Role distribution


        return Map.of(
                "budgetChart",    budgetChart,
                "tierDist",       tierDist,
                "priceProgress",  priceProgress,
                "roleDist",       roleDist
        );
    }

    // ─────────────────────────────────────────────────────────────────
// TYPE-SAFE HELPERS  (fix for Map<?,?> generics erasure)
// ─────────────────────────────────────────────────────────────────

    /** Safely convert any Number-like object to long */
    private long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number) return ((Number) val).longValue();
        try { return Long.parseLong(val.toString()); }
        catch (NumberFormatException e) { return 0L; }
    }

    /** Safely convert any Number-like object to int */
    private int toInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(val.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    /** Safely convert any Number-like object to double */
    private double toDouble(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(val.toString()); }
        catch (NumberFormatException e) { return 0.0; }
    }
}