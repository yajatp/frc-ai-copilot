package org.mercsmavs.frccopilot.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads/writes {@link RobotProfile} as human-editable YAML (Jackson handles records natively). */
public final class ProfileMapper {

    private static final ObjectMapper YAML =
            new ObjectMapper(
                            new YAMLFactory()
                                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES))
                    .enable(SerializationFeature.INDENT_OUTPUT);

    public static String toYaml(RobotProfile profile) throws IOException {
        return YAML.writeValueAsString(profile);
    }

    public static void write(RobotProfile profile, Path path, String headerComment) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (headerComment != null) {
            for (String line : headerComment.split("\n", -1)) {
                sb.append("# ").append(line).append('\n');
            }
        }
        sb.append(toYaml(profile));
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, sb.toString());
    }

    public static RobotProfile read(Path path) throws IOException {
        return YAML.readValue(Files.readString(path), RobotProfile.class);
    }

    private ProfileMapper() {}
}
