package com.auctionx.model;

import lombok.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory state of one live auction session.
 * One instance per tournament while auction is LIVE.
 * Thread-safe fields for concurrent bid handling.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuctionState {

    private Long tournamentId;

    // ── Current player on the block ───────────────────────────────────
    private Player  currentPlayer;       // player being auctioned right now
    private Double  currentBid;          // highest bid so far
    private Long    currentHighBidderTeamId;
    private String  currentHighBidderName;  // captain name shown on screen

    // ── Bid history for this player ───────────────────────────────────
    @Builder.Default
    private List<BidRecord> bidHistory = new ArrayList<>();

    // ── Timer ─────────────────────────────────────────────────────────
    private Integer totalTimerSeconds;   // from settings
    private Integer resetTimerSeconds;   // reset on new bid
    private AtomicInteger remainingSeconds = new AtomicInteger(0);

    // ── State flags ───────────────────────────────────────────────────
    @Builder.Default
    private AtomicBoolean isRunning  = new AtomicBoolean(false);
    @Builder.Default
    private AtomicBoolean isPaused   = new AtomicBoolean(false);
    @Builder.Default
    private AtomicBoolean isBidding  = new AtomicBoolean(false);

    // ── Auction phase ─────────────────────────────────────────────────
    private AuctionPhase phase;

    public enum AuctionPhase {
        IDLE,           // waiting for spin
        SPINNING,       // wheel is spinning
        PLAYER_REVEAL,  // player card revealed, about to start bidding
        BIDDING,        // timer running, bids accepted
        PAUSED,         // organiser paused
        SOLD,           // hammer dropped, player sold
        UNSOLD,         // no bids / marked unsold
        RE_AUCTION,     // re-auctioning unsold players
        COMPLETED       // all players done
    }

    // ── Player queues ─────────────────────────────────────────────────
    @Builder.Default
    private List<Player> remainingPlayers = new ArrayList<>();   // not yet auctioned
    @Builder.Default
    private List<Player> unsoldPlayers    = new ArrayList<>();   // marked unsold
    @Builder.Default
    private List<AuctionResult> soldResults = new ArrayList<>();  // completed sales

    // ── Settings ──────────────────────────────────────────────────────
    private Boolean autoSpin;
    private Integer pauseBetweenPlayersSeconds;
    private Boolean tierOrderEnabled;
    private Double  bidIncrement;

    private LocalDateTime auctionStartedAt;
}