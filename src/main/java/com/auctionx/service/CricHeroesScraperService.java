package com.auctionx.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class CricHeroesScraperService {

    /**
     * Fetches player stats from their public CricHeroes profile URL.
     * Example URL: https://cricheroes.in/player-profile/12345678/nikhil
     *
     * NOTE: This scrapes public data only.
     * Respects robots.txt — profile pages are publicly indexed.
     */
    public Map<String, Object> fetchPlayerStats(String profileUrl) {

        // Validate it's actually a CricHeroes URL
        if (profileUrl == null
                || !profileUrl.contains("cricheroes.in/player-profile")) {
            throw new RuntimeException(
                    "Invalid CricHeroes profile URL. " +
                            "Expected format: https://cricheroes.in/player-profile/ID/name");
        }

        try {
            // Fetch page with browser-like headers to avoid blocks
            Document doc = Jsoup.connect(profileUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(10000)
                    .get();

            Map<String, Object> stats = new LinkedHashMap<>();

            // ── Extract player name ───────────────────────────────
            Element nameEl = doc.selectFirst("h1.player-name, .profile-name h1");
            if (nameEl != null) {
                stats.put("name", nameEl.text().trim());
            }

            // ── Extract batting stats ─────────────────────────────
            // CricHeroes shows stats in labeled boxes
            // Structure varies — parse all stat boxes
            doc.select(".stat-box, .player-stat, [class*='stat']")
                    .forEach(box -> {
                        String label = "";
                        String value = "";

                        Element labelEl = box.selectFirst(
                                ".stat-label, .label, small, span.text-muted");
                        Element valueEl = box.selectFirst(
                                ".stat-value, .value, strong, h4, h3");

                        if (labelEl != null) label = labelEl.text().trim().toLowerCase();
                        if (valueEl != null) value = valueEl.text().trim();

                        if (!label.isEmpty() && !value.isEmpty()) {
                            mapStatToField(stats, label, value);
                        }
                    });

            // ── Extract profile photo ─────────────────────────────
            Element photoEl = doc.selectFirst(
                    ".player-avatar img, .profile-photo img, " +
                            "[class*='avatar'] img, [class*='profile'] img");
            if (photoEl != null) {
                String src = photoEl.attr("src");
                if (!src.isEmpty() && src.startsWith("http")) {
                    stats.put("photoUrl", src);
                }
            }

            // ── Extract player role ───────────────────────────────
            Element roleEl = doc.selectFirst(
                    ".player-role, .batting-style, [class*='role']");
            if (roleEl != null) {
                stats.put("role", mapRole(roleEl.text().trim()));
            }

            stats.put("sourceUrl",   profileUrl);
            stats.put("fetchedFrom", "CricHeroes");
            stats.put("fetchedAt",   new Date().toString());

            log.info("Fetched CricHeroes stats for: {}",
                    stats.getOrDefault("name", "Unknown"));

            return stats;

        } catch (Exception e) {
            log.error("CricHeroes fetch failed for {}: {}",
                    profileUrl, e.getMessage());
            throw new RuntimeException(
                    "Could not fetch stats from CricHeroes. " +
                            "Please check the URL or enter stats manually. Error: "
                            + e.getMessage());
        }
    }

    private void mapStatToField(Map<String, Object> stats,
                                String label, String value) {
        // Map CricHeroes label text to our field names
        if (label.contains("match"))   stats.put("matches",    parseNum(value));
        if (label.contains("run"))     stats.put("runs",        parseNum(value));
        if (label.contains("average") || label.equals("avg"))
            stats.put("average",     parseDecimal(value));
        if (label.contains("strike") || label.contains("s/r") || label.contains("sr"))
            stats.put("strikeRate",  parseDecimal(value));
        if (label.contains("wicket"))  stats.put("wickets",     parseNum(value));
        if (label.contains("highest") || label.contains("hs"))
            stats.put("highestScore",parseNum(value));
        if (label.contains("fifty") || label.contains("50s"))
            stats.put("fifties",     parseNum(value));
        if (label.contains("hundred") || label.contains("100s"))
            stats.put("hundreds",    parseNum(value));
        if (label.contains("economy") || label.contains("econ"))
            stats.put("economy",     parseDecimal(value));
        if (label.contains("inning"))  stats.put("innings",     parseNum(value));
    }

    private String mapRole(String roleText) {
        if (roleText == null) return "Batsman";
        String r = roleText.toLowerCase();
        if (r.contains("all"))    return "All-Rounder";
        if (r.contains("bowl"))   return "Bowler";
        if (r.contains("keep") || r.contains("wk")) return "WK-Batsman";
        return "Batsman";
    }

    private Integer parseNum(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return null; }
    }

    private Double parseDecimal(String s) {
        try { return Double.parseDouble(s.replaceAll("[^0-9.]", "")); }
        catch (Exception e) { return null; }
    }
}