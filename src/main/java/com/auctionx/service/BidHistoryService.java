package com.auctionx.service;

import com.auctionx.model.*;
import com.auctionx.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BidHistoryService {

    private final AuctionResultRepository  resultRepository;
    private final PlayerRepository         playerRepository;
    private final TeamRepository           teamRepository;
    private final TournamentRepository     tournamentRepository;
    private final ObjectMapper             objectMapper;

    // ═══════════════════════════════════════════════════════════════
    // 1. FULL BID HISTORY FOR TOURNAMENT
    //    Every player — every bid placed — in auction order
    // ═══════════════════════════════════════════════════════════════
    public Map<String, Object> getTournamentBidHistory(Long tournamentId) {

        List<AuctionResult> results = resultRepository
                .findByTournamentId(tournamentId)
                .stream()
                .filter(r -> r.getSoldAt() != null)
                .sorted(Comparator.comparing(AuctionResult::getSoldAt))
                .collect(Collectors.toList());

        List<Map<String, Object>> playerHistories = results.stream()
                .map(r -> buildPlayerBidEntry(r, tournamentId))
                .collect(Collectors.toList());

        // Summary stats
        OptionalDouble maxBids = results.stream()
                .filter(r -> r.getTotalBids() != null)
                .mapToInt(AuctionResult::getTotalBids)
                .average();

        AuctionResult mostContested = results.stream()
                .filter(r -> r.getTotalBids() != null)
                .max(Comparator.comparingInt(AuctionResult::getTotalBids))
                .orElse(null);

        AuctionResult quickestSold = results.stream()
                .filter(r -> r.getStatus() == AuctionResult.ResultStatus.SOLD
                        && r.getTotalBids() != null)
                .min(Comparator.comparingInt(AuctionResult::getTotalBids))
                .orElse(null);

        double totalBidsPlaced = results.stream()
                .filter(r -> r.getTotalBids() != null)
                .mapToInt(AuctionResult::getTotalBids)
                .sum();

        return Map.of(
                "tournamentId",   tournamentId,
                "totalPlayers",   results.size(),
                "totalBidsPlaced",totalBidsPlaced,
                "avgBidsPerPlayer", maxBids.orElse(0),
                "mostContested",  mostContested != null ? Map.of(
                        "player", mostContested.getPlayerName(),
                        "bids",   mostContested.getTotalBids(),
                        "price",  mostContested.getSoldPrice(),
                        "team",   mostContested.getTeamName() != null
                                ? mostContested.getTeamName() : "Unsold"
                ) : null,
                "quickestSold", quickestSold != null ? Map.of(
                        "player", quickestSold.getPlayerName(),
                        "bids",   quickestSold.getTotalBids(),
                        "price",  quickestSold.getSoldPrice()
                ) : null,
                "players",     playerHistories
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. BID HISTORY FOR SINGLE PLAYER
    // ═══════════════════════════════════════════════════════════════
    public Map<String, Object> getPlayerBidHistory(Long playerId) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() ->
                        new RuntimeException("Player not found: " + playerId));

        // Get the auction result for this player
        AuctionResult result = resultRepository
                .findByTournamentId(player.getTournament().getId())
                .stream()
                .filter(r -> playerId.equals(r.getPlayerId()))
                .findFirst()
                .orElse(null);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("playerId",    player.getId());
        profile.put("name",        player.getName());
        profile.put("role",        player.getRole());
        profile.put("tier",        player.getTier() != null
                ? player.getTier().name() : "BRONZE");
        profile.put("photo",       player.getPhotoPath());
        profile.put("nationality", player.getNationality());
        profile.put("age",         player.getAge());
        profile.put("matches",     player.getMatches());
        profile.put("average",     player.getAverage());
        profile.put("strikeRate",  player.getStrikeRate());
        profile.put("basePrice",   player.getBasePrice());
        profile.put("status",      player.getStatus().name());

        if (result != null) {
            profile.put("soldPrice",   result.getSoldPrice());
            profile.put("totalBids",   result.getTotalBids());
            profile.put("soldTo",      result.getTeamName());
            profile.put("soldToColor", result.getTeamColor());
            profile.put("auctionStatus", result.getStatus().name());
            profile.put("soldAt",      result.getSoldAt() != null
                    ? result.getSoldAt().toString() : null);
            // Parse bid timeline if stored as JSON
            profile.put("bidTimeline", parseBidTimeline(result));
            // Price increase percentage
            if (player.getBasePrice() != null
                    && player.getBasePrice() > 0
                    && result.getSoldPrice() != null) {
                double pct = ((result.getSoldPrice() - player.getBasePrice())
                        / player.getBasePrice()) * 100;
                profile.put("priceIncreasePct", Math.round(pct));
            }
        }

        return profile;
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. TEAM SPENDING ANALYSIS
    // ═══════════════════════════════════════════════════════════════
    public Map<String, Object> getTeamSpendingAnalysis(Long tournamentId) {
        List<Team> teams = teamRepository.findByTournamentId(tournamentId);

        List<Map<String, Object>> teamAnalysis = teams.stream().map(team -> {

                    List<AuctionResult> bought = resultRepository
                            .findByTeamId(team.getId())
                            .stream()
                            .filter(r -> tournamentId.equals(r.getTournamentId())
                                    && r.getStatus() ==
                                    AuctionResult.ResultStatus.SOLD)
                            .sorted(Comparator.comparing(
                                    AuctionResult::getSoldPrice).reversed())
                            .collect(Collectors.toList());

                    double totalSpent = bought.stream()
                            .mapToDouble(AuctionResult::getSoldPrice).sum();
                    double avgPerPlayer = bought.isEmpty() ? 0
                            : totalSpent / bought.size();
                    double maxSpend = bought.stream()
                            .mapToDouble(AuctionResult::getSoldPrice)
                            .max().orElse(0);

                    // Spend by tier
                    Map<String, Double> byTier = bought.stream()
                            .collect(Collectors.groupingBy(
                                    r -> r.getPlayerTier() != null
                                            ? r.getPlayerTier() : "BRONZE",
                                    Collectors.summingDouble(
                                            AuctionResult::getSoldPrice)));

                    // Spend by role
                    Map<String, Double> byRole = bought.stream()
                            .filter(r -> r.getPlayerRole() != null)
                            .collect(Collectors.groupingBy(
                                    AuctionResult::getPlayerRole,
                                    Collectors.summingDouble(
                                            AuctionResult::getSoldPrice)));

                    // Value index: base price / sold price ratio
                    // Higher = better value (paid less over base)
                    double valueIndex = bought.stream()
                            .filter(r -> r.getBasePrice() != null
                                    && r.getBasePrice() > 0)
                            .mapToDouble(r -> r.getBasePrice() / r.getSoldPrice())
                            .average().orElse(0);

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("teamId",       team.getId());
                    m.put("teamName",     team.getTeamName());
                    m.put("captainName",  team.getCaptainName());
                    m.put("teamColor",    team.getTeamColor());
                    m.put("totalBudget",  team.getTotalBudget());
                    m.put("totalSpent",   Math.round(totalSpent));
                    m.put("remaining",    Math.round(team.getTotalBudget()
                            - totalSpent));
                    m.put("playerCount",  bought.size());
                    m.put("avgPerPlayer", Math.round(avgPerPlayer));
                    m.put("biggestBuy",   maxSpend);
                    m.put("valueIndex",   Math.round(valueIndex * 100.0)
                            / 100.0);
                    m.put("spendByTier",  byTier);
                    m.put("spendByRole",  byRole);
                    m.put("topPlayers",   bought.stream().limit(3).map(r ->
                            Map.of("name",  r.getPlayerName(),
                                    "price", r.getSoldPrice(),
                                    "role",  r.getPlayerRole() != null
                                            ? r.getPlayerRole() : "—",
                                    "photo", r.getPlayerPhotoPath() != null
                                            ? r.getPlayerPhotoPath() : "")
                    ).collect(Collectors.toList()));
                    return m;

                }).sorted(Comparator.comparingDouble(
                        m -> -((Number) m.get("totalSpent")).doubleValue()))
                .collect(Collectors.toList());

        return Map.of(
                "tournamentId", tournamentId,
                "teams",        teamAnalysis
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. PLAYER OF THE TOURNAMENT (MVP)
    // ═══════════════════════════════════════════════════════════════
    public Map<String, Object> getPlayerOfTournament(Long tournamentId) {
        List<AuctionResult> sold = resultRepository
                .findByTournamentIdAndStatus(
                        tournamentId, AuctionResult.ResultStatus.SOLD);

        if (sold.isEmpty()) return Map.of("message", "No sales yet");

        // MVP Score = (soldPrice / basePrice) * tierMultiplier * bidCount
        Map<String, Double> tierMultiplier = Map.of(
                "PLATINUM", 4.0, "GOLD", 3.0,
                "SILVER",   2.0, "BRONZE", 1.0
        );

        AuctionResult mvp = sold.stream()
                .filter(r -> r.getBasePrice() != null
                        && r.getBasePrice() > 0)
                .max(Comparator.comparingDouble(r -> {
                    double ratio    = r.getSoldPrice() / r.getBasePrice();
                    double tier     = tierMultiplier.getOrDefault(
                            r.getPlayerTier(), 1.0);
                    double bids     = r.getTotalBids() != null
                            ? r.getTotalBids() : 1;
                    return ratio * tier * Math.log1p(bids);
                }))
                .orElse(null);

        // Most wanted — most bids placed
        AuctionResult mostWanted = sold.stream()
                .filter(r -> r.getTotalBids() != null)
                .max(Comparator.comparingInt(AuctionResult::getTotalBids))
                .orElse(null);

        // Best bargain — lowest sold/base ratio
        AuctionResult bargain = sold.stream()
                .filter(r -> r.getBasePrice() != null
                        && r.getBasePrice() > 0)
                .min(Comparator.comparingDouble(
                        r -> r.getSoldPrice() / r.getBasePrice()))
                .orElse(null);

        Map<String, Object> result = new LinkedHashMap<>();

        if (mvp != null) result.put("mvp", Map.of(
                "name",       mvp.getPlayerName(),
                "role",       mvp.getPlayerRole() != null
                        ? mvp.getPlayerRole() : "—",
                "tier",       mvp.getPlayerTier() != null
                        ? mvp.getPlayerTier() : "—",
                "photo",      mvp.getPlayerPhotoPath() != null
                        ? mvp.getPlayerPhotoPath() : "",
                "basePrice",  mvp.getBasePrice(),
                "soldPrice",  mvp.getSoldPrice(),
                "team",       mvp.getTeamName() != null
                        ? mvp.getTeamName() : "—",
                "teamColor",  mvp.getTeamColor() != null
                        ? mvp.getTeamColor() : "#888",
                "bids",       mvp.getTotalBids() != null
                        ? mvp.getTotalBids() : 0,
                "pricePct",   mvp.getBasePrice() > 0
                        ? Math.round(((mvp.getSoldPrice() - mvp.getBasePrice())
                        / mvp.getBasePrice()) * 100) : 0
        ));

        if (mostWanted != null) result.put("mostWanted", Map.of(
                "name",  mostWanted.getPlayerName(),
                "bids",  mostWanted.getTotalBids(),
                "price", mostWanted.getSoldPrice(),
                "team",  mostWanted.getTeamName() != null
                        ? mostWanted.getTeamName() : "—"
        ));

        if (bargain != null) result.put("bestBargain", Map.of(
                "name",      bargain.getPlayerName(),
                "basePrice", bargain.getBasePrice(),
                "soldPrice", bargain.getSoldPrice(),
                "team",      bargain.getTeamName() != null
                        ? bargain.getTeamName() : "—",
                "ratio",     Math.round((bargain.getSoldPrice()
                        / bargain.getBasePrice()) * 100.0) / 100.0
        ));

        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. AUCTION PACE TRACKER
    //    How fast each round was — duration per player
    // ═══════════════════════════════════════════════════════════════
    public List<Map<String, Object>> getAuctionPace(Long tournamentId) {
        return resultRepository
                .findByTournamentId(tournamentId)
                .stream()
                .filter(r -> r.getSoldAt() != null)
                .sorted(Comparator.comparing(AuctionResult::getSoldAt))
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("playerName",  r.getPlayerName());
                    m.put("playerTier",  r.getPlayerTier());
                    m.put("status",      r.getStatus().name());
                    m.put("totalBids",   r.getTotalBids() != null
                            ? r.getTotalBids() : 0);
                    m.put("soldPrice",   r.getSoldPrice());
                    m.put("basePrice",   r.getBasePrice());
                    m.put("teamName",    r.getTeamName() != null
                            ? r.getTeamName() : "Unsold");
                    m.put("teamColor",   r.getTeamColor() != null
                            ? r.getTeamColor() : "#555");
                    m.put("soldAt",      r.getSoldAt().toString());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // ── Private helpers ───────────────────────────────────────────
    private Map<String, Object> buildPlayerBidEntry(
            AuctionResult r, Long tournamentId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("playerId",    r.getPlayerId());
        m.put("playerName",  r.getPlayerName());
        m.put("playerRole",  r.getPlayerRole());
        m.put("playerTier",  r.getPlayerTier());
        m.put("playerPhoto", r.getPlayerPhotoPath());
        m.put("basePrice",   r.getBasePrice());
        m.put("soldPrice",   r.getSoldPrice());
        m.put("totalBids",   r.getTotalBids() != null
                ? r.getTotalBids() : 0);
        m.put("status",      r.getStatus().name());
        m.put("soldTo",      r.getTeamName());
        m.put("soldToColor", r.getTeamColor());
        m.put("soldAt",      r.getSoldAt() != null
                ? r.getSoldAt().toString() : null);
        m.put("bidTimeline", parseBidTimeline(r));
        if (r.getBasePrice() != null && r.getBasePrice() > 0
                && r.getSoldPrice() != null) {
            m.put("priceJump", Math.round(
                    ((r.getSoldPrice() - r.getBasePrice())
                            / r.getBasePrice()) * 100));
        } else {
            m.put("priceJump", 0);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseBidTimeline(AuctionResult r) {
        if (r.getBidTimeline() == null || r.getBidTimeline().isBlank()) {
            // No stored timeline — reconstruct minimal from available data
            if (r.getStatus() == AuctionResult.ResultStatus.SOLD) {
                return List.of(Map.of(
                        "teamName",  r.getTeamName() != null
                                ? r.getTeamName() : "—",
                        "teamColor", r.getTeamColor() != null
                                ? r.getTeamColor() : "#888",
                        "amount",    r.getSoldPrice() != null
                                ? r.getSoldPrice() : 0,
                        "isWinner",  true
                ));
            }
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    r.getBidTimeline(), List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}