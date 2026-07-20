package com.auctionx.controller;

import com.auctionx.dto.BulkRemoveRequest;
import com.auctionx.dto.PlayerDTO;
import com.auctionx.model.Player;
import com.auctionx.service.PlayerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/players")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class PlayerController {

    private final PlayerService playerService;

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<?> addPlayer(
            @RequestPart("data") PlayerDTO dto,
            @RequestPart(value = "photo", required = false) MultipartFile photo) {
        try {
            return ResponseEntity.ok(playerService.addPlayer(dto, photo));
        } catch (Exception e) {
            log.error("Add player failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** POST /api/players/bulk?tournamentId=1 — CSV only */
    @PostMapping(value = "/bulk", consumes = "multipart/form-data")
    public ResponseEntity<?> bulkUpload(
            @RequestParam("file") MultipartFile csvFile,
            @RequestParam Long tournamentId) {
        try {
            List<Player> players = playerService.bulkUploadPlayers(csvFile, tournamentId);
            return ResponseEntity.ok(Map.of(
                    "count",   players.size(),
                    "players", players.stream().map(p -> Map.of(
                            "id",   p.getId(),
                            "name", p.getName(),
                            "tier", p.getTier() != null ? p.getTier().name() : "BRONZE",
                            "photo", p.getPhotoPath() != null ? p.getPhotoPath() : ""
                    )).toList()
            ));
        } catch (Exception e) {
            log.error("Bulk upload failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/players/bulk-zip?tournamentId=1
     * ZIP file containing players.csv + images/ folder
     */
    @PostMapping(value = "/bulk-zip", consumes = "multipart/form-data")
    public ResponseEntity<?> bulkUploadWithImages(
            @RequestParam("file") MultipartFile zipFile,
            @RequestParam Long tournamentId) {
        try {
            List<Player> players =
                    playerService.bulkUploadWithImages(zipFile, tournamentId);
            return ResponseEntity.ok(Map.of(
                    "count",   players.size(),
                    "message", players.size() + " players uploaded with images",
                    "players", players.stream().map(p -> Map.of(
                            "id",    p.getId(),
                            "name",  p.getName(),
                            "photo", p.getPhotoPath() != null ? p.getPhotoPath() : "none",
                            "tier",  p.getTier() != null ? p.getTier().name() : "BRONZE"
                    )).toList()
            ));
        } catch (Exception e) {
            log.error("Bulk ZIP upload failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> getPlayers(@RequestParam Long tournamentId) {
        try {
            return ResponseEntity.ok(playerService.getPlayersByTournament(tournamentId));
        } catch (Exception e) {
            log.error("Get players failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/available")
    public ResponseEntity<?> getAvailablePlayers(@RequestParam Long tournamentId) {
        try {
            return ResponseEntity.ok(playerService.getAvailablePlayers(tournamentId));
        } catch (Exception e) {
            log.error("Get available players failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * DELETE /api/players/{playerId}
     * Remove single player from pool
     * ✅ FIXED: Path variable syntax corrected from /{/playerId} to /{playerId}
     */
    @DeleteMapping("/{playerId}")
    public ResponseEntity<?> removePlayer(@PathVariable Long playerId) {
        try {
            log.info("Removing player ID: {}", playerId);
            playerService.removePlayer(playerId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "player removed successfully",
                    "playerId", playerId));
        } catch (Exception e) {
            log.error("Remove player {} failed: {}", playerId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * POST /api/players/bulk-remove
     * Remove multiple players
     * ✅ FIXED: Using BulkRemoveRequest DTO instead of raw Map
     * ✅ FIXED: Removed duplicate method
     */
    @PostMapping("/bulk-remove")
    public ResponseEntity<?> removePlayers(@RequestBody BulkRemoveRequest request) {
        try {
            List<Long> ids = request.getPlayerIds();
            if (ids == null || ids.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "playerIds list is required"));
            }
            log.info("Bulk removing {} players", ids.size());
            return ResponseEntity.ok(playerService.removePlayers(ids));
        } catch (Exception e) {
            log.error("Bulk remove failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * PUT /api/players/{playerId}
     * Edit player details before auction
     */
    @PutMapping(value = "/{playerId}", consumes = "multipart/form-data")
    public ResponseEntity<?> updatePlayer(
            @PathVariable Long playerId,
            @RequestPart("data") PlayerDTO dto,
            @RequestPart(value = "photo", required = false) MultipartFile photo) {
        try {
            log.info("Updating player ID: {}", playerId);
            return ResponseEntity.ok(playerService.updatePlayer(playerId, dto, photo));
        } catch (Exception e) {
            log.error("Update player {} failed: {}", playerId, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
