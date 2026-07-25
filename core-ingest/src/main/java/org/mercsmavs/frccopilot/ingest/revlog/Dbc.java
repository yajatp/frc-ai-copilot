package org.mercsmavs.frccopilot.ingest.revlog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal parser for the subset of the CAN DBC text format REV ships with its {@code .revlog}
 * decoder (Vector DBC: {@code BO_} message lines, {@code SG_} signal lines, {@code SIG_VALTYPE_}
 * float/double overrides, {@code CM_ SG_} comments). Ported from REV's own
 * {@code node-revlog-converter} (BSD-3-Clause) so the same {@code .dbc} files it ships
 * (vendored under {@code core-ingest/src/main/resources/revlog-dbc/}) can be read here.
 */
public final class Dbc {

    public static final class Signal {
        public final String name;
        public final int startBit;
        public final int length;
        public final boolean littleEndian;
        public final boolean signed;
        public final double factor;
        public final double offset;
        public final double min;
        public final double max;
        public final String unit;
        public String description;
        /** "int" (default), "float", or "double" — set by a {@code SIG_VALTYPE_} override. */
        public String dataType = "int";

        Signal(String name, int startBit, int length, boolean littleEndian, boolean signed,
                double factor, double offset, double min, double max, String unit) {
            this.name = name;
            this.startBit = startBit;
            this.length = length;
            this.littleEndian = littleEndian;
            this.signed = signed;
            this.factor = factor;
            this.offset = offset;
            this.min = min;
            this.max = max;
            this.unit = unit;
        }
    }

    public static final class Message {
        public final int id;
        public final String name;
        public final int dlc;
        public final Map<String, Signal> signals = new LinkedHashMap<>();

        Message(int id, String name, int dlc) {
            this.id = id;
            this.name = name;
            this.dlc = dlc;
        }
    }

    public final Map<String, Message> messagesByName = new LinkedHashMap<>();
    public final Map<Integer, Message> messagesById = new LinkedHashMap<>();

    private static final Pattern BO =
            Pattern.compile("^BO_\\s+(\\d+)\\s+(\\w+):\\s*(\\d+)\\s+(\\w+)");
    private static final Pattern SG = Pattern.compile(
            "^\\s*SG_\\s+(\\w+)\\s*:\\s*(\\d+)\\|(\\d+)@([01])([+-])\\s*\\(\\s*([\\d.eE+-]+)\\s*,\\s*([\\d.eE+-]+)\\s*\\)"
                    + "\\s*\\[\\s*([\\d.eE+-]+)\\s*\\|\\s*([\\d.eE+-]+)\\s*\\]\\s*\"(.*?)\"");
    private static final Pattern SIG_VALTYPE = Pattern.compile("^\\s*SIG_VALTYPE_\\s+(\\d+)\\s+(\\w+)\\s*:\\s*(\\d+)\\s*;");
    private static final Pattern CM_SG = Pattern.compile("^\\s*CM_\\s+SG_\\s+(\\d+)\\s+(\\w+)\\s*\"(.*)\";");

    public Dbc load(String content) {
        Message current = null;
        for (String line : content.split("\r?\n")) {
            Matcher bo = BO.matcher(line);
            if (bo.find()) {
                // DBC message IDs are unsigned 32-bit; truncate to int (same two's-complement bit
                // pattern) so later bitwise shifts on this id match REV's own (JS ToInt32) decoder.
                current = new Message((int) Long.parseLong(bo.group(1)),
                        bo.group(2), Integer.parseInt(bo.group(3)));
                messagesByName.put(current.name, current);
                messagesById.put(current.id, current);
                continue;
            }
            Matcher sg = SG.matcher(line);
            if (sg.find() && current != null) {
                Signal signal = new Signal(
                        sg.group(1),
                        Integer.parseInt(sg.group(2)),
                        Integer.parseInt(sg.group(3)),
                        "1".equals(sg.group(4)),
                        "-".equals(sg.group(5)),
                        Double.parseDouble(sg.group(6)),
                        Double.parseDouble(sg.group(7)),
                        Double.parseDouble(sg.group(8)),
                        Double.parseDouble(sg.group(9)),
                        sg.group(10));
                current.signals.put(signal.name, signal);
                continue;
            }
            Matcher vt = SIG_VALTYPE.matcher(line);
            if (vt.find()) {
                Message msg = messagesById.get((int) Long.parseLong(vt.group(1)));
                Signal sig = msg == null ? null : msg.signals.get(vt.group(2));
                if (sig != null) {
                    if ("1".equals(vt.group(3))) sig.dataType = "float";
                    if ("2".equals(vt.group(3))) sig.dataType = "double";
                }
                continue;
            }
            Matcher cm = CM_SG.matcher(line);
            if (cm.find()) {
                Message msg = messagesById.get((int) Long.parseLong(cm.group(1)));
                Signal sig = msg == null ? null : msg.signals.get(cm.group(2));
                if (sig != null) {
                    sig.description = cm.group(3);
                }
            }
        }
        return this;
    }
}
