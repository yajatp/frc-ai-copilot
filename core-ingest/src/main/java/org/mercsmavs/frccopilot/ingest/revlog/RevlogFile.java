package org.mercsmavs.frccopilot.ingest.revlog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Parses REV Hardware Client's proprietary {@code .revlog} binary format (SPARK MAX/Flex, Servo
 * Hub, and through-bore encoder CAN status-frame telemetry), relevant to Team 6369's mixed
 * CTRE+REV robot.
 *
 * <p>The format itself is REV-proprietary, but REV also publishes an official open-source decoder,
 * {@code node-revlog-converter} (BSD-3-Clause,
 * <a href="https://github.com/REVrobotics/node-revlog-converter">github.com/REVrobotics/node-revlog-converter</a>),
 * which this class is a faithful Java port of: the outer record framing (a variable-width
 * bitfield/entryId/size/payload structure), the firmware (entryId 1) and periodic-CAN (entryId 2)
 * record layouts, and CAN signal decoding via the same {@code .dbc} database files REV ships
 * (vendored under {@code src/main/resources/revlog-dbc/}, see the LICENSE file there). See
 * {@link Dbc} and {@link CanDecoder} for the DBC-parsing and bit-decoding pieces.
 *
 * <p>Decoded signals are returned as plain (timestampMs, signal path, value) samples rather than
 * converted straight to a {@code .wpilog} — the format-independent, already-tested piece of this
 * pipeline is {@link TimeSync}, which cross-correlates a decoded REV signal against a WPILOG
 * signal to recover their timing offset.
 */
public final class RevlogFile {

    public record Sample(long timestampMs, String signal, double value) {}

    public record FirmwareRecord(String signal, String version) {}

    public record ParseResult(List<Sample> samples, List<FirmwareRecord> firmware) {}

    private static final class Device {
        final Dbc dbc;
        final String prefix;
        final Dbc.Message firmwareMessage;
        final Map<Integer, Dbc.Message> periodicFrames;
        final Function<byte[], String> firmwareVersionParser;

        Device(Dbc dbc, String prefix, String firmwareMessageName, String periodicPrefix,
                Function<byte[], String> firmwareVersionParser) {
            this.dbc = dbc;
            this.prefix = prefix;
            this.firmwareMessage = dbc.messagesByName.get(firmwareMessageName);
            this.periodicFrames = new LinkedHashMap<>();
            for (Dbc.Message m : dbc.messagesByName.values()) {
                if (m.name.startsWith(periodicPrefix)) {
                    periodicFrames.put((m.id >> 6) & 0xF, m);
                }
            }
            this.firmwareVersionParser = firmwareVersionParser;
        }
    }

    private static final int MOTOR_CONTROLLER_ID = 2;
    private static final int SERVO_CONTROLLER_ID = 12;
    private static final int ENCODER_ID = 7;

    private static final Map<Integer, Device> DEVICES = new LinkedHashMap<>();
    private static final CanDecoder DECODER = new CanDecoder();

    static {
        Dbc sparkDbc = loadDbc("spark.public.dbc");
        Dbc servoHubDbc = loadDbc("servo_hub.public.dbc");
        Dbc encoderDbc = loadDbc("encoder.public.dbc");

        DEVICES.put(MOTOR_CONTROLLER_ID, new Device(sparkDbc, "REV/Spark-", "GET_FIRMWARE_VERSION", "STATUS_",
                RevlogFile::defaultFirmwareVersion));
        DEVICES.put(SERVO_CONTROLLER_ID, new Device(servoHubDbc, "REV/ServoHub-", "GET_VERSION", "STATUS_",
                RevlogFile::defaultFirmwareVersion));
        DEVICES.put(ENCODER_ID, new Device(encoderDbc, "REV/Encoder-", "GET_VERSIONING_RESP", "PERIODIC_FRAME_",
                RevlogFile::encoderFirmwareVersion));
    }

