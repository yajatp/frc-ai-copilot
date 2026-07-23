package org.mercsmavs.frccopilot.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class StructDecoderTest {

    @Test
    void decodesPose2dRoundTrip() {
        Pose2d pose = new Pose2d(1.5, -2.25, Rotation2d.fromDegrees(90));
        byte[] bytes = new byte[Pose2d.struct.getSize()];
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        Pose2d.struct.pack(bb, pose);

        Object decoded = StructDecoder.decode("struct:Pose2d", bytes);
        Pose2d back = assertInstanceOf(Pose2d.class, decoded);
        assertEquals(1.5, back.getX(), 1e-9);
        assertEquals(-2.25, back.getY(), 1e-9);
        assertEquals(90, back.getRotation().getDegrees(), 1e-9);
    }

    @Test
    void decodesSwerveModuleStateArray() {
        SwerveModuleState[] states = {
            new SwerveModuleState(3.0, Rotation2d.fromDegrees(10)),
            new SwerveModuleState(-1.0, Rotation2d.fromDegrees(-45)),
        };
        int size = SwerveModuleState.struct.getSize();
        byte[] bytes = new byte[size * states.length];
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (SwerveModuleState s : states) {
            SwerveModuleState.struct.pack(bb, s);
        }

        Object decoded = StructDecoder.decode("struct:SwerveModuleState[]", bytes);
        Object[] arr = assertInstanceOf(Object[].class, decoded);
        assertEquals(2, arr.length);
        assertEquals(3.0, ((SwerveModuleState) arr[0]).speedMetersPerSecond, 1e-9);
    }

    @Test
    void unknownStructAndNonStructFallBack() {
        assertNull(StructDecoder.decode("struct:SomeCustomTeamType", new byte[8]));
        assertNull(StructDecoder.decode("double", new byte[8]));
        assertTrue(StructDecoder.supportedTypes().contains("Pose2d"));
        assertTrue(StructDecoder.supportedTypes().contains("ChassisSpeeds"));
    }
}
