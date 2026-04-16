package com.auctionx.service;

import com.auctionx.dto.TeamDTO;
import com.auctionx.model.Team;
import com.auctionx.model.Tournament;
import com.auctionx.repository.TeamRepository;
import com.auctionx.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teamRepository;
    private final TournamentRepository tournamentRepository;
    private final FileStorageService fileStorageService;

    public Team registerTeam(TeamDTO dto, MultipartFile logo) throws IOException {
        if (teamRepository.existsByTeamNameAndTournamentId(dto.getTeamName(), dto.getTournamentId())) {
            throw new RuntimeException("Team name already taken in this tournament");
        }

        Tournament tournament = tournamentRepository.findById(dto.getTournamentId())
                .orElseThrow(() -> new RuntimeException("Tournament not found"));

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

        return teamRepository.save(team);
    }

    public List<Team> getTeamsByTournament(Long tournamentId) {
        return teamRepository.findByTournamentId(tournamentId);
    }

    public Team getTeam(Long id) {
        return teamRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Team not found"));
    }
}