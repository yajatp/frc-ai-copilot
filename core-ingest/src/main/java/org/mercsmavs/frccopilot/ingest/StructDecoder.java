package org.mercsmavs.frccopilot.ingest;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Rotation3d;
import edu.wpi.first.math.geometry.Transform2d;
import edu.wpi.first.math.geometry.Transform3d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.geometry.Translation3d;
import edu.wpi.first.math.geometry.Twist2d;
import edu.wpi.first.math.geometry.Twist3d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.util.struct.Struct;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Decodes WPILib "struct:*" payloads (geometry/kinematics types) using WPILib's own {@link Struct}
 * serializers — so we get real {@code Pose2d}/{@code SwerveModuleState}/... objects instead of raw
 * bytes, without reimplementing any binary layout. Custom/team-defined structs (whose schema is
 * embedded in the log rather than known ahead of time) are left as raw bytes for now.
 */
public final class StructDecoder {

    private static final Map<String, Struct<?>> REGISTRY = new HashMap<>();

    static {
        register(Translation2d.struct);
        register(Translation3d.struct);
        register(Rotation2d.struct);
        register(Rotation3d.struct);
        register(Pose2d.struct);
        register(Pose3d.struct);
        register(Transform2d.struct);
        register(Transform3d.struct);
        register(Twist2d.struct);
        register(Twist3d.struct);
        register(ChassisSpeeds.struct);
        register(SwerveModuleState.struct);
        register(SwerveModulePosition.struct);
    }

    private static void register(Struct<?> struct) {
        REGISTRY.put(baseName(struct.getTypeString()), struct);
    }

    public static boolean isStructType(String wpilogType) {
        return wpilogType != null && wpilogType.startsWith("struct:");
    }

    /**
     * Decode struct or struct-array bytes into a typed object (or array), or return {@code null} if
     * the type is unknown or the bytes don't match (caller falls back to raw).
     */
    public static Object decode(String wpilogType, byte[] raw) {
        if (!isStructType(wpilogType) || raw == null) {
            return null;
        }
        String base = baseName(wpilogType);
        boolean array = base.endsWith("[]");
        if (array) {
            base = base.substring(0, base.length() - 2);
        }
        Struct<?> struct = REGISTRY.get(base);
        if (struct == null) {
            return null;
        }
        try {
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            if (array) {
                int size = struct.getSize();
                if (size <= 0) {
                    return null;
                }
                return unpackArray(bb, raw.length / size, struct);
            }
            return struct.unpack(bb);
        } catch (RuntimeException e) {
            return null; // truncated / mismatched payload — let caller keep the raw bytes
        }
    }

    private static <T> Object unpackArray(ByteBuffer bb, int count, Struct<T> struct) {
        return Struct.unpackArray(bb, count, struct);
    }

    private static String baseName(String typeString) {
        return typeString.startsWith("struct:") ? typeString.substring("struct:".length()) : typeString;
    }

    public static Set<String> supportedTypes() {
        return REGISTRY.keySet();
    }

    private StructDecoder() {}
}
