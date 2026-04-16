package com.auctionx.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamDTO {
    private String teamName;
    private String captainName;
    private String captainEmail;
    private String captainPhone;
    private String teamColor;
    private Long tournamentId;
}