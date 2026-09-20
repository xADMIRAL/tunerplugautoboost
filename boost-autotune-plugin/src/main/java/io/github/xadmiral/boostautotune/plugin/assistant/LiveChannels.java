package io.github.xadmiral.boostautotune.plugin.assistant;

import io.github.xadmiral.boostautotune.plugin.ecu.EcuException;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Latest value and a short history of every output channel the assistant asked for. Subscribes
 * lazily through the {@link EcuPort}; the history is a ring per channel so a few minutes of
 * driving can be looked at after the fact.
 */
public final class LiveChannels implements EcuPort.ChannelListener {
    /** One recorded point: seconds since the plugin started, value. */
    public static final class Point {
        public final double t;
        public final double v;

        Point(double t, double v) {
            this.t = t;
            this.v = v;
        }
    }

    private static final int RING = 6000; // ~5 min at 20 Hz
    private final EcuPort port;
    private final String config;
    private final long startNanos = System.nanoTime();
    private final Map<String, Double> latest = new HashMap<String, Double>();
    private final Map<String, Point[]> rings = new HashMap<String, Point[]>();
    private final Map<String, int[]> heads = new HashMap<String, int[]>(); // [next index, count]
    private final Set<String> subscribed = new LinkedHashSet<String>();
    private List<String> known;

    public LiveChannels(EcuPort port, String config) {
        this.port = port;
        this.config = config;
    }

    public double now() {
        return (System.nanoTime() - startNanos) / 1e9;
    }

    /** Output channel names of the ECU (cached). */
    public synchronized List<String> knownChannels() {
        if (known == null) {
            known = port == null ? new ArrayList<String>() : new ArrayList<String>(port.channelNames(config));
        }
        return known;
    }

    public synchronized boolean isSubscribed(String ch) {
        return subscribed.contains(ch);
    }

    /**
     * Subscribes to the channels not yet followed. Names the ECU does not have are reported back
     * instead of failing the whole call.
     */
    public synchronized List<String> subscribe(List<String> channels) throws EcuException {
        List<String> unknown = new ArrayList<String>();
        List<String> add = new ArrayList<String>();
        List<String> all = knownChannels();
        for (String ch : channels) {
            if (ch == null || ch.trim().isEmpty() || subscribed.contains(ch)) {
                continue;
            }
            if (!all.isEmpty() && !all.contains(ch)) {
                unknown.add(ch);
                continue;
            }
            add.add(ch);
        }
        if (!add.isEmpty() && port != null) {
            port.subscribe(config, add, this);
            subscribed.addAll(add);
        }
        return unknown;
    }

    public synchronized List<String> subscribedChannels() {
        return new ArrayList<String>(subscribed);
    }

    @Override
    public void channelValue(String channel, double value) {
        double t = now();
        synchronized (this) {
            latest.put(channel, value);
            Point[] ring = rings.get(channel);
            int[] head = heads.get(channel);
            if (ring == null) {
                ring = new Point[RING];
                head = new int[2];
                rings.put(channel, ring);
                heads.put(channel, head);
            }
            ring[head[0]] = new Point(t, value);
            head[0] = (head[0] + 1) % RING;
            if (head[1] < RING) {
                head[1]++;
            }
        }
    }

    /** Latest value, NaN when nothing arrived yet. */
    public synchronized double latest(String channel) {
        Double v = latest.get(channel);
        return v == null ? Double.NaN : v;
    }

    /** Points of the last {@code seconds}, oldest first, thinned to at most {@code maxPoints}. */
    public synchronized List<Point> history(String channel, double seconds, int maxPoints) {
        List<Point> out = new ArrayList<Point>();
        Point[] ring = rings.get(channel);
        int[] head = heads.get(channel);
        if (ring == null) {
            return out;
        }
        double from = now() - seconds;
        List<Point> window = new ArrayList<Point>();
        int count = head[1];
        int start = (head[0] - count + RING) % RING;
        for (int k = 0; k < count; k++) {
            Point p = ring[(start + k) % RING];
            if (p != null && p.t >= from) {
                window.add(p);
            }
        }
        if (maxPoints <= 0 || window.size() <= maxPoints) {
            return window;
        }
        double step = (double) window.size() / maxPoints;
        for (int k = 0; k < maxPoints; k++) {
            out.add(window.get((int) Math.floor(k * step)));
        }
        return out;
    }

    public void dispose() {
        if (port != null) {
            port.unsubscribe(this);
        }
        synchronized (this) {
            subscribed.clear();
        }
    }
}
