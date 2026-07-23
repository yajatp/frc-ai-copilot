package org.mercsmavs.frccopilot.write;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A minimal structural diff between two JSON trees, reported as changed leaf paths. Used to present
 * a proposed path edit as a small, reviewable change list rather than a wall of re-serialized JSON —
 * a human applies it (or we write to a copy). Nothing is ever silently overwritten.
 */
public final class PathDiff {

    public record Change(String path, String before, String after) {
        @Override
        public String toString() {
            return path + ": " + before + " -> " + after;
        }
    }

    public static List<Change> diff(JsonNode before, JsonNode after) {
        List<Change> changes = new ArrayList<>();
        walk("", before, after, changes);
        return changes;
    }

    private static void walk(String path, JsonNode a, JsonNode b, List<Change> out) {
        if (a == null) a = com.fasterxml.jackson.databind.node.NullNode.getInstance();
        if (b == null) b = com.fasterxml.jackson.databind.node.NullNode.getInstance();

        if (a.isObject() && b.isObject()) {
            Set<String> keys = new LinkedHashSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            b.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                walk(join(path, k), a.get(k), b.get(k), out);
            }
        } else if (a.isArray() && b.isArray()) {
            int n = Math.max(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                walk(path + "[" + i + "]", a.get(i), b.get(i), out);
            }
        } else if (!a.equals(b)) {
            out.add(new Change(path.isEmpty() ? "(root)" : path, render(a), render(b)));
        }
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static String render(JsonNode n) {
        return n == null || n.isMissingNode() ? "(absent)" : n.toString();
    }

    private PathDiff() {}
}
