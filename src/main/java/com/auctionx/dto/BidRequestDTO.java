package com.auctionx.dto;

import com.auctionx.model.BidMode;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BidRequestDTO {

    private Long      tournamentId;
    private Long      teamId;
    private String    captainName;
    private String    teamName;
    private String    teamColor;

    // ── Amount fields ──────────────────────────────────────────────
    private Double    bidAmount;          // explicit amount (both modes)
    private Boolean   useAutoIncrement;   // true = add bidIncrement to current

    // ── Mode ──────────────────────────────────────────────────────
    private BidMode   bidMode;            // ORGANIZER_CONTROLLED | CAPTAIN_SELF

    // ── Self-bid validation (Mode 2) ──────────────────────────────
    private String    captainToken;       // simple auth: teamId + tournamentId hash
}