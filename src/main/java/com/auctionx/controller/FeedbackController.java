package com.auctionx.controller;

import com.auctionx.model.Feedback;
import com.auctionx.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class FeedbackController {

    private final FeedbackService feedbackService;

    /** POST /api/feedback */
    @PostMapping
    public ResponseEntity<?> submit(@RequestBody Feedback feedback) {
        try {
            return ResponseEntity.ok(feedbackService.submitFeedback(feedback));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/feedback/{tournamentId} */
    @GetMapping("/{tournamentId}")
    public ResponseEntity<?> getAll(@PathVariable Long tournamentId) {
        try {
            return ResponseEntity.ok(
                    feedbackService.getFeedbackByTournament(tournamentId));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/feedback/{tournamentId}/summary */
    @GetMapping("/{tournamentId}/summary")
    public ResponseEntity<?> getSummary(@PathVariable Long tournamentId) {
        try {
            return ResponseEntity.ok(
                    feedbackService.getFeedbackSummary(tournamentId));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}