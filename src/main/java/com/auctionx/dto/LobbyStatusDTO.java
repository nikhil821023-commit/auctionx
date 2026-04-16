package com.auctionx.dto;

import com.auctionx.model.LobbySession;
import lombok.*;
import java.util.List;

/**
 * Broadcast to all connected clients whenever lobby state changes
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LobbyStatusDTO {

    private Long tournamentId;
    private String tournamentName;
    private String sportType;

    private Integer totalTeams;
    private Integer readyTeams;
    private Integer totalPlayersInPool;

    private List<LobbySession> connectedCaptains;

    private AuctionSettingsSnapshot settings;
    private String lobbyState;   // WAITING, READY_TO_START, STARTING

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuctionSettingsSnapshot {
        private Integer bidTimerSeconds;
        private Integer bidTimerResetSeconds;
        private Boolean autoSpin;
        private Integer pauseBetweenPlayers;
        private Boolean allowTierOrder;
    }
}