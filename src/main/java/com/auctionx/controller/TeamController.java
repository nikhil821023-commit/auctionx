package com.auctionx.controller;

import com.auctionx.dto.TeamDTO;
import com.auctionx.dto.TeamResponseDTO;
import com.auctionx.service.TeamService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
@Slf4j
public class TeamController {

    private final TeamService teamService;

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<?> registerTeam(
            @RequestPart("data") TeamDTO dto,
            @RequestPart(value = "logo", required = false) MultipartFile logo) {
        try {
            return ResponseEntity.ok(teamService.registerTeam(dto, logo));
        } catch (Exception e) {
            log.error("Team registration failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/teams?tournamentId=14
     * Returns flat TeamResponseDTO list — no circular JSON
     */
    @GetMapping
    public ResponseEntity<?> getTeams(
            @RequestParam(required = false) Long tournamentId) {
        try {
            if (tournamentId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "tournamentId param is required"));
            }
            List<TeamResponseDTO> teams =
                    teamService.getTeamsByTournament(tournamentId);
            log.info("Returning {} teams for tournament {}", teams.size(), tournamentId);
            return ResponseEntity.ok(teams);
        } catch (Exception e) {
            log.error("getTeams failed for tournamentId={}: {}", tournamentId, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getTeam(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(teamService.getTeam(id));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/teams/my-team?tournamentId=14
     * Restores the logged-in captain's own team using their JWT email.
     */
    @GetMapping("/my-team")
    public ResponseEntity<?> getMyTeam(
            @RequestParam Long tournamentId,
            HttpServletRequest request) {
        try {
            String email = (String) request.getAttribute("userEmail");
            if (email == null) {
                return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
            }
            return ResponseEntity.ok(teamService.getMyTeam(tournamentId, email));
        } catch (Exception e) {
            log.error("getMyTeam failed for tournamentId={}: {}", tournamentId, e.getMessage());
            return ResponseEntity.status(404)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
