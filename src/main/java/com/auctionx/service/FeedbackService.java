package com.auctionx.service;

import com.auctionx.model.Feedback;
import com.auctionx.repository.FeedbackRepository;
import com.auctionx.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeedbackService {

    private final FeedbackRepository  feedbackRepository;
    private final TournamentRepository tournamentRepository;

    public Feedback submitFeedback(Feedback feedback) {
        // Enrich with tournament name
        tournamentRepository.findById(feedback.getTournamentId())
                .ifPresent(t -> feedback.setTournamentName(t.getName()));

        Feedback saved = feedbackRepository.save(feedback);
        log.info("Feedback submitted for tournament {} by {}",
                feedback.getTournamentId(),
                feedback.getSubmitterName() != null
                        ? feedback.getSubmitterName() : "Anonymous");
        return saved;
    }

    public List<Feedback> getFeedbackByTournament(Long tournamentId) {
        return feedbackRepository.findByTournamentId(tournamentId);
    }

    public Map<String, Object> getFeedbackSummary(Long tournamentId) {
        List<Feedback> all = feedbackRepository.findByTournamentId(tournamentId);
        if (all.isEmpty()) {
            return Map.of("total", 0, "message", "No feedback yet");
        }

        double avgOverall  = all.stream()
                .filter(f -> f.getOverallRating() != null)
                .mapToInt(Feedback::getOverallRating)
                .average().orElse(0);
        double avgEase     = all.stream()
                .filter(f -> f.getPlatformEaseOfUse() != null)
                .mapToInt(Feedback::getPlatformEaseOfUse)
                .average().orElse(0);
        double avgBid      = all.stream()
                .filter(f -> f.getBidProcessRating() != null)
                .mapToInt(Feedback::getBidProcessRating)
                .average().orElse(0);
        long wouldRecommend = all.stream()
                .filter(f -> Boolean.TRUE.equals(f.getWouldRecommend()))
                .count();

        // Feature request counts
        Map<String, Long> featureRequests = new LinkedHashMap<>();
        featureRequests.put("playerStats",
                all.stream().filter(f -> Boolean.TRUE.equals(f.getWantsPlayerStats())).count());
        featureRequests.put("liveStream",
                all.stream().filter(f -> Boolean.TRUE.equals(f.getWantsLiveStream())).count());
        featureRequests.put("teamChat",
                all.stream().filter(f -> Boolean.TRUE.equals(f.getWantsTeamChat())).count());
        featureRequests.put("mobileApp",
                all.stream().filter(f -> Boolean.TRUE.equals(f.getWantsMobileApp())).count());
        featureRequests.put("autoTimer",
                all.stream().filter(f -> Boolean.TRUE.equals(f.getWantsAutoTimer())).count());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total",           all.size());
        summary.put("avgOverall",      Math.round(avgOverall * 10.0) / 10.0);
        summary.put("avgEaseOfUse",    Math.round(avgEase    * 10.0) / 10.0);
        summary.put("avgBidProcess",   Math.round(avgBid     * 10.0) / 10.0);
        summary.put("wouldRecommend",  wouldRecommend);
        summary.put("recommendPct",    all.size() > 0
                ? Math.round((wouldRecommend * 100.0) / all.size()) : 0);
        summary.put("featureRequests", featureRequests);
        summary.put("recentComments",  all.stream()
                .filter(f -> f.getBestPart() != null && !f.getBestPart().isBlank())
                .sorted(Comparator.comparing(Feedback::getSubmittedAt).reversed())
                .limit(5)
                .map(f -> Map.of(
                        "name",    f.getSubmitterName() != null
                                ? f.getSubmitterName() : "Anonymous",
                        "role",    f.getSubmitterRole() != null
                                ? f.getSubmitterRole() : "USER",
                        "comment", f.getBestPart(),
                        "rating",  f.getOverallRating() != null
                                ? f.getOverallRating() : 0
                ))
                .toList());
        return summary;
    }
}