package com.auctionx.model;

import lombok.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuctionState {

    private Long tournamentId;

    private Player  currentPlayer;
    private Double  currentBid;
    private Long    currentHighBidderTeamId;
    private String  currentHighBidderName;

    // Add these two lines after currentHighBidderName field:
    private String currentHighBidderTeamName;
    private String currentHighBidderTeamColor;

    @Builder.Default
    private List<BidRecord> bidHistory = new ArrayList<>();

    private Integer totalTimerSeconds;
    private Integer resetTimerSeconds;

    // ✅ FIX: Use @Builder.Default so Lombok initializes these properly
    @Builder.Default
    private AtomicInteger remainingSeconds = new AtomicInteger(0);

    @Builder.Default
    private AtomicBoolean isRunning = new AtomicBoolean(false);

    @Builder.Default
    private AtomicBoolean isPaused = new AtomicBoolean(false);

    @Builder.Default
    private AtomicBoolean isBidding = new AtomicBoolean(false);

    private AuctionPhase phase;

    private BidMode bidMode;  // add after phase field

    public enum AuctionPhase {
        IDLE, SPINNING, PLAYER_REVEAL, BIDDING,
        PAUSED, SOLD, UNSOLD, RE_AUCTION, COMPLETED
    }

    @Builder.Default
    private List<Player> remainingPlayers = new ArrayList<>();

    @Builder.Default
    private List<Player> unsoldPlayers = new ArrayList<>();

    @Builder.Default
    private List<AuctionResult> soldResults = new ArrayList<>();

    private Boolean autoSpin;
    private Integer pauseBetweenPlayersSeconds;
    private Boolean tierOrderEnabled;
    private Double  bidIncrement;

    private LocalDateTime auctionStartedAt;

    // ✅ FIX: Safe getters that never return null
    public AtomicInteger getRemainingSeconds() {
        if (remainingSeconds == null) remainingSeconds = new AtomicInteger(0);
        return remainingSeconds;
    }

    public AtomicBoolean getIsRunning() {
        if (isRunning == null) isRunning = new AtomicBoolean(false);
        return isRunning;
    }

    public AtomicBoolean getIsPaused() {
        if (isPaused == null) isPaused = new AtomicBoolean(false);
        return isPaused;
    }

    public AtomicBoolean getIsBidding() {
        if (isBidding == null) isBidding = new AtomicBoolean(false);
        return isBidding;
    }
}