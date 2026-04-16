package com.auctionx.service;

import com.auctionx.model.AuctionState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;

/**
 * Manages countdown timers for each active auction.
 * Each tournament gets its own ScheduledFuture.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@EnableAsync
public class AuctionTimerService {

    private final SimpMessagingTemplate messagingTemplate;

    // One timer per tournament
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> timers
            = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler
            = Executors.newScheduledThreadPool(10);

    // Callback interface — AuctionEngineService implements this
    public interface TimerExpiredCallback {
        void onTimerExpired(Long tournamentId);
    }

    /**
     * Start a countdown for a tournament.
     * Ticks every second, broadcasts remaining time,
     * calls callback when it hits zero.
     */
    public void startTimer(Long tournamentId,
                           AuctionState state,
                           TimerExpiredCallback onExpired) {

        cancelTimer(tournamentId); // cancel any existing timer

        state.getRemainingSeconds().set(state.getTotalTimerSeconds());

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            if (state.getIsPaused().get()) return; // skip tick while paused

            int remaining = state.getRemainingSeconds().decrementAndGet();

            // Broadcast tick to all clients
            broadcastTick(tournamentId, state, remaining);

            if (remaining <= 0) {
                cancelTimer(tournamentId);
                log.info("⏰ Timer expired for tournament {}", tournamentId);
                onExpired.onTimerExpired(tournamentId);
            }
        }, 1, 1, TimeUnit.SECONDS);

        timers.put(tournamentId, future);
        log.info("▶ Timer started: {}s for tournament {}", state.getTotalTimerSeconds(), tournamentId);
    }

    /**
     * Reset timer on new bid (e.g. add 10 more seconds).
     */
    public void resetTimer(Long tournamentId, AuctionState state) {
        int resetTo = state.getResetTimerSeconds() != null
                ? state.getResetTimerSeconds() : 10;
        // Only reset if remaining < resetTo (prevents extending too much)
        if (state.getRemainingSeconds().get() < resetTo) {
            state.getRemainingSeconds().set(resetTo);
            log.debug("🔄 Timer reset to {}s for tournament {}", resetTo, tournamentId);
        }
    }

    public void pauseTimer(Long tournamentId, AuctionState state) {
        state.getIsPaused().set(true);
        log.info("⏸ Timer paused for tournament {}", tournamentId);
    }

    public void resumeTimer(Long tournamentId, AuctionState state) {
        state.getIsPaused().set(false);
        log.info("▶ Timer resumed for tournament {}", tournamentId);
    }

    public void cancelTimer(Long tournamentId) {
        ScheduledFuture<?> existing = timers.remove(tournamentId);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }
    }

    private void broadcastTick(Long tournamentId, AuctionState state, int remaining) {
        messagingTemplate.convertAndSend(
                "/topic/auction/" + tournamentId + "/timer",
                Map.of(
                        "event",            "TIMER_TICK",
                        "remainingSeconds", remaining,
                        "totalSeconds",     state.getTotalTimerSeconds(),
                        "isPaused",         state.getIsPaused().get(),
                        "currentBid",       state.getCurrentBid() != null ? state.getCurrentBid() : 0,
                        "highBidder",       state.getCurrentHighBidderName() != null
                                ? state.getCurrentHighBidderName() : ""
                )
        );
    }
}