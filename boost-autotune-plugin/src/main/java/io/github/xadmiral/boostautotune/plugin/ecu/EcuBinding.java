package io.github.xadmiral.boostautotune.plugin.ecu;

import io.github.xadmiral.boostautotune.core.model.LoadSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Names of the ECU channels and parameters the plugin works with. Empty strings mean "not
 * available on this firmware". Everything is user-editable in the Setup tab.
 */
public final class EcuBinding {
    public String presetName = "Custom";
    public String configName = "";

    // output channels
    public String rpmChannel = "rpm";
    public String tpsChannel = "tps";
    public String mapChannel = "map";
    public String targetChannel = "";
    public String dutyChannel = "";
    public String cltChannel = "coolant";
    public String gearChannel = "";
    public String boostCutChannel = "";
    /** Sub-second time channel; empty = wall clock. Only the simulator provides one, real ECUs report whole seconds. */
    public String timeChannel = "";
    /** Bit mask applied to boostCutChannel; 0 = any non-zero value means cut. */
    public int boostCutMask = 0;
    public boolean cltFahrenheit = false;

    // target table
    public String targetTable = "";
    public String targetXBins = "";
    public String targetYBins = "";
    public LoadSource targetLoadSource = LoadSource.TPS;

    // bias (feed-forward) table, X = rpm, Y = target boost
    public String biasTable = "";
    public String biasXBins = "";
    public String biasYBins = "";

    // open-loop duty table
    public String openLoopTable = "";
    public String openLoopXBins = "";
    public String openLoopYBins = "";
    public LoadSource openLoopLoadSource = LoadSource.TPS;

    // scalars
    public String pidP = "";
    public String pidI = "";
    public String pidD = "";
    public String minDuty = "";
    public String maxDuty = "";
    public String overboostLimit = "";

    // mode switches (bits parameters; options are the ini option strings)
    public String modeParam = "";
    public String openLoopOption = "";
    public String closedLoopOption = "";
    public String enableParam = "";
    public String enableOption = "";
    /** Extra parameter forced when closing the loop, e.g. MS3 boost_ctl_flags = "Advanced Mode". */
    public String closedLoopExtraParam = "";
    public String closedLoopExtraOption = "";

    // ---- VVT ----
    public String vvtAngleChannel = "";
    public String vvtTargetChannel = "";
    public String fuelLoadChannel = "";
    public String vvtTable = "";
    public String vvtXBins = "";
    public String vvtYBins = "";
    public LoadSource vvtLoadSource = LoadSource.FUEL_LOAD;
    public String vvtPidP = "";
    public String vvtPidI = "";
    public String vvtPidD = "";

    // ---- ignition ----
    public String advanceChannel = "";
    public String knockChannel = "";
    public String knockRetardChannel = "";
    public String afrChannel = "";
    public String ignLoadChannel = "";
    public String sparkTable = "";
    public String sparkXBins = "";
    public String sparkYBins = "";
    public LoadSource sparkLoadSource = LoadSource.IGN_LOAD;

    public TableOrientation orientation = TableOrientation.AUTO;

    public boolean has(String name) {
        return name != null && !name.trim().isEmpty();
    }

    public boolean hasBiasTable() {
        return has(biasTable) && has(biasXBins) && has(biasYBins);
    }

    public boolean hasOpenLoopTable() {
        return has(openLoopTable) && has(openLoopXBins) && has(openLoopYBins);
    }

    public boolean hasModeSwitch() {
        return has(modeParam) && has(openLoopOption) && has(closedLoopOption);
    }

    public boolean hasVvtTable() {
        return has(vvtTable) && has(vvtXBins) && has(vvtYBins);
    }

    public boolean hasVvtPid() {
        return has(vvtPidP) && has(vvtPidI);
    }

    public boolean hasSparkTable() {
        return has(sparkTable) && has(sparkXBins) && has(sparkYBins);
    }

    /** Problems for the VVT modes (sweep needs the table, PID tuning needs the gains). */
    public List<String> validateVvt(List<String> channels, List<String> params, boolean pid) {
        List<String> problems = new ArrayList<String>();
        checkChannel(problems, channels, "RPM channel", rpmChannel, true);
        checkChannel(problems, channels, "TPS channel", tpsChannel, true);
        checkChannel(problems, channels, "MAP channel", mapChannel, true);
        checkChannel(problems, channels, "CLT channel", cltChannel, false);
        checkChannel(problems, channels, "VVT angle channel", vvtAngleChannel, true);
        checkChannel(problems, channels, "VVT target channel", vvtTargetChannel, true);
        checkChannel(problems, channels, "Fuel load channel", fuelLoadChannel, false);
        checkChannel(problems, channels, "Gear channel", gearChannel, false);
        if (pid) {
            checkParam(problems, params, "VVT P gain", vvtPidP, true);
            checkParam(problems, params, "VVT I gain", vvtPidI, true);
            checkParam(problems, params, "VVT D gain", vvtPidD, false);
        } else {
            checkParam(problems, params, "VVT table", vvtTable, true);
            checkParam(problems, params, "VVT table RPM bins", vvtXBins, true);
            checkParam(problems, params, "VVT table load bins", vvtYBins, true);
        }
        return problems;
    }

