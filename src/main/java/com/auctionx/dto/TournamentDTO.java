package com.auctionx.dto;

import lombok.*;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TournamentDTO {
    private String name;
    private String sportType;
    private LocalDate tournamentDate;
    private Double teamBudget;
    private Integer maxPlayersPerTeam;
    private Double basePrice;
    private Double bidIncrement;
}