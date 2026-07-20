package com.auctionx.service;

import com.auctionx.dto.PlayerDTO;
import com.auctionx.model.Player;
import com.auctionx.model.Tournament;
import com.auctionx.repository.PlayerRepository;
import com.auctionx.repository.TournamentRepository;
import com.auctionx.util.ByteArrayMultipartFile;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlayerService {

    private final PlayerRepository     playerRepository;
    private final TournamentRepository tournamentRepository;
    private final FileStorageService   fileStorageService;

    @Value("${file.upload.dir:uploads/}")
    private String uploadDir;

    // ─────────────────────────────────────────────────────────────────
    // 1. SINGLE PLAYER ADD
    // ─────────────────────────────────────────────────────────────────
    public Player addPlayer(PlayerDTO dto, MultipartFile photo) throws IOException {
        Tournament tournament = getTournament(dto.getTournamentId());

        String photoPath = null;
        if (photo != null && !photo.isEmpty()) {
            photoPath = fileStorageService.saveFile(photo, "players");
        }

        Player player = buildPlayer(dto, tournament, photoPath);
        return playerRepository.save(player);
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. BULK CSV (no images)
    // ─────────────────────────────────────────────────────────────────
    public List<Player> bulkUploadPlayers(MultipartFile csvFile,
                                          Long tournamentId) throws IOException {
        Tournament tournament = getTournament(tournamentId);

        // ✅ FIX: parseCsv throws IOException + CsvValidationException
        //    wrap CsvValidationException as IOException so callers stay simple
        List<Player> players;
        try {
            players = parseCsv(csvFile, tournament, Collections.emptyMap());
        } catch (CsvValidationException e) {
            throw new IOException("Invalid CSV format: " + e.getMessage(), e);
        }

        return playerRepository.saveAll(players);
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. BULK ZIP (CSV + images bundled)
    // ─────────────────────────────────────────────────────────────────
    public List<Player> bulkUploadWithImages(MultipartFile zipFile,
                                             Long tournamentId) throws IOException {
        Tournament tournament = getTournament(tournamentId);

        // Ensure players directory exists
        Path playersDir = Paths.get(uploadDir + "players/");
        if (!Files.exists(playersDir)) {
            Files.createDirectories(playersDir);
        }

        // ── Extract ZIP ───────────────────────────────────────────────
        Map<String, String> imageMap = new HashMap<>(); // normalizedName → /uploads/players/x.jpg
        byte[] csvBytes = null;

        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                String entryName = entry.getName();
                String lower     = entryName.toLowerCase();

                if (lower.endsWith(".csv")) {
                    // ✅ Read all CSV bytes while stream is open
                    csvBytes = zis.readAllBytes();
                    log.debug("Found CSV in ZIP: {}", entryName);

                } else if (isImageFile(lower)) {
                    // ✅ Read image bytes while stream is open
                    byte[] imgBytes  = zis.readAllBytes();
                    String baseName  = getBaseName(entryName);
                    String ext       = getExtension(entryName);
                    String savedName = UUID.randomUUID().toString().substring(0, 8)
                            + "_" + sanitize(baseName) + "." + ext;

                    Path savePath = playersDir.resolve(savedName);
                    Files.write(savePath, imgBytes);

                    String url = "/uploads/players/" + savedName;
                    imageMap.put(normalize(baseName), url);

                    // Also map no-space and first-word variants upfront
                    String noSpace   = normalize(baseName).replace(" ", "");
                    String firstWord = normalize(baseName).split("\\s+")[0];
                    imageMap.putIfAbsent(noSpace,   url);
                    imageMap.putIfAbsent(firstWord,  url);

                    log.debug("Extracted image: {} → {}", baseName, url);
                }

                zis.closeEntry();
            }
        }

        if (csvBytes == null) {
            throw new IOException("ZIP must contain a .csv file (e.g. players.csv)");
        }

        log.info("ZIP processed: {} images found for tournament {}",
                imageMap.size(), tournamentId);

        // ── Parse CSV with image map ──────────────────────────────────
        // ✅ FIX: Use ByteArrayMultipartFile instead of MockMultipartFile
        MultipartFile csvWrapper = new ByteArrayMultipartFile(
                "file", "players.csv", "text/csv", csvBytes);

        List<Player> players;
        try {
            players = parseCsv(csvWrapper, tournament, imageMap);
        } catch (CsvValidationException e) {
            throw new IOException("Invalid CSV format in ZIP: " + e.getMessage(), e);
        }

        return playerRepository.saveAll(players);
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. CSV PARSER (shared)
    // ─────────────────────────────────────────────────────────────────
    /**
     * Parses CSV file into Player list.
     * Expected columns: name, role, nationality, age, matches,
     *                   average, strikeRate, basePrice, tier
     *
     * @throws IOException           on file read errors
     * @throws CsvValidationException on malformed CSV rows
     */
    private List<Player> parseCsv(MultipartFile csvFile,
                                  Tournament tournament,
                                  Map<String, String> imageMap)
            throws IOException, CsvValidationException {

        List<Player> players = new ArrayList<>();

        // ✅ FIX: CSVReader.readNext() declares both IOException
        //         AND CsvValidationException — both must be caught
        try (CSVReader reader = new CSVReader(
                new InputStreamReader(csvFile.getInputStream()))) {

            // Skip header row
            String[] header = reader.readNext();
            if (header == null) {
                throw new IOException("CSV file is empty");
            }

            String[] row;
            int lineNum = 1;

            while (true) {
                try {
                    row = reader.readNext();
                } catch (CsvValidationException e) {
                    // Log bad row and continue to next row
                    log.warn("Skipping malformed CSV row {}: {}", lineNum, e.getMessage());
                    lineNum++;
                    continue;
                }

                if (row == null) break; // end of file

                lineNum++;
                if (row.length == 0 || row[0].isBlank()) continue;

                try {
                    String name    = row[0].trim();
                    String photoUrl = matchImage(name, imageMap);

                    PlayerDTO dto = PlayerDTO.builder()
                            .name(name)
                            .role(safeGet(row, 1, "Batsman"))
                            .nationality(safeGet(row, 2, "Indian"))
                            .age(parseIntSafe(safeGet(row, 3, null)))
                            .matches(parseIntSafe(safeGet(row, 4, null)))
                            .average(parseDoubleSafe(safeGet(row, 5, null)))
                            .strikeRate(parseDoubleSafe(safeGet(row, 6, null)))
                            .basePrice(parseDoubleSafe(safeGet(row, 7, null)))
                            .tier(safeGet(row, 8, "BRONZE"))
                            .tournamentId(tournament.getId())
                            .build();

                    players.add(buildPlayer(dto, tournament, photoUrl));
                    log.debug("Parsed player: {} (photo: {})", name, photoUrl);

                } catch (Exception e) {
                    log.warn("Skipping CSV row {} [{}]: {}",
                            lineNum, Arrays.toString(row), e.getMessage());
                }
            }
        }
        // ✅ Let IOException propagate — caller handles it

        if (players.isEmpty()) {
            throw new IOException(
                    "No valid players found in CSV. " +
                            "Check format: name,role,nationality,age,matches,average,strikeRate,basePrice,tier");
        }

        log.info("✅ Parsed {} players from CSV (tournament {})",
                players.size(), tournament.getId());
        return players;
    }

    // ─────────────────────────────────────────────────────────────────
    // QUERIES
    // ─────────────────────────────────────────────────────────────────
    public List<Player> getPlayersByTournament(Long tournamentId) {
        return playerRepository.findByTournamentId(tournamentId);
    }

    public List<Player> getAvailablePlayers(Long tournamentId) {
        return playerRepository.findByTournamentIdAndStatus(
                tournamentId, Player.PlayerStatus.AVAILABLE);
    }

    // ─────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────

    private Player buildPlayer(PlayerDTO dto,
                               Tournament tournament,
                               String photoPath) {
        return Player.builder()
                .name(dto.getName())
                .role(dto.getRole() != null ? dto.getRole() : "Batsman")
                .nationality(dto.getNationality() != null ? dto.getNationality() : "Indian")
                .age(dto.getAge())
                .matches(dto.getMatches())
                .average(dto.getAverage())
                .strikeRate(dto.getStrikeRate())
                .basePrice(dto.getBasePrice() != null
                        ? dto.getBasePrice()
                        : tournament.getBasePrice())
                .tier(parseTier(dto.getTier()))
                .status(Player.PlayerStatus.AVAILABLE)
                .photoPath(photoPath)
                .tournament(tournament)
                .build();
    }

    private Tournament getTournament(Long id) {
        return tournamentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException(
                        "Tournament not found: " + id));
    }

    /**
     * Match player name to an extracted image URL using fuzzy matching.
     * Tries: exact → no-space → first-word → prefix-contains
     */
    private String matchImage(String playerName,
                              Map<String, String> imageMap) {
        if (imageMap == null || imageMap.isEmpty()) return null;

        String norm = normalize(playerName);

        // 1. Exact normalized match: "virat kohli"
        if (imageMap.containsKey(norm)) return imageMap.get(norm);

        // 2. No-space: "viratkohli"
        String noSpace = norm.replace(" ", "");
        if (imageMap.containsKey(noSpace)) return imageMap.get(noSpace);

        // 3. First word only: "virat"
        String[] parts = norm.split("\\s+");
        if (parts.length > 0 && imageMap.containsKey(parts[0])) {
            return imageMap.get(parts[0]);
        }

        // 4. Any key that starts with first word
        final String fw = parts.length > 0 ? parts[0] : norm;
        return imageMap.entrySet().stream()
                .filter(e -> e.getKey().startsWith(fw))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private boolean isImageFile(String lowerName) {
        return lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png")
                || lowerName.endsWith(".webp")
                || lowerName.endsWith(".gif");
    }

    private String getBaseName(String filename) {
        // Works for paths like "images/virat.jpg" or just "virat.jpg"
        String name = Paths.get(filename).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0
                ? filename.substring(dot + 1).toLowerCase()
                : "jpg";
    }

    /** Lowercase, trim, keep only alphanumeric + spaces */
    private String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .trim()
                .replaceAll("[^a-z0-9 ]", "");
    }

    /** Safe for use in filenames */
    private String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String safeGet(String[] arr, int i, String defaultVal) {
        if (arr != null && arr.length > i
                && arr[i] != null
                && !arr[i].isBlank()) {
            return arr[i].trim();
        }
        return defaultVal;
    }

    private Integer parseIntSafe(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private Double parseDoubleSafe(String s) {
        if (s == null) return null;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private Player.PlayerTier parseTier(String tier) {
        if (tier == null) return Player.PlayerTier.BRONZE;
        try {
            return Player.PlayerTier.valueOf(tier.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Player.PlayerTier.BRONZE;
        }
    }


    // Add to existing PlayerService.java

    /**
     * Remove a player from the pool entirely.
     * Only allowed if player is still AVAILABLE (not sold/in auction).
     */
    public void removePlayer(Long playerId) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() ->
                        new RuntimeException("Player not found: " + playerId));

        if (player.getStatus() == Player.PlayerStatus.SOLD) {
            throw new RuntimeException(
                    "Cannot remove " + player.getName() +
                            " — already SOLD to " +
                            (player.getTeam() != null
                                    ? player.getTeam().getTeamName() : "a team"));
        }

        // Delete photo file if exists
        if (player.getPhotoPath() != null) {
            try {
                fileStorageService.deleteFile(player.getPhotoPath());
            } catch (Exception e) {
                log.warn("Could not delete photo for player {}: {}",
                        player.getName(), e.getMessage());
            }
        }

        playerRepository.delete(player);
        log.info("🗑️ Player '{}' removed from tournament {}",
                player.getName(), player.getTournament().getId());
    }

    /**
     * Remove multiple players at once (bulk remove).
     */
    public Map<String, Object> removePlayers(List<Long> playerIds) {
        List<String> removed  = new ArrayList<>();
        List<String> failed   = new ArrayList<>();

        for (Long id : playerIds) {
            try {
                Player p = playerRepository.findById(id)
                        .orElseThrow(() ->
                                new RuntimeException("Not found"));
                if (p.getStatus() == Player.PlayerStatus.SOLD) {
                    failed.add(p.getName() + " (already sold)");
                    continue;
                }
                playerRepository.delete(p);
                removed.add(p.getName());
            } catch (Exception e) {
                failed.add("ID " + id + ": " + e.getMessage());
            }
        }

        return Map.of(
                "removed", removed,
                "failed",  failed,
                "count",   removed.size()
        );
    }

    /**
     * Update player details (edit before auction starts).
     */
    public Player updatePlayer(Long playerId, PlayerDTO dto,
                               MultipartFile photo)
            throws IOException {

        Player player = playerRepository.findById(playerId)
                .orElseThrow(() ->
                        new RuntimeException("Player not found"));

        if (player.getStatus() == Player.PlayerStatus.SOLD) {
            throw new RuntimeException("Cannot edit a sold player");
        }

        if (dto.getName()        != null) player.setName(dto.getName());
        if (dto.getRole()        != null) player.setRole(dto.getRole());
        if (dto.getNationality() != null)
            player.setNationality(dto.getNationality());
        if (dto.getAge()         != null) player.setAge(dto.getAge());
        if (dto.getMatches()     != null) player.setMatches(dto.getMatches());
        if (dto.getAverage()     != null) player.setAverage(dto.getAverage());
        if (dto.getStrikeRate()  != null)
            player.setStrikeRate(dto.getStrikeRate());
        if (dto.getBasePrice()   != null)
            player.setBasePrice(dto.getBasePrice());
        if (dto.getTier()        != null)
            player.setTier(parseTier(dto.getTier()));

        if (photo != null && !photo.isEmpty()) {
            // Delete old photo
            if (player.getPhotoPath() != null) {
                try {
                    fileStorageService.deleteFile(player.getPhotoPath());
                } catch (Exception ignored) {}
            }
            player.setPhotoPath(
                    fileStorageService.saveFile(photo, "players"));
        }

        return playerRepository.save(player);
    }

}