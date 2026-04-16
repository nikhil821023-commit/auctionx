package com.auctionx.model;

import lombok.*;
import java.time.LocalDateTime;

/**
 * Tracks a captain's live presence in the lobby.
 * Stored in-memory (or Redis). NOT a DB entity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LobbySession {

    private String sessionId;       // WebSocket session ID
    private Long teamId;
    private Long tournamentId;
    private String captainName;
    private String teamName;
    private String teamColor;
    private String logoPath;
    private Boolean isReady;        // captain clicked "Ready"
    private LocalDateTime joinedAt;

    public enum LobbyRole {
        ORGANISER, CAPTAIN, SPECTATOR
    }

    private LobbyRole role;
}