package io.github.xadmiral.boostautotune.plugin.ecu;

import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.model.PidGains;
import io.github.xadmiral.boostautotune.core.session.EcuState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Translates between the ECU's parameters (through an {@link EcuPort} and an {@link EcuBinding})
 * and the core's {@link EcuState}. Remembers the raw layout of every table so writes match reads.
 */
public final class EcuAdapter {
    private final EcuPort port;
    private final EcuBinding b;
    private TableLayout targetLayout;
    private TableLayout biasLayout;
    private TableLayout openLoopLayout;
    private TableLayout vvtLayout;
    private TableLayout sparkLayout;
    private TableLayout alsLayout;
    private EcuPort.ParamInfo alsTableInfo;
    private EcuPort.ParamInfo pInfo, iInfo, dInfo, targetInfo;
    private EcuPort.ParamInfo windowInfo;
    private EcuPort.ParamInfo vvtPInfo, vvtIInfo, vvtDInfo, vvtTableInfo, sparkTableInfo;

    public EcuAdapter(EcuPort port, EcuBinding binding) {
        this.port = port;
        this.b = binding;
    }

    public EcuBinding binding() {
        return b;
    }

    public EcuPort port() {
        return port;
    }

    public String config() {
        if (b.has(b.configName)) {
            return b.configName;
        }
        List<String> names = port.configurationNames();
        return names.isEmpty() ? "" : names.get(0);
    }

    /** Reads everything the session needs. Warnings collect non-fatal oddities. */
    public EcuState read(List<String> warnings) throws EcuException {
        String cfg = config();
        EcuState s = new EcuState();
        targetInfo = port.parameterInfo(cfg, b.targetTable);
        targetLayout = layoutFor(cfg, b.targetTable, b.targetXBins, b.targetYBins, targetInfo);
        s.targetTable = readGrid(cfg, b.targetTable, b.targetXBins, b.targetYBins, targetLayout);
        s.targetLoadSource = b.targetLoadSource;
        s.targetTableMax = targetInfo.max > 0 ? targetInfo.max : 400;
        if (b.hasBiasTable()) {
            biasLayout = layoutFor(cfg, b.biasTable, b.biasXBins, b.biasYBins, port.parameterInfo(cfg, b.biasTable));
            s.biasTable = readGrid(cfg, b.biasTable, b.biasXBins, b.biasYBins, biasLayout);
            if (!s.biasTable.yAxis().isStrictlyIncreasing()) {
                warnings.add("Bias table Y axis (" + b.biasYBins + ") is not strictly increasing: " + s.biasTable.yAxis());
            }
        }
        if (b.hasOpenLoopTable()) {
            openLoopLayout = layoutFor(cfg, b.openLoopTable, b.openLoopXBins, b.openLoopYBins,
                    port.parameterInfo(cfg, b.openLoopTable));
            s.openLoopTable = readGrid(cfg, b.openLoopTable, b.openLoopXBins, b.openLoopYBins, openLoopLayout);
            s.openLoopLoadSource = b.openLoopLoadSource;
        }
        pInfo = port.parameterInfo(cfg, b.pidP);
        iInfo = port.parameterInfo(cfg, b.pidI);
        double p = port.readScalar(cfg, b.pidP);
        double i = port.readScalar(cfg, b.pidI);
        double d = 0;
        if (b.has(b.pidD)) {
            dInfo = port.parameterInfo(cfg, b.pidD);
            d = port.readScalar(cfg, b.pidD);
        }
        s.pid = new PidGains(p, i, d);
        s.minDuty = b.has(b.minDuty) ? port.readScalar(cfg, b.minDuty) : 0;
        s.maxDuty = b.has(b.maxDuty) ? port.readScalar(cfg, b.maxDuty) : 100;
        if (b.has(b.closedLoopWindowParam)) {
            windowInfo = port.parameterInfo(cfg, b.closedLoopWindowParam);
            s.closedLoopWindowKpa = port.readScalar(cfg, b.closedLoopWindowParam);
        } else {
            windowInfo = null;
            s.closedLoopWindowKpa = Double.NaN;
        }
        if (s.minDuty >= s.maxDuty) {
            warnings.add(String.format(Locale.US, "Min duty %.0f%% is not below max duty %.0f%%", s.minDuty, s.maxDuty));
        }
        if (b.hasModeSwitch()) {
            String mode = port.readOption(cfg, b.modeParam);
            s.closedLoop = b.closedLoopOption.equalsIgnoreCase(mode == null ? "" : mode.trim());
        } else {
            s.closedLoop = true;
        }
        if (!s.targetTable.xAxis().isStrictlyIncreasing()) {
            warnings.add("Target table RPM axis is not strictly increasing: " + s.targetTable.xAxis());
        }
        if (!s.targetTable.yAxis().isStrictlyIncreasing()) {
            warnings.add("Target table load axis is not strictly increasing: " + s.targetTable.yAxis());
        }
        return s;
    }

