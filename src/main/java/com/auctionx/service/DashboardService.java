package com.auctionx.service;

import com.auctionx.dto.DashboardDTO;
import com.auctionx.model.*;
import com.auctionx.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TournamentRepository    tournamentRepository;
    private final TeamRepository          teamRepository;
    private final PlayerRepository        playerRepository;
    private final AuctionResultRepository resultRepository;

    public DashboardDTO buildDashboard(Long tournamentId) {

        Tournament tournament = tournamentRepository
                .findById(tournamentId)
                .orElseThrow(() ->
                        new RuntimeException("Tournament not found"));

        int maxPlayers = tournament.getMaxPlayersPerTeam() != null
                ? tournament.getMaxPlayersPerTeam() : 11;

        // ── Player counts ─────────────────────────────────────────
        long sold = resultRepository
                .countByTournamentIdAndStatus(
                        tournamentId, AuctionResult.ResultStatus.SOLD);

        long unsold = resultRepository
                .countByTournamentIdAndStatus(
                        tournamentId, AuctionResult.ResultStatus.UNSOLD);

        long remaining = playerRepository
                .countByTournamentIdAndStatus(
                        tournamentId, Player.PlayerStatus.AVAILABLE);

        // ── Team dashboards ───────────────────────────────────────
        List<Team> teams = teamRepository.findByTournamentId(tournamentId);

        List<DashboardDTO.TeamCard> teamCards = teams.stream()
                .map(team -> {

                    // All players bought by this team
                    List<AuctionResult> bought = resultRepository
                            .findByTeamIdAndTournamentIdAndStatus(
                                    team.getId(),
                                    tournamentId,
                                    AuctionResult.ResultStatus.SOLD
                            );

                    int  playersBought = bought.size();
                    int  playersNeeded = Math.max(0,
                            maxPlayers - playersBought);
                    double spentBudget = team.getSpentBudget() != null
                            ? team.getSpentBudget() : 0.0;
                    double totalBudget = team.getTotalBudget() != null
                            ? team.getTotalBudget() : 0.0;
                    double budgetUsedPct = totalBudget > 0
                            ? (spentBudget / totalBudget) * 100 : 0;

                    // Recent 5 players (newest first)
                    List<Map<String, Object>> recentPlayers = bought.stream()
                            .sorted(Comparator.comparing(
                                    AuctionResult::getSoldAt,
                                    Comparator.nullsLast(
                                            Comparator.reverseOrder())))
                            .limit(5)
                            .map(r -> {
                                Map<String, Object> p =
                                        new LinkedHashMap<>();
                                p.put("name",      r.getPlayerName());
                                p.put("role",      r.getPlayerRole() != null
                                        ? r.getPlayerRole() : "");
                                p.put("tier",      r.getPlayerTier() != null
                                        ? r.getPlayerTier() : "BRONZE");
                                p.put("soldPrice", r.getSoldPrice());
                                p.put("photo",
                                        r.getPlayerPhotoPath() != null
                                                ? r.getPlayerPhotoPath() : "");
                                return p;
                            })
                            .collect(Collectors.toList());

                    return DashboardDTO.TeamCard.builder()
                            .teamId(team.getId())
                            .teamName(team.getTeamName())
                            .captainName(team.getCaptainName())
                            .teamColor(team.getTeamColor())
                            .totalBudget(totalBudget)
                            .spentBudget(spentBudget)
                            .remainingBudget(
                                    team.getRemainingBudget() != null
                                            ? team.getRemainingBudget()
                                            : totalBudget)
                            .budgetUsedPercent(
                                    Math.round(budgetUsedPct * 10) / 10.0)
                            .playerCount(playersBought)
                            .playersBought(playersBought)
                            .playersNeeded(playersNeeded)
                            .maxPlayers(maxPlayers)
                            .recentPlayers(recentPlayers)
                            .build();
                })
                .sorted(Comparator.comparingDouble(
                        t -> -(t.getSpentBudget() != null
                                ? t.getSpentBudget() : 0)))
                .collect(Collectors.toList());

        // ── Recent activity feed (last 20) ────────────────────────
        List<AuctionResult> recentResults = resultRepository
                .findByTournamentId(tournamentId)
                .stream()
                .filter(r -> r.getSoldAt() != null)
                .sorted(Comparator.comparing(
                        AuctionResult::getSoldAt).reversed())
                .limit(20)
                .collect(Collectors.toList());

        List<DashboardDTO.AuctionFeedItem> feed = recentResults.stream()
                .map(r -> DashboardDTO.AuctionFeedItem.builder()
                        .event(r.getStatus().name())
                        .playerName(r.getPlayerName())
                        .playerRole(r.getPlayerRole())
                        .playerTier(r.getPlayerTier())
                        .teamName(r.getTeamName() != null
                                ? r.getTeamName() : "—")
                        .captainName(r.getCaptainName() != null
                                ? r.getCaptainName() : "—")
                        .teamColor(r.getTeamColor() != null
                                ? r.getTeamColor() : "#ccc")
                        .soldPrice(r.getSoldPrice())
                        .timestamp(r.getSoldAt() != null
                                ? r.getSoldAt().toString() : "")
                        .build())
                .collect(Collectors.toList());

        // ── Build final DTO ───────────────────────────────────────
        return DashboardDTO.builder()
                .tournamentId(tournamentId)
                .tournamentName(tournament.getName())
                .totalPlayersInPool((int) (sold + unsold + remaining))
                .playersSold((int) sold)
                .playersUnsold((int) unsold)
                .playersRemaining((int) remaining)
                .teams(teamCards)
                .recentActivity(feed)
                .maxPlayersPerTeam(tournament.getMaxPlayersPerTeam())
                .bidIncrement(tournament.getBidIncrement())
                .build();
    }
}