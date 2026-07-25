package org.mercsmavs.frccopilot.ingest.tba;

import java.util.List;

/** A match from The Blue Alliance, reduced to what we use for log enrichment. */
public record TbaMatch(
        String key,
        String compLevel,
        int matchNumber,
        int redScore,
        int blueScore,
        String winningAlliance,
        List<String> redTeams,
        List<String> blueTeams,
        long actualTimeEpochSec) {

    public enum Outcome {
        WIN,
        LOSS,
        TIE,
        UNKNOWN
    }

    /** "red" / "blue" / null for a team key like "frc6369". */
    public String allianceOf(String teamKey) {
        if (redTeams != null && redTeams.contains(teamKey)) {
            return "red";
        }
        if (blueTeams != null && blueTeams.contains(teamKey)) {
            return "blue";
        }
        return null;
    }

    public Outcome resultForTeam(int team) {
        String alliance = allianceOf("frc" + team);
        if (alliance == null) {
            return Outcome.UNKNOWN;
        }
        if (winningAlliance == null || winningAlliance.isBlank()) {
            return Outcome.TIE;
        }
        return winningAlliance.equals(alliance) ? Outcome.WIN : Outcome.LOSS;
    }

    public int scoreForAlliance(String alliance) {
        return "red".equals(alliance) ? redScore : blueScore;
    }
}
