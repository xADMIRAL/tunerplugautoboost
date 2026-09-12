package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.model.Sample;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a sample stream into {@link Pull}s. A pull starts when the classifier reports a WOT
 * sample and ends after the load has been below WOT for {@code pullEndHoldSec}.
 */
public final class PullSegmenter {
    private final AutotuneConfig cfg;
    private final SampleClassifier classifier;
    private final List<Pull> completed = new ArrayList<Pull>();
    private Pull current;
    private double belowWotSince = Double.NaN;
    private double lastSampleTime = Double.NaN;
    private double lastWotTime = Double.NaN;

    public PullSegmenter(AutotuneConfig cfg, SampleClassifier classifier) {
        this.cfg = cfg;
        this.classifier = classifier;
    }

    public void reset() {
        completed.clear();
        current = null;
        belowWotSince = Double.NaN;
        lastSampleTime = Double.NaN;
        lastWotTime = Double.NaN;
        classifier.reset();
    }

    /** Feeds a sample; returns its classification. */
    public SampleState feed(Sample s) {
        SampleState st = classifier.classify(s);
        lastSampleTime = s.timeSec;
        if (st.isPullSample() || st == SampleState.BOOST_CUT || st == SampleState.OVERBOOST
                || st == SampleState.RPM_OUT_OF_RANGE) {
            if (classifier.isWot(s)) {
                if (current == null) {
                    current = new Pull();
                }
                current.add(s, st);
                belowWotSince = Double.NaN;
                lastWotTime = s.timeSec;
                return st;
            }
        }
        // not WOT (or cold)
        if (current != null) {
            if (Double.isNaN(belowWotSince)) {
                belowWotSince = s.timeSec;
            }
            if (s.timeSec - belowWotSince >= cfg.pullEndHoldSec) {
                closeCurrent();
            }
        }
        return st;
    }

    /** Closes a pull in progress (end of run). */
    public void flush() {
        closeCurrent();
    }

    private void closeCurrent() {
        if (current != null) {
            if (current.durationSec() >= cfg.minPullDurationSec) {
                completed.add(current);
            }
            current = null;
        }
        belowWotSince = Double.NaN;
    }

    public List<Pull> pulls() {
        return new ArrayList<Pull>(completed);
    }

    public boolean pullInProgress() {
        return current != null;
    }

    /** Seconds since the last WOT sample (NaN before the first WOT sample). */
    public double idleSeconds() {
        if (Double.isNaN(lastSampleTime) || Double.isNaN(lastWotTime)) {
            return Double.NaN;
        }
        return lastSampleTime - lastWotTime;
    }

    /** Number of pulls completed so far in this run. */
    public int pullCount() {
        return completed.size();
    }

    public double lastSampleTime() {
        return lastSampleTime;
    }
}
