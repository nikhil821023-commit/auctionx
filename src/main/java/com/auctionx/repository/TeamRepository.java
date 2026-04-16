package com.auctionx.repository;

import com.auctionx.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TeamRepository extends JpaRepository<Team, Long> {
    List<Team> findByTournamentId(Long tournamentId);
    boolean existsByTeamNameAndTournamentId(String teamName, Long tournamentId);
}