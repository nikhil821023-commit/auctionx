package com.auctionx.dto;

import lombok.*;

/**
 * Sent by captain when joining lobby via join code
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaptainJoinDTO {
    private String joinCode;        // 6-char tournament join code
    private Long teamId;            // captain's registered team ID
    private String captainName;
}