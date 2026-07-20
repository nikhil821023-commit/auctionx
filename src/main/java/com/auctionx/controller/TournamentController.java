package com.auctionx.controller;

import com.auctionx.dto.TournamentDTO;
import com.auctionx.model.Tournament;
import com.auctionx.repository.TournamentRepository;
import com.auctionx.service.TournamentSchedulerService;
import com.auctionx.service.TournamentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tournaments")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TournamentController {

    private final TournamentService tournamentService;
    private final TournamentRepository tournamentRepository;

    @Autowired
    private TournamentSchedulerService schedulerService;

    // GET /api/tournaments
    @GetMapping
    public ResponseEntity<List<Tournament>> getAll() {
        return ResponseEntity.ok(tournamentService.getAllTournaments());
    }

    // GET /api/tournaments/{id}
    @GetMapping("/{id}")
    public ResponseEntity<Tournament> getById(@PathVariable Long id) {
        return ResponseEntity.ok(tournamentService.getTournament(id));
    }

    // GET /api/tournaments/join/{code}
    @GetMapping("/join/{code}")
    public ResponseEntity<Tournament> getByJoinCode(@PathVariable String code) {
        return ResponseEntity.ok(tournamentService.getTournamentByJoinCode(code));
    }

    // PATCH /api/tournaments/{id}/status
    @PatchMapping("/{id}/status")
    public ResponseEntity<Tournament> updateStatus(
            @PathVariable Long id,
            @RequestParam Tournament.TournamentStatus status) {
        return ResponseEntity.ok(tournamentService.updateStatus(id, status));
    }

    /**
     * POST /api/tournaments/{id}/schedule
     * Organizer schedules auction for a future date
     */
    @PostMapping("/{id}/schedule")
    public ResponseEntity<?> schedule(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            LocalDateTime time = LocalDateTime.parse(
                    body.get("scheduledTime").toString());
            Integer days    = body.get("reservedDays") != null
                    ? Integer.valueOf(body.get("reservedDays").toString()) : 3;
            Boolean auto    = body.get("autoStart") != null
                    && Boolean.parseBoolean(
                    body.get("autoStart").toString());
            String  email   = body.getOrDefault(
                    "organizerEmail", "").toString();
            String  name    = body.getOrDefault(
                    "organizerName", "Organizer").toString();

            return ResponseEntity.ok(
                    schedulerService.scheduleTournament(
                            id, time, days, auto, email, name));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/tournaments/{id}/postpone
     * Organizer moves auction to new date
     */
    @PostMapping("/{id}/postpone")
    public ResponseEntity<?> postpone(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            LocalDateTime newTime = LocalDateTime.parse(
                    body.get("newTime").toString());
            String  reason    = body.getOrDefault(
                    "reason", "").toString();
            Integer extendDays = body.get("extendDays") != null
                    ? Integer.valueOf(
                    body.get("extendDays").toString()) : 3;

            return ResponseEntity.ok(
                    schedulerService.postponeTournament(
                            id, newTime, reason, extendDays));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/tournaments/{id}/extend
     * Extend reservation by more days
     */
    @PostMapping("/{id}/extend")
    public ResponseEntity<?> extend(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            Integer days = Integer.valueOf(
                    body.get("additionalDays").toString());
            return ResponseEntity.ok(
                    schedulerService.extendReservation(id, days));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/tournaments/{id}/cancel
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            String reason = body.getOrDefault(
                    "reason", "Cancelled by organizer").toString();
            return ResponseEntity.ok(
                    schedulerService.cancelTournament(id, reason));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/tournaments/{id}/status
     * Full schedule + expiry info
     */
    @GetMapping("/{id}/status")
    public ResponseEntity<?> getStatus(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(
                    schedulerService.getTournamentStatus(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/tournaments/by-organizer?email=xxx
     */
    @GetMapping("/by-organizer")
    public ResponseEntity<?> getByOrganizer(
            @RequestParam String email) {
        try {
            return ResponseEntity.ok(
                    tournamentRepository.findByOrganizerEmail(email));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // --- NEW: Create tournament with multipart/form-data and user info ---
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<?> createTournament(
            @RequestPart("data") TournamentDTO dto,
            @RequestPart(value = "logo", required = false) MultipartFile logo,
            HttpServletRequest request)
            throws IOException {
        try {
            Long   userId = (Long)   request.getAttribute("userId");
            String name   = (String) request.getAttribute("userName");
            String email  = (String) request.getAttribute("userEmail");

            // Pass owner info to service
            return ResponseEntity.ok(
                    tournamentService.createTournament(
                            dto, logo, userId, name, email));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // --- NEW: Get tournaments created by current authenticated user ---
    @GetMapping("/my")
    public ResponseEntity<?> getMyTournaments(
            HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            return ResponseEntity.ok(
                    tournamentRepository.findByCreatedByUserId(userId));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

}