package com.auctionx.controller;

import com.auctionx.model.Tournament;
import com.auctionx.repository.TournamentRepository;
import com.auctionx.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Simple join-code validation before captain enters lobby
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {

    private final TournamentRepository tournamentRepository;
    private final TeamRepository teamRepository;

    /**
     * POST /api/auth/validate-join
     * Captain submits join code + teamId to get tournament info
     */
    @PostMapping("/validate-join")
    public ResponseEntity<?> validateJoin(@RequestBody Map<String, Object> body) {
        String joinCode = body.get("joinCode").toString();
        Long teamId     = Long.valueOf(body.get("teamId").toString());

        Tournament tournament = tournamentRepository.findByJoinCode(joinCode)
                .orElseThrow(() -> new RuntimeException("Invalid join code"));

        boolean teamBelongs = teamRepository.findByTournamentId(tournament.getId())
                .stream().anyMatch(t -> t.getId().equals(teamId));

        if (!teamBelongs) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Team does not belong to this tournament"));
        }

        if (tournament.getStatus() == Tournament.TournamentStatus.COMPLETED) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Tournament is already completed"));
        }

        return ResponseEntity.ok(Map.of(
                "valid", true,
                "tournamentId", tournament.getId(),
                "tournamentName", tournament.getName(),
                "status", tournament.getStatus()
        ));
    }
}