package org.mercsmavs.frccopilot.ingest.tba;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TbaClientTest {

    private static final String MATCH_JSON =
            """
            {
              "key": "2026dal_qm10",
              "comp_level": "qm",
              "match_number": 10,
              "winning_alliance": "red",
              "actual_time": 1700000000,
              "alliances": {
                "red":  { "score": 88, "team_keys": ["frc6369", "frc118", "frc254"] },
                "blue": { "score": 72, "team_keys": ["frc6773", "frc148", "frc973"] }
              }
            }
            """;

    @Test
    void parsesMatchScoreWinnerAllianceAndResult() throws Exception {
        TbaClient client = new TbaClient(url -> MATCH_JSON);
        TbaMatch m = client.getMatch("2026dal_qm10");

        assertEquals("2026dal_qm10", m.key());
        assertEquals(10, m.matchNumber());
        assertEquals(88, m.redScore());
        assertEquals(72, m.blueScore());
        assertEquals("red", m.allianceOf("frc6369"));
        assertEquals("blue", m.allianceOf("frc6773"));
        assertEquals(TbaMatch.Outcome.WIN, m.resultForTeam(6369));
        assertEquals(TbaMatch.Outcome.LOSS, m.resultForTeam(6773));
        assertEquals(TbaMatch.Outcome.UNKNOWN, m.resultForTeam(9999));
    }

    @Test
    void midnightRolloverSnapsToTbaDateKeepingTimeOfDay() {
        long tba = 1_700_000_000L; // authoritative
        long logOneDayOff = tba + 86_400L; // FMS stamped a day late, same time-of-day
        long fixed = TbaClient.midnightRolloverFix(logOneDayOff, tba);
        // Corrected time is within the same day as TBA (diff <= 12h now).
        assertEquals(true, Math.abs(fixed - tba) <= 12 * 3600);
        // A close time is left untouched.
        assertEquals(tba + 30, TbaClient.midnightRolloverFix(tba + 30, tba));
    }
}
