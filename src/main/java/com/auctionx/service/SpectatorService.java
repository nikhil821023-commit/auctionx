package com.auctionx.service;

import com.auctionx.model.SpectatorReaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class SpectatorService {

    private final SimpMessagingTemplate messagingTemplate;

    // tournamentId → spectator sessions
    private final ConcurrentHashMap<Long, Set<String>> spectatorSessions
            = new ConcurrentHashMap<>();

    // tournamentId → reaction count per emoji
    private final ConcurrentHashMap<Long, Map<String, Integer>> reactionCounts
            = new ConcurrentHashMap<>();

    // Valid emojis — prevent abuse
    private static final Set<String> VALID_EMOJIS = Set.of(
            "🔥", "😮", "👏", "💰", "🚀", "😱", "🎉",
            "👑", "💪", "🏏", "⚡", "🤯", "😍", "👎"
    );

    // ── Join spectator lobby ──────────────────────────────────────
    public Map<String, Object> joinAsSpectator(Long tournamentId,
                                               String sessionId,
                                               String nickname) {
        spectatorSessions
                .computeIfAbsent(tournamentId, k -> ConcurrentHashMap.newKeySet())
                .add(sessionId);

        int count = getSpectatorCount(tournamentId);

        // Notify everyone a new spectator joined
        messagingTemplate.convertAndSend(
                "/topic/spectators/" + tournamentId,
                Map.of(
                        "event",     "SPECTATOR_JOINED",
                        "count",     count,
                        "nickname",  nickname != null ? nickname : "Someone",
                        "message",   (nickname != null ? nickname : "Someone")
                                + " joined as spectator"
                )
        );

        log.info("Spectator joined tournament {} — total: {}", tournamentId, count);

        return Map.of(
                "spectatorCount", count,
                "tournamentId",   tournamentId,
                "sessionId",      sessionId
        );
    }

    // ── Leave spectator lobby ─────────────────────────────────────
    public void leaveAsSpectator(Long tournamentId, String sessionId) {
        Set<String> sessions = spectatorSessions.get(tournamentId);
        if (sessions != null) {
            sessions.remove(sessionId);
            messagingTemplate.convertAndSend(
                    "/topic/spectators/" + tournamentId,
                    Map.of(
                            "event", "SPECTATOR_LEFT",
                            "count", getSpectatorCount(tournamentId)
                    )
            );
        }
    }

    // ── Send emoji reaction ───────────────────────────────────────
    public void sendReaction(SpectatorReaction reaction) {
        // Validate emoji
        if (!VALID_EMOJIS.contains(reaction.getEmoji())) {
            throw new RuntimeException("Invalid emoji: " + reaction.getEmoji());
        }

        reaction.setTimestamp(LocalDateTime.now());

        // Track counts per tournament
        reactionCounts
                .computeIfAbsent(reaction.getTournamentId(),
                        k -> new ConcurrentHashMap<>())
                .merge(reaction.getEmoji(), 1, Integer::sum);

        // Broadcast reaction to everyone watching
        messagingTemplate.convertAndSend(
                "/topic/reactions/" + reaction.getTournamentId(),
                Map.of(
                        "event",         "REACTION",
                        "emoji",         reaction.getEmoji(),
                        "spectatorName", reaction.getSpectatorName() != null
                                ? reaction.getSpectatorName() : "Someone",
                        "context",       reaction.getContext() != null
                                ? reaction.getContext() : "",
                        "counts",        getReactionCounts(reaction.getTournamentId()),
                        "timestamp",     reaction.getTimestamp().toString()
                )
        );
    }

    // ── Get live counts ───────────────────────────────────────────
    public int getSpectatorCount(Long tournamentId) {
        Set<String> sessions = spectatorSessions.get(tournamentId);
        return sessions != null ? sessions.size() : 0;
    }

    public Map<String, Integer> getReactionCounts(Long tournamentId) {
        return reactionCounts.getOrDefault(tournamentId, new HashMap<>());
    }

    // ── Broadcast auction event to spectators ─────────────────────
    // Called by AuctionEngineService on major events
    public void broadcastToSpectators(Long tournamentId,
                                      String event,
                                      Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put("event",           event);
        payload.put("spectatorCount",  getSpectatorCount(tournamentId));

        messagingTemplate.convertAndSend(
                "/topic/spectators/" + tournamentId,
                payload
        );
    }
}