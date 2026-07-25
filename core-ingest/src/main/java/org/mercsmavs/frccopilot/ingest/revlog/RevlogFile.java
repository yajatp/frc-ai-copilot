package org.mercsmavs.frccopilot.ingest.revlog;

/**
 * Placeholder for parsing REV's binary {@code .revlog} format (SPARK MAX/Flex telemetry), relevant
 * to Team 6773's mixed CTRE+REV robot.
 *
 * <p><b>Status: intentionally a stub.</b> The {@code .revlog} binary layout is REV-proprietary and
 * version-dependent; reverse-engineering it is out of scope for v1. The valuable, format-independent
 * piece — aligning a decoded REV signal to a WPILOG signal — lives in {@link TimeSync} and is fully
 * implemented and tested. When a decoder is added, feed its signals to {@code TimeSync.crossCorrelate}
 * to recover the offset/confidence for correlation.
 *
 * <p>TODO: implement binary decoding once the format is confirmed (REV Hardware Client export, or a
 * documented spec). Until then, callers should treat {@code .revlog} correlation as unavailable and
 * rely on WPILOG data alone.
 */
public final class RevlogFile {

    public static boolean isSupported() {
        return false;
    }

    private RevlogFile() {}
}
