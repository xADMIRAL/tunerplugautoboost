package io.github.xadmiral.boostautotune.plugin.ecu;

import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.model.PidGains;
import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.EcuState;
import io.github.xadmiral.boostautotune.core.sim.BoostPlant;
import io.github.xadmiral.boostautotune.core.sim.PullSimulator;
import io.github.xadmiral.boostautotune.core.sim.SimEcu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory ECU with Stealth PCM / MS3 parameter names, backed by the core simulator. Lets the
 * whole plugin be exercised without a car: "Simulate pull" streams samples like a real pull.
 */
public final class SimEcuPort implements EcuPort {
    public static final String CONFIG = "Simulated MS3";
    private final Map<String, Double> scalars = new HashMap<String, Double>();
    private final Map<String, String> options = new HashMap<String, String>();
    private final Map<String, double[][]> arrays = new HashMap<String, double[][]>();
    private final List<ChannelListener> listeners = new ArrayList<ChannelListener>();
    private final BoostPlant plant = new BoostPlant(7);
    private final SimEcu ecu;
    private final PullSimulator sim;
    private volatile boolean pulling;

    public SimEcuPort() {
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        arrays.put(b.targetTable, filled(8, 8, 100));
        arrays.put(b.targetXBins, column(1500, 2500, 3500, 4500, 5000, 5500, 6000, 7000));
        arrays.put(b.targetYBins, column(0, 20, 40, 60, 70, 80, 90, 100));
        arrays.put(b.biasTable, filled(8, 8, 100));
        arrays.put(b.biasXBins, column(1500, 2500, 3500, 4500, 5000, 5500, 6000, 6500));
        arrays.put(b.biasYBins, column(100, 110, 130, 140, 150, 160, 170, 180));
        arrays.put(b.openLoopTable, filled(8, 8, 0));
        arrays.put(b.openLoopXBins, column(500, 1000, 2000, 3000, 4000, 5000, 6000, 7000));
        arrays.put(b.openLoopYBins, column(0, 20, 40, 60, 70, 80, 90, 100));
        scalars.put(b.pidP, 100.0);
        scalars.put(b.pidI, 100.0);
        scalars.put(b.pidD, 100.0);
        scalars.put(b.minDuty, 0.0);
        scalars.put(b.maxDuty, 100.0);
        scalars.put(b.overboostLimit, 210.0);
        options.put(b.modeParam, "Open-loop");
        options.put(b.enableParam, "Off");
        options.put(b.closedLoopExtraParam, "Basic Mode");
        ecu = new SimEcu(toState());
        sim = new PullSimulator(plant, ecu);
    }

    private EcuState toState() {
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        EcuState s = new EcuState();
        s.targetTable = grid(b.targetTable, b.targetXBins, b.targetYBins);
        s.biasTable = grid(b.biasTable, b.biasXBins, b.biasYBins);
        s.openLoopTable = grid(b.openLoopTable, b.openLoopXBins, b.openLoopYBins);
        s.pid = new PidGains(scalars.get(b.pidP), scalars.get(b.pidI), scalars.get(b.pidD));
        s.minDuty = scalars.get(b.minDuty);
        s.maxDuty = scalars.get(b.maxDuty);
        s.closedLoop = "Closed-loop".equals(options.get(b.modeParam));
        return s;
    }

    private Grid grid(String z, String x, String y) {
        double[][] raw = arrays.get(z);
        return new Grid(new Axis(flat(arrays.get(x))), new Axis(flat(arrays.get(y))), raw);
    }

    private static double[] flat(double[][] col) {
        double[] out = new double[col.length];
        for (int i = 0; i < col.length; i++) {
            out[i] = col[i][0];
        }
        return out;
    }

    private static double[][] filled(int rows, int cols, double v) {
        double[][] a = new double[rows][cols];
        for (double[] r : a) {
            Arrays.fill(r, v);
        }
        return a;
    }

    private static double[][] column(double... v) {
        double[][] a = new double[v.length][1];
        for (int i = 0; i < v.length; i++) {
            a[i][0] = v[i];
        }
        return a;
    }

    @Override
    public String signature() {
        return "MS3 Format DM00.22f (simulated)";
    }

    @Override
    public List<String> configurationNames() {
        return Collections.singletonList(CONFIG);
    }

    @Override
    public List<String> parameterNames(String config) {
        List<String> names = new ArrayList<String>(scalars.keySet());
        names.addAll(options.keySet());
        names.addAll(arrays.keySet());
        Collections.sort(names);
        return names;
    }

    @Override
    public List<String> channelNames(String config) {
        return Arrays.asList("rpm", "tps", "map", "boost_targ_1", "boostduty", "coolant", "gear", "status2", "seconds");
    }