    private static Dbc loadDbc(String resourceName) {
        try (InputStream in = RevlogFile.class.getResourceAsStream("/revlog-dbc/" + resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled DBC resource: " + resourceName);
            }
            String content = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            return new Dbc().load(content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String defaultFirmwareVersion(byte[] data) {
        if (data.length == 0) return "0.0.0";
        int major = data.length > 0 ? data[0] & 0xFF : 0;
        int minor = data.length > 1 ? data[1] & 0xFF : 0;
        int build = data.length > 3 ? ((data[2] & 0xFF) << 8) | (data[3] & 0xFF) : 0;
        return major + "." + minor + "." + build;
    }

    private static String encoderFirmwareVersion(byte[] data) {
        if (data.length < 6) return "0.0.0";
        int swMajor = data[5] & 0xFF;
        int swMinor = data[4] & 0xFF;
        int swFix = data[3] & 0xFF;
        return swMajor + "." + swMinor + "." + swFix;
    }

    public static boolean isSupported() {
        return true;
    }

    public static ParseResult parse(Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    public static ParseResult parse(byte[] data) {
        List<Sample> samples = new ArrayList<>();
        List<FirmwareRecord> firmware = new ArrayList<>();

        int cursor = 0;
        while (cursor < data.length) {
            int bitfield = data[cursor] & 0xFF;
            int entryIdLen = (bitfield & 0b11) + 1;
            int sizeLen = ((bitfield >> 2) & 0b11) + 1;
            if (cursor + 1 + entryIdLen + sizeLen > data.length) {
                break; // truncated trailing record
            }

            int p = cursor + 1;
            long entryId = readUIntLE(data, p, entryIdLen);
            p += entryIdLen;
            long payloadSize = readUIntLE(data, p, sizeLen);
            p += sizeLen;
            if (payloadSize > 5L * 1024 * 1024) {
                throw new IllegalArgumentException("Corrupted .revlog: unrealistic payload size " + payloadSize);
            }
            if (p + payloadSize > data.length) {
                break; // truncated trailing record
            }
            int payloadStart = p;
            int payloadEnd = (int) (p + payloadSize);

            if (entryId == 1) {
                parseFirmwareBlock(data, payloadStart, payloadEnd, firmware);
            } else if (entryId == 2) {
                parsePeriodicBlock(data, payloadStart, payloadEnd, samples);
            }

            cursor = payloadEnd;
        }
        return new ParseResult(samples, firmware);
    }

    private static void parseFirmwareBlock(byte[] data, int start, int end, List<FirmwareRecord> firmware) {
        for (int pc = start; pc + 10 <= end; pc += 10) {
            int messageId = readInt32LE(data, pc);
            byte[] canData = slice(data, pc + 4, pc + 10);
            int deviceType = (messageId >> 24) & 0x1F;
            Device device = DEVICES.get(deviceType);
            if (device == null || device.firmwareMessage == null) {
                continue;
            }
            // The firmware version itself is parsed from the raw bytes below (REV's own decoder does the
            // same); decoding through the DBC message here is only to mirror the reference framing.
            String name = device.prefix + (messageId & 0x3F) + "/FIRMWARE";
            firmware.add(new FirmwareRecord(name, device.firmwareVersionParser.apply(canData)));
        }
    }

    private static void parsePeriodicBlock(byte[] data, int start, int end, List<Sample> samples) {
        for (int pc = start; pc + 16 <= end; pc += 16) {
            long msgTsMs = readUIntLE(data, pc, 4);
            int messageId = readInt32LE(data, pc + 4);
            byte[] canData = slice(data, pc + 8, pc + 16);
            int deviceType = (messageId >> 24) & 0x1F;
            Device device = DEVICES.get(deviceType);
            if (device == null) {
                continue;
            }
            int frameIndex = (messageId >> 6) & 0xF;
            Dbc.Message messageSpec = device.periodicFrames.get(frameIndex);
            if (messageSpec == null) {
                continue;
            }
            byte[] aligned = canData.length < messageSpec.dlc ? pad(canData, messageSpec.dlc) : canData;
            List<CanDecoder.DecodedSignal> decoded = DECODER.decode(messageSpec, aligned);
            int deviceId = messageId & 0x3F;
            for (CanDecoder.DecodedSignal sig : decoded) {
                String folder = sig.name().endsWith("FAULT") ? "/FAULT/"
                        : sig.name().endsWith("WARNING") ? "/WARNING/" : "/";
                String name = device.prefix + deviceId + folder + sig.name();
                samples.add(new Sample(msgTsMs, name, sig.value()));
            }
        }
    }

    private static long readUIntLE(byte[] data, int offset, int length) {
        long value = 0;
        for (int i = 0; i < length; i++) {
            value |= (data[offset + i] & 0xFFL) << (i * 8);
        }
        return value;
    }

    private static int readInt32LE(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static byte[] slice(byte[] data, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(data, from, out, 0, to - from);
        return out;
    }

    private static byte[] pad(byte[] data, int length) {
        if (data.length >= length) {
            return data;
        }
        byte[] out = new byte[length];
        System.arraycopy(data, 0, out, 0, data.length);
        return out;
    }

    private RevlogFile() {}
}
