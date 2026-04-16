package com.auctionx.service;

import com.auctionx.dto.TournamentDTO;
import com.auctionx.model.Tournament;
import com.auctionx.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TournamentService {

    private final TournamentRepository tournamentRepository;
    private final FileStorageService fileStorageService;

    public Tournament createTournament(TournamentDTO dto, MultipartFile logo) throws IOException {
        if (tournamentRepository.existsByName(dto.getName())) {
            throw new RuntimeException("Tournament name already exists");
        }

        String logoPath = null;
        if (logo != null && !logo.isEmpty()) {
            logoPath = fileStorageService.saveFile(logo, "tournaments");
        }

        Tournament tournament = Tournament.builder()
                .name(dto.getName())
                .sportType(dto.getSportType())
                .tournamentDate(dto.getTournamentDate())
                .logoPath(logoPath)
                .teamBudget(dto.getTeamBudget())
                .maxPlayersPerTeam(dto.getMaxPlayersPerTeam())
                .basePrice(dto.getBasePrice())
                .bidIncrement(dto.getBidIncrement())
                .status(Tournament.TournamentStatus.SETUP)
                .joinCode(generateJoinCode())
                .build();

        return tournamentRepository.save(tournament);
    }

    public Tournament getTournament(Long id) {
        return tournamentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Tournament not found"));
    }

    public List<Tournament> getAllTournaments() {
        return tournamentRepository.findAll();
    }

    public Tournament getTournamentByJoinCode(String joinCode) {
        return tournamentRepository.findByJoinCode(joinCode)
                .orElseThrow(() -> new RuntimeException("Invalid join code"));
    }

    public Tournament updateStatus(Long id, Tournament.TournamentStatus status) {
        Tournament t = getTournament(id);
        t.setStatus(status);
        return tournamentRepository.save(t);
    }

    private String generateJoinCode() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}