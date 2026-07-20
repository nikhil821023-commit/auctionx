package com.auctionx.controller;

import com.auctionx.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdminController {

    private final AdminService adminService;

    // Simple secret key check — replace with proper auth in production
    private static final String ADMIN_KEY = "auctionx_admin_2024";

    private boolean isAuthorized(String key) {
        return ADMIN_KEY.equals(key);
    }

    /** GET /api/admin/analytics?key=xxx */
    @GetMapping("/analytics")
    public ResponseEntity<?> getAnalytics(
            @RequestParam String key) {
        if (!isAuthorized(key)) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Unauthorized"));
        }
        try {
            return ResponseEntity.ok(
                    adminService.getPlatformAnalytics());
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/admin/feedback?key=xxx */
    @GetMapping("/feedback")
    public ResponseEntity<?> getAllFeedback(
            @RequestParam String key) {
        if (!isAuthorized(key)) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Unauthorized"));
        }
        try {
            return ResponseEntity.ok(adminService.getAllFeedback());
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/admin/tournaments?key=xxx */
    @GetMapping("/tournaments")
    public ResponseEntity<?> getTournaments(
            @RequestParam String key) {
        if (!isAuthorized(key)) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Unauthorized"));
        }
        try {
            return ResponseEntity.ok(
                    adminService.getAllTournamentsOverview());
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /** POST /api/admin/track (called by frontend on page load) */
    @PostMapping("/track")
    public ResponseEntity<?> trackVisit(
            @RequestBody Map<String, String> body) {
        try {
            adminService.trackVisit(
                    body.get("sessionId"),
                    body.get("role"),
                    body.get("page"),
                    body.get("tournamentId") != null
                            ? Long.parseLong(body.get("tournamentId")) : null,
                    body.get("userAgent")
            );
            return ResponseEntity.ok(Map.of("tracked", true));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("tracked", false));
        }
    }
}