    @Override
    public ParamInfo parameterInfo(String config, String name) throws EcuException {
        if (arrays.containsKey(name)) {
            double[][] a = arrays.get(name);
            return new ParamInfo("array", "", 0, name.contains("targets") ? 400 : 100, 0, a[0].length, a.length,
                    Collections.<String>emptyList());
        }
        if (scalars.containsKey(name)) {
            return new ParamInfo("scalar", "%", 0, name.contains("Kp") || name.contains("Ki") || name.contains("Kd") ? 200 : 100,
                    0, 1, 1, Collections.<String>emptyList());
        }
        if (options.containsKey(name)) {
            return new ParamInfo("bits", "", 0, 0, 0, 1, 1, Arrays.asList("Open-loop", "Closed-loop"));
        }
        throw new EcuException("Unknown parameter " + name);
    }

    @Override
    public double readScalar(String config, String name) throws EcuException {
        Double v = scalars.get(name);
        if (v == null) {
            throw new EcuException("Unknown scalar " + name);
        }
        return v;
    }

    @Override
    public String readOption(String config, String name) throws EcuException {
        String v = options.get(name);
        if (v == null) {
            throw new EcuException("Unknown option parameter " + name);
        }
        return v;
    }

    @Override
    public double[] readArray1D(String config, String name) throws EcuException {
        double[][] a = arrays.get(name);
        if (a == null || a[0].length != 1) {
            throw new EcuException("Not a 1-D array: " + name);
        }
        return flat(a);
    }

    @Override
    public double[][] readArray2D(String config, String name) throws EcuException {
        double[][] a = arrays.get(name);
        if (a == null) {
            throw new EcuException("Unknown array " + name);
        }
        double[][] c = new double[a.length][];
        for (int i = 0; i < a.length; i++) {
            c[i] = a[i].clone();
        }
        return c;
    }

    @Override
    public void writeScalar(String config, String name, double value) throws EcuException {
        if (!scalars.containsKey(name)) {
            throw new EcuException("Unknown scalar " + name);
        }
        scalars.put(name, value);
        ecu.apply(toState());
    }

    @Override
    public void writeOption(String config, String name, String option) throws EcuException {
        if (!options.containsKey(name)) {
            throw new EcuException("Unknown option parameter " + name);
        }
        options.put(name, option);
        ecu.apply(toState());
    }

    @Override
    public void writeArray2D(String config, String name, double[][] raw) throws EcuException {
        if (!arrays.containsKey(name)) {
            throw new EcuException("Unknown array " + name);
        }
        double[][] c = new double[raw.length][];
        for (int i = 0; i < raw.length; i++) {
            c[i] = raw[i].clone();
        }
        arrays.put(name, c);
        ecu.apply(toState());
    }

    @Override
    public void burn(String config) {
        // nothing to persist in the demo
    }

    @Override
    public void subscribe(String config, List<String> channels, ChannelListener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    @Override
    public void unsubscribe(ChannelListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    @Override
    public List<UiTableInfo> uiTables(String config) {
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        return Arrays.asList(
                new UiTableInfo("Boost Control Targets 1", b.targetXBins, b.targetYBins, b.targetTable, "rpm", "throttle"),
                new UiTableInfo("Boost Control Bias Duty 1", b.biasXBins, b.biasYBins, b.biasTable, "rpm", "boost_targ_1"),
                new UiTableInfo("Boost Control Duty 1", b.openLoopXBins, b.openLoopYBins, b.openLoopTable, "rpm", "throttle"));
    }

    public boolean isPulling() {
        return pulling;
    }

    /**
     * Streams one simulated pull to the subscribers in real time (or faster).
     * @param gear gear used for the pull
     * @param speedFactor 1 = real time, 10 = ten times faster
     */
    public void simulatePull(final int gear, final double speedFactor) {
        if (pulling) {
            return;
        }
        pulling = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ecu.apply(toState());
                    List<Sample> samples = sim.pull(gear);
                    double last = Double.NaN;
                    for (Sample s : samples) {
                        if (!Double.isNaN(last)) {
                            long ms = (long) ((s.timeSec - last) * 1000 / speedFactor);
                            if (ms > 0) {
                                Thread.sleep(ms);
                            }
                        }
                        last = s.timeSec;
                        emit("rpm", s.rpm);
                        emit("tps", s.tps);
                        emit("coolant", s.clt);
                        emit("gear", s.gear);
                        emit("boostduty", s.duty);
                        emit("boost_targ_1", Double.isNaN(s.target) ? 0 : s.target);
                        emit("status2", 0);
                        emit("seconds", s.timeSec);
                        emit("map", s.map); // trigger channel last
                    }
                    sim.setTime(sim.time() + 3);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    pulling = false;
                }
            }
        }, "sim-pull");
        t.setDaemon(true);
        t.start();
    }

    private void emit(String ch, double v) {
        List<ChannelListener> copy;
        synchronized (listeners) {
            copy = new ArrayList<ChannelListener>(listeners);
        }
        for (ChannelListener l : copy) {
            l.channelValue(ch, v);
        }
    }
}
