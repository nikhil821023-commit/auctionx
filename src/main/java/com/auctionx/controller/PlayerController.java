package com.auctionx.controller;

import com.auctionx.dto.PlayerDTO;
import com.auctionx.model.Player;
import com.auctionx.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/players")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PlayerController {

    private final PlayerService playerService;

    // POST /api/players  (single player with optional photo)
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<Player> addPlayer(
            @RequestPart("data") PlayerDTO dto,
            @RequestPart(value = "photo", required = false) MultipartFile photo)
            throws IOException {
        return ResponseEntity.ok(playerService.addPlayer(dto, photo));
    }

    // POST /api/players/bulk?tournamentId=1  (CSV upload)
    @PostMapping(value = "/bulk", consumes = "multipart/form-data")
    public ResponseEntity<List<Player>> bulkUpload(
            @RequestParam("file") MultipartFile csvFile,
            @RequestParam Long tournamentId)
            throws IOException {
        return ResponseEntity.ok(playerService.bulkUploadPlayers(csvFile, tournamentId));
    }

    // GET /api/players?tournamentId=1
    @GetMapping
    public ResponseEntity<List<Player>> getPlayers(
            @RequestParam Long tournamentId) {
        return ResponseEntity.ok(playerService.getPlayersByTournament(tournamentId));
    }

    // GET /api/players/available?tournamentId=1
    @GetMapping("/available")
    public ResponseEntity<List<Player>> getAvailablePlayers(
            @RequestParam Long tournamentId) {
        return ResponseEntity.ok(playerService.getAvailablePlayers(tournamentId));
    }
}