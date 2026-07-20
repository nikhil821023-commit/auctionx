package com.auctionx.repository;

import com.auctionx.model.PlatformVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDateTime;
import java.util.List;

public interface PlatformVisitRepository
        extends JpaRepository<PlatformVisit, Long> {

    long countByVisitedAtAfter(LocalDateTime since);

    long countByRole(String role);

    @Query("SELECT COUNT(DISTINCT p.sessionId) FROM PlatformVisit p " +
            "WHERE p.visitedAt >= :since")
    long countUniqueVisitorsSince(LocalDateTime since);

    @Query("SELECT p.page, COUNT(p) FROM PlatformVisit p " +
            "GROUP BY p.page ORDER BY COUNT(p) DESC")
    List<Object[]> getPageVisitCounts();

    @Query("SELECT DATE(p.visitedAt), COUNT(p) FROM PlatformVisit p " +
            "WHERE p.visitedAt >= :since GROUP BY DATE(p.visitedAt) " +
            "ORDER BY DATE(p.visitedAt)")
    List<Object[]> getDailyVisitsSince(LocalDateTime since);

    List<PlatformVisit> findByTournamentId(Long tournamentId);
}