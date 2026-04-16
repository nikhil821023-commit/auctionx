package com.auctionx.service;

import com.auctionx.dto.PlayerDTO;
import com.auctionx.model.Player;
import com.auctionx.model.Tournament;
import com.auctionx.repository.PlayerRepository;
import com.auctionx.repository.TournamentRepository;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final TournamentRepository tournamentRepository;
    private final FileStorageService fileStorageService;

    // ── Single Player Add ──────────────────────────────────────────────
    public Player addPlayer(PlayerDTO dto, MultipartFile photo) throws IOException {
        Tournament tournament = tournamentRepository.findById(dto.getTournamentId())
                .orElseThrow(() -> new RuntimeException("Tournament not found"));

        String photoPath = null;
        if (photo != null && !photo.isEmpty()) {
            photoPath = fileStorageService.saveFile(photo, "players");
        }

        Player player = Player.builder()
                .name(dto.getName())
                .role(dto.getRole())
                .nationality(dto.getNationality())
                .age(dto.getAge())
                .matches(dto.getMatches())
                .average(dto.getAverage())
                .strikeRate(dto.getStrikeRate())
                .basePrice(dto.getBasePrice() != null ? dto.getBasePrice() : tournament.getBasePrice())
                .tier(parseTier(dto.getTier()))
                .status(Player.PlayerStatus.AVAILABLE)
                .photoPath(photoPath)
                .tournament(tournament)
                .build();

        return playerRepository.save(player);
    }

    // ── Bulk Upload via CSV ────────────────────────────────────────────
    // Expected CSV columns:
    // name,role,nationality,age,matches,average,strikeRate,basePrice,tier
    public List<Player> bulkUploadPlayers(MultipartFile csvFile, Long tournamentId) throws IOException {
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new RuntimeException("Tournament not found"));

        List<Player> players = new ArrayList<>();

        try (CSVReader csvReader = new CSVReader(new InputStreamReader(csvFile.getInputStream()))) {

            try {
                csvReader.readNext(); // skip header row
                String[] row;

                while ((row = csvReader.readNext()) != null) {
                    if (row.length < 9) continue; // skip malformed rows

                    Double basePrice = parseDoubleSafe(row[7]);

                    Player player = Player.builder()
                            .name(row[0].trim())
                            .role(row[1].trim())
                            .nationality(row[2].trim())
                            .age(parseIntSafe(row[3]))
                            .matches(parseIntSafe(row[4]))
                            .average(parseDoubleSafe(row[5]))
                            .strikeRate(parseDoubleSafe(row[6]))
                            .basePrice(basePrice != null ? basePrice : tournament.getBasePrice())
                            .tier(parseTier(row[8].trim()))
                            .status(Player.PlayerStatus.AVAILABLE)
                            .tournament(tournament)
                            .build();

                    players.add(player);
                }
            } catch (CsvValidationException e) {
                // Option A: handle OpenCSV checked exception here
                throw new RuntimeException("Invalid CSV file (failed to read/validate CSV rows)", e);
            }
        }

        return playerRepository.saveAll(players);
    }

    public List<Player> getPlayersByTournament(Long tournamentId) {
        return playerRepository.findByTournamentId(tournamentId);
    }

    public List<Player> getAvailablePlayers(Long tournamentId) {
        return playerRepository.findByTournamentIdAndStatus(
                tournamentId, Player.PlayerStatus.AVAILABLE);
    }

    // ── Helpers ───────────────────────────────────────────────────────
    private Player.PlayerTier parseTier(String tier) {
        try {
            return Player.PlayerTier.valueOf(tier.toUpperCase());
        } catch (Exception e) {
            return Player.PlayerTier.BRONZE;
        }
    }

    private Integer parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return null; }
    }

    private Double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s.trim()); }
        catch (Exception e) { return null; }
    }
}