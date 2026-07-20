package com.auctionx.dto;

import lombok.*;

/**
 * Safe flat DTO for returning team data to frontend.
 * Avoids circular JSON from JPA entity relations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamResponseDTO {
    private Long   id;
    private String teamName;
    private String captainName;
    private String captainEmail;
    private String captainPhone;
    private String teamColor;
    private String logoPath;
    private Double totalBudget;
    private Double spentBudget;
    private Double remainingBudget;
    private Long   tournamentId;
}