package com.auctionx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
    private String teamColor;
    private String logoPath;

    private Double totalBudget;
    private Double spentBudget;
    private Double remainingBudget;

    // ✅ Ignore tournament back-reference in JSON to prevent circular loop
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tournament_id", nullable = false)
    @JsonIgnoreProperties({"teams", "players", "hibernateLazyInitializer"})
    private Tournament tournament;

    // ✅ Ignore player list in JSON — not needed in team listing
    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnoreProperties({"team", "tournament", "hibernateLazyInitializer"})
    private List<Player> players;

    @PrePersist
    public void initBudget() {
        if (this.spentBudget == null)    this.spentBudget = 0.0;
        if (this.remainingBudget == null) this.remainingBudget = this.totalBudget;
    }
}