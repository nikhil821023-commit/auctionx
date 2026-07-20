package com.auctionx.repository;

import com.auctionx.model.Tournament;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TournamentRepository
        extends JpaRepository<Tournament, Long> {

    Optional<Tournament> findByJoinCode(String joinCode);
    boolean existsByName(String name);

    // ✅ NEW queries
    List<Tournament> findByStatus(Tournament.TournamentStatus status);

    // Tournaments that should auto-start now
    @Query("SELECT t FROM Tournament t " +
            "WHERE t.status = 'SCHEDULED' " +
            "AND t.autoStartEnabled = true " +
            "AND t.scheduledAuctionTime <= :now")
    List<Tournament> findDueToStart(LocalDateTime now);

    // Tournaments that have expired
    @Query("SELECT t FROM Tournament t " +
            "WHERE t.status IN ('SETUP','SCHEDULED') " +
            "AND t.expiresAt <= :now")
    List<Tournament> findExpired(LocalDateTime now);

    // Remind 24h before
    @Query("SELECT t FROM Tournament t " +
            "WHERE t.status = 'SCHEDULED' " +
            "AND t.reminderSent24h = false " +
            "AND t.scheduledAuctionTime BETWEEN :from AND :to")
    List<Tournament> findNeedingReminder24h(
            LocalDateTime from, LocalDateTime to);

    // Remind 1h before
    @Query("SELECT t FROM Tournament t " +
            "WHERE t.status = 'SCHEDULED' " +
            "AND t.reminderSent1h = false " +
            "AND t.scheduledAuctionTime BETWEEN :from AND :to")
    List<Tournament> findNeedingReminder1h(
            LocalDateTime from, LocalDateTime to);

    // By organizer email
    List<Tournament> findByOrganizerEmail(String email);
    // Add this query:
    List<Tournament> findByCreatedByUserId(Long userId);
}