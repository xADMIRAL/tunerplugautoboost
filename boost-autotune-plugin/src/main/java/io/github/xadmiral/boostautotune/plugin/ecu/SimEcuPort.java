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
        // VVT: the JZX110 base tune's intake table and gains
        double[][] vvt = {
                {0, 0, 0, 0, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 0, 0, 0},
                {0, 10, 10, 5, 5, 0, 0, 0},
                {0, 32.5, 30, 30, 15, 5, 0, 0},
                {0, 45, 40, 30, 15, 5, 0, 0},
                {0, 45, 40, 30, 15, 5, 0, 0},
                {0, 45, 40, 25, 15, 5, 0, 0},
                {0, 45, 40, 20, 15, 5, 0, 0}};
        arrays.put(b.vvtTable, vvt);
        arrays.put(b.vvtXBins, column(500, 1000, 2000, 3000, 4000, 5000, 6000, 7000));
        arrays.put(b.vvtYBins, column(40, 60, 100, 120, 150, 180, 200, 220));
        scalars.put(b.vvtPidP, 70.0);
        scalars.put(b.vvtPidI, 15.0);
        scalars.put(b.vvtPidD, 40.0);
        // spark: 16x16 on the base tune's axes, a few degrees below the simulator's MBT
        double[] srpm = {500, 700, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000, 5500, 6500, 7000, 8000, 9000};
        double[] sload = {20, 30, 40, 50, 60, 80, 100, 120, 140, 160, 180, 200, 220, 240, 260, 280};
        ecu = new SimEcu(toState());
        double[][] spark = new double[16][16];
        for (int yi = 0; yi < 16; yi++) {
            for (int xi = 0; xi < 16; xi++) {
                spark[yi][xi] = Math.round((ecu.torque.mbt(srpm[xi], sload[yi]) - 6) * 10) / 10.0;
            }
        }
        arrays.put(b.sparkTable, spark);
        arrays.put(b.sparkXBins, column(srpm));
        arrays.put(b.sparkYBins, column(sload));
        applyAuxiliaries();
        sim = new PullSimulator(plant, ecu);
    }

    private void applyAuxiliaries() {
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        ecu.vvtTable = grid(b.vvtTable, b.vvtXBins, b.vvtYBins);
        ecu.vvtPid = new PidGains(scalars.get(b.vvtPidP), scalars.get(b.vvtPidI), scalars.get(b.vvtPidD));
        ecu.sparkTable = grid(b.sparkTable, b.sparkXBins, b.sparkYBins);
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
        return Arrays.asList("rpm", "tps", "map", "boost_targ_1", "boostduty", "coolant", "gear", "status2", "seconds",
                "vvt_ang1", "vvt_target1", "fuelload", "ignload", "advance", "knock", "knockRetard", "afr1");
    }

    @Override
    public ParamInfo parameterInfo(String config, String name) throws EcuException {
        if (arrays.containsKey(name)) {
            double[][] a = arrays.get(name);
            double min = name.startsWith("advance") ? -45 : 0;
            double max = name.contains("targets") ? 400 : name.startsWith("advance") ? 90 : name.startsWith("vvt_timing1") ? 720 : 100;
            return new ParamInfo("array", "", min, max, 1, a[0].length, a.length, Collections.<String>emptyList());
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
        applyAuxiliaries();
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
        applyAuxiliaries();
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
                new UiTableInfo("Boost Control Duty 1", b.openLoopXBins, b.openLoopYBins, b.openLoopTable, "rpm", "throttle"),
                new UiTableInfo("VVT Intake (Relative Timing)", b.vvtXBins, b.vvtYBins, b.vvtTable, "rpm", "vvt_load"),
                new UiTableInfo("Ignition Table 1 (Spark Advance)", b.sparkXBins, b.sparkYBins, b.sparkTable, "rpm", "ignload"));
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
        stream(gear, speedFactor, false, 0);
    }

    /** Streams ordinary driving (for the VVT PID mode). */
    public void simulateDrive(final double seconds, final double speedFactor) {
        stream(3, speedFactor, true, seconds);
    }

    private void stream(final int gear, final double speedFactor, final boolean drive, final double seconds) {
        if (pulling) {
            return;
        }
        pulling = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ecu.apply(toState());
                    applyAuxiliaries();
                    ecu.resetAuxiliaries();
                    List<Sample> samples = drive ? sim.drive(seconds) : sim.pull(gear);
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
                        emit("fuelload", s.fuelLoad);
                        emit("ignload", s.ignLoad);
                        emit("vvt_ang1", s.vvtAngle);
                        emit("vvt_target1", s.vvtTarget);
                        emit("advance", s.advance);
                        emit("knock", s.knock);
                        emit("knockRetard", s.knockRetard);
                        emit("afr1", s.afr);
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
