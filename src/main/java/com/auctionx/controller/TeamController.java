package com.auctionx.controller;

import com.auctionx.dto.TeamDTO;
import com.auctionx.dto.TeamResponseDTO;
import com.auctionx.service.TeamService;
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
@CrossOrigin(origins = "*")
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
}