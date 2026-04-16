package com.auctionx.model;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;

@Entity
@Table(name = "teams")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String teamName;

    private String captainName;
    private String captainEmail;
    private String captainPhone;
    private String teamColor;         // hex color code
    private String logoPath;

    private Double totalBudget;
    private Double spentBudget;       // updated during auction
    private Double remainingBudget;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tournament_id", nullable = false)
    private Tournament tournament;

    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Player> players;     // players won at auction

    @PrePersist
    public void initBudget() {
        if (this.spentBudget == null) this.spentBudget = 0.0;
        if (this.remainingBudget == null) this.remainingBudget = this.totalBudget;
    }
}