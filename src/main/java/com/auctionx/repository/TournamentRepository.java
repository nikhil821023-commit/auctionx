package com.auctionx.repository;

import com.auctionx.model.Tournament;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TournamentRepository extends JpaRepository<Tournament, Long> {
    Optional<Tournament> findByJoinCode(String joinCode);
    boolean existsByName(String name);
}