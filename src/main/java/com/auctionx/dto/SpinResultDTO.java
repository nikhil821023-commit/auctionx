package com.auctionx.dto;

import lombok.*;

/**
 * Broadcast when wheel stops on a player
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpinResultDTO {

    private Long   playerId;
    private String playerName;
    private String playerRole;
    private String playerPhotoPath;
    private String playerTier;
    private Double basePrice;
    private Integer age;
    private String nationality;
    private Integer matches;
    private Double average;
    private Double strikeRate;

    // Wheel animation hint
    private Integer wheelStopIndex;     // index in remaining players list
    private Integer totalPlayersLeft;
}