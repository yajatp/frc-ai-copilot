package org.mercsmavs.frccopilot.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AutoFileTest {

    // The real structure of 6369 Echo's LeftJamesAuto.auto, verbatim.
    private static final String AUTO_JSON =
            """
            {
              "version": "2025.0",
              "command": {
                "type": "sequential",
                "data": {
                  "commands": [
                    { "type": "path", "data": { "pathName": "JAMES1" } },
                    { "type": "named", "data": { "name": "AimMode" } },
                    { "type": "named", "data": { "name": "BeginShot" } },
                    { "type": "wait", "data": { "waitTime": 2.5 } },
                    { "type": "named", "data": { "name": "StopShot" } },
                    { "type": "named", "data": { "name": "StopAim" } },
                    { "type": "path", "data": { "pathName": "JAMES2" } },
                    { "type": "named", "data": { "name": "BeginShot" } },
                    { "type": "named", "data": { "name": "AimMode" } },
                    { "type": "wait", "data": { "waitTime": 2.5 } },
                    { "type": "named", "data": { "name": "StopShot" } },
                    { "type": "named", "data": { "name": "StopAim" } },
                    { "type": "path", "data": { "pathName": "JAMES3" } }
                  ]
                }
              },
              "resetOdom": true,
              "folder": null,
              "choreoAuto": false
            }
            """;

    @Test
    void parsesRealAutoSchema() throws Exception {
        AutoFile auto = AutoFile.parse(AUTO_JSON);
        assertEquals("2025.0", auto.version());
        assertEquals("sequential", auto.commandType());
        assertTrue(auto.resetOdom());
        assertFalse(auto.choreoAuto());
        assertEquals(null, auto.folder());
    }

    @Test
    void listsPathReferencesInOrder() throws Exception {
        AutoFile auto = AutoFile.parse(AUTO_JSON);
        assertEquals(List.of("JAMES1", "JAMES2", "JAMES3"), auto.listPathReferences());
    }

    @Test
    void swapOneReferencePreservesEverythingElse() throws Exception {
        AutoFile auto = AutoFile.parse(AUTO_JSON);
        AutoFile edited = auto.copy();

        int n = edited.replacePathReference("JAMES2", "RJAMES2");
        assertEquals(1, n);

        // Exactly one path reference changed; the other two JAMES paths and every named/wait command
        // are untouched.
        assertEquals(List.of("JAMES1", "RJAMES2", "JAMES3"), edited.listPathReferences());

        List<PathDiff.Change> changes = PathDiff.diff(auto.root(), edited.root());
        assertEquals(1, changes.size(), () -> "unexpected changes: " + changes);
        PathDiff.Change change = changes.get(0);
        assertTrue(change.path().contains("pathName"), "changed leaf should be a pathName field");
        assertTrue(change.before().contains("JAMES2"));
        assertTrue(change.after().contains("RJAMES2"));

        // Everything not touched by the swap round-trips identically.
        assertEquals(auto.version(), edited.version());
        assertEquals(auto.commandType(), edited.commandType());
        assertEquals(auto.resetOdom(), edited.resetOdom());
        assertEquals(auto.choreoAuto(), edited.choreoAuto());
        assertTrue(edited.toJson().contains("\"AimMode\""));
        assertTrue(edited.toJson().contains("\"waitTime\" : 2.5"));
    }

    @Test
    void swappingUnreferencedNameIsANoOp() throws Exception {
        AutoFile auto = AutoFile.parse(AUTO_JSON);
        AutoFile edited = auto.copy();
        int n = edited.replacePathReference("DOES_NOT_EXIST", "WHATEVER");
        assertEquals(0, n);
        assertEquals(List.of("JAMES1", "JAMES2", "JAMES3"), edited.listPathReferences());
        assertEquals(0, PathDiff.diff(auto.root(), edited.root()).size());
    }

    @Test
    void showSummaryIncludesKeyFieldsAndPaths() throws Exception {
        AutoFile auto = AutoFile.parse(AUTO_JSON);
        String summary = auto.show();
        assertTrue(summary.contains("sequential"));
        assertTrue(summary.contains("JAMES1"));
        assertTrue(summary.contains("JAMES2"));
        assertTrue(summary.contains("JAMES3"));
    }

    @Test
    void roundTripsThroughJsonUnchanged() throws Exception {
        AutoFile auto = AutoFile.parse(AUTO_JSON);
        AutoFile reparsed = AutoFile.parse(auto.toJson());
        assertEquals(0, PathDiff.diff(auto.root(), reparsed.root()).size());
    }
}
