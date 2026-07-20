package com.auctionx.service;

import com.auctionx.model.Tournament;
import com.auctionx.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
@EnableScheduling
public class TournamentSchedulerService {

    private final TournamentRepository  tournamentRepository;
    private final SimpMessagingTemplate messagingTemplate;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    // ── Runs every 60 seconds ─────────────────────────────────────
    @Scheduled(fixedDelay = 60_000)
    public void runSchedulerTick() {
        LocalDateTime now = LocalDateTime.now();
        checkExpired(now);
        checkAutoStart(now);
        checkReminders(now);
    }

    // ═══════════════════════════════════════════════════════════
    // SCHEDULE A TOURNAMENT
    // ═══════════════════════════════════════════════════════════
    public Tournament scheduleTournament(
            Long tournamentId,
            LocalDateTime scheduledTime,
            Integer reservedDays,
            Boolean autoStart,
            String organizerEmail,
            String organizerName) {

        Tournament t = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new RuntimeException(
                        "Tournament not found"));

        if (scheduledTime.isBefore(LocalDateTime.now())) {
            throw new RuntimeException(
                    "Scheduled time must be in the future");
        }

        int days = (reservedDays != null && reservedDays >= 1
                && reservedDays <= 7) ? reservedDays : 3;

        t.setScheduledAuctionTime(scheduledTime);
        t.setExpiresAt(scheduledTime.plusDays(days)); // expire X days after scheduled time
        t.setReservedDays(days);
        t.setAutoStartEnabled(autoStart != null ? autoStart : false);
        t.setOrganizerEmail(organizerEmail);
        t.setOrganizerName(organizerName);
        t.setStatus(Tournament.TournamentStatus.SCHEDULED);
        t.setReminderSent24h(false);
        t.setReminderSent1h(false);
        t.setPostponeCount(0);

        Tournament saved = tournamentRepository.save(t);

        log.info("📅 Tournament '{}' scheduled for {} — expires in {} days",
                t.getName(),
                scheduledTime.format(FMT),
                days);

        // Notify all connected clients
        broadcastScheduleUpdate(saved, "TOURNAMENT_SCHEDULED");

