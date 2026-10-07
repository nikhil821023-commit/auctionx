package com.auctionx.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fetches player stats from a CricHeroes share link such as
 * https://chshare.link/player/eYcqVy
 *
 * The page is rendered in the browser (JS), so plain HTML parsing returns nothing.
 * Strategy: open it in headless Chromium, capture every JSON response the page
 * receives, then search those JSON bodies for stat fields by key name.
 * This survives layout/CSS changes. It only breaks if CricHeroes renames the
 * JSON keys or blocks automated browsers.
 *
 * pom.xml:
 *   <dependency>
 *     <groupId>com.microsoft.playwright</groupId>
 *     <artifactId>playwright</artifactId>
 *     <version>1.48.0</version>
 *   </dependency>
 * One-time browser install on the server:
 *   mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
 */
@Service
@Slf4j
public class CricHeroesFetcherService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // our field -> possible JSON key names (lower-cased, underscores removed)
    private static final Map<String, List<String>> KEYS = new LinkedHashMap<>();
    static {
        KEYS.put("name",         List.of("playername", "name", "fullname"));
        KEYS.put("matches",      List.of("totalmatches", "matches", "matchesplayed", "mat"));
        KEYS.put("innings",      List.of("innings", "totalinnings", "inns"));
        KEYS.put("runs",         List.of("totalruns", "runs", "runsscored"));
        KEYS.put("average",      List.of("battingaverage", "average", "avg", "batavg"));
        KEYS.put("strikeRate",   List.of("strikerate", "battingstrikerate", "sr"));
        KEYS.put("highestScore", List.of("highestscore", "hs", "highestruns"));
        KEYS.put("fifties",      List.of("fifties", "50s", "halfcenturies"));
        KEYS.put("hundreds",     List.of("hundreds", "100s", "centuries"));
        KEYS.put("wickets",      List.of("totalwickets", "wickets", "wkts"));
        KEYS.put("economy",      List.of("economy", "economyrate", "econ"));
        KEYS.put("role",         List.of("playingrole", "role", "playerrole"));
        KEYS.put("photoUrl",     List.of("profilephoto", "profilepic", "photo", "avatar"));
    }

    public static boolean isValidUrl(String url) {
        return url != null && (url.startsWith("https://chshare.link/player/")
                || url.contains("cricheroes.in/player-profile")
                || url.contains("cricheroes.com/player-profile"));
    }

    public Map<String, Object> fetchPlayerStats(String url) {
        if (!isValidUrl(url)) {
            throw new IllegalArgumentException(
                    "Invalid CricHeroes link. Use the share link, e.g. https://chshare.link/player/xxxxxx");
        }

        List<JsonNode> jsonBodies = new CopyOnWriteArrayList<>();

        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {

            BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .setLocale("en-IN"));
            Page page = ctx.newPage();

            // Capture every JSON response and log its URL, so you can find the real endpoint
            page.onResponse(resp -> {
                try {
                    String type = resp.headers().getOrDefault("content-type", "");
                    if (type.contains("json")) {
                        log.info("[CricHeroes JSON] {} {}", resp.status(), resp.url());
                        jsonBodies.add(MAPPER.readTree(resp.text()));
                    }
                } catch (Exception ignored) { }
            });

            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE)
                    .setTimeout(30000));
            page.waitForTimeout(1500); // let late calls finish

            Map<String, Object> stats = new LinkedHashMap<>();

            // 1) Preferred: pull from captured JSON
            for (JsonNode body : jsonBodies) {
                extract(body, stats);
            }

            // 2) Fallback: page title / visible text for the name at least
            if (!stats.containsKey("name")) {
                String title = page.title();
                if (title != null && !title.isBlank()) stats.put("name", title.split("[|\\-–]")[0].trim());
            }

            if (stats.size() <= 1) {
                log.warn("No stats found. Captured {} JSON responses. Visible text: {}",
                        jsonBodies.size(),
                        page.innerText("body").substring(0, Math.min(500, page.innerText("body").length())));
                throw new RuntimeException(
                        "Could not read stats from this link. Enter them manually.");
            }

            stats.put("sourceUrl", url);
            stats.put("fetchedFrom", "CricHeroes");
            return stats;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("CricHeroes fetch failed for {}: {}", url, e.getMessage());
            throw new RuntimeException("Could not fetch stats: " + e.getMessage());
        }
    }

    /** Walk the whole JSON tree; fill any of our fields that are still empty. */
    private void extract(JsonNode node, Map<String, Object> out) {
        if (node == null) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String key = e.getKey().toLowerCase().replace("_", "").replace(" ", "");
                JsonNode val = e.getValue();
                if (val.isValueNode() && !val.isNull()) {
                    for (var f : KEYS.entrySet()) {
                        if (!out.containsKey(f.getKey()) && f.getValue().contains(key)) {
                            out.put(f.getKey(), val.isNumber() ? val.numberValue() : val.asText());
                        }
                    }
                } else {
                    extract(val, out);
                }
            });
        } else if (node.isArray()) {
            node.forEach(n -> extract(n, out));
        }
    }
}