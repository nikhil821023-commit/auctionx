package com.auctionx.repository;

import com.auctionx.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlayerRepository extends JpaRepository<Player, Long> {
    List<Player> findByTournamentId(Long tournamentId);
    List<Player> findByTournamentIdAndStatus(Long tournamentId, Player.PlayerStatus status);
    List<Player> findByTournamentIdAndTier(Long tournamentId, Player.PlayerTier tier);
}