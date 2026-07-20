package com.auctionx.service;

import com.auctionx.dto.AuctionSettingsDTO;
import com.auctionx.dto.CaptainJoinDTO;
import com.auctionx.dto.LobbyStatusDTO;
import com.auctionx.model.*;
import com.auctionx.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class LobbyService {

    private final TournamentRepository tournamentRepository;
    private final TeamRepository       teamRepository;
    private final PlayerRepository     playerRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${lobby.min.teams:2}")
    private int minTeams;

    private final Map<Long, List<LobbySession>>  lobbyMap    = new ConcurrentHashMap<>();
    private final Map<Long, AuctionSettingsDTO>  settingsMap = new ConcurrentHashMap<>();

    // ── Captain joins lobby ───────────────────────────────────────────
    public LobbyStatusDTO captainJoin(CaptainJoinDTO dto, String wsSessionId) {
        Tournament tournament;

        if (dto.getJoinCode() != null && !dto.getJoinCode().isBlank()) {
            tournament = tournamentRepository.findByJoinCode(dto.getJoinCode())
                    .orElseThrow(() -> new RuntimeException("Invalid join code"));
        } else if (dto.getTournamentId() != null) {
            tournament = tournamentRepository.findById(dto.getTournamentId())
                    .orElseThrow(() -> new RuntimeException("Tournament not found"));
        } else {
            throw new RuntimeException("Either joinCode or tournamentId is required");
        }

        Team team = teamRepository.findById(dto.getTeamId())
                .orElseThrow(() -> new RuntimeException("Team not found"));

        List<LobbySession> sessions = lobbyMap.computeIfAbsent(
                tournament.getId(), k -> new ArrayList<>());

        // Remove old session if reconnecting
        sessions.removeIf(s -> s.getTeamId().equals(team.getId()));

        LobbySession session = LobbySession.builder()
                .sessionId(wsSessionId)
                .teamId(team.getId())
                .tournamentId(tournament.getId())
                .captainName(team.getCaptainName())
                .teamName(team.getTeamName())
                .teamColor(team.getTeamColor())
                .logoPath(team.getLogoPath())
                .isReady(false)
                .joinedAt(LocalDateTime.now())
                .role(LobbySession.LobbyRole.CAPTAIN)
                .build();

        sessions.add(session);

        log.info("Captain [{}] joined lobby for tournament [{}]",
                team.getCaptainName(), tournament.getName());

        LobbyStatusDTO status = buildLobbyStatus(tournament.getId());
        broadcastLobbyUpdate(tournament.getId(), status);
        return status;
    }

    // ── Captain marks themselves ready ────────────────────────────────
    public void setCaptainReady(Long tournamentId, String wsSessionId, boolean ready) {
        List<LobbySession> sessions = lobbyMap.getOrDefault(tournamentId, List.of());
        sessions.stream()
                .filter(s -> s.getSessionId().equals(wsSessionId))
                .findFirst()
                .ifPresent(s -> s.setIsReady(ready));

        LobbyStatusDTO status = buildLobbyStatus(tournamentId);
        broadcastLobbyUpdate(tournamentId, status);
    }

    // ── Captain disconnects ───────────────────────────────────────────
    public void captainLeave(String wsSessionId) {
        lobbyMap.values().forEach(sessions ->
                sessions.removeIf(s -> s.getSessionId().equals(wsSessionId))
        );
        lobbyMap.forEach((tid, sessions) ->
                broadcastLobbyUpdate(tid, buildLobbyStatus(tid))
        );
    }

    // ── Organiser saves auction settings ─────────────────────────────
    public AuctionSettingsDTO saveSettings(AuctionSettingsDTO dto) {
        if (dto.getBidTimerSeconds() == null || dto.getBidTimerSeconds() < 5)
            dto.setBidTimerSeconds(30);
        if (dto.getBidTimerResetSeconds() == null)
            dto.setBidTimerResetSeconds(10);
        if (dto.getAutoSpin() == null)
            dto.setAutoSpin(true);
        if (dto.getPauseBetweenPlayers() == null)
            dto.setPauseBetweenPlayers(5);
        if (dto.getAllowTierOrder() == null)
            dto.setAllowTierOrder(true);

        settingsMap.put(dto.getTournamentId(), dto);

        LobbyStatusDTO status = buildLobbyStatus(dto.getTournamentId());
        broadcastLobbyUpdate(dto.getTournamentId(), status);

        log.info("Auction settings saved for tournament {}", dto.getTournamentId());
        return dto;
    }

    // ── Organiser starts the auction ──────────────────────────────────
    public void startAuction(Long tournamentId) {
        List<LobbySession> sessions = lobbyMap.getOrDefault(tournamentId, List.of());

        if (sessions.size() < minTeams) {
            throw new RuntimeException(
                    "Need at least " + minTeams + " teams. Currently: " + sessions.size());
        }

        // ✅ FIXED: use getSettings() — never throws, always returns defaults
        AuctionSettingsDTO settings = getSettings(tournamentId);

        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new RuntimeException("Tournament not found"));
        tournament.setStatus(Tournament.TournamentStatus.LIVE);
        tournamentRepository.save(tournament);

        messagingTemplate.convertAndSend(
                "/topic/lobby/" + tournamentId + "/start",
                Map.of(
                        "event",        "AUCTION_STARTING",
                        "tournamentId", tournamentId,
                        "message",      "Auction is starting! Get ready...",
                        "settings",     settings
                )
        );

        log.info("Auction STARTED for tournament {}", tournamentId);
    }

    // ── Get current lobby status ──────────────────────────────────────
    public LobbyStatusDTO getLobbyStatus(Long tournamentId) {
        return buildLobbyStatus(tournamentId);
    }

    // ✅ FIXED: never throws — caches defaults if nothing saved
    public AuctionSettingsDTO getSettings(Long tournamentId) {
        AuctionSettingsDTO saved = settingsMap.get(tournamentId);
        if (saved != null) return saved;

        AuctionSettingsDTO defaults = defaultSettings(tournamentId);
        settingsMap.put(tournamentId, defaults); // cache so next call reuses
        return defaults;
    }

    // ── Internal helpers ──────────────────────────────────────────────
    private LobbyStatusDTO buildLobbyStatus(Long tournamentId) {
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new RuntimeException("Tournament not found"));

        List<LobbySession> sessions  = lobbyMap.getOrDefault(tournamentId, List.of());
        long readyCount  = sessions.stream().filter(LobbySession::getIsReady).count();
        int  playerCount = playerRepository.findByTournamentId(tournamentId).size();

        // ✅ Use getSettings() so defaults are always available
        AuctionSettingsDTO settings = getSettings(tournamentId);

        String lobbyState;
        if (sessions.size() < minTeams) {
            lobbyState = "WAITING";
        } else if (readyCount == sessions.size()) {
            lobbyState = "READY_TO_START";
        } else {
            lobbyState = "TEAMS_JOINED";
        }

        return LobbyStatusDTO.builder()
                .tournamentId(tournamentId)
                .tournamentName(tournament.getName())
                .sportType(tournament.getSportType())
                .totalTeams(sessions.size())
                .readyTeams((int) readyCount)
                .totalPlayersInPool(playerCount)
                .connectedCaptains(new ArrayList<>(sessions))
                .settings(LobbyStatusDTO.AuctionSettingsSnapshot.builder()
                        .bidTimerSeconds(settings.getBidTimerSeconds())
                        .bidTimerResetSeconds(settings.getBidTimerResetSeconds())
                        .autoSpin(settings.getAutoSpin())
                        .pauseBetweenPlayers(settings.getPauseBetweenPlayers())
                        .allowTierOrder(settings.getAllowTierOrder())
                        .build())
                .lobbyState(lobbyState)
                .build();
    }

    private void broadcastLobbyUpdate(Long tournamentId, LobbyStatusDTO status) {
        messagingTemplate.convertAndSend("/topic/lobby/" + tournamentId, status);
    }

    private AuctionSettingsDTO defaultSettings(Long tournamentId) {
        return AuctionSettingsDTO.builder()
                .tournamentId(tournamentId)
                .bidTimerSeconds(30)
                .bidTimerResetSeconds(10)
                .autoSpin(true)
                .pauseBetweenPlayers(5)
                .allowTierOrder(true)
                .build();
    }
}