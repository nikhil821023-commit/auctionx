package com.auctionx.controller;

import com.auctionx.service.DraftModeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/draft")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DraftModeController {

    private final DraftModeService draftModeService;

    /**
     * POST /api/draft/{tournamentId}/start
     * Body: {
     *   roundConfigs: [...],       optional — uses defaults if empty
     *   maxPlayersPerTeamPerRound: 1,
     *   bidTimerSeconds: 30,
     *   autoAdvance: true
     * }
     */
    @PostMapping("/{tournamentId}/start")
    public ResponseEntity<?> startDraft(
            @PathVariable Long tournamentId,
            @RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rounds =
                    (List<Map<String, Object>>) body.get("roundConfigs");

            Integer max  = body.get("maxPlayersPerTeamPerRound") != null
                    ? Integer.valueOf(body.get("maxPlayersPerTeamPerRound").toString())
                    : 0;
            Integer timer = body.get("bidTimerSeconds") != null
                    ? Integer.valueOf(body.get("bidTimerSeconds").toString())
                    : 30;
            Boolean auto  = body.get("autoAdvance") != null
                    && Boolean.parseBoolean(body.get("autoAdvance").toString());

            return ResponseEntity.ok(
                    draftModeService.startDraft(
                            tournamentId, rounds, max, timer, auto));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/draft/{tournamentId}/state */
    @GetMapping("/{tournamentId}/state")
    public ResponseEntity<?> getState(
            @PathVariable Long tournamentId) {
        try {
            return ResponseEntity.ok(
                    draftModeService.getDraftState(tournamentId));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/draft/{tournamentId}/sold
     * Called by AuctionEngine after a player is sold
     */
    @PostMapping("/{tournamentId}/sold")
    public ResponseEntity<?> recordSold(
            @PathVariable Long tournamentId,
            @RequestBody Map<String, Object> body) {
        try {
            Long    playerId   = Long.valueOf(body.get("playerId").toString());
            Long    teamId     = Long.valueOf(body.get("teamId").toString());
            Double  soldPrice  = Double.valueOf(body.get("soldPrice").toString());
            Integer totalBids  = body.get("totalBids") != null
                    ? Integer.valueOf(body.get("totalBids").toString()) : 0;

            draftModeService.recordDraftSold(
                    tournamentId, playerId, teamId, soldPrice, totalBids);
            return ResponseEntity.ok(Map.of("recorded", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** POST /api/draft/{tournamentId}/unsold */
    @PostMapping("/{tournamentId}/unsold")
    public ResponseEntity<?> recordUnsold(
            @PathVariable Long tournamentId,
            @RequestBody Map<String, Object> body) {
        try {
            Long playerId = Long.valueOf(body.get("playerId").toString());
            draftModeService.recordDraftUnsold(tournamentId, playerId);
            return ResponseEntity.ok(Map.of("recorded", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** POST /api/draft/{tournamentId}/skip-round */
    @PostMapping("/{tournamentId}/skip-round")
    public ResponseEntity<?> skipRound(
            @PathVariable Long tournamentId) {
        try {
            return ResponseEntity.ok(
                    draftModeService.skipToNextRound(tournamentId));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/draft/{tournamentId}/summary */
    @GetMapping("/{tournamentId}/summary")
    public ResponseEntity<?> getSummary(
            @PathVariable Long tournamentId) {
        try {
            return ResponseEntity.ok(
                    draftModeService.getDraftSummary(tournamentId));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/draft/{tournamentId}/can-pick?teamId=3
     * Check if a team has reached their pick limit in current round
     */
    @GetMapping("/{tournamentId}/can-pick")
    public ResponseEntity<?> canPick(
            @PathVariable Long tournamentId,
            @RequestParam Long teamId) {
        try {
            boolean canPick = draftModeService
                    .canTeamPickInCurrentRound(tournamentId, teamId);
            return ResponseEntity.ok(Map.of("canPick", canPick));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}