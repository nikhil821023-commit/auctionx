package com.auctionx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

    private String role;
    private String nationality;
    private Integer age;
    private String photoPath;

    private Integer matches;
    private Double average;
    private Double strikeRate;
    private Double basePrice;

    @Enumerated(EnumType.STRING)
    private PlayerTier tier;

    @Enumerated(EnumType.STRING)
    private PlayerStatus status;

    private Double soldPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tournament_id", nullable = false)
    @JsonIgnoreProperties({"players", "teams", "hibernateLazyInitializer", "handler"})
    private Tournament tournament;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    @JsonIgnoreProperties({"players", "tournament", "hibernateLazyInitializer", "handler"})
    private Team team;

    public enum PlayerTier {
        PLATINUM, GOLD, SILVER, BRONZE
    }

    public enum PlayerStatus {
        AVAILABLE, SOLD, UNSOLD
    }
}