        return saved;
    }

    // ═══════════════════════════════════════════════════════════
    // POSTPONE — move to a new date
    // ═══════════════════════════════════════════════════════════
    public Tournament postponeTournament(
            Long tournamentId,
            LocalDateTime newTime,
            String reason,
            Integer extendDays) {

        Tournament t = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new RuntimeException(
                        "Tournament not found"));

        if (newTime.isBefore(LocalDateTime.now())) {
            throw new RuntimeException(
                    "New time must be in the future");
        }

        // Track postpone history
        t.setPreviousStatus(t.getStatus());
        t.setLastPostponedAt(LocalDateTime.now());
        t.setPostponeCount((t.getPostponeCount() != null
                ? t.getPostponeCount() : 0) + 1);
        t.setPostponeReason(reason);
        t.setScheduledAuctionTime(newTime);

        // Extend expiry
        int extend = (extendDays != null && extendDays >= 1) ? extendDays : 3;
        t.setExpiresAt(newTime.plusDays(extend));
        t.setStatus(Tournament.TournamentStatus.SCHEDULED);

        // Reset reminder flags for new time
        t.setReminderSent24h(false);
        t.setReminderSent1h(false);

        Tournament saved = tournamentRepository.save(t);

        log.info("⏰ Tournament '{}' postponed to {} (reason: {})",
                t.getName(), newTime.format(FMT), reason);

        // Notify captains who already registered
        broadcastScheduleUpdate(saved, "TOURNAMENT_POSTPONED");

        return saved;
    }

    // ═══════════════════════════════════════════════════════════
    // EXTEND RESERVATION — add more days without changing time
    // ═══════════════════════════════════════════════════════════
    public Tournament extendReservation(
            Long tournamentId, Integer additionalDays) {

        Tournament t = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new RuntimeException(
                        "Tournament not found"));

        if (additionalDays < 1 || additionalDays > 7) {
            throw new RuntimeException(
                    "Can extend by 1-7 days only");
        }

        LocalDateTime currentExpiry = t.getExpiresAt() != null
                ? t.getExpiresAt() : LocalDateTime.now();

        t.setExpiresAt(currentExpiry.plusDays(additionalDays));
        t.setReservedDays((t.getReservedDays() != null
                ? t.getReservedDays() : 0) + additionalDays);

        Tournament saved = tournamentRepository.save(t);

        log.info("📆 Tournament '{}' reservation extended by {} days",
                t.getName(), additionalDays);

        broadcastScheduleUpdate(saved, "RESERVATION_EXTENDED");
        return saved;
    }

    // ═══════════════════════════════════════════════════════════
    // CANCEL TOURNAMENT
    // ═══════════════════════════════════════════════════════════
    public Tournament cancelTournament(
            Long tournamentId, String reason) {

        Tournament t = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new RuntimeException(
                        "Tournament not found"));

        t.setStatus(Tournament.TournamentStatus.CANCELLED);
        t.setPostponeReason(reason);

        Tournament saved = tournamentRepository.save(t);

        log.info("❌ Tournament '{}' cancelled. Reason: {}",
                t.getName(), reason);

        broadcastScheduleUpdate(saved, "TOURNAMENT_CANCELLED");
        return saved;
    }

    // ═══════════════════════════════════════════════════════════
    // GET TOURNAMENT STATUS DETAILS
    // ═══════════════════════════════════════════════════════════
    public Map<String, Object> getTournamentStatus(Long tournamentId) {
        Tournament t = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new RuntimeException(
                        "Tournament not found"));

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("id",              t.getId());
        status.put("name",            t.getName());
        status.put("status",          t.getStatus().name());
        status.put("joinCode",        t.getJoinCode());
        status.put("organizerName",   t.getOrganizerName());

        if (t.getScheduledAuctionTime() != null) {
            status.put("scheduledFor",
                    t.getScheduledAuctionTime().toString());
            status.put("scheduledForFormatted",
                    t.getScheduledAuctionTime().format(FMT));
            status.put("minutesUntilStart", t.minutesUntilStart());
        }

        if (t.getExpiresAt() != null) {
            status.put("expiresAt", t.getExpiresAt().toString());
            long hoursLeft = java.time.Duration.between(
                    LocalDateTime.now(), t.getExpiresAt()).toHours();
            status.put("hoursUntilExpiry", Math.max(hoursLeft, 0));
            status.put("isExpired",        t.isExpired());
        }

        status.put("reservedDays",    t.getReservedDays());
        status.put("autoStart",       t.getAutoStartEnabled());
        status.put("postponeCount",   t.getPostponeCount());
        status.put("postponeReason",  t.getPostponeReason());
        status.put("lastPostponedAt",
                t.getLastPostponedAt() != null
                        ? t.getLastPostponedAt().toString() : null);

        return status;
    }

    // ═══════════════════════════════════════════════════════════
    // SCHEDULED JOBS
    // ═══════════════════════════════════════════════════════════

    private void checkExpired(LocalDateTime now) {
        List<Tournament> expired =
                tournamentRepository.findExpired(now);
        for (Tournament t : expired) {
            t.setStatus(Tournament.TournamentStatus.EXPIRED);
            tournamentRepository.save(t);
            broadcastScheduleUpdate(t, "TOURNAMENT_EXPIRED");
            log.info("⌛ Tournament '{}' expired", t.getName());
        }
    }

    private void checkAutoStart(LocalDateTime now) {
        List<Tournament> due =
                tournamentRepository.findDueToStart(now);
        for (Tournament t : due) {
            t.setStatus(Tournament.TournamentStatus.LOBBY);
            tournamentRepository.save(t);
            broadcastScheduleUpdate(t, "TOURNAMENT_AUTO_STARTED");
            log.info("🚀 Tournament '{}' auto-started",
                    t.getName());
        }
    }

    private void checkReminders(LocalDateTime now) {
        // 24h reminder
        List<Tournament> remind24 = tournamentRepository
                .findNeedingReminder24h(
                        now.plusHours(23), now.plusHours(25));
        for (Tournament t : remind24) {
            t.setReminderSent24h(true);
            tournamentRepository.save(t);
            broadcastScheduleUpdate(t, "REMINDER_24H");
            log.info("🔔 24h reminder sent for '{}'", t.getName());
        }

        // 1h reminder
        List<Tournament> remind1 = tournamentRepository
                .findNeedingReminder1h(
                        now.plusMinutes(55), now.plusMinutes(65));
        for (Tournament t : remind1) {
            t.setReminderSent1h(true);
            tournamentRepository.save(t);
            broadcastScheduleUpdate(t, "REMINDER_1H");
            log.info("🔔 1h reminder sent for '{}'", t.getName());
        }
    }

    // ── Broadcast to all connected clients ────────────────────
    private void broadcastScheduleUpdate(
            Tournament t, String event) {
        messagingTemplate.convertAndSend(
                "/topic/tournament/" + t.getId() + "/schedule",
                Map.of(
                        "event",       event,
                        "tournamentId",t.getId(),
                        "name",        t.getName(),
                        "status",      t.getStatus().name(),
                        "scheduledFor",t.getScheduledAuctionTime() != null
                                ? t.getScheduledAuctionTime().toString() : "",
                        "expiresAt",   t.getExpiresAt() != null
                                ? t.getExpiresAt().toString() : "",
                        "reason",      t.getPostponeReason() != null
                                ? t.getPostponeReason() : "",
                        "message",     buildMessage(event, t)
                )
        );
    }

    private String buildMessage(String event, Tournament t) {
        return switch (event) {
            case "TOURNAMENT_SCHEDULED"  ->
                    t.getName() + " scheduled for "
                            + (t.getScheduledAuctionTime() != null
                            ? t.getScheduledAuctionTime().format(FMT) : "");
            case "TOURNAMENT_POSTPONED"  ->
                    t.getName() + " postponed. New time: "
                            + (t.getScheduledAuctionTime() != null
                            ? t.getScheduledAuctionTime().format(FMT) : "");
            case "TOURNAMENT_CANCELLED"  ->
                    t.getName() + " has been cancelled.";
            case "TOURNAMENT_EXPIRED"    ->
                    t.getName() + " reservation has expired.";
            case "TOURNAMENT_AUTO_STARTED" ->
                    t.getName() + " is starting now!";
            case "REMINDER_24H"          ->
                    t.getName() + " starts in 24 hours!";
            case "REMINDER_1H"           ->
                    t.getName() + " starts in 1 hour!";
            case "RESERVATION_EXTENDED"  ->
                    t.getName() + " reservation extended.";
            default -> event;
        };
    }
}