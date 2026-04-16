package com.auctionx.dto;

import com.auctionx.model.AuctionState;
import com.auctionx.model.BidRecord;
import lombok.*;
import java.util.List;

/**
 * Full auction state broadcast to all clients on every event.
 * This is what every connected screen renders.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuctionStateDTO {

    private Long   tournamentId;
    private String event;               // BID_PLACED, TIMER_TICK, SOLD, UNSOLD, SPIN etc.

    // Current player
    private Long   currentPlayerId;
    private String currentPlayerName;
    private String currentPlayerRole;
    private String currentPlayerPhoto;
    private String currentPlayerTier;
    private Double currentPlayerBasePrice;

    // Bidding state
    private Double  currentBid;
    private Long    highBidderTeamId;
    private String  highBidderTeamName;
    private String  highBidderCaptainName;
    private String  highBidderTeamColor;

    // Timer
    private Integer remainingSeconds;
    private Integer totalTimerSeconds;

    // Phase
    private AuctionState.AuctionPhase phase;

    // Bid history (last 10)
    private List<BidRecord> recentBids;

    // Counts
    private Integer playersRemaining;
    private Integer playersSold;
    private Integer playersUnsold;

    // Alert flags
    private Boolean isBidWar;           // 2 teams bidding repeatedly = true
    private String  alertMessage;       // e.g. "BID WAR! 🔥"
}