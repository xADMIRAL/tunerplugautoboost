package io.github.xadmiral.boostautotune.plugin.ecu;

import io.github.xadmiral.boostautotune.core.model.Sample;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles {@link Sample}s from individual output channel updates and keeps track of which
 * channels actually deliver. A sample is emitted each time the MAP channel updates (TunerStudio
 * refreshes the channels of a frame one after another, so MAP is a good clock); when MAP stays
 * silent while other channels move, any update emits one so the live values keep flowing.
 * Updates come either from TunerStudio's subscription callbacks or from the controller polling
 * the channels ({@link #pushPolled}).
 */
public final class LiveFeed implements EcuPort.ChannelListener {

    public interface SampleListener {
        void sample(Sample s);
    }

    /** What the feed is doing right now. */
    public static final class Status {
        public int subscribed;
        public int delivering;
        public final List<String> silent = new ArrayList<String>();
        public double samplesPerSec;
        public long samples;
        public double sinceLastSampleSec = Double.NaN;
        public double sinceStartSec;
        public String source = "callbacks";
    }

    private final EcuBinding b;
    private final SampleListener listener;
    private final Map<String, Double> values = new HashMap<String, Double>();
    private final Map<String, Long> updatedNanos = new HashMap<String, Long>();
    private final long startNanos = System.nanoTime();
    private volatile long lastCallbackNanos;
    private long lastMapNanos;
    private long lastEmitNanos;
    private double lastEmitTime = Double.NEGATIVE_INFINITY;
    private long samples;
    private final long[] recentEmits = new long[128];
    private int recentIdx;
    private volatile String source = "callbacks";
    private int subscribed;
    public double minIntervalSec = 0.015;
    /** MAP silent for this long while other channels update: emit on any update. */
    public double mapSilentSec = 0.3;
    /** A channel without an update for this long counts as silent in the status. */
    public double silentAfterSec = 2.0;

    public LiveFeed(EcuBinding binding, SampleListener listener) {
        this.b = binding;
        this.listener = listener;
    }

    /** The channels named in the binding, in a fixed order, without duplicates. */
    public List<String> requiredChannels() {
        List<String> out = new ArrayList<String>();
        for (String c : new String[]{b.rpmChannel, b.tpsChannel, b.mapChannel, b.targetChannel, b.dutyChannel,
                b.cltChannel, b.gearChannel, b.boostCutChannel, b.timeChannel, b.vvtAngleChannel, b.vvtTargetChannel,
                b.fuelLoadChannel, b.advanceChannel, b.knockChannel, b.knockRetardChannel, b.afrChannel, b.ignLoadChannel,
                b.alsActiveChannel, b.matChannel}) {
            if (b.has(c) && !out.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Channel families that may or may not exist on this ECU (per-cylinder knock): subscribed one by one, failures ignored. */
    public List<String> optionalChannels() {
        List<String> out = new ArrayList<String>();
        if (b.has(b.knockCylChannelPrefix)) {
            for (int c = 1; c <= 8; c++) {
                out.add(b.knockCylChannel(c));
            }
        }
        return out;
    }

    public List<String> channels() {
        List<String> out = requiredChannels();
        out.addAll(optionalChannels());
        return out;
    }

    public void setSubscribed(int n) {
        subscribed = n;
    }

    public void setSource(String s) {
        source = s;
    }

    public String source() {
        return source;
    }

    /** Seconds since TunerStudio last called back with a value (polled values do not count); large when never. */
    public double secondsSinceLastCallback() {
        long t = lastCallbackNanos;
        return t == 0 ? (System.nanoTime() - startNanos) / 1e9 : (System.nanoTime() - t) / 1e9;
    }

    @Override
    public void channelValue(String channel, double value) {
        accept(channel, value, false);
    }

    /** Values read by polling, one per name (NaN = not readable). MAP is applied last so one sample is emitted per poll. */
    public void pushPolled(List<String> names, double[] vals) {
        int map = -1;
        for (int i = 0; i < names.size() && i < vals.length; i++) {
            if (names.get(i).equals(b.mapChannel)) {
                map = i;
            } else if (!Double.isNaN(vals[i])) {
                accept(names.get(i), vals[i], true);
            }
        }
        if (map >= 0 && !Double.isNaN(vals[map])) {
            accept(names.get(map), vals[map], true);
        }
    }

    private void accept(String channel, double value, boolean polled) {
        Sample s = null;
        long now = System.nanoTime();
        synchronized (values) {
            values.put(channel, value);
            updatedNanos.put(channel, now);
            if (!polled) {
                lastCallbackNanos = now;
            }
            boolean trigger;
            if (channel.equals(b.mapChannel)) {
                lastMapNanos = now;
                trigger = true;
            } else {
                long since = lastMapNanos == 0 ? now - startNanos : now - lastMapNanos;
                trigger = since > mapSilentSec * 1e9;
            }
            if (trigger) {
                double t = b.has(b.timeChannel) ? get(b.timeChannel, Double.NaN) : (now - startNanos) / 1e9;
                if (!Double.isNaN(t) && t - lastEmitTime >= minIntervalSec) {
                    lastEmitTime = t;
                    lastEmitNanos = now;
                    recentEmits[recentIdx++ % recentEmits.length] = now;
                    samples++;
                    s = build(t);
                }
            }
        }
        if (s != null && listener != null) {
            listener.sample(s);
        }
    }

    private Sample build(double t) {
        double clt = get(b.cltChannel, 90);
        if (b.cltFahrenheit) {
            clt = (clt - 32) / 1.8;
        }
        boolean cut = false;
        if (b.has(b.boostCutChannel)) {
            double v = get(b.boostCutChannel, 0);
            cut = b.boostCutMask == 0 ? v != 0 : (((long) v) & b.boostCutMask) != 0;
        }
        boolean als = false;
        if (b.has(b.alsActiveChannel)) {
            double v = get(b.alsActiveChannel, 0);
            als = b.alsActiveMask == 0 ? v != 0 : (((long) v) & b.alsActiveMask) != 0;
        }
        double[] cyl = null;
        if (b.has(b.knockCylChannelPrefix)) {
            int n = 0;
            for (int c = 1; c <= 8; c++) {
                if (values.containsKey(b.knockCylChannel(c))) {
                    n = c;
                }
            }
            if (n > 0) {
                cyl = new double[n];
                for (int c = 1; c <= n; c++) {
                    cyl[c - 1] = get(b.knockCylChannel(c), Double.NaN);
                }
            }
        }
        return Sample.builder()
                .time(t)
                .rpm(get(b.rpmChannel, 0))
                .tps(get(b.tpsChannel, 0))
                .map(get(b.mapChannel, Double.NaN))
                .target(b.has(b.targetChannel) ? get(b.targetChannel, Double.NaN) : Double.NaN)
                .duty(b.has(b.dutyChannel) ? get(b.dutyChannel, Double.NaN) : Double.NaN)
                .clt(clt)
                .gear(b.has(b.gearChannel) ? (int) Math.round(get(b.gearChannel, 0)) : 0)
                .boostCut(cut)
                .vvtAngle(optional(b.vvtAngleChannel))
                .vvtTarget(optional(b.vvtTargetChannel))
                .fuelLoad(optional(b.fuelLoadChannel))
                .ignLoad(optional(b.ignLoadChannel))
                .advance(optional(b.advanceChannel))
                .knock(optional(b.knockChannel))
                .knockRetard(optional(b.knockRetardChannel))
                .afr(optional(b.afrChannel))
                .mat(optional(b.matChannel))
                .alsActive(als)
                .knockCyl(cyl)
                .build();
    }

    private double optional(String ch) {
        return b.has(ch) ? get(ch, Double.NaN) : Double.NaN;
    }

    private double get(String ch, double def) {
        Double v = values.get(ch);
        return v == null ? def : v;
    }

    /** Latest raw value of a channel for display. */
    public double latest(String ch) {
        synchronized (values) {
            return get(ch, Double.NaN);
        }
    }

    /** Snapshot of what is delivering, how fast, and what is silent. */
    public Status status() {
        Status st = new Status();
        long now = System.nanoTime();
        st.source = source;
        st.subscribed = subscribed;
        st.sinceStartSec = (now - startNanos) / 1e9;
        synchronized (values) {
            st.samples = samples;
            st.sinceLastSampleSec = lastEmitNanos == 0 ? Double.NaN : (now - lastEmitNanos) / 1e9;
            int recent = 0;
            for (long e : recentEmits) {
                if (e != 0 && now - e <= 2_000_000_000L) {
                    recent++;
                }
            }
            st.samplesPerSec = recent / 2.0;
            for (String ch : requiredChannels()) {
                Long t = updatedNanos.get(ch);
                if (t != null && now - t <= silentAfterSec * 1e9) {
                    st.delivering++;
                } else {
                    st.silent.add(ch);
                }
            }
        }
        return st;
    }
}
