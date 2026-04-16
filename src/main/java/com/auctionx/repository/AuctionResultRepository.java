package com.auctionx.repository;

import com.auctionx.model.AuctionResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AuctionResultRepository extends JpaRepository<AuctionResult, Long> {

    List<AuctionResult> findByTournamentId(Long tournamentId);

    List<AuctionResult> findByTeamId(Long teamId);

    List<AuctionResult> findByTournamentIdAndStatus(
            Long tournamentId, AuctionResult.ResultStatus status);

    // Total spent by a team in a tournament
    @Query("SELECT COALESCE(SUM(r.soldPrice), 0) FROM AuctionResult r " +
            "WHERE r.teamId = :teamId AND r.tournamentId = :tournamentId " +
            "AND r.status = 'SOLD'")
    Double getTotalSpentByTeam(Long teamId, Long tournamentId);

    // Count players sold to a team
    @Query("SELECT COUNT(r) FROM AuctionResult r " +
            "WHERE r.teamId = :teamId AND r.status = 'SOLD'")
    Integer countPlayersByTeam(Long teamId);
}