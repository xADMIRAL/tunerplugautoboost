package io.github.xadmiral.boostautotune.plugin.ecu;

import io.github.xadmiral.boostautotune.core.model.Sample;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles {@link Sample}s from individual output channel updates. A sample is emitted each
 * time the MAP channel updates (it is the last channel TunerStudio refreshes in a frame for the
 * demo port and a good clock for real ECUs), no more often than {@code minIntervalMs}.
 */
public final class LiveFeed implements EcuPort.ChannelListener {

    public interface SampleListener {
        void sample(Sample s);
    }

    private final EcuBinding b;
    private final SampleListener listener;
    private final Map<String, Double> values = new HashMap<String, Double>();
    private final long startNanos = System.nanoTime();
    private double lastEmitTime = Double.NEGATIVE_INFINITY;
    public double minIntervalSec = 0.015;

    public LiveFeed(EcuBinding binding, SampleListener listener) {
        this.b = binding;
        this.listener = listener;
    }

    public List<String> channels() {
        List<String> out = new ArrayList<String>();
        for (String c : new String[]{b.rpmChannel, b.tpsChannel, b.mapChannel, b.targetChannel, b.dutyChannel,
                b.cltChannel, b.gearChannel, b.boostCutChannel, b.timeChannel, b.vvtAngleChannel, b.vvtTargetChannel,
                b.fuelLoadChannel, b.advanceChannel, b.knockChannel, b.knockRetardChannel, b.afrChannel, b.ignLoadChannel}) {
            if (b.has(c) && !out.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    @Override
    public void channelValue(String channel, double value) {
        Sample s = null;
        synchronized (values) {
            values.put(channel, value);
            if (channel.equals(b.mapChannel)) {
                double t = b.has(b.timeChannel) ? get(b.timeChannel, Double.NaN) : (System.nanoTime() - startNanos) / 1e9;
                if (!Double.isNaN(t) && t - lastEmitTime >= minIntervalSec) {
                    lastEmitTime = t;
                    s = build(t);
                }
            }
        }
        if (s != null) {
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
}
