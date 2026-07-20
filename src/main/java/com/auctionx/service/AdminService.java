package com.auctionx.service;

import com.auctionx.model.*;
import com.auctionx.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {

    private final PlatformVisitRepository  visitRepository;
    private final FeedbackRepository       feedbackRepository;
    private final TournamentRepository     tournamentRepository;
    private final TeamRepository           teamRepository;
    private final PlayerRepository         playerRepository;
    private final AuctionResultRepository  resultRepository;

    // ── Track a visit ─────────────────────────────────────────────
    public void trackVisit(String sessionId, String role,
                           String page, Long tournamentId,
                           String userAgent) {
        try {
            PlatformVisit visit = PlatformVisit.builder()
                    .sessionId(sessionId)
                    .role(role)
                    .page(page)
                    .tournamentId(tournamentId)
                    .userAgent(userAgent)
                    .build();
            visitRepository.save(visit);
        } catch (Exception e) {
            log.warn("Failed to track visit: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PLATFORM ANALYTICS
    // ═══════════════════════════════════════════════════════════════
    public Map<String, Object> getPlatformAnalytics() {
        LocalDateTime now     = LocalDateTime.now();
        LocalDateTime today   = now.toLocalDate().atStartOfDay();
        LocalDateTime week    = now.minusDays(7);
        LocalDateTime month   = now.minusDays(30);

        // Visit counts
        long totalVisits    = visitRepository.count();
        long todayVisits    = visitRepository.countByVisitedAtAfter(today);
        long weekVisits     = visitRepository.countByVisitedAtAfter(week);
        long uniqueWeek     = visitRepository.countUniqueVisitorsSince(week);

        // Role breakdown
        long organizers = visitRepository.countByRole("ORGANIZER");
        long captains   = visitRepository.countByRole("CAPTAIN");
        long spectators = visitRepository.countByRole("SPECTATOR");

        // Page popularity
        List<Object[]> pageRaw = visitRepository.getPageVisitCounts();
        List<Map<String, Object>> topPages = pageRaw.stream()
                .limit(10)
                .map(row -> Map.<String, Object>of(
                        "page",  row[0] != null ? row[0].toString() : "unknown",
                        "count", ((Number) row[1]).longValue()
                ))
                .collect(Collectors.toList());

        // Daily chart (last 14 days)
        LocalDateTime twoWeeks = now.minusDays(14);
        List<Object[]> dailyRaw = visitRepository.getDailyVisitsSince(twoWeeks);
        List<Map<String, Object>> dailyChart = dailyRaw.stream()
                .map(row -> Map.<String, Object>of(
                        "date",  row[0].toString(),
                        "count", ((Number) row[1]).longValue()
                ))
                .collect(Collectors.toList());

        // Tournament stats
        long totalTournaments   = tournamentRepository.count();
        long completedTournaments = tournamentRepository
                .findAll().stream()
                .filter(t -> t.getStatus() ==
                        Tournament.TournamentStatus.COMPLETED)
                .count();
        long liveTournaments = tournamentRepository
                .findAll().stream()
                .filter(t -> t.getStatus() ==
                        Tournament.TournamentStatus.LIVE)
                .count();

        // Platform totals
        long totalTeams   = teamRepository.count();
        long totalPlayers = playerRepository.count();
        long totalSales   = resultRepository
                .findAll().stream()
                .filter(r -> r.getStatus() ==
                        AuctionResult.ResultStatus.SOLD)
                .count();
        double totalMoneyTransacted = resultRepository
                .findAll().stream()
                .filter(r -> r.getStatus() ==
                        AuctionResult.ResultStatus.SOLD
                        && r.getSoldPrice() != null)
                .mapToDouble(AuctionResult::getSoldPrice)
                .sum();

        // Feedback stats
        long totalFeedback = feedbackRepository.count();
        double avgRating   = feedbackRepository.findAll().stream()
                .filter(f -> f.getOverallRating() != null)
                .mapToInt(Feedback::getOverallRating)
                .average().orElse(0);

        Map<String, Object> analytics = new LinkedHashMap<>();

        // Traffic
        analytics.put("traffic", Map.of(
                "totalVisits",  totalVisits,
                "todayVisits",  todayVisits,
                "weekVisits",   weekVisits,
                "uniqueWeek",   uniqueWeek,
                "dailyChart",   dailyChart
        ));

        // Users
        analytics.put("users", Map.of(
                "organizers", organizers,
                "captains",   captains,
                "spectators", spectators,
                "total",      organizers + captains + spectators
        ));

        // Pages
        analytics.put("topPages", topPages);

        // Platform
        analytics.put("platform", Map.of(
                "totalTournaments",     totalTournaments,
                "completedTournaments", completedTournaments,
                "liveTournaments",      liveTournaments,
                "totalTeams",           totalTeams,
                "totalPlayers",         totalPlayers,
                "totalSales",           totalSales,
                "totalMoneyTransacted", Math.round(totalMoneyTransacted)
        ));

        // Feedback
        analytics.put("feedback", Map.of(
                "totalResponses", totalFeedback,
                "averageRating",  Math.round(avgRating * 10.0) / 10.0
        ));

        return analytics;
    }

    // ═══════════════════════════════════════════════════════════════
    // ALL FEEDBACK
    // ═══════════════════════════════════════════════════════════════
    public Map<String, Object> getAllFeedback() {
        List<Feedback> all = feedbackRepository
                .findAll().stream()
                .sorted(Comparator.comparing(
                        Feedback::getSubmittedAt).reversed())
                .collect(Collectors.toList());

        double avgOverall = all.stream()
                .filter(f -> f.getOverallRating() != null)
                .mapToInt(Feedback::getOverallRating)
                .average().orElse(0);

        long recommend = all.stream()
                .filter(f -> Boolean.TRUE.equals(f.getWouldRecommend()))
                .count();

        // Rating distribution
        Map<Integer, Long> ratingDist = all.stream()
                .filter(f -> f.getOverallRating() != null)
                .collect(Collectors.groupingBy(
                        Feedback::getOverallRating, Collectors.counting()));

        // Feature requests
        Map<String, Long> features = new LinkedHashMap<>();
        features.put("Player Stats",
                all.stream().filter(f ->
                        Boolean.TRUE.equals(f.getWantsPlayerStats())).count());
        features.put("Live Stream",
                all.stream().filter(f ->
                        Boolean.TRUE.equals(f.getWantsLiveStream())).count());
        features.put("Team Chat",
                all.stream().filter(f ->
                        Boolean.TRUE.equals(f.getWantsTeamChat())).count());
        features.put("Mobile App",
                all.stream().filter(f ->
                        Boolean.TRUE.equals(f.getWantsMobileApp())).count());
        features.put("Auto Timer",
                all.stream().filter(f ->
                        Boolean.TRUE.equals(f.getWantsAutoTimer())).count());

        // Role breakdown
        Map<String, Long> byRole = all.stream()
                .filter(f -> f.getSubmitterRole() != null)
                .collect(Collectors.groupingBy(
                        Feedback::getSubmitterRole, Collectors.counting()));

        return Map.of(
                "total",           all.size(),
                "avgRating",       Math.round(avgOverall * 10.0) / 10.0,
                "recommendCount",  recommend,
                "recommendPct",    all.size() > 0
                        ? Math.round((recommend * 100.0) / all.size()) : 0,
                "ratingDist",      ratingDist,
                "featureRequests", features,
                "byRole",          byRole,
                "entries",         all.stream().map(f -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id",           f.getId());
                    entry.put("name",         f.getSubmitterName() != null
                            ? f.getSubmitterName() : "Anonymous");
                    entry.put("role",         f.getSubmitterRole());
                    entry.put("tournament",   f.getTournamentName());
                    entry.put("overall",      f.getOverallRating());
                    entry.put("ease",         f.getPlatformEaseOfUse());
                    entry.put("bid",          f.getBidProcessRating());
                    entry.put("recommend",    f.getWouldRecommend());
                    entry.put("bestPart",     f.getBestPart());
                    entry.put("improve",      f.getImproveSuggestion());
                    entry.put("comments",     f.getAdditionalComments());
                    entry.put("submittedAt",  f.getSubmittedAt() != null
                            ? f.getSubmittedAt().toString() : "");
                    return entry;
                }).collect(Collectors.toList())
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // ALL TOURNAMENTS OVERVIEW
    // ═══════════════════════════════════════════════════════════════
    public List<Map<String, Object>> getAllTournamentsOverview() {
        return tournamentRepository.findAll().stream()
                .sorted(Comparator.comparing(
                        Tournament::getId).reversed())
                .map(t -> {
                    long teams   = teamRepository
                            .findByTournamentId(t.getId()).size();
                    long players = playerRepository
                            .findByTournamentId(t.getId()).size();
                    long sold    = resultRepository
                            .findByTournamentId(t.getId()).stream()
                            .filter(r -> r.getStatus() ==
                                    AuctionResult.ResultStatus.SOLD)
                            .count();
                    double money = resultRepository
                            .findByTournamentId(t.getId()).stream()
                            .filter(r -> r.getStatus() ==
                                    AuctionResult.ResultStatus.SOLD
                                    && r.getSoldPrice() != null)
                            .mapToDouble(AuctionResult::getSoldPrice)
                            .sum();
                    long feedback = feedbackRepository
                            .countByTournament(t.getId());

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",         t.getId());
                    m.put("name",       t.getName());
                    m.put("sportType",  t.getSportType());
                    m.put("status",     t.getStatus().name());
                    m.put("date",       t.getTournamentDate() != null
                            ? t.getTournamentDate().toString() : "");
                    m.put("joinCode",   t.getJoinCode());
                    m.put("teams",      teams);
                    m.put("players",    players);
                    m.put("sold",       sold);
                    m.put("money",      Math.round(money));
                    m.put("feedback",   feedback);
                    m.put("budget",     t.getTeamBudget());
                    return m;
                })
                .collect(Collectors.toList());
    }
}