package com.auctionx.service;

import com.auctionx.dto.TeamDTO;
import com.auctionx.dto.TeamResponseDTO;
import com.auctionx.model.Team;
import com.auctionx.model.Tournament;
import com.auctionx.repository.TeamRepository;
import com.auctionx.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TeamService {

    private final TeamRepository         teamRepository;
    private final TournamentRepository   tournamentRepository;
    private final FileStorageService     fileStorageService;

    public TeamResponseDTO registerTeam(TeamDTO dto, MultipartFile logo)
            throws IOException {

        if (teamRepository.existsByTeamNameAndTournamentId(
                dto.getTeamName(), dto.getTournamentId())) {
            throw new RuntimeException(
                    "Team name '" + dto.getTeamName() + "' already taken in this tournament");
        }

        Tournament tournament = tournamentRepository
                .findById(dto.getTournamentId())
                .orElseThrow(() -> new RuntimeException(
                        "Tournament not found: " + dto.getTournamentId()));

        String logoPath = null;
        if (logo != null && !logo.isEmpty()) {
            logoPath = fileStorageService.saveFile(logo, "teams");
        }

        Team team = Team.builder()
                .teamName(dto.getTeamName())
                .captainName(dto.getCaptainName())
                .captainEmail(dto.getCaptainEmail())
                .captainPhone(dto.getCaptainPhone())
                .teamColor(dto.getTeamColor())
                .logoPath(logoPath)
                .totalBudget(tournament.getTeamBudget())
                .spentBudget(0.0)
                .remainingBudget(tournament.getTeamBudget())
                .tournament(tournament)
                .build();

        Team saved = teamRepository.save(team);
        log.info("Team registered: {} (tournament {})",
                saved.getTeamName(), tournament.getId());
        return toDTO(saved);
    }

    public List<TeamResponseDTO> getTeamsByTournament(Long tournamentId) {
        return teamRepository
                .findByTournamentId(tournamentId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    public TeamResponseDTO getTeam(Long id) {
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Team not found: " + id));
        return toDTO(team);
    }

    // ── Internal refresh (used by AuctionEngine after budget update) ──
    public TeamResponseDTO refreshBudget(Long teamId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new RuntimeException("Team not found"));
        return toDTO(team);
    }

    // ── Entity → DTO mapper ───────────────────────────────────────────
    public TeamResponseDTO toDTO(Team team) {
        return TeamResponseDTO.builder()
                .id(team.getId())
                .teamName(team.getTeamName())
                .captainName(team.getCaptainName())
                .captainEmail(team.getCaptainEmail())
                .captainPhone(team.getCaptainPhone())
                .teamColor(team.getTeamColor())
                .logoPath(team.getLogoPath())
                .totalBudget(team.getTotalBudget())
                .spentBudget(team.getSpentBudget() != null ? team.getSpentBudget() : 0.0)
                .remainingBudget(team.getRemainingBudget() != null
                        ? team.getRemainingBudget() : team.getTotalBudget())
                .tournamentId(team.getTournament() != null
                        ? team.getTournament().getId() : null)
                .build();
    }
}