package org.mercsmavs.frccopilot.ingest.tba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The Blue Alliance API v3 client, used to enrich logs with match scores/results/timing.
 *
 * <p>The HTTP call is behind an injectable {@code fetcher} (url -&gt; JSON body) so the parsing and
 * enrichment logic is fully unit-testable offline. Use {@link #withKey}/{@link #fromEnv} for the
 * real HTTP-backed client. The API key is never hardcoded.
 */
public final class TbaClient {

    private static final String BASE = "https://www.thebluealliance.com/api/v3";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Function<String, String> fetcher;

    public TbaClient(Function<String, String> fetcher) {
        this.fetcher = fetcher;
    }

    public static TbaClient withKey(String authKey) {
        HttpClient http = HttpClient.newHttpClient();
        return new TbaClient(url -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("X-TBA-Auth-Key", authKey)
                        .GET()
                        .build();
                return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("TBA request failed: " + url, e);
            }
        });
    }

    public static TbaClient fromEnv() {
        String key = System.getenv("TBA_AUTH_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("TBA_AUTH_KEY environment variable is not set");
        }
        return withKey(key);
    }

    public TbaMatch getMatch(String matchKey) throws IOException {
        return parseMatch(mapper.readTree(fetcher.apply(BASE + "/match/" + matchKey)));
    }

    public List<TbaMatch> matchesForTeamEvent(String teamKey, String eventKey) throws IOException {
        JsonNode arr = mapper.readTree(fetcher.apply(BASE + "/team/" + teamKey + "/event/" + eventKey + "/matches"));
        List<TbaMatch> matches = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                matches.add(parseMatch(n));
            }
        }
        return matches;
    }

    static TbaMatch parseMatch(JsonNode n) {
        return new TbaMatch(
                n.path("key").asText(null),
                n.path("comp_level").asText(null),
                n.path("match_number").asInt(0),
                n.at("/alliances/red/score").asInt(-1),
                n.at("/alliances/blue/score").asInt(-1),
                n.path("winning_alliance").asText(""),
                teamKeys(n.at("/alliances/red/team_keys")),
                teamKeys(n.at("/alliances/blue/team_keys")),
                n.path("actual_time").asLong(0));
    }

    private static List<String> teamKeys(JsonNode arr) {
        List<String> keys = new ArrayList<>();
        if (arr.isArray()) {
            arr.forEach(k -> keys.add(k.asText()));
        }
        return keys;
    }

    /**
     * FMS occasionally stamps a log with the wrong date (a midnight rollover). Given the log's start
     * time and TBA's authoritative match time, if they differ by more than 12 hours, snap the log to
     * TBA's date while keeping the log's time-of-day. Returns the (possibly corrected) epoch seconds.
     */
    public static long midnightRolloverFix(long logEpochSec, long tbaActualEpochSec) {
        if (tbaActualEpochSec <= 0) {
            return logEpochSec;
        }
        long diff = Math.abs(logEpochSec - tbaActualEpochSec);
        if (diff <= 12 * 3600) {
            return logEpochSec; // close enough; trust the log
        }
        long secondsOfDay = Math.floorMod(logEpochSec, 86_400L);
        long tbaDayStart = Instant.ofEpochSecond(tbaActualEpochSec)
                .atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        return tbaDayStart + secondsOfDay;
    }
}
