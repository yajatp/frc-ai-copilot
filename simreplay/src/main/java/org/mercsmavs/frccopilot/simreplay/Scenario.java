package org.mercsmavs.frccopilot.simreplay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A named set of success criteria — the unit of the regression suite. Every verified fix becomes a
 * stored scenario that future changes are re-checked against (the compounding value the plan calls
 * out; neither reference project has this).
 */
public record Scenario(String name, String description, List<Assertion> assertions) {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static Scenario load(Path file) throws IOException {
        return YAML.readValue(Files.readString(file), Scenario.class);
    }

    public String toYaml() throws IOException {
        return YAML.writeValueAsString(this);
    }

    public void save(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, toYaml());
    }
}
