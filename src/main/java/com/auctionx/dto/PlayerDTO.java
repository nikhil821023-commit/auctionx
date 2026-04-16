package com.auctionx.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlayerDTO {
    private String name;
    private String role;
    private String nationality;
    private Integer age;
    private Integer matches;
    private Double average;
    private Double strikeRate;
    private Double basePrice;
    private String tier;
    private Long tournamentId;
}