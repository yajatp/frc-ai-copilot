package org.mercsmavs.frccopilot.ingest.revlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class RevlogFileTest {

    @Test
    void decodesSparkStatus0PeriodicFrame() {
        // STATUS_0 ("Spark" deviceType=2, apiIndex=0): base CAN id 2181412864, device instance id
        // lives in the low 6 bits (see spark.public.dbc). 11 signals total in this frame.
        int deviceInstanceId = 3;
        int messageId = (int) (2181412864L + deviceInstanceId);

        byte[] canData = new byte[8];
        packLE(canData, 16, 12, 1638); // VOLTAGE raw -> ~12.0 V (factor 0.0073260073260073)
        packLE(canData, 28, 12, 137); // CURRENT raw -> ~5.02 A (factor 0.0366300366300366)
        packLE(canData, 40, 8, 30); // MOTOR_TEMPERATURE raw -> 30 degC (factor 1)
        packLE(canData, 48, 1, 1); // HARD_FORWARD_LIMIT_REACHED -> true

        byte[] revlog = buildRevlog(1234L, messageId, canData);

        RevlogFile.ParseResult result = RevlogFile.parse(revlog);

        assertEquals(11, result.samples().size());
        assertSample(result, "REV/Spark-3/VOLTAGE", 12.0, 0.01);
        assertSample(result, "REV/Spark-3/CURRENT", 5.02, 0.1);
        assertSample(result, "REV/Spark-3/MOTOR_TEMPERATURE", 30.0, 0.001);
        assertSample(result, "REV/Spark-3/HARD_FORWARD_LIMIT_REACHED", 1.0, 0.0);
        assertEquals(1234L, result.samples().get(0).timestampMs());
    }

    @Test
    void ignoresUnknownDeviceTypesWithoutThrowing() {
        // deviceType bits (24-28) set to an id no DBC declares.
        int messageId = (0x1F << 24);
        byte[] revlog = buildRevlog(0L, messageId, new byte[8]);
        assertEquals(0, RevlogFile.parse(revlog).samples().size());
    }

    private static void assertSample(RevlogFile.ParseResult result, String signal, double expected, double tolerance) {
        for (RevlogFile.Sample s : result.samples()) {
            if (s.signal().equals(signal)) {
                assertEquals(expected, s.value(), tolerance, signal);
                return;
            }
        }
        fail("missing signal " + signal);
    }

    /** Sets {@code length} bits of {@code value} starting at absolute bit {@code startBit} (LSB-first, Intel order). */
    private static void packLE(byte[] data, int startBit, int length, long value) {
        for (int i = 0; i < length; i++) {
            int bit = (int) ((value >> i) & 1);
            int absoluteBit = startBit + i;
            int byteIdx = absoluteBit / 8;
            int bitIdx = absoluteBit % 8;
            data[byteIdx] |= (byte) (bit << bitIdx);
        }
    }

    /** Builds a minimal .revlog byte stream with one entryId=2 (periodic) record containing one CAN frame. */
    private static byte[] buildRevlog(long timestampMs, int messageId, byte[] canData) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeUIntLE(payload, timestampMs, 4);
        writeUIntLE(payload, Integer.toUnsignedLong(messageId), 4);
        payload.writeBytes(canData);
        byte[] payloadBytes = payload.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); // bitfield: entryIdLen=1, sizeLen=1
        writeUIntLE(out, 2, 1); // entryId = 2 (periodic block)
        writeUIntLE(out, payloadBytes.length, 1);
        out.writeBytes(payloadBytes);
        return out.toByteArray();
    }

    private static void writeUIntLE(ByteArrayOutputStream out, long value, int length) {
        for (int i = 0; i < length; i++) {
            out.write((int) ((value >> (i * 8)) & 0xFF));
        }
    }
}
