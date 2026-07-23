package org.mercsmavs.frccopilot.analysis;

import java.util.ArrayList;
import java.util.List;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * A numeric time series: parallel arrays of values and their timestamps (microseconds).
 *
 * <p>Primitives accept a {@code Series} so they are trivially unit-testable with synthetic data
 * and never assume any particular log path.
 */
public record Series(double[] values, long[] timestampsUs) {

    public Series {
        if (values.length != timestampsUs.length) {
            throw new IllegalArgumentException("values/timestamps length mismatch");
        }
    }

    public int size() {
        return values.length;
    }

    public boolean isEmpty() {
        return values.length == 0;
    }

    /** Build from decoded log samples, keeping only numeric (double/int64/boolean) points. */
    public static Series fromSamples(List<WpilogReader.Sample> samples) {
        List<Double> vals = new ArrayList<>(samples.size());
        List<Long> ts = new ArrayList<>(samples.size());
        for (WpilogReader.Sample s : samples) {
            Double v = numeric(s.value());
            if (v != null) {
                vals.add(v);
                ts.add(s.timestampUs());
            }
        }
        double[] va = new double[vals.size()];
        long[] ta = new long[ts.size()];
        for (int i = 0; i < va.length; i++) {
            va[i] = vals.get(i);
            ta[i] = ts.get(i);
        }
        return new Series(va, ta);
    }

    private static Double numeric(Object value) {
        if (value instanceof Double d) return d;
        if (value instanceof Float f) return (double) f;
        if (value instanceof Long l) return (double) l;
        if (value instanceof Integer i) return (double) i;
        if (value instanceof Boolean b) return b ? 1.0 : 0.0;
        return null;
    }
}
