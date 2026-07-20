package com.auctionx.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaptainJoinDTO {
    private String joinCode;
    private Long   teamId;
    private String captainName;
    private Long   tournamentId;  // ← fallback when joinCode is empty
}