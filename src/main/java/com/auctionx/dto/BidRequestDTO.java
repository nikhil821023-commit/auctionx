package com.auctionx.dto;

import lombok.*;

/**
 * Organiser sends this when tapping a captain's bid button
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BidRequestDTO {

    private Long   tournamentId;
    private Long   teamId;           // the team placing the bid
    private String captainName;
    private String teamName;
    private String teamColor;
    private Double bidAmount;        // explicit amount OR null = auto-increment
    private Boolean useAutoIncrement; // true = add bidIncrement to current bid
}