    /** Overboost cut limit configured in the ECU, or NaN. */
    public double readOverboostLimit() {
        if (!b.has(b.overboostLimit)) {
            return Double.NaN;
        }
        try {
            return port.readScalar(config(), b.overboostLimit);
        } catch (EcuException e) {
            return Double.NaN;
        }
    }

    public EcuPort.ParamInfo pidInfo(int which) {
        return which == 0 ? pInfo : which == 1 ? iInfo : dInfo;
    }

    /** Range of the closed-loop window parameter, or null when it is not bound. */
    public EcuPort.ParamInfo windowInfo() {
        return windowInfo;
    }

    /** Writes tables, gains and mode. Returns a description of what was written. */
    public List<String> write(EcuState s, boolean writeTargets, boolean writeBias, boolean writeOpenLoop) throws EcuException {
        String cfg = config();
        List<String> done = new ArrayList<String>();
        if (writeTargets && s.targetTable != null) {
            port.writeArray2D(cfg, b.targetTable, targetLayout.fromGrid(s.targetTable));
            done.add("target table " + b.targetTable);
        }
        if (writeBias && s.biasTable != null && b.hasBiasTable()) {
            port.writeArray2D(cfg, b.biasTable, biasLayout.fromGrid(s.biasTable));
            done.add("bias table " + b.biasTable);
        }
        if (writeOpenLoop && s.openLoopTable != null && b.hasOpenLoopTable()) {
            port.writeArray2D(cfg, b.openLoopTable, openLoopLayout.fromGrid(s.openLoopTable));
            done.add("open-loop table " + b.openLoopTable);
        }
        port.writeScalar(cfg, b.pidP, s.pid.p);
        port.writeScalar(cfg, b.pidI, s.pid.i);
        if (b.has(b.pidD)) {
            port.writeScalar(cfg, b.pidD, s.pid.d);
        }
        done.add("PID " + s.pid);
        if (b.has(b.closedLoopWindowParam) && !Double.isNaN(s.closedLoopWindowKpa)) {
            port.writeScalar(cfg, b.closedLoopWindowParam, s.closedLoopWindowKpa);
            done.add(b.closedLoopWindowParam + " = " + s.closedLoopWindowKpa);
        }
        if (b.has(b.enableParam) && b.has(b.enableOption)) {
            port.writeOption(cfg, b.enableParam, b.enableOption);
        }
        if (b.hasModeSwitch()) {
            port.writeOption(cfg, b.modeParam, s.closedLoop ? b.closedLoopOption : b.openLoopOption);
            done.add(b.modeParam + " = " + (s.closedLoop ? b.closedLoopOption : b.openLoopOption));
        }
        if (s.closedLoop && b.has(b.closedLoopExtraParam) && b.has(b.closedLoopExtraOption)) {
            port.writeOption(cfg, b.closedLoopExtraParam, b.closedLoopExtraOption);
            done.add(b.closedLoopExtraParam + " = " + b.closedLoopExtraOption);
        }
        return done;
    }

