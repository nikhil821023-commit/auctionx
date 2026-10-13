package com.auctionx.dto;

import lombok.*;
import java.util.List;
import java.util.Map;

/**
 * Live dashboard state — one TeamCard per team.
 * Broadcast after every SOLD event.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardDTO {

    private Long    tournamentId;
    private String  tournamentName;
    private Integer totalPlayersInPool;
    private Integer playersSold;
    private Integer playersUnsold;
    private Integer playersRemaining;
    private Integer maxPlayersPerTeam;   // ✅ NEW (outer level)
    private Double  bidIncrement;        // ✅ NEW (outer level)

    private List<TeamCard> teams;
    private List<AuctionFeedItem> recentActivity; // last 20 sold/unsold events

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TeamCard {
        private Long   teamId;
        private String teamName;
        private String captainName;
        private String teamColor;
        private String logoPath;

        private Double totalBudget;
        private Double spentBudget;
        private Double remainingBudget;
        private Double budgetUsedPercent;   // 0-100

        private Integer playerCount;
        private List<PlayerMini> players;   // players won so far

        // ✅ NEW — squad progress (these belong on the team, not the outer class)
        private Integer playersBought;
        private Integer playersNeeded;
        private Integer maxPlayers;
        private List<Map<String, Object>> recentPlayers;  // last 5 bought
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlayerMini {
        private Long   playerId;
        private String playerName;
        private String playerRole;
        private String photoPath;
        private Double soldPrice;
        private String tier;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuctionFeedItem {
        private String event;           // "SOLD" or "UNSOLD"
        private String playerName;
        private String playerRole;
        private String playerTier;
        private String teamName;
        private String captainName;
        private String teamColor;
        private Double soldPrice;
        private String timestamp;
    }
}