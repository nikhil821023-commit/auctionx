package com.auctionx.controller;

import com.auctionx.dto.TournamentDTO;
import com.auctionx.model.Tournament;
import com.auctionx.service.TournamentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/tournaments")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TournamentController {

    private final TournamentService tournamentService;

    // POST /api/tournaments  (multipart: dto fields + optional logo file)
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<Tournament> createTournament(
            @RequestPart("data") TournamentDTO dto,
            @RequestPart(value = "logo", required = false) MultipartFile logo)
            throws IOException {
        return ResponseEntity.ok(tournamentService.createTournament(dto, logo));
    }

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
}