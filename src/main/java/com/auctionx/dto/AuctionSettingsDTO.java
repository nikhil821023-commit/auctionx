package com.auctionx.dto;

import lombok.*;

/**
 * Organiser configures these before starting the auction
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuctionSettingsDTO {
    private Long tournamentId;
    private Integer bidTimerSeconds;       // e.g. 30
    private Integer bidTimerResetSeconds;  // reset on new bid e.g. 10
    private Boolean autoSpin;             // auto-spin wheel after each sold
    private Integer pauseBetweenPlayers;  // seconds pause between auctions
    private Boolean allowTierOrder;       // auction by tier: PLATINUM first
}