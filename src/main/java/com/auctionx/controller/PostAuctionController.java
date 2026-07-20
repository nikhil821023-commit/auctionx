package com.auctionx.controller;

import com.auctionx.service.PostAuctionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/post-auction")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class PostAuctionController {

    private final PostAuctionService postAuctionService;

    /** GET /api/post-auction/{tid}/summary */
    @GetMapping("/{tid}/summary")
    public ResponseEntity<?> getSummary(@PathVariable Long tid) {
        try { return ResponseEntity.ok(postAuctionService.getTournamentSummary(tid)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    /** GET /api/post-auction/{tid}/squads */
    @GetMapping("/{tid}/squads")
    public ResponseEntity<?> getAllSquads(@PathVariable Long tid) {
        try { return ResponseEntity.ok(postAuctionService.getAllSquads(tid)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    /** GET /api/post-auction/{tid}/squads/{teamId} */
    @GetMapping("/{tid}/squads/{teamId}")
    public ResponseEntity<?> getSquad(@PathVariable Long tid, @PathVariable Long teamId) {
        try { return ResponseEntity.ok(postAuctionService.getSquad(tid, teamId)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    /** GET /api/post-auction/{tid}/leaderboard */
    @GetMapping("/{tid}/leaderboard")
    public ResponseEntity<?> getLeaderboard(@PathVariable Long tid) {
        try { return ResponseEntity.ok(postAuctionService.getLeaderboard(tid)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    /** GET /api/post-auction/{tid}/unsold */
    @GetMapping("/{tid}/unsold")
    public ResponseEntity<?> getUnsold(@PathVariable Long tid) {
        try { return ResponseEntity.ok(postAuctionService.getUnsoldPlayers(tid)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    /** GET /api/post-auction/{tid}/charts */
    @GetMapping("/{tid}/charts")
    public ResponseEntity<?> getCharts(@PathVariable Long tid) {
        try { return ResponseEntity.ok(postAuctionService.getChartData(tid)); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }
}