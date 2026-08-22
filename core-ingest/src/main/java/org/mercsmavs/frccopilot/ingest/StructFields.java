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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Projects a decoded struct value onto scalar fields, so the parts of it that a check can actually
 * be written against are reachable by name.
 *
 * <p>This is what makes {@code /RealOutputs/Odometry/Robot/X} a signal. A {@code Pose2d} is the
 * single most important thing an autonomous routine produces, but nothing downstream — assertions,
 * scenario generation, baseline divergence — can compare two poses without first choosing a number.
 * Rather than teach each of those about geometry types, the reader publishes every struct entry a
 * second time as its scalar fields, and they stay unchanged.
 *
 * <p>Field names are part of the interface: a scenario committed against {@code .../Robot/X} has to
 * keep resolving next season, so these suffixes do not get renamed. Angles are published in both
 * radians (the bare name, matching WPILib) and degrees (the {@code Deg} suffix), because a
 * hand-written threshold is nearly always in degrees and silently reading radians as degrees is a
 * mistake worth designing out.
 *
 * <p>Struct <em>arrays</em> are deliberately not projected. A {@code SwerveModuleState[]} or an
 * odometry batch has no stable per-index meaning across runs, so an assertion on element 3 would
 * defend nothing.
 */
public final class StructFields {

    private StructFields() {}

    /** Separator between a struct entry's name and one of its scalar fields. */
    public static final String SEPARATOR = "/";

    /**
     * The scalar fields of one decoded struct value, in a stable order, or an empty map if the
     * value is not a struct this knows how to project (including any struct array).
     */
    public static Map<String, Double> project(Object value) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (value instanceof Pose2d p) {
            out.put("X", p.getX());
            out.put("Y", p.getY());
            putAngle(out, "Rotation", p.getRotation().getRadians());
        } else if (value instanceof Pose3d p) {
            out.put("X", p.getX());
            out.put("Y", p.getY());
            out.put("Z", p.getZ());
            putRotation3d(out, p.getRotation());
        } else if (value instanceof Translation2d t) {
            out.put("X", t.getX());
            out.put("Y", t.getY());
        } else if (value instanceof Translation3d t) {
            out.put("X", t.getX());
            out.put("Y", t.getY());
            out.put("Z", t.getZ());
        } else if (value instanceof Rotation2d r) {
            putAngle(out, "Rotation", r.getRadians());
        } else if (value instanceof Rotation3d r) {
            putRotation3d(out, r);
        } else if (value instanceof Transform2d t) {
            out.put("X", t.getX());
            out.put("Y", t.getY());
            putAngle(out, "Rotation", t.getRotation().getRadians());
        } else if (value instanceof Transform3d t) {
            out.put("X", t.getX());
            out.put("Y", t.getY());
            out.put("Z", t.getZ());
            putRotation3d(out, t.getRotation());
        } else if (value instanceof Twist2d t) {
            out.put("Dx", t.dx);
            out.put("Dy", t.dy);
            putAngle(out, "Dtheta", t.dtheta);
        } else if (value instanceof Twist3d t) {
            out.put("Dx", t.dx);
            out.put("Dy", t.dy);
            out.put("Dz", t.dz);
            putAngle(out, "Rx", t.rx);
            putAngle(out, "Ry", t.ry);
            putAngle(out, "Rz", t.rz);
        } else if (value instanceof ChassisSpeeds s) {
            out.put("Vx", s.vxMetersPerSecond);
            out.put("Vy", s.vyMetersPerSecond);
            putAngle(out, "Omega", s.omegaRadiansPerSecond);
            out.put("Speed", Math.hypot(s.vxMetersPerSecond, s.vyMetersPerSecond));
        } else if (value instanceof SwerveModuleState s) {
            out.put("Speed", s.speedMetersPerSecond);
            putAngle(out, "Angle", s.angle.getRadians());
        } else if (value instanceof SwerveModulePosition p) {
            out.put("Distance", p.distanceMeters);
            putAngle(out, "Angle", p.angle.getRadians());
        }
        return out;
    }

    /** True when {@link #project} would return anything for this value. */
    public static boolean isProjectable(Object value) {
        return !project(value).isEmpty();
    }

    private static void putRotation3d(Map<String, Double> out, Rotation3d r) {
        putAngle(out, "Roll", r.getX());
        putAngle(out, "Pitch", r.getY());
        putAngle(out, "Yaw", r.getZ());
    }

    /** Publish an angle under both its radian name and a {@code Deg} companion. */
    private static void putAngle(Map<String, Double> out, String name, double radians) {
        out.put(name, radians);
        out.put(name + "Deg", Math.toDegrees(radians));
    }
}
