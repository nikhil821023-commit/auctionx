package com.auctionx.websocket;

import com.auctionx.dto.BidRequestDTO;
import com.auctionx.service.AuctionEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;

/**
 * STOMP WebSocket handlers for real-time auction room events.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class AuctionMessageHandler {

    private final AuctionEngineService  auctionEngine;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Organiser spins wheel via WebSocket
     * /app/auction/{tournamentId}/spin
     */
    @MessageMapping("/auction/{tournamentId}/spin")
    public void spin(@DestinationVariable Long tournamentId,
                     SimpMessageHeaderAccessor accessor) {
        try {
            auctionEngine.spinWheel(tournamentId);
        } catch (Exception e) {
            sendError(tournamentId, accessor.getSessionId(), e.getMessage());
        }
    }

    /**
     * Organiser starts bidding after reveal
     * /app/auction/{tournamentId}/start-bidding
     */
    @MessageMapping("/auction/{tournamentId}/start-bidding")
    public void startBidding(@DestinationVariable Long tournamentId,
                             SimpMessageHeaderAccessor accessor) {
        try {
            auctionEngine.startBidding(tournamentId);
        } catch (Exception e) {
            sendError(tournamentId, accessor.getSessionId(), e.getMessage());
        }
    }

    /**
     * Organiser taps captain button to register a bid
     * /app/auction/{tournamentId}/bid
     * Payload: BidRequestDTO
     */
    @MessageMapping("/auction/{tournamentId}/bid")
    public void placeBid(@DestinationVariable Long tournamentId,
                         @Payload BidRequestDTO dto,
                         SimpMessageHeaderAccessor accessor) {
        try {
            dto.setTournamentId(tournamentId);
            auctionEngine.placeBid(dto);
        } catch (Exception e) {
            sendError(tournamentId, accessor.getSessionId(), e.getMessage());
        }
    }

    /**
     * Organiser pauses/resumes
     * /app/auction/{tournamentId}/control
     * Payload: { "action": "PAUSE" | "RESUME" | "SOLD" | "UNSOLD" }
     */
    @MessageMapping("/auction/{tournamentId}/control")
    public void control(@DestinationVariable Long tournamentId,
                        @Payload Map<String, String> payload,
                        SimpMessageHeaderAccessor accessor) {
        String action = payload.getOrDefault("action", "");
        try {
            switch (action) {
                case "PAUSE"   -> auctionEngine.pauseAuction(tournamentId);
                case "RESUME"  -> auctionEngine.resumeAuction(tournamentId);
                case "SOLD"    -> auctionEngine.soldPlayer(tournamentId);
                case "UNSOLD"  -> auctionEngine.markUnsold(tournamentId);
                case "COMPLETE"-> auctionEngine.completeAuction(tournamentId);
                default -> log.warn("Unknown auction control action: {}", action);
            }
        } catch (Exception e) {
            sendError(tournamentId, accessor.getSessionId(), e.getMessage());
        }
    }

    private void sendError(Long tournamentId, String sessionId, String message) {
        messagingTemplate.convertAndSendToUser(
                sessionId, "/queue/errors",
                Map.of("error", message, "tournamentId", tournamentId)
        );
    }
}