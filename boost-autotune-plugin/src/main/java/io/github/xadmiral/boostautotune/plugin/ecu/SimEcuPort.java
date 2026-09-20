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
        scalars.put(b.closedLoopWindowParam, 30.0);
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
        // anti-lag: the base tune's rally-style defaults, ALS off
        arrays.put(b.alsTimingTable, new double[][]{
                {-30, -28, -26, -24, -22, -20}, {-30, -28, -26, -24, -22, -20}, {-30, -28, -26, -24, -22, -20},
                {-30, -28, -26, -24, -22, -20}, {-30, -28, -26, -24, -22, -20}, {-30, -28, -26, -24, -22, -20}});
        arrays.put(b.alsXBins, column(1300, 1600, 1900, 2200, 2500, 2800));
        arrays.put(b.alsYBins, column(0, 4, 8, 12, 16, 20));
        arrays.put("als_addfuel", filled(6, 6, 25));
        arrays.put("als_sparkcut", filled(6, 6, 80));
        arrays.put("als_fuelcut", filled(6, 6, 80));
        scalars.put(b.alsAirStepsParam, 150.0);
        scalars.put(b.alsAirDutyParam, 58.8);
        scalars.put(b.alsAirDbwParam, 10.0);
        options.put(b.dbwEnableParam, "Off");
        scalars.put("als_acttps", 50.0);
        scalars.put("als_maxtps", 30.0);
        scalars.put("als_minrpm", 500.0);
        scalars.put("als_maxrpm", 3000.0);
        scalars.put("als_maxtime", 4.0);
        scalars.put("als_pausetime", 10.0);
        scalars.put("als_maxmat_C", 76.7);
        scalars.put("als_minclt_C", 50.0);
        scalars.put("als_maxclt_C", 110.0);
        scalars.put("flats_arm", 3000.0);
        scalars.put("flats_hrd", 6500.0);
        scalars.put("flats_deg", -5.0);
        scalars.put("launch_hrd_lim", 3800.0);
        options.put(b.alsEnableParam, "Off");
        options.put(b.idleTypeParam, "Stepper valve (6 wire)");
        options.put("als_opt_sc", "Off");
        options.put("als_opt_fc", "Off");
        options.put("als_opt_idle", "Off");
        options.put("als_opt_ri", "Off");
        options.put("als_opt_fuel", "Off");
        options.put("launch_opt_on", "Off");
        options.put("launchlimopt", "Spark Cut");
        // over-run fuel cut: the base tune's street settings
        options.put("OvrRunC", "On");
        options.put("OvrRunC_progcut", "Off");
        options.put("OvrRunC_progign", "Off");
        options.put("OvrRunC_progret", "On");
        options.put("OvrRunC_retign", "Off");
        scalars.put("fc_rpm", 2000.0);
        scalars.put("fc_kpa", 38.0);
        scalars.put("fc_tps", 1.0);
        scalars.put("fc_clt_C", 70.0);
        scalars.put("fc_delay", 0.0);
        scalars.put("fc_timing", 0.0);
        scalars.put("fc_transition_time", 0.6);
        scalars.put("fc_trans_time_ret", 0.5);
        scalars.put("fc_rpm_lower", 1200.0);
        scalars.put("fc_ae_time", 0.0);
        scalars.put("fc_ae_pct", 0.0);
        // knock: the base tune's threshold curve, the internal module listening per cylinder, gains
        // roughly right for this engine (a fresh MS3 has 1.000 everywhere: far too loud)
        arrays.put(b.knockRpmBins, column(2000, 2500, 3000, 3500, 4000, 4500, 5000, 5500, 6000, 7000));
        arrays.put(b.knockThresholdTable, column(33, 40, 42, 45, 45, 45, 45, 45, 50, 50));
        for (int c = 1; c <= 8; c++) {
            options.put(b.knockGainParam(c), "0.421");
        }
        options.put(b.knockPerCylParam, "On");
        options.put("knock_conf_percylact", "Off");
        options.put(b.cylindersParam, "6");
        options.put(b.knockControlParam, "Safe Mode");
        options.put("knk_option_an", "Internal");
        options.put("knock_conf_num", "2");
        scalars.put(b.knockMinLoadParam, 80.0);
        scalars.put(b.knockLoRpmParam, 1500.0);
        scalars.put(b.knockHiRpmParam, 7000.0);
        scalars.put("knk_maxrtd", 6.0);
        scalars.put("knk_ndet", 2.0);
        applyAuxiliaries();
        sim = new PullSimulator(plant, ecu);
    }

    /** The MS3 knock gain options. */
    static final String[] KNOCK_GAIN = {"2.000", "1.882", "1.778", "1.684", "1.600", "1.523", "1.455", "1.391", "1.333", "1.280", "1.231",
            "1.185", "1.143", "1.063", "1.000", "0.944", "0.895", "0.85", "0.81", "0.773", "0.739", "0.708", "0.680", "0.654", "0.630", "0.607",
            "0.586", "0.567", "0.548", "0.500", "0.471", "0.444", "0.421", "0.400", "0.381", "0.364", "0.348", "0.333", "0.320", "0.308", "0.296",
            "0.286", "0.276", "0.267", "0.258", "0.250", "0.236", "0.222", "0.211", "0.200", "0.190", "0.182", "0.174", "0.167", "0.160", "0.154",
            "0.148", "0.143", "0.138", "0.133", "0.129", "0.125", "0.118", "0.111"};

    private void applyAuxiliaries() {
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        ecu.vvtTable = grid(b.vvtTable, b.vvtXBins, b.vvtYBins);
        ecu.vvtPid = new PidGains(scalars.get(b.vvtPidP), scalars.get(b.vvtPidI), scalars.get(b.vvtPidD));
        ecu.sparkTable = grid(b.sparkTable, b.sparkXBins, b.sparkYBins);
        ecu.alsTiming = grid(b.alsTimingTable, b.alsXBins, b.alsYBins);
        ecu.alsAirIsThrottle = b.dbwEnableOption.equals(options.get(b.dbwEnableParam));
        ecu.alsAir = ecu.alsAirIsThrottle ? scalars.get(b.alsAirDbwParam) : scalars.get(b.alsAirStepsParam);
        ecu.alsEnabled = !"Off".equals(options.get(b.alsEnableParam));
        ecu.alsArmTps = scalars.get("als_acttps");
        ecu.alsOperateTps = scalars.get("als_maxtps");
        ecu.alsMinRpm = scalars.get("als_minrpm");
        ecu.alsMaxRpm = scalars.get("als_maxrpm");
        ecu.alsMaxTimeSec = scalars.get("als_maxtime");
        ecu.knockNoiseEnabled = true;
        ecu.knockRpmBins = flat(arrays.get(b.knockRpmBins));
        ecu.knockThresholds = flat(arrays.get(b.knockThresholdTable));
        boolean perCyl = b.knockPerCylOnOption.equals(options.get(b.knockPerCylParam));
        double[] gains = new double[6];
        for (int c = 0; c < gains.length; c++) {
            gains[c] = Double.parseDouble(options.get(b.knockGainParam(perCyl ? c + 1 : 1)));
        }
        ecu.knockGains = gains;
        ecu.knockMinLoad = scalars.get(b.knockMinLoadParam);
        ecu.knockLoRpm = scalars.get(b.knockLoRpmParam);
        ecu.knockHiRpm = scalars.get(b.knockHiRpmParam);
    }

    /** Cool-down between anti-lag runs (the demo has no wind). */
    public void coolDown(double seconds) {
        ecu.coolDown(seconds);
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
        s.closedLoopWindowKpa = scalars.containsKey(b.closedLoopWindowParam) ? scalars.get(b.closedLoopWindowParam) : Double.NaN;
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
                "vvt_ang1", "vvt_target1", "fuelload", "ignload", "advance", "knock", "knockRetard", "afr1", "status10", "mat",
                "knock_cyl01", "knock_cyl02", "knock_cyl03", "knock_cyl04", "knock_cyl05", "knock_cyl06");
    }

    @Override
    public ParamInfo parameterInfo(String config, String name) throws EcuException {
        if (arrays.containsKey(name)) {
            double[][] a = arrays.get(name);
            double min = name.startsWith("advance") || name.equals("als_timing") ? -50 : 0;
            double max = name.contains("targets") ? 400 : name.startsWith("advance") ? 90 : name.startsWith("vvt_timing1") ? 720
                    : name.equals("als_timing") ? 50 : name.endsWith("_rpms") ? 25000 : 100;
            return new ParamInfo("array", "", min, max, 1, a[0].length, a.length, Collections.<String>emptyList());
        }
        if (scalars.containsKey(name)) {
            double max = name.contains("Kp") || name.contains("Ki") || name.contains("Kd") ? 200 : name.equals("als_iac_steps") ? 255
                    : name.contains("rpm") || name.contains("_arm") || name.contains("_hrd") || name.contains("_lim") ? 25000
                    : name.equals("boost_ctl_lowerlimit") ? 200 : name.equals("als_iac_pos") ? 25.5
                    : name.equals("fc_kpa") ? 400 : name.equals("fc_ae_pct") ? 255 : name.equals("fc_timing") ? 180 : 100;
            double min = name.equals("flats_deg") || name.equals("fc_timing") ? -90 : name.equals("boost_ctl_lowerlimit") ? 5
                    : name.equals("fc_trans_time_ret") || name.equals("fc_transition_time") ? 0.5 : 0;
            return new ParamInfo("scalar", "", min, max, name.equals("als_iac_steps") ? 0 : 1, 1, 1, Collections.<String>emptyList());
        }
        if (options.containsKey(name)) {
            List<String> opts = name.equals("als_in_pin") ? Arrays.asList("Off", "Always ON")
                    : name.equals("launch_opt_on") ? Arrays.asList("Off", "Launch", "Launch/Flatshift")
                    : name.equals("IdleCtl") ? Arrays.asList("None", "PWM valve (2 or 3 wire)", "Stepper valve (6 wire)")
                    : name.startsWith("knock_gain") ? Arrays.asList(KNOCK_GAIN)
                    : name.equals("nCylinders") ? Arrays.asList("INVALID", "1", "2", "3", "4", "5", "6", "7", "8")
                    : name.equals("knk_option") ? Arrays.asList("Disabled", "Safe Mode", "Aggressive Mode")
                    : name.equals("knk_option_an") ? Arrays.asList("Analogue", "Internal")
                    : name.equals("knock_conf_num") ? Arrays.asList("1", "2")
                    : Arrays.asList("Off", "On", "Open-loop", "Closed-loop");
            return new ParamInfo("bits", "", 0, 0, 0, 1, 1, opts);
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
        applyAuxiliaries();
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
    public void writeArray1D(String config, String name, double[] values) throws EcuException {
        double[][] a = arrays.get(name);
        if (a == null || a[0].length != 1 || a.length != values.length) {
            throw new EcuException("Not a 1-D array of " + values.length + " bins: " + name);
        }
        arrays.put(name, column(values));
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
                new UiTableInfo("Ignition Table 1 (Spark Advance)", b.sparkXBins, b.sparkYBins, b.sparkTable, "rpm", "ignload"),
                new UiTableInfo("Anti-Lag Timing", b.alsXBins, b.alsYBins, b.alsTimingTable, "rpm", "tps"));
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
        stream(gear, speedFactor, 0, 0);
    }

    /** Streams ordinary driving (for the VVT PID mode). */
    public void simulateDrive(final double seconds, final double speedFactor) {
        stream(3, speedFactor, 1, seconds);
    }

    /** Streams an anti-lag exercise: throttle bursts and full lifts with the anti-lag holding boost. */
    public void simulateAntilag(final int lifts, final double speedFactor) {
        stream(3, speedFactor, 2, lifts);
    }

    private void stream(final int gear, final double speedFactor, final int kind, final double arg) {
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
                    List<Sample> samples = kind == 1 ? sim.drive(arg) : kind == 2 ? sim.alsCycle((int) arg) : sim.pull(gear);
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
                        emit("status10", s.alsActive ? 128 : 0);
                        emit("mat", s.mat);
                        if (s.knockCyl != null) {
                            for (int c = 0; c < s.knockCyl.length && c < 6; c++) {
                                emit(String.format(java.util.Locale.US, "knock_cyl%02d", c + 1), s.knockCyl[c]);
                            }
                        }
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
