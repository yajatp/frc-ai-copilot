package org.mercsmavs.frccopilot.ingest.revlog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes a raw CAN frame's data bytes into physical signal values using a {@link Dbc} message
 * definition. Ported from REV's {@code node-revlog-converter} {@code CanDecoder} (BSD-3-Clause):
 * same little-endian (Intel) and big-endian (Motorola "sawtooth") bit-packing, factor/offset
 * scaling, and min/max clamping.
 */
public final class CanDecoder {

    public record DecodedSignal(String name, double value) {}

    /** Decode {@code data} (padded up to the message's DLC, at least 8 bytes) against {@code message}. */
    public List<DecodedSignal> decode(Dbc.Message message, byte[] data) {
        int neededLength = Math.max(message.dlc, 8);
        if (data.length < neededLength) {
            byte[] padded = new byte[neededLength];
            System.arraycopy(data, 0, padded, 0, data.length);
            data = padded;
        }

        List<DecodedSignal> out = new ArrayList<>(message.signals.size());
        for (Dbc.Signal signal : message.signals.values()) {
            double physicalValue;
            if ("float".equals(signal.dataType) || "double".equals(signal.dataType)) {
                physicalValue = decodeFloat(signal, data);
            } else {
                physicalValue = signal.littleEndian ? decodeLittleEndian(signal, data) : decodeBigEndian(signal, data);
            }
            if (signal.min != 0 || signal.max != 0) {
                if (physicalValue < signal.min) physicalValue = signal.min;
                if (physicalValue > signal.max) physicalValue = signal.max;
            }
            out.add(new DecodedSignal(signal.name, physicalValue));
        }
        return out;
    }

    private static double decodeFloat(Dbc.Signal signal, byte[] data) {
        int byteOffset = signal.startBit / 8;
        boolean isDouble = "double".equals(signal.dataType) || signal.length == 64;
        int width = isDouble ? 8 : 4;
        if (byteOffset + width > data.length) {
            return 0;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, byteOffset, width).order(ByteOrder.LITTLE_ENDIAN);
        return isDouble ? buf.getDouble() : buf.getFloat();
    }

    private static double decodeLittleEndian(Dbc.Signal signal, byte[] data) {
        int byteStart = signal.startBit / 8;
        int bitStart = signal.startBit % 8;

        long raw = 0;
        for (int i = 0; i < 8 && byteStart + i < data.length; i++) {
            raw |= (data[byteStart + i] & 0xFFL) << (i * 8);
        }
        raw = raw >>> bitStart;
        if (signal.length < 64) {
            raw &= (1L << signal.length) - 1;
        }
        if (signal.signed && signal.length < 64) {
            long signBit = 1L << (signal.length - 1);
            if ((raw & signBit) != 0) {
                raw -= 1L << signal.length;
            }
        }
        return scale(signal, raw);
    }

    private static double decodeBigEndian(Dbc.Signal signal, byte[] data) {
        long raw = 0;
        int currentBit = signal.startBit;
        for (int i = 0; i < signal.length; i++) {
            int byteIdx = currentBit / 8;
            int bitIdx = currentBit % 8;
            if (byteIdx < data.length) {
                long bitVal = (data[byteIdx] >> bitIdx) & 1;
                raw = (raw << 1) | bitVal;
            }
            if (bitIdx == 0) {
                currentBit += 15;
            } else {
                currentBit -= 1;
            }
        }
        if (signal.signed && signal.length < 64) {
            long signBit = 1L << (signal.length - 1);
            if ((raw & signBit) != 0) {
                raw -= 1L << signal.length;
            }
        }
        return scale(signal, raw);
    }

    private static double scale(Dbc.Signal signal, long raw) {
        if (signal.factor == 1 && signal.offset == 0) {
            return raw;
        }
        return raw * signal.factor + signal.offset;
    }
}