    /** Problems for the ignition sweep. */
    public List<String> validateIgnition(List<String> channels, List<String> params) {
        List<String> problems = new ArrayList<String>();
        checkChannel(problems, channels, "RPM channel", rpmChannel, true);
        checkChannel(problems, channels, "TPS channel", tpsChannel, true);
        checkChannel(problems, channels, "MAP channel", mapChannel, true);
        checkChannel(problems, channels, "CLT channel", cltChannel, false);
        checkChannel(problems, channels, "Advance channel", advanceChannel, true);
        checkChannel(problems, channels, "Knock retard channel", knockRetardChannel, true);
        checkChannel(problems, channels, "Knock level channel", knockChannel, false);
        checkChannel(problems, channels, "AFR channel", afrChannel, false);
        checkChannel(problems, channels, "Ignition load channel", ignLoadChannel, false);
        checkChannel(problems, channels, "Gear channel", gearChannel, false);
        checkParam(problems, params, "Spark table", sparkTable, true);
        checkParam(problems, params, "Spark table RPM bins", sparkXBins, true);
        checkParam(problems, params, "Spark table load bins", sparkYBins, true);
        return problems;
    }

    public EcuBinding copy() {
        EcuBinding b = new EcuBinding();
        Properties p = new Properties();
        store(p, "");
        b.load(p, "");
        return b;
    }

    /** Required names that are missing from the ECU. */
    public List<String> validate(List<String> channels, List<String> params) {
        List<String> problems = new ArrayList<String>();
        checkChannel(problems, channels, "RPM channel", rpmChannel, true);
        checkChannel(problems, channels, "TPS channel", tpsChannel, true);
        checkChannel(problems, channels, "MAP channel", mapChannel, true);
        checkChannel(problems, channels, "Boost target channel", targetChannel, true);
        checkChannel(problems, channels, "Boost duty channel", dutyChannel, true);
        checkChannel(problems, channels, "CLT channel", cltChannel, false);
        checkChannel(problems, channels, "Gear channel", gearChannel, false);
        checkChannel(problems, channels, "Boost cut channel", boostCutChannel, false);
        checkChannel(problems, channels, "Time channel", timeChannel, false);
        checkParam(problems, params, "Target table", targetTable, true);
        checkParam(problems, params, "Target table X bins", targetXBins, true);
        checkParam(problems, params, "Target table Y bins", targetYBins, true);
        checkParam(problems, params, "Bias table", biasTable, false);
        checkParam(problems, params, "Bias table X bins", biasXBins, false);
        checkParam(problems, params, "Bias table Y bins", biasYBins, false);
        checkParam(problems, params, "Open-loop duty table", openLoopTable, false);
        checkParam(problems, params, "Open-loop X bins", openLoopXBins, false);
        checkParam(problems, params, "Open-loop Y bins", openLoopYBins, false);
        checkParam(problems, params, "P gain", pidP, true);
        checkParam(problems, params, "I gain", pidI, true);
        checkParam(problems, params, "D gain", pidD, false);
        checkParam(problems, params, "Minimum duty", minDuty, false);
        checkParam(problems, params, "Maximum duty", maxDuty, false);
        checkParam(problems, params, "Open/closed loop switch", modeParam, false);
        checkParam(problems, params, "Boost control enable", enableParam, false);
        checkParam(problems, params, "Extra closed-loop parameter", closedLoopExtraParam, false);
        checkParam(problems, params, "Overboost limit", overboostLimit, false);
        if (hasOpenLoopTable() && !hasModeSwitch()) {
            problems.add("Open-loop characterization needs the open/closed loop switch parameter and both option names");
        }
        return problems;
    }

    private static void checkChannel(List<String> out, List<String> names, String label, String value, boolean required) {
        check(out, names, label, value, required, "output channel");
    }

    private static void checkParam(List<String> out, List<String> names, String label, String value, boolean required) {
        check(out, names, label, value, required, "parameter");
    }

    private static void check(List<String> out, List<String> names, String label, String value, boolean required, String kind) {
        boolean set = value != null && !value.trim().isEmpty();
        if (!set) {
            if (required) {
                out.add(label + " is required");
            }
            return;
        }
        if (names != null && !names.contains(value.trim())) {
            out.add(label + ": " + kind + " '" + value + "' does not exist in this ECU definition");
        }
    }

    public void store(Properties p, String prefix) {
        p.setProperty(prefix + "presetName", presetName);
        p.setProperty(prefix + "configName", configName);
        p.setProperty(prefix + "rpmChannel", rpmChannel);
        p.setProperty(prefix + "tpsChannel", tpsChannel);
        p.setProperty(prefix + "mapChannel", mapChannel);
        p.setProperty(prefix + "targetChannel", targetChannel);
        p.setProperty(prefix + "dutyChannel", dutyChannel);
        p.setProperty(prefix + "cltChannel", cltChannel);
        p.setProperty(prefix + "gearChannel", gearChannel);
        p.setProperty(prefix + "boostCutChannel", boostCutChannel);
        p.setProperty(prefix + "timeChannel", timeChannel);
        p.setProperty(prefix + "boostCutMask", Integer.toString(boostCutMask));
        p.setProperty(prefix + "cltFahrenheit", Boolean.toString(cltFahrenheit));
        p.setProperty(prefix + "targetTable", targetTable);
        p.setProperty(prefix + "targetXBins", targetXBins);
        p.setProperty(prefix + "targetYBins", targetYBins);
        p.setProperty(prefix + "targetLoadSource", targetLoadSource.name());
        p.setProperty(prefix + "biasTable", biasTable);
        p.setProperty(prefix + "biasXBins", biasXBins);
        p.setProperty(prefix + "biasYBins", biasYBins);
        p.setProperty(prefix + "openLoopTable", openLoopTable);
        p.setProperty(prefix + "openLoopXBins", openLoopXBins);
        p.setProperty(prefix + "openLoopYBins", openLoopYBins);
        p.setProperty(prefix + "openLoopLoadSource", openLoopLoadSource.name());
        p.setProperty(prefix + "pidP", pidP);
        p.setProperty(prefix + "pidI", pidI);
        p.setProperty(prefix + "pidD", pidD);
        p.setProperty(prefix + "minDuty", minDuty);
        p.setProperty(prefix + "maxDuty", maxDuty);
        p.setProperty(prefix + "overboostLimit", overboostLimit);
        p.setProperty(prefix + "modeParam", modeParam);
        p.setProperty(prefix + "openLoopOption", openLoopOption);
        p.setProperty(prefix + "closedLoopOption", closedLoopOption);
        p.setProperty(prefix + "enableParam", enableParam);
        p.setProperty(prefix + "enableOption", enableOption);
        p.setProperty(prefix + "closedLoopExtraParam", closedLoopExtraParam);
        p.setProperty(prefix + "closedLoopExtraOption", closedLoopExtraOption);
        p.setProperty(prefix + "vvtAngleChannel", vvtAngleChannel);
        p.setProperty(prefix + "vvtTargetChannel", vvtTargetChannel);
        p.setProperty(prefix + "fuelLoadChannel", fuelLoadChannel);
        p.setProperty(prefix + "vvtTable", vvtTable);
        p.setProperty(prefix + "vvtXBins", vvtXBins);
        p.setProperty(prefix + "vvtYBins", vvtYBins);
        p.setProperty(prefix + "vvtLoadSource", vvtLoadSource.name());
        p.setProperty(prefix + "vvtPidP", vvtPidP);
        p.setProperty(prefix + "vvtPidI", vvtPidI);
        p.setProperty(prefix + "vvtPidD", vvtPidD);
        p.setProperty(prefix + "advanceChannel", advanceChannel);
        p.setProperty(prefix + "knockChannel", knockChannel);
        p.setProperty(prefix + "knockRetardChannel", knockRetardChannel);
        p.setProperty(prefix + "afrChannel", afrChannel);
        p.setProperty(prefix + "ignLoadChannel", ignLoadChannel);
        p.setProperty(prefix + "sparkTable", sparkTable);
        p.setProperty(prefix + "sparkXBins", sparkXBins);
        p.setProperty(prefix + "sparkYBins", sparkYBins);
        p.setProperty(prefix + "sparkLoadSource", sparkLoadSource.name());
        p.setProperty(prefix + "orientation", orientation.name());
    }

    public void load(Properties p, String prefix) {
        presetName = p.getProperty(prefix + "presetName", presetName);
        configName = p.getProperty(prefix + "configName", configName);
        rpmChannel = p.getProperty(prefix + "rpmChannel", rpmChannel);
        tpsChannel = p.getProperty(prefix + "tpsChannel", tpsChannel);
        mapChannel = p.getProperty(prefix + "mapChannel", mapChannel);
        targetChannel = p.getProperty(prefix + "targetChannel", targetChannel);
        dutyChannel = p.getProperty(prefix + "dutyChannel", dutyChannel);
        cltChannel = p.getProperty(prefix + "cltChannel", cltChannel);
        gearChannel = p.getProperty(prefix + "gearChannel", gearChannel);
        boostCutChannel = p.getProperty(prefix + "boostCutChannel", boostCutChannel);
        timeChannel = p.getProperty(prefix + "timeChannel", timeChannel);
        boostCutMask = parseInt(p.getProperty(prefix + "boostCutMask"), boostCutMask);
        cltFahrenheit = Boolean.parseBoolean(p.getProperty(prefix + "cltFahrenheit", Boolean.toString(cltFahrenheit)));
        targetTable = p.getProperty(prefix + "targetTable", targetTable);
        targetXBins = p.getProperty(prefix + "targetXBins", targetXBins);
        targetYBins = p.getProperty(prefix + "targetYBins", targetYBins);
        targetLoadSource = parseLoad(p.getProperty(prefix + "targetLoadSource"), targetLoadSource);
        biasTable = p.getProperty(prefix + "biasTable", biasTable);
        biasXBins = p.getProperty(prefix + "biasXBins", biasXBins);
        biasYBins = p.getProperty(prefix + "biasYBins", biasYBins);
        openLoopTable = p.getProperty(prefix + "openLoopTable", openLoopTable);
        openLoopXBins = p.getProperty(prefix + "openLoopXBins", openLoopXBins);
        openLoopYBins = p.getProperty(prefix + "openLoopYBins", openLoopYBins);
        openLoopLoadSource = parseLoad(p.getProperty(prefix + "openLoopLoadSource"), openLoopLoadSource);
        pidP = p.getProperty(prefix + "pidP", pidP);
        pidI = p.getProperty(prefix + "pidI", pidI);
        pidD = p.getProperty(prefix + "pidD", pidD);
        minDuty = p.getProperty(prefix + "minDuty", minDuty);
        maxDuty = p.getProperty(prefix + "maxDuty", maxDuty);
        overboostLimit = p.getProperty(prefix + "overboostLimit", overboostLimit);
        modeParam = p.getProperty(prefix + "modeParam", modeParam);
        openLoopOption = p.getProperty(prefix + "openLoopOption", openLoopOption);
        closedLoopOption = p.getProperty(prefix + "closedLoopOption", closedLoopOption);
        enableParam = p.getProperty(prefix + "enableParam", enableParam);
        enableOption = p.getProperty(prefix + "enableOption", enableOption);
        closedLoopExtraParam = p.getProperty(prefix + "closedLoopExtraParam", closedLoopExtraParam);
        closedLoopExtraOption = p.getProperty(prefix + "closedLoopExtraOption", closedLoopExtraOption);
        vvtAngleChannel = p.getProperty(prefix + "vvtAngleChannel", vvtAngleChannel);
        vvtTargetChannel = p.getProperty(prefix + "vvtTargetChannel", vvtTargetChannel);
        fuelLoadChannel = p.getProperty(prefix + "fuelLoadChannel", fuelLoadChannel);
        vvtTable = p.getProperty(prefix + "vvtTable", vvtTable);
        vvtXBins = p.getProperty(prefix + "vvtXBins", vvtXBins);
        vvtYBins = p.getProperty(prefix + "vvtYBins", vvtYBins);
        vvtLoadSource = parseLoad(p.getProperty(prefix + "vvtLoadSource"), vvtLoadSource);
        vvtPidP = p.getProperty(prefix + "vvtPidP", vvtPidP);
        vvtPidI = p.getProperty(prefix + "vvtPidI", vvtPidI);
        vvtPidD = p.getProperty(prefix + "vvtPidD", vvtPidD);
        advanceChannel = p.getProperty(prefix + "advanceChannel", advanceChannel);
        knockChannel = p.getProperty(prefix + "knockChannel", knockChannel);
        knockRetardChannel = p.getProperty(prefix + "knockRetardChannel", knockRetardChannel);
        afrChannel = p.getProperty(prefix + "afrChannel", afrChannel);
        ignLoadChannel = p.getProperty(prefix + "ignLoadChannel", ignLoadChannel);
        sparkTable = p.getProperty(prefix + "sparkTable", sparkTable);
        sparkXBins = p.getProperty(prefix + "sparkXBins", sparkXBins);
        sparkYBins = p.getProperty(prefix + "sparkYBins", sparkYBins);
        sparkLoadSource = parseLoad(p.getProperty(prefix + "sparkLoadSource"), sparkLoadSource);
        try {
            orientation = TableOrientation.valueOf(p.getProperty(prefix + "orientation", orientation.name()));
        } catch (IllegalArgumentException e) {
            orientation = TableOrientation.AUTO;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static LoadSource parseLoad(String s, LoadSource def) {
        try {
            return s == null ? def : LoadSource.valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
