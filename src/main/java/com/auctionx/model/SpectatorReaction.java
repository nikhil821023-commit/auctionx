package com.auctionx.model;

import lombok.*;
import java.time.LocalDateTime;

/**
 * In-memory only — not persisted.
 * Represents one emoji reaction from a spectator.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpectatorReaction {
    private String   spectatorId;   // random UUID assigned on join
    private String   spectatorName; // optional nickname
    private String   emoji;         //
    private Long     tournamentId;
    private String   context;       // "BID_PLACED" / "PLAYER_SOLD" / "BID_WAR"
    private LocalDateTime timestamp;
}