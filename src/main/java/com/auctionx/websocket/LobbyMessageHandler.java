package com.auctionx.websocket;

import com.auctionx.service.LobbyService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Handles all STOMP WebSocket messages for the lobby room.
 * Separated from REST controller for clarity.
 */
@Controller
@RequiredArgsConstructor
public class LobbyMessageHandler {

    private final SimpMessagingTemplate messagingTemplate;
    private final LobbyService lobbyService;

    /**
     * Captain joins lobby room
     * Subscribe to: /topic/lobby/{tournamentId}
     */
    @MessageMapping("/lobby/{tournamentId}/ping")
    public void handlePing(
            @DestinationVariable Long tournamentId,
            SimpMessageHeaderAccessor headerAccessor) {

        String sessionId = headerAccessor.getSessionId();

        // Reply back to just this client with pong
        messagingTemplate.convertAndSendToUser(
                sessionId,
                "/queue/pong",
                Map.of(
                        "event", "PONG",
                        "tournamentId", tournamentId,
                        "serverTime", LocalDateTime.now().toString()
                )
        );
    }

    /**
     * Lobby chat message
     * Client sends to: /app/lobby/{tournamentId}/chat
     * Broadcasts to:   /topic/lobby/{tournamentId}/chat
     */
    @MessageMapping("/lobby/{tournamentId}/chat")
    public void handleChat(
            @DestinationVariable Long tournamentId,
            @Payload Map<String, String> payload,
            SimpMessageHeaderAccessor headerAccessor) {

        messagingTemplate.convertAndSend(
                "/topic/lobby/" + tournamentId + "/chat",
                Map.of(
                        "event", "CHAT_MESSAGE",
                        "sender", payload.getOrDefault("senderName", "Unknown"),
                        "teamName", payload.getOrDefault("teamName", ""),
                        "message", payload.getOrDefault("message", ""),
                        "time", LocalDateTime.now().toString()
                )
        );
    }

    /**
     * Organiser broadcasts a countdown before auction starts
     * Client sends to: /app/lobby/{tournamentId}/countdown
     */
    @MessageMapping("/lobby/{tournamentId}/countdown")
    public void handleCountdown(
            @DestinationVariable Long tournamentId,
            @Payload Map<String, Object> payload) {

        int seconds = Integer.parseInt(
                payload.getOrDefault("seconds", "5").toString());

        messagingTemplate.convertAndSend(
                "/topic/lobby/" + tournamentId + "/countdown",
                Map.of(
                        "event", "COUNTDOWN",
                        "seconds", seconds,
                        "message", "Auction starts in " + seconds + " seconds!"
                )
        );
    }
}