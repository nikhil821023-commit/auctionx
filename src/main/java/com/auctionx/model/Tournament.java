package com.auctionx.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    @Column(nullable = false, name = "name")
    private String name;

    private String    sportType;
    private LocalDate tournamentDate;
    private String    logoPath;

    private Double  teamBudget;
    private Integer maxPlayersPerTeam;
    private Double  basePrice;
    private Double  bidIncrement;

    @Enumerated(EnumType.STRING)
    private TournamentStatus status;

    private String joinCode;

    // ── NEW: Scheduling fields ─────────────────────────────────
    private LocalDateTime scheduledAuctionTime;  // when auction auto-opens
    private LocalDateTime expiresAt;             // auto-delete/expire after
    private Integer       reservedDays;          // how many days to keep (1-7)
    private String        organizerEmail;        // for reminder emails
    private String        organizerName;
    private Boolean       autoStartEnabled;      // auto-open lobby at scheduled time
    private String        postponeReason;        // optional reason for delay
    private LocalDateTime lastPostponedAt;       // when last postponed
    private Integer       postponeCount;         // how many times postponed
    private Boolean       reminderSent24h;       // tracking reminders
    private Boolean       reminderSent1h;

    private Long createdByUserId;
    private String createdByName;
    private String createdByEmail;

    @Enumerated(EnumType.STRING)
    private TournamentStatus previousStatus;     // before postpone

    @OneToMany(mappedBy = "tournament",
            cascade = CascadeType.ALL,
            fetch = FetchType.LAZY)
    private List<Team> teams;

    @OneToMany(mappedBy = "tournament",
            cascade = CascadeType.ALL,
            fetch = FetchType.LAZY)
    private List<Player> players;

    public enum TournamentStatus {
        SETUP,       // just created, no schedule yet
        SCHEDULED,   // ✅ NEW — scheduled for future date
        LOBBY,       // captains joining
        LIVE,        // auction running
        PAUSED,      // ✅ NEW — auction paused/postponed mid-way
        COMPLETED,   // done
        EXPIRED,     // ✅ NEW — reservation expired
        CANCELLED    // ✅ NEW — organizer cancelled
    }

    // ── Helper: is this tournament still valid? ────────────────
    public boolean isExpired() {
        return expiresAt != null
                && LocalDateTime.now().isAfter(expiresAt);
    }

    // ── Helper: minutes until scheduled start ─────────────────
    public long minutesUntilStart() {
        if (scheduledAuctionTime == null) return -1;
        java.time.Duration d = java.time.Duration.between(
                LocalDateTime.now(), scheduledAuctionTime);
        return d.toMinutes();
    }
}