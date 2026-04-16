package com.auctionx.model;

import lombok.*;
import java.time.LocalDateTime;

/**
 * Records each individual bid during an auction round.
 * Kept in-memory inside AuctionState.bidHistory.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BidRecord {

    private Long   teamId;
    private String teamName;
    private String captainName;
    private String teamColor;
    private Double amount;
    private LocalDateTime timestamp;
    private Integer timerSnapshotSeconds;  // how many seconds were left when bid placed
}