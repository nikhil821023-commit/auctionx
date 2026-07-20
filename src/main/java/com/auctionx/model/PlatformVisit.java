package com.auctionx.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "platform_visits")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformVisit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sessionId;
    private String role;           // ORGANIZER / CAPTAIN / SPECTATOR
    private String page;           // which page visited
    private Long   tournamentId;   // nullable
    private String userAgent;      // browser info
    private LocalDateTime visitedAt;

    @PrePersist
    public void setTime() {
        this.visitedAt = LocalDateTime.now();
    }
}