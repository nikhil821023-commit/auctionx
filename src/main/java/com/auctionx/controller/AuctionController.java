package com.auctionx.controller;

import com.auctionx.dto.*;
import com.auctionx.model.AuctionState;
import com.auctionx.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auction")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuctionController {

    private final AuctionEngineService auctionEngine;
    private final DashboardService     dashboardService;
    private final LobbyService         lobbyService;

    /**
     * POST /api/auction/{tournamentId}/init
     * Called right after lobby start — initialises engine
     */
    @PostMapping("/{tournamentId}/init")
    public ResponseEntity<Map<String, Object>> initAuction(
            @PathVariable Long tournamentId) {

        AuctionSettingsDTO settings = lobbyService.getSettings(tournamentId);
        AuctionState state = auctionEngine.initAuction(tournamentId, settings);

        return ResponseEntity.ok(Map.of(
                "status",          "AUCTION_READY",
                "tournamentId",    tournamentId,
                "totalPlayers",    state.getRemainingPlayers().size(),
                "bidTimer",        state.getTotalTimerSeconds(),
                "autoSpin",        state.getAutoSpin()
        ));
    }

    /**
     * POST /api/auction/{tournamentId}/spin
     * Organiser spins the wheel
     */
    @PostMapping("/{tournamentId}/spin")
    public ResponseEntity<SpinResultDTO> spin(@PathVariable Long tournamentId) {
        return ResponseEntity.ok(auctionEngine.spinWheel(tournamentId));
    }

    /**
     * POST /api/auction/{tournamentId}/start-bidding
     * Organiser starts bidding after player reveal
     */
    @PostMapping("/{tournamentId}/start-bidding")
    public ResponseEntity<Map<String, String>> startBidding(
            @PathVariable Long tournamentId) {
        auctionEngine.startBidding(tournamentId);
        return ResponseEntity.ok(Map.of("status", "BIDDING_OPEN"));
    }

    /**
     * POST /api/auction/{tournamentId}/bid
     * Organiser taps a captain's button to place their bid
     */
    @PostMapping("/{tournamentId}/bid")
    public ResponseEntity<AuctionStateDTO> placeBid(
            @PathVariable Long tournamentId,
            @RequestBody BidRequestDTO dto) {
        dto.setTournamentId(tournamentId);
        return ResponseEntity.ok(auctionEngine.placeBid(dto));
    }

    /**
     * POST /api/auction/{tournamentId}/sold
     * Organiser manually triggers sold (before timer ends)
     */
    @PostMapping("/{tournamentId}/sold")
    public ResponseEntity<AuctionStateDTO> sold(@PathVariable Long tournamentId) {
        return ResponseEntity.ok(auctionEngine.soldPlayer(tournamentId));
    }

    /**
     * POST /api/auction/{tournamentId}/unsold
     * Organiser marks current player unsold
     */
    @PostMapping("/{tournamentId}/unsold")
    public ResponseEntity<AuctionStateDTO> unsold(@PathVariable Long tournamentId) {
        return ResponseEntity.ok(auctionEngine.markUnsold(tournamentId));
    }

    /**
     * POST /api/auction/{tournamentId}/pause
     */
    @PostMapping("/{tournamentId}/pause")
    public ResponseEntity<Map<String, String>> pause(@PathVariable Long tournamentId) {
        auctionEngine.pauseAuction(tournamentId);
        return ResponseEntity.ok(Map.of("status", "PAUSED"));
    }

    /**
     * POST /api/auction/{tournamentId}/resume
     */
    @PostMapping("/{tournamentId}/resume")
    public ResponseEntity<Map<String, String>> resume(@PathVariable Long tournamentId) {
        auctionEngine.resumeAuction(tournamentId);
        return ResponseEntity.ok(Map.of("status", "RESUMED"));
    }

    /**
     * POST /api/auction/{tournamentId}/re-auction
     * Move all unsold players back to pool and restart
     */
    @PostMapping("/{tournamentId}/re-auction")
    public ResponseEntity<SpinResultDTO> reAuction(@PathVariable Long tournamentId) {
        return ResponseEntity.ok(auctionEngine.startReAuction(tournamentId));
    }

    /**
     * POST /api/auction/{tournamentId}/complete
     * Organiser manually closes the auction
     */
    @PostMapping("/{tournamentId}/complete")
    public ResponseEntity<Map<String, String>> complete(@PathVariable Long tournamentId) {
        auctionEngine.completeAuction(tournamentId);
        return ResponseEntity.ok(Map.of("status", "AUCTION_COMPLETED"));
    }

    /**
     * GET /api/auction/{tournamentId}/dashboard
     * Poll dashboard anytime
     */
    @GetMapping("/{tournamentId}/dashboard")
    public ResponseEntity<DashboardDTO> getDashboard(@PathVariable Long tournamentId) {
        return ResponseEntity.ok(dashboardService.buildDashboard(tournamentId));
    }

    /**
     * GET /api/auction/{tournamentId}/state
     * Get current engine state snapshot
     */
    @GetMapping("/{tournamentId}/state")
    public ResponseEntity<AuctionStateDTO> getState(@PathVariable Long tournamentId) {
        AuctionState state = auctionEngine.getState(tournamentId);
        return ResponseEntity.ok(Map.of(
                "phase",            state.getPhase(),
                "playersRemaining", state.getRemainingPlayers().size(),
                "playersUnsold",    state.getUnsoldPlayers().size(),
                "currentBid",       state.getCurrentBid() != null ? state.getCurrentBid() : 0
        )).getClass() == null ? null : ResponseEntity.ok(null); // replaced below
        // proper return:
    }

    /**
     * GET /api/auction/{tournamentId}/results
     * All sold/unsold results for this tournament
     */
    @GetMapping("/{tournamentId}/results")
    public ResponseEntity<?> getResults(@PathVariable Long tournamentId) {
        return ResponseEntity.ok(
                dashboardService.buildDashboard(tournamentId)
        );
    }
}