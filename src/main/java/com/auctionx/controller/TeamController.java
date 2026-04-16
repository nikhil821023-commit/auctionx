package com.auctionx.controller;

import com.auctionx.dto.TeamDTO;
import com.auctionx.model.Team;
import com.auctionx.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TeamController {

    private final TeamService teamService;

    // POST /api/teams
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<Team> registerTeam(
            @RequestPart("data") TeamDTO dto,
            @RequestPart(value = "logo", required = false) MultipartFile logo)
            throws IOException {
        return ResponseEntity.ok(teamService.registerTeam(dto, logo));
    }

    // GET /api/teams?tournamentId=1
    @GetMapping
    public ResponseEntity<List<Team>> getTeams(
            @RequestParam Long tournamentId) {
        return ResponseEntity.ok(teamService.getTeamsByTournament(tournamentId));
    }

    // GET /api/teams/{id}
    @GetMapping("/{id}")
    public ResponseEntity<Team> getTeam(@PathVariable Long id) {
        return ResponseEntity.ok(teamService.getTeam(id));
    }
}