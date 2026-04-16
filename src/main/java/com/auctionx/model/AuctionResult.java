package com.auctionx.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Persisted to DB when a player is SOLD.
 * Permanent record of every auction outcome.
 */
@Entity
@Table(name = "auction_results")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuctionResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long   tournamentId;
    private Long   playerId;
    private String playerName;
    private String playerRole;
    private String playerPhotoPath;
    private String playerTier;

    private Long   teamId;
    private String teamName;
    private String captainName;
    private String teamColor;

    private Double basePrice;
    private Double soldPrice;
    private Integer totalBids;         // how many bids were placed

    @Enumerated(EnumType.STRING)
    private ResultStatus status;       // SOLD or UNSOLD

    private LocalDateTime soldAt;

    public enum ResultStatus {
        SOLD, UNSOLD
    }
}