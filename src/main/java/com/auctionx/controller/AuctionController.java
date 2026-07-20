package com.auctionx.controller;

import com.auctionx.dto.*;
import com.auctionx.model.AuctionState;
import com.auctionx.model.BidMode;
import com.auctionx.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auction")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuctionController {

    private final AuctionEngineService auctionEngine;
    private final DashboardService     dashboardService;
    private final LobbyService         lobbyService;

    @PostMapping("/{tournamentId}/init")
    public ResponseEntity<Map<String, Object>> initAuction(
            @PathVariable Long tournamentId) {
        try {
            // ✅ getSettings NEVER throws — always returns defaults
            AuctionSettingsDTO settings = lobbyService.getSettings(tournamentId);
            AuctionState state = auctionEngine.initAuction(tournamentId, settings);

            return ResponseEntity.ok(Map.of(
                    "status",       "AUCTION_READY",
                    "tournamentId", tournamentId,
                    "totalPlayers", state.getRemainingPlayers().size(),
                    "bidTimer",     state.getTotalTimerSeconds(),
                    "autoSpin",     state.getAutoSpin() != null && state.getAutoSpin()
            ));

        } catch (Exception e) {
            log.error("Auction init failed for tournament {}: {}", tournamentId, e.getMessage());
            // ✅ Return 200 with error key so frontend catch block reads it cleanly
            return ResponseEntity.ok(Map.of(
                    "status", "INIT_FAILED",
                    "error",  e.getMessage() != null ? e.getMessage() : "Unknown error"
            ));
        }
    }

    @PostMapping("/{tournamentId}/spin")
    public ResponseEntity<?> spin(@PathVariable Long tournamentId) {
        try {
            return ResponseEntity.ok(auctionEngine.spinWheel(tournamentId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{tournamentId}/start-bidding")
    public ResponseEntity<?> startBidding(@PathVariable Long tournamentId) {
        try {
            auctionEngine.startBidding(tournamentId);
            return ResponseEntity.ok(Map.of("status", "BIDDING_OPEN"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{tournamentId}/bid")
    public ResponseEntity<?> placeBid(
            @PathVariable Long tournamentId,
            @RequestBody BidRequestDTO dto) {
        try {
            dto.setTournamentId(tournamentId);
            return ResponseEntity.ok(auctionEngine.placeBid(dto));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/auction/{tournamentId}/self-bid
     * Called by CAPTAIN from their own screen (Mode 2)
     */
    @PostMapping("/{tournamentId}/self-bid")
    public ResponseEntity<?> selfBid(
            @PathVariable Long tournamentId,
            @RequestBody BidRequestDTO dto) {
        try {
            dto.setTournamentId(tournamentId);
            dto.setBidMode(BidMode.CAPTAIN_SELF);
            return ResponseEntity.ok(auctionEngine.placeBid(dto));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/auction/{tournamentId}/captain-token?teamId=5
     * Captain fetches their token once after joining
     */
    @GetMapping("/{tournamentId}/captain-token")
    public ResponseEntity<?> getCaptainToken(
            @PathVariable Long tournamentId,
            @RequestParam Long teamId) {
        try {
            String token = auctionEngine.generateCaptainToken(teamId, tournamentId);
            return ResponseEntity.ok(Map.of(
                    "token",        token,
                    "teamId",       teamId,
                    "tournamentId", tournamentId
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/auction/{tournamentId}/bid-mode
     * Returns which mode is active for this tournament
     */
    @GetMapping("/{tournamentId}/bid-mode")
    public ResponseEntity<?> getBidMode(@PathVariable Long tournamentId) {
        try {
            AuctionState state = auctionEngine.getState(tournamentId);
            return ResponseEntity.ok(Map.of(
                    "bidMode", state.getBidMode() != null
                            ? state.getBidMode().name()
                            : "ORGANIZER_CONTROLLED"
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("bidMode", "ORGANIZER_CONTROLLED"));
        }
    }

    /**
     * POST /api/auction/{tournamentId}/set-bid-mode
     * Organiser switches mode mid-auction if needed
     */
    @PostMapping("/{tournamentId}/set-bid-mode")
    public ResponseEntity<?> setBidMode(
            @PathVariable Long tournamentId,
            @RequestParam String mode) {
        try {
            BidMode bidMode = BidMode.valueOf(mode);
            auctionEngine.setBidMode(tournamentId, bidMode);
            return ResponseEntity.ok(Map.of("bidMode", mode));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{tournamentId}/sold")
    public ResponseEntity<?> sold(@PathVariable Long tournamentId) {
        try {
            return ResponseEntity.ok(auctionEngine.soldPlayer(tournamentId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{tournamentId}/unsold")
    public ResponseEntity<?> unsold(@PathVariable Long tournamentId) {
        try {
            return ResponseEntity.ok(auctionEngine.markUnsold(tournamentId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{tournamentId}/pause")
    public ResponseEntity<?> pause(@PathVariable Long tournamentId) {
        try {
            auctionEngine.pauseAuction(tournamentId);
            return ResponseEntity.ok(Map.of("status", "PAUSED"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{tournamentId}/resume")
    public ResponseEntity<?> resume(@PathVariable Long tournamentId) {
        try {
            auctionEngine.resumeAuction(tournamentId);
            return ResponseEntity.ok(Map.of("status", "RESUMED"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{tournamentId}/re-auction")
    public ResponseEntity<?> reAuction(@PathVariable Long tournamentId) {
        try {
            return ResponseEntity.ok(auctionEngine.startReAuction(tournamentId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{tournamentId}/complete")
    public ResponseEntity<?> complete(@PathVariable Long tournamentId) {
        try {
            auctionEngine.completeAuction(tournamentId);
            return ResponseEntity.ok(Map.of("status", "AUCTION_COMPLETED"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{tournamentId}/dashboard")
    public ResponseEntity<?> getDashboard(@PathVariable Long tournamentId) {
        try {
            return ResponseEntity.ok(dashboardService.buildDashboard(tournamentId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{tournamentId}/state")
    public ResponseEntity<?> getState(@PathVariable Long tournamentId) {
        try {
            AuctionState state = auctionEngine.getState(tournamentId);
            return ResponseEntity.ok(Map.of(
                    "phase",            state.getPhase().name(),
                    "playersRemaining", state.getRemainingPlayers().size(),
                    "playersUnsold",    state.getUnsoldPlayers().size(),
                    "currentBid",       state.getCurrentBid() != null ? state.getCurrentBid() : 0,
                    "isPaused",         state.getIsPaused().get(),
                    "isRunning",        state.getIsRunning().get()
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "phase", "IDLE", "playersRemaining", 0,
                    "playersUnsold", 0, "currentBid", 0,
                    "isPaused", false, "isRunning", false
            ));
        }
    }

    @GetMapping("/{tournamentId}/results")
    public ResponseEntity<?> getResults(@PathVariable Long tournamentId) {
        try {
            return ResponseEntity.ok(dashboardService.buildDashboard(tournamentId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}