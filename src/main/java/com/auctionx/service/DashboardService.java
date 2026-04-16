package com.auctionx.service;

import com.auctionx.dto.DashboardDTO;
import com.auctionx.model.*;
import com.auctionx.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TournamentRepository   tournamentRepository;
    private final TeamRepository         teamRepository;
    private final PlayerRepository       playerRepository;
    private final AuctionResultRepository resultRepository;

    public DashboardDTO buildDashboard(Long tournamentId) {

        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new RuntimeException("Tournament not found"));

        List<Team> teams   = teamRepository.findByTournamentId(tournamentId);
        List<Player> all   = playerRepository.findByTournamentId(tournamentId);

        long sold    = all.stream().filter(p -> p.getStatus() == Player.PlayerStatus.SOLD).count();
        long unsold  = all.stream().filter(p -> p.getStatus() == Player.PlayerStatus.UNSOLD).count();
        long remaining = all.stream().filter(p -> p.getStatus() == Player.PlayerStatus.AVAILABLE).count();

        List<DashboardDTO.TeamCard> teamCards = teams.stream().map(team -> {
            List<Player> teamPlayers = playerRepository.findByTournamentId(tournamentId)
                    .stream()
                    .filter(p -> team.equals(p.getTeam()))
                    .collect(Collectors.toList());

            double budgetUsedPct = team.getTotalBudget() > 0
                    ? (team.getSpentBudget() / team.getTotalBudget()) * 100 : 0;

            List<DashboardDTO.PlayerMini> minis = teamPlayers.stream()
                    .map(p -> DashboardDTO.PlayerMini.builder()
                            .playerId(p.getId())
                            .playerName(p.getName())
                            .playerRole(p.getRole())
                            .photoPath(p.getPhotoPath())
                            .soldPrice(p.getSoldPrice())
                            .tier(p.getTier() != null ? p.getTier().name() : "")
                            .build())
                    .collect(Collectors.toList());

            return DashboardDTO.TeamCard.builder()
                    .teamId(team.getId())
                    .teamName(team.getTeamName())
                    .captainName(team.getCaptainName())
                    .teamColor(team.getTeamColor())
                    .logoPath(team.getLogoPath())
                    .totalBudget(team.getTotalBudget())
                    .spentBudget(team.getSpentBudget())
                    .remainingBudget(team.getRemainingBudget())
                    .budgetUsedPercent(Math.round(budgetUsedPct * 10.0) / 10.0)
                    .playerCount(teamPlayers.size())
                    .players(minis)
                    .build();
        }).collect(Collectors.toList());

        // Recent activity feed (last 20 results)
        List<AuctionResult> recentResults = resultRepository
                .findByTournamentId(tournamentId)
                .stream()
                .sorted(Comparator.comparing(AuctionResult::getSoldAt).reversed())
                .limit(20)
                .collect(Collectors.toList());

        List<DashboardDTO.AuctionFeedItem> feed = recentResults.stream().map(r ->
                DashboardDTO.AuctionFeedItem.builder()
                        .event(r.getStatus().name())
                        .playerName(r.getPlayerName())
                        .playerRole(r.getPlayerRole())
                        .playerTier(r.getPlayerTier())
                        .teamName(r.getTeamName() != null ? r.getTeamName() : "—")
                        .captainName(r.getCaptainName() != null ? r.getCaptainName() : "—")
                        .teamColor(r.getTeamColor() != null ? r.getTeamColor() : "#ccc")
                        .soldPrice(r.getSoldPrice())
                        .timestamp(r.getSoldAt() != null ? r.getSoldAt().toString() : "")
                        .build()
        ).collect(Collectors.toList());

        return DashboardDTO.builder()
                .tournamentId(tournamentId)
                .tournamentName(tournament.getName())
                .totalPlayersInPool((int) (sold + unsold + remaining))
                .playersSold((int) sold)
                .playersUnsold((int) unsold)
                .playersRemaining((int) remaining)
                .teams(teamCards)
                .recentActivity(feed)
                .build();
    }
}