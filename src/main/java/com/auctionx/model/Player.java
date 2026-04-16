package com.auctionx.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "players")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String role;             // Batsman, Bowler, All-Rounder, WK
    private String nationality;
    private Integer age;
    private String photoPath;

    // Stats
    private Integer matches;
    private Double average;
    private Double strikeRate;

    private Double basePrice;

    @Enumerated(EnumType.STRING)
    private PlayerTier tier;         // PLATINUM, GOLD, SILVER, BRONZE

    @Enumerated(EnumType.STRING)
    private PlayerStatus status;     // AVAILABLE, SOLD, UNSOLD

    private Double soldPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;               // null until sold

    public enum PlayerTier {
        PLATINUM, GOLD, SILVER, BRONZE
    }

    public enum PlayerStatus {
        AVAILABLE, SOLD, UNSOLD
    }
}