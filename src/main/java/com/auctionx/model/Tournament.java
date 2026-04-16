package com.auctionx.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "tournaments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tournament {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String sportType;         // cricket, football, etc.
    private LocalDate tournamentDate;
    private String logoPath;          // stored file path

    private Double teamBudget;        // default budget per team
    private Integer maxPlayersPerTeam;
    private Double basePrice;         // default base bid price
    private Double bidIncrement;      // e.g. 10.0

    @Enumerated(EnumType.STRING)
    private TournamentStatus status;  // SETUP, LOBBY, LIVE, COMPLETED

    private String joinCode;          // unique 6-char code for captains

    @OneToMany(mappedBy = "tournament", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Team> teams;

    @OneToMany(mappedBy = "tournament", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Player> players;

    public enum TournamentStatus {
        SETUP, LOBBY, LIVE, COMPLETED
    }
}