    /** Emergency: drop the valve to minimum duty and put the ECU in open loop. */
    public List<String> writeSafeState(EcuState s) throws EcuException {
        String cfg = config();
        List<String> done = new ArrayList<String>();
        if (b.hasModeSwitch()) {
            port.writeOption(cfg, b.modeParam, b.openLoopOption);
            done.add(b.modeParam + " = " + b.openLoopOption);
        }
        if (s.openLoopTable != null && b.hasOpenLoopTable()) {
            Grid safe = s.openLoopTable.copy();
            safe.fill(s.minDuty);
            port.writeArray2D(cfg, b.openLoopTable, openLoopLayout.fromGrid(safe));
            done.add("open-loop table forced to " + s.minDuty + "%");
        }
        if (b.has(b.maxDuty)) {
            port.writeScalar(cfg, b.maxDuty, s.minDuty);
            done.add(b.maxDuty + " = " + s.minDuty);
        }
        return done;
    }

    public void burn() throws EcuException {
        port.burn(config());
    }

    // ---- VVT ----------------------------------------------------------------------------------

    public Grid readVvtTable() throws EcuException {
        String cfg = config();
        vvtTableInfo = port.parameterInfo(cfg, b.vvtTable);
        vvtLayout = layoutFor(cfg, b.vvtTable, b.vvtXBins, b.vvtYBins, vvtTableInfo);
        return readGrid(cfg, b.vvtTable, b.vvtXBins, b.vvtYBins, vvtLayout);
    }

    public void writeVvtTable(Grid g) throws EcuException {
        if (vvtLayout == null) {
            readVvtTable();
        }
        port.writeArray2D(config(), b.vvtTable, vvtLayout.fromGrid(g));
    }

    public EcuPort.ParamInfo vvtTableInfo() {
        return vvtTableInfo;
    }

    public PidGains readVvtGains() throws EcuException {
        String cfg = config();
        vvtPInfo = port.parameterInfo(cfg, b.vvtPidP);
        vvtIInfo = port.parameterInfo(cfg, b.vvtPidI);
        double d = 0;
        if (b.has(b.vvtPidD)) {
            vvtDInfo = port.parameterInfo(cfg, b.vvtPidD);
            d = port.readScalar(cfg, b.vvtPidD);
        }
        return new PidGains(port.readScalar(cfg, b.vvtPidP), port.readScalar(cfg, b.vvtPidI), d);
    }

    public void writeVvtGains(PidGains g) throws EcuException {
        String cfg = config();
        port.writeScalar(cfg, b.vvtPidP, g.p);
        port.writeScalar(cfg, b.vvtPidI, g.i);
        if (b.has(b.vvtPidD)) {
            port.writeScalar(cfg, b.vvtPidD, g.d);
        }
    }

    public EcuPort.ParamInfo vvtPidInfo(int which) {
        return which == 0 ? vvtPInfo : which == 1 ? vvtIInfo : vvtDInfo;
    }

    // ---- anti-lag -----------------------------------------------------------------------------

    public Grid readAlsTiming() throws EcuException {
        String cfg = config();
        alsTableInfo = port.parameterInfo(cfg, b.alsTimingTable);
        alsLayout = layoutFor(cfg, b.alsTimingTable, b.alsXBins, b.alsYBins, alsTableInfo);
        return readGrid(cfg, b.alsTimingTable, b.alsXBins, b.alsYBins, alsLayout);
    }

    public void writeAlsTiming(Grid g) throws EcuException {
        if (alsLayout == null) {
            readAlsTiming();
        }
        port.writeArray2D(config(), b.alsTimingTable, alsLayout.fromGrid(g));
    }

    public EcuPort.ParamInfo alsTableInfo() {
        return alsTableInfo;
    }

    /** True when drive-by-wire is on: the anti-lag then opens the throttle instead of an idle valve. */
    public boolean alsAirIsThrottle() {
        if (!b.has(b.alsAirDbwParam) || !b.has(b.dbwEnableParam) || !b.has(b.dbwEnableOption)) {
            return false;
        }
        try {
            String v = port.readOption(config(), b.dbwEnableParam);
            return v != null && v.trim().equalsIgnoreCase(b.dbwEnableOption.trim());
        } catch (EcuException e) {
            return false;
        }
    }

    /** ECU "operate ALS below this TPS", or NaN when not bound. */
    public double readAlsMaxTps() throws EcuException {
        return b.has(b.alsMaxTpsParam) ? port.readScalar(config(), b.alsMaxTpsParam) : Double.NaN;
    }

    public void writeAlsMaxTps(double pct) throws EcuException {
        if (b.has(b.alsMaxTpsParam) && !Double.isNaN(pct)) {
            port.writeScalar(config(), b.alsMaxTpsParam, pct);
        }
    }

    /**
     * The second anti-lag knob for this ECU: the DBW throttle opening when drive-by-wire is on,
     * otherwise the idle valve (steps for steppers, duty for PWM).
     */
    public String alsAirParam() {
        if (alsAirIsThrottle()) {
            return b.alsAirDbwParam;
        }
        boolean stepper = b.has(b.alsAirStepsParam);
        if (b.has(b.idleTypeParam) && b.has(b.idleTypeStepperOption) && b.has(b.alsAirStepsParam) && b.has(b.alsAirDutyParam)) {
            try {
                String type = port.readOption(config(), b.idleTypeParam);
                stepper = type != null && type.trim().equalsIgnoreCase(b.idleTypeStepperOption.trim());
            } catch (EcuException e) {
                stepper = true;
            }
        }
        if (stepper && b.has(b.alsAirStepsParam)) {
            return b.alsAirStepsParam;
        }
        return b.has(b.alsAirDutyParam) ? b.alsAirDutyParam : b.alsAirStepsParam;
    }

    public double readAlsAir() throws EcuException {
        return port.readScalar(config(), alsAirParam());
    }

    public void writeAlsAir(double v) throws EcuException {
        port.writeScalar(config(), alsAirParam(), v);
    }

    public EcuPort.ParamInfo alsAirInfo() throws EcuException {
        return port.parameterInfo(config(), alsAirParam());
    }

    /** ECU maximum anti-lag time per activation, or NaN when not bound. */
    public double readAlsMaxTime() throws EcuException {
        return b.has(b.alsMaxTimeParam) ? port.readScalar(config(), b.alsMaxTimeParam) : Double.NaN;
    }

    public void writeAlsMaxTime(double sec) throws EcuException {
        if (b.has(b.alsMaxTimeParam) && !Double.isNaN(sec)) {
            port.writeScalar(config(), b.alsMaxTimeParam, sec);
        }
    }

    /** ECU anti-lag cut-off RPM, or NaN when not bound. */
    public double readAlsMinRpm() throws EcuException {
        return b.has(b.alsMinRpmParam) ? port.readScalar(config(), b.alsMinRpmParam) : Double.NaN;
    }

    public void writeAlsMinRpm(double rpm) throws EcuException {
        if (b.has(b.alsMinRpmParam) && !Double.isNaN(rpm)) {
            port.writeScalar(config(), b.alsMinRpmParam, rpm);
        }
    }

    public String readAlsEnable() throws EcuException {
        return b.has(b.alsEnableParam) ? port.readOption(config(), b.alsEnableParam) : null;
    }

    public void writeAlsEnable(String option) throws EcuException {
        if (b.has(b.alsEnableParam) && option != null) {
            port.writeOption(config(), b.alsEnableParam, option);
        }
    }

    // ---- generic access for presets ------------------------------------------------------------

    /** Reads any parameter as text: options as their label, scalars as a number, arrays as rows. */
    public String readAny(String name) throws EcuException {
        String cfg = config();
        EcuPort.ParamInfo info = port.parameterInfo(cfg, name);
        if ("bits".equalsIgnoreCase(info.paramClass)) {
            return port.readOption(cfg, name);
        }
        if ("array".equalsIgnoreCase(info.paramClass)) {
            double[][] raw = port.readArray2D(cfg, name);
            StringBuilder sb = new StringBuilder();
            for (double[] row : raw) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) {
                        sb.append(' ');
                    }
                    sb.append(io.github.xadmiral.boostautotune.plugin.settings.SettingsStore.fmt(row[i]));
                }
            }
            return sb.toString();
        }
        return io.github.xadmiral.boostautotune.plugin.settings.SettingsStore.fmt(port.readScalar(cfg, name));
    }

    /**
     * Writes any parameter from text: an option label for bits, a number for scalars, a single
     * number to fill a whole array or a space separated list for a 1-D axis.
     */
    public void writeAny(String name, String value) throws EcuException {
        String cfg = config();
        EcuPort.ParamInfo info = port.parameterInfo(cfg, name);
        String v = value == null ? "" : value.trim();
        if ("bits".equalsIgnoreCase(info.paramClass)) {
            port.writeOption(cfg, name, v);
            return;
        }
        if ("array".equalsIgnoreCase(info.paramClass)) {
            String[] parts = v.replace(';', ' ').trim().split("[\\s,]+");
            double[][] raw = port.readArray2D(cfg, name);
            if (parts.length == 1) {
                double fill = parseNumber(name, parts[0]);
                for (double[] row : raw) {
                    java.util.Arrays.fill(row, fill);
                }
                port.writeArray2D(cfg, name, raw);
                return;
            }
            double[] vals = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                vals[i] = parseNumber(name, parts[i]);
            }
            if (raw.length == 1 || raw[0].length == 1) {
                port.writeArray1D(cfg, name, vals);
                return;
            }
            throw new EcuException(name + ": give one number to fill the table, or a bin list for a 1-D axis");
        }
        port.writeScalar(cfg, name, parseNumber(name, v));
    }

    private static double parseNumber(String name, String s) throws EcuException {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new EcuException(name + ": '" + s + "' is not a number");
        }
    }

    public boolean hasParameter(String name) {
        return port.parameterNames(config()).contains(name);
    }

    // ---- ignition -----------------------------------------------------------------------------

    public Grid readSparkTable() throws EcuException {
        String cfg = config();
        sparkTableInfo = port.parameterInfo(cfg, b.sparkTable);
        sparkLayout = layoutFor(cfg, b.sparkTable, b.sparkXBins, b.sparkYBins, sparkTableInfo);
        return readGrid(cfg, b.sparkTable, b.sparkXBins, b.sparkYBins, sparkLayout);
    }

    public void writeSparkTable(Grid g) throws EcuException {
        if (sparkLayout == null) {
            readSparkTable();
        }
        port.writeArray2D(config(), b.sparkTable, sparkLayout.fromGrid(g));
    }

    public EcuPort.ParamInfo sparkTableInfo() {
        return sparkTableInfo;
    }

    public TableLayout targetLayout() {
        return targetLayout;
    }

    public TableLayout biasLayout() {
        return biasLayout;
    }

    /** Reads a table as a grid, using bins from two 1-D parameters. */
    public Grid readGrid(String cfg, String z, String xBins, String yBins, TableLayout layout) throws EcuException {
        double[] x = port.readArray1D(cfg, xBins);
        double[] y = port.readArray1D(cfg, yBins);
        double[][] raw = port.readArray2D(cfg, z);
        double[][] yx = layout.toYX(raw);
        if (yx.length != y.length || yx[0].length != x.length) {
            throw new EcuException(String.format(Locale.US,
                    "Table %s is %dx%d after orientation but the axes have %d (X) and %d (Y) bins; change the table orientation in Setup",
                    z, yx[0].length, yx.length, x.length, y.length));
        }
        return new Grid(new Axis(x), new Axis(y), yx);
    }

    /** Works out which way round TunerStudio handed the table over. */
    public TableLayout layoutFor(String cfg, String z, String xBins, String yBins, EcuPort.ParamInfo info) throws EcuException {
        double[][] raw = port.readArray2D(cfg, z);
        int rows = raw.length;
        int cols = raw[0].length;
        int xLen = port.readArray1D(cfg, xBins).length;
        int yLen = port.readArray1D(cfg, yBins).length;
        boolean rowsAreY;
        switch (b.orientation) {
            case ROWS_ARE_Y:
                rowsAreY = true;
                break;
            case ROWS_ARE_X:
                rowsAreY = false;
                break;
            default:
                if (rows == yLen && cols == xLen && xLen != yLen) {
                    rowsAreY = true;
                } else if (rows == xLen && cols == yLen && xLen != yLen) {
                    rowsAreY = false;
                } else if (info != null && info.shapeHeight > 0 && info.shapeWidth > 0 && info.shapeHeight != info.shapeWidth) {
                    rowsAreY = rows == info.shapeHeight;
                } else {
                    rowsAreY = true;
                }
        }
        return new TableLayout(rows, cols, rowsAreY);
    }
}
