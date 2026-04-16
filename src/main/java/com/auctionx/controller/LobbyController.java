package com.auctionx.controller;

import com.auctionx.dto.*;
import com.auctionx.service.LobbyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/lobby")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class LobbyController {

    private final LobbyService lobbyService;

    // ─────────────────────────────────────────────────────────────────
    // REST ENDPOINTS (HTTP)
    // ─────────────────────────────────────────────────────────────────

    /**
     * GET /api/lobby/{tournamentId}/status
     * Organiser or captain polls current lobby state
     */
    @GetMapping("/{tournamentId}/status")
    public ResponseEntity<LobbyStatusDTO> getLobbyStatus(
            @PathVariable Long tournamentId) {
        return ResponseEntity.ok(lobbyService.getLobbyStatus(tournamentId));
    }

    /**
     * POST /api/lobby/{tournamentId}/settings
     * Organiser saves auction settings (timer, auto-spin, tier order etc.)
     */
    @PostMapping("/{tournamentId}/settings")
    public ResponseEntity<AuctionSettingsDTO> saveSettings(
            @PathVariable Long tournamentId,
            @RequestBody AuctionSettingsDTO dto) {
        dto.setTournamentId(tournamentId);
        return ResponseEntity.ok(lobbyService.saveSettings(dto));
    }

    /**
     * GET /api/lobby/{tournamentId}/settings
     * Get current auction settings
     */
    @GetMapping("/{tournamentId}/settings")
    public ResponseEntity<AuctionSettingsDTO> getSettings(
            @PathVariable Long tournamentId) {
        return ResponseEntity.ok(lobbyService.getSettings(tournamentId));
    }

    /**
     * POST /api/lobby/{tournamentId}/start
     * Organiser fires this to launch the auction
     */
    @PostMapping("/{tournamentId}/start")
    public ResponseEntity<Map<String, String>> startAuction(
            @PathVariable Long tournamentId) {
        lobbyService.startAuction(tournamentId);
        return ResponseEntity.ok(Map.of(
                "status", "AUCTION_STARTING",
                "message", "Broadcast sent to all clients"
        ));
    }

    // ─────────────────────────────────────────────────────────────────
    // WEBSOCKET MESSAGE HANDLERS (STOMP)
    // ─────────────────────────────────────────────────────────────────

    /**
     * WS: Captain sends join request
     * Client sends to: /app/lobby/join
     * Server broadcasts to: /topic/lobby/{tournamentId}
     */
    @MessageMapping("/lobby/join")
    public void captainJoin(
            CaptainJoinDTO dto,
            SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = headerAccessor.getSessionId();
        lobbyService.captainJoin(dto, sessionId);
    }

    /**
     * WS: Captain toggles ready status
     * Client sends to: /app/lobby/ready
     * Payload: { "tournamentId": 1, "ready": true }
     */
    @MessageMapping("/lobby/ready")
    public void setReady(
            @Payload Map<String, Object> payload,
            SimpMessageHeaderAccessor headerAccessor) {
        Long tournamentId = Long.valueOf(payload.get("tournamentId").toString());
        Boolean ready = Boolean.valueOf(payload.get("ready").toString());
        String sessionId = headerAccessor.getSessionId();
        lobbyService.setCaptainReady(tournamentId, sessionId, ready);
    }

    /**
     * WS: Captain sends a chat message in lobby
     * Client sends to: /app/lobby/chat
     * Server broadcasts to: /topic/lobby/{tournamentId}/chat
     */
    @MessageMapping("/lobby/chat")
    public void lobbyChat(
            @Payload Map<String, Object> payload,
            SimpMessageHeaderAccessor headerAccessor) {

        Long tournamentId = Long.valueOf(payload.get("tournamentId").toString());
        String message    = payload.get("message").toString();
        String sender     = payload.get("senderName").toString();

        // Re-broadcast to all in the lobby
        org.springframework.messaging.simp.SimpMessagingTemplate template =
                null; // injected via constructor — see LobbyMessageHandler instead
        // See LobbyMessageHandler.java below for proper injection
    }
}