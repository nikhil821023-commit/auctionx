package com.auctionx.controller;

import com.auctionx.model.SpectatorReaction;
import com.auctionx.service.SpectatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/spectators")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SpectatorController {

    private final SpectatorService spectatorService;

    /** GET /api/spectators/{tournamentId}/count */
    @GetMapping("/{tournamentId}/count")
    public ResponseEntity<?> getCount(@PathVariable Long tournamentId) {
        return ResponseEntity.ok(Map.of(
                "count", spectatorService.getSpectatorCount(tournamentId)
        ));
    }

    /** GET /api/spectators/{tournamentId}/reactions */
    @GetMapping("/{tournamentId}/reactions")
    public ResponseEntity<?> getReactions(@PathVariable Long tournamentId) {
        return ResponseEntity.ok(
                spectatorService.getReactionCounts(tournamentId));
    }

    // ── WebSocket handlers ────────────────────────────────────────

    /** /app/spectator/{tid}/join */
    @MessageMapping("/spectator/{tid}/join")
    public void join(
            @DestinationVariable Long tid,
            @Payload Map<String, String> payload,
            SimpMessageHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        String nickname  = payload.getOrDefault("nickname", "Spectator");
        spectatorService.joinAsSpectator(tid, sessionId, nickname);
    }

    /** /app/spectator/{tid}/leave */
    @MessageMapping("/spectator/{tid}/leave")
    public void leave(
            @DestinationVariable Long tid,
            SimpMessageHeaderAccessor accessor) {
        spectatorService.leaveAsSpectator(tid, accessor.getSessionId());
    }

    /** /app/spectator/{tid}/react */
    @MessageMapping("/spectator/{tid}/react")
    public void react(
            @DestinationVariable Long tid,
            @Payload Map<String, String> payload,
            SimpMessageHeaderAccessor accessor) {
        SpectatorReaction reaction = SpectatorReaction.builder()
                .spectatorId(accessor.getSessionId())
                .spectatorName(payload.get("nickname"))
                .emoji(payload.get("emoji"))
                .tournamentId(tid)
                .context(payload.getOrDefault("context", ""))
                .build();
        try {
            spectatorService.sendReaction(reaction);
        } catch (Exception e) {
            // invalid emoji — ignore silently
        }
    }
}