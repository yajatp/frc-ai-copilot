package org.mercsmavs.frccopilot.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A PathPlanner {@code .auto} file, edited with full round-trip fidelity — same philosophy as
 * {@link PathFile}: keep the whole document as a Jackson tree and only mutate the specific fields we
 * intend to change, so everything we don't model (nested command groups, named-command args, wait
 * times, etc.) is preserved byte-for-byte in meaning.
 *
 * <p>Schema confirmed against real 6369 Echo autos ({@code LeftJamesAuto.auto},
 * {@code RightJamesAuto.auto}):
 *
 * <pre>{@code
 * {
 *   "version": "2025.0",
 *   "command": {
 *     "type": "sequential",
 *     "data": {
 *       "commands": [
 *         { "type": "path",  "data": { "pathName": "JAMES1" } },
 *         { "type": "named", "data": { "name": "AimMode" } },
 *         { "type": "wait",  "data": { "waitTime": 2.5 } },
 *         ...
 *       ]
 *     }
 *   },
 *   "resetOdom": true,
 *   "folder": null,
 *   "choreoAuto": false
 * }
 * }</pre>
 *
 * <p>Command groups (only {@code "sequential"} observed in the reference autos, but PathPlanner also
 * emits {@code "parallel"}, {@code "race"} and {@code "deadline"}) nest further {@code "commands"}
 * arrays, and individual commands ({@code "path"}, {@code "named"}, {@code "wait"}, ...) may in turn
 * be group commands themselves. Rather than hard-coding that recursion for one purpose, the read/edit
 * operations below walk the raw tree generically: any object field named {@code "pathName"} is a path
 * reference, wherever it's nested.
 */
public final class AutoFile {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectNode root;

    public AutoFile(ObjectNode root) {
        this.root = root;
    }

    public static AutoFile parse(String json) throws IOException {
        JsonNode node = JSON.readTree(json);
        if (!(node instanceof ObjectNode obj)) {
            throw new IOException("Not a PathPlanner auto object");
        }
        return new AutoFile(obj);
    }

    public static AutoFile load(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    /** Pretty-printed JSON (2-space indent, matching PathPlanner's own output style). */
    public String toJson() throws IOException {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }

    public void save(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, toJson());
    }

    /** A deep copy, so callers can diff before/after. */
    public AutoFile copy() {
        return new AutoFile(root.deepCopy());
    }

    /** The underlying JSON tree (read view), e.g. for diffing before/after an edit. */
    public JsonNode root() {
        return root;
    }

    // --- read ---

    public String version() {
        return root.path("version").asText(null);
    }

    public String commandType() {
        return root.path("command").path("type").asText(null);
    }

    public boolean resetOdom() {
        return root.path("resetOdom").asBoolean(false);
    }

    public boolean choreoAuto() {
        return root.path("choreoAuto").asBoolean(false);
    }

    /** The PathPlanner UI folder this auto is filed under, or {@code null} if unset/root. */
    public String folder() {
        JsonNode f = root.get("folder");
        return (f == null || f.isNull()) ? null : f.asText();
    }

    /**
     * Every {@code pathName} referenced anywhere in the command tree, in document order — i.e. every
     * {@code .path} file this auto depends on. Walks the whole tree generically (see class Javadoc),
     * so it finds path references regardless of how deeply they're nested inside command groups.
     */
    public List<String> listPathReferences() {
        List<String> out = new ArrayList<>();
        collectPathNames(root, out);
        return out;
    }

    /** A short human-readable summary, e.g. for a CLI {@code show} command. */
    public String show() {
        List<String> paths = listPathReferences();
        StringBuilder sb = new StringBuilder();
        sb.append("version:     ").append(version()).append('\n');
        sb.append("commandType: ").append(commandType()).append('\n');
        sb.append("resetOdom:   ").append(resetOdom()).append('\n');
        sb.append("folder:      ").append(folder()).append('\n');
        sb.append("choreoAuto:  ").append(choreoAuto()).append('\n');
        sb.append("paths (").append(paths.size()).append("):").append('\n');
        for (String p : paths) {
            sb.append("  - ").append(p).append('\n');
        }
        return sb.toString();
    }

    // --- edit: the "point this auto at a renamed/replacement path" operation ---

    /**
     * Rename every reference to path {@code oldName} to {@code newName}, wherever it occurs in the
     * command tree (including inside nested sequential/parallel/race/deadline groups). Everything
     * else — named-command args, wait times, group structure — is left untouched.
     *
     * @return the number of references renamed (0 if {@code oldName} wasn't referenced at all).
     */
    public int replacePathReference(String oldName, String newName) {
        return replacePathNames(root, oldName, newName);
    }

    private static void collectPathNames(JsonNode node, List<String> out) {
        if (node.isObject()) {
            JsonNode pathName = node.get("pathName");
            if (pathName != null && pathName.isTextual()) {
                out.add(pathName.asText());
            }
            node.fields().forEachRemaining(entry -> collectPathNames(entry.getValue(), out));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectPathNames(child, out);
            }
        }
    }

    private static int replacePathNames(JsonNode node, String oldName, String newName) {
        int count = 0;
        if (node instanceof ObjectNode obj) {
            JsonNode pathName = obj.get("pathName");
            if (pathName != null && pathName.isTextual() && pathName.asText().equals(oldName)) {
                obj.put("pathName", newName);
                count++;
            }
            var fields = obj.fields();
            while (fields.hasNext()) {
                count += replacePathNames(fields.next().getValue(), oldName, newName);
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode child : arr) {
                count += replacePathNames(child, oldName, newName);
            }
        }
        return count;
    }
}
