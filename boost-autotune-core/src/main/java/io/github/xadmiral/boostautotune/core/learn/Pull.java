package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.model.Sample;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One wide-open-throttle event ("заезд"): the samples between throttle opening and lift. */
public final class Pull {
    private final List<Sample> samples = new ArrayList<Sample>();
    private final List<SampleState> states = new ArrayList<SampleState>();

    void add(Sample s, SampleState state) {
        samples.add(s);
        states.add(state);
    }

    public List<Sample> samples() {
        return Collections.unmodifiableList(samples);
    }

    public SampleState stateAt(int i) {
        return states.get(i);
    }

    public int size() {
        return samples.size();
    }

    public boolean isEmpty() {
        return samples.isEmpty();
    }

    public double startTime() {
        return samples.isEmpty() ? Double.NaN : samples.get(0).timeSec;
    }

    public double endTime() {
        return samples.isEmpty() ? Double.NaN : samples.get(samples.size() - 1).timeSec;
    }

    public double durationSec() {
        return samples.isEmpty() ? 0 : endTime() - startTime();
    }

    public double maxMap() {
        double m = Double.NEGATIVE_INFINITY;
        for (Sample s : samples) {
            m = Math.max(m, s.map);
        }
        return m;
    }

    public double minRpm() {
        double m = Double.POSITIVE_INFINITY;
        for (Sample s : samples) {
            m = Math.min(m, s.rpm);
        }
        return m;
    }

    public double maxRpm() {
        double m = Double.NEGATIVE_INFINITY;
        for (Sample s : samples) {
            m = Math.max(m, s.rpm);
        }
        return m;
    }

    public int steadyCount() {
        int n = 0;
        for (SampleState st : states) {
            if (st == SampleState.STEADY) {
                n++;
            }
        }
        return n;
    }

    public int gear() {
        // most common gear value in the pull
        int best = 0;
        int bestCount = -1;
        for (int g = 0; g <= 8; g++) {
            int c = 0;
            for (Sample s : samples) {
                if (s.gear == g) {
                    c++;
                }
            }
            if (c > bestCount) {
                bestCount = c;
                best = g;
            }
        }
        return best;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "Pull[%.1fs, %d samples, rpm %.0f-%.0f, peak %.1f kPa]",
                durationSec(), size(), minRpm(), maxRpm(), maxMap());
    }
}
