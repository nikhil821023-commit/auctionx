package com.auctionx.controller;

import com.auctionx.service.BidHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/bid-history")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BidHistoryController {

    private final BidHistoryService bidHistoryService;

    /** GET /api/bid-history/{tournamentId} */
    @GetMapping("/{tournamentId}")
    public ResponseEntity<?> getTournamentHistory(
            @PathVariable Long tournamentId) {
        try {
            return ResponseEntity.ok(
                    bidHistoryService.getTournamentBidHistory(tournamentId));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/bid-history/player/{playerId} */
    @GetMapping("/player/{playerId}")
    public ResponseEntity<?> getPlayerHistory(
            @PathVariable Long playerId) {
        try {
            return ResponseEntity.ok(
                    bidHistoryService.getPlayerBidHistory(playerId));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/bid-history/{tournamentId}/spending */
    @GetMapping("/{tournamentId}/spending")
    public ResponseEntity<?> getSpending(
            @PathVariable Long tournamentId) {
        try {
            return ResponseEntity.ok(
                    bidHistoryService.getTeamSpendingAnalysis(tournamentId));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/bid-history/{tournamentId}/mvp */
    @GetMapping("/{tournamentId}/mvp")
    public ResponseEntity<?> getMvp(
            @PathVariable Long tournamentId) {
        try {
            return ResponseEntity.ok(
                    bidHistoryService.getPlayerOfTournament(tournamentId));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/bid-history/{tournamentId}/pace */
    @GetMapping("/{tournamentId}/pace")
    public ResponseEntity<?> getPace(
            @PathVariable Long tournamentId) {
        try {
            return ResponseEntity.ok(
                    bidHistoryService.getAuctionPace(tournamentId));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}