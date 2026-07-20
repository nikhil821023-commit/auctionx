package com.auctionx.repository;

import com.auctionx.model.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    List<Feedback> findByTournamentId(Long tournamentId);

    @Query("SELECT AVG(f.overallRating) FROM Feedback f " +
            "WHERE f.tournamentId = :tournamentId")
    Double getAvgRatingByTournament(Long tournamentId);

    @Query("SELECT COUNT(f) FROM Feedback f WHERE f.tournamentId = :tournamentId")
    Long countByTournament(Long tournamentId);
}