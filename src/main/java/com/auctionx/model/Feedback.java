package com.auctionx.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "feedback")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long   tournamentId;
    private String tournamentName;

    // Who submitted
    private String submitterName;    // optional
    private String submitterRole;    // ORGANIZER / CAPTAIN / SPECTATOR

    // Ratings 1-5
    private Integer overallRating;
    private Integer auctionExperience;
    private Integer platformEaseOfUse;
    private Integer bidProcessRating;

    // Written feedback
    @Column(length = 1000)
    private String bestPart;

    @Column(length = 1000)
    private String improveSuggestion;

    @Column(length = 1000)
    private String additionalComments;

    // Would recommend?
    private Boolean wouldRecommend;

    // Feature requests checkboxes
    private Boolean wantsPlayerStats;
    private Boolean wantsLiveStream;
    private Boolean wantsTeamChat;
    private Boolean wantsMobileApp;
    private Boolean wantsAutoTimer;

    private LocalDateTime submittedAt;

    @PrePersist
    public void setTimestamp() {
        this.submittedAt = LocalDateTime.now();
    }
}