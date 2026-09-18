package io.github.xadmiral.boostautotune.plugin.ecu;

import io.github.xadmiral.boostautotune.core.model.LoadSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Known firmware layouts. Names come from the respective INI files. */
public final class EcuPresets {
    public static final String STEALTH_PCM = "Stealth PCM / MS3 (DM00.22f)";
    public static final String MS3 = "MegaSquirt MS3 1.5+";
    public static final String SPEEDUINO = "Speeduino 2023+";
    public static final String RUSEFI = "rusEFI";
    public static final String CUSTOM = "Custom";

    private EcuPresets() {
    }

    public static List<String> names() {
        return Arrays.asList(STEALTH_PCM, MS3, SPEEDUINO, RUSEFI, CUSTOM);
    }

    public static EcuBinding create(String name) {
        EcuBinding b = new EcuBinding();
        b.presetName = name;
        if (STEALTH_PCM.equals(name) || MS3.equals(name)) {
            b.rpmChannel = "rpm";
            b.tpsChannel = "tps";
            b.mapChannel = "map";
            b.targetChannel = "boost_targ_1";
            b.dutyChannel = "boostduty";
            b.cltChannel = "coolant";
            b.gearChannel = "gear";
            b.boostCutChannel = "status2";
            b.boostCutMask = 64;
            b.targetTable = "boost_ctl_load_targets";
            b.targetXBins = "boost_ctl_loadtarg_rpm_bins";
            b.targetYBins = "boost_ctl_loadtarg_tps_bins";
            b.targetLoadSource = LoadSource.TPS;
            b.biasTable = "boost_ctl_cl_pwm_targs1";
            b.biasXBins = "boost_ctl_cl_pwm_rpms1";
            b.biasYBins = "boost_ctl_cl_pwm_targboosts1";
            b.openLoopTable = "boost_ctl_pwm_targets";
            b.openLoopXBins = "boost_ctl_pwmtarg_rpm_bins";
            b.openLoopYBins = "boost_ctl_pwmtarg_tps_bins";
            b.openLoopLoadSource = LoadSource.TPS;
            b.pidP = "boost_ctl_Kp";
            b.pidI = "boost_ctl_Ki";
            b.pidD = "boost_ctl_Kd";
            b.minDuty = "boost_ctl_closeduty";
            b.maxDuty = "boost_ctl_openduty";
            b.overboostLimit = "OverBoostKpa";
            b.closedLoopWindowParam = "boost_ctl_lowerlimit";
            b.modeParam = "boost_ctl_settings_cl";
            b.openLoopOption = "Open-loop";
            b.closedLoopOption = "Closed-loop";
            b.enableParam = "boost_ctl_settings_on";
            b.enableOption = "On";
            b.closedLoopExtraParam = "boost_ctl_flags";
            b.closedLoopExtraOption = "Advanced Mode";
            b.vvtAngleChannel = "vvt_ang1";
            b.vvtTargetChannel = "vvt_target1";
            b.fuelLoadChannel = "fuelload";
            b.vvtTable = "vvt_timing1";
            b.vvtXBins = "vvt_timing_rpm";
            b.vvtYBins = "vvt_timing_load";
            b.vvtLoadSource = LoadSource.FUEL_LOAD;
            b.vvtPidP = "vvt_ctl_Kp";
            b.vvtPidI = "vvt_ctl_Ki";
            b.vvtPidD = "vvt_ctl_Kd";
            b.advanceChannel = "advance";
            b.knockChannel = "knock";
            b.knockRetardChannel = "knockRetard";
            b.afrChannel = "afr1";
            b.ignLoadChannel = "ignload";
            b.sparkTable = "advanceTable1";
            b.sparkXBins = "srpm_table1";
            b.sparkYBins = "smap_table1";
            b.sparkLoadSource = LoadSource.IGN_LOAD;
            b.alsActiveChannel = "status10";
            b.alsActiveMask = 128;
            b.matChannel = "mat";
            b.alsTimingTable = "als_timing";
            b.alsXBins = "als_rpms";
            b.alsYBins = "als_tpss";
            b.alsAirStepsParam = "als_iac_steps";
            b.alsAirDutyParam = "als_iac_duty";
            b.idleTypeParam = "IdleCtl";
            b.idleTypeStepperOption = "Stepper valve (6 wire)";
            b.alsEnableParam = "als_in_pin";
            b.alsDisableOption = "Off";
            b.alsMaxTimeParam = "als_maxtime";
            b.alsMinRpmParam = "als_minrpm";
            b.alsAirDbwParam = "als_iac_pos";
            b.dbwEnableParam = "drivebywire_opt_on";
            b.dbwEnableOption = "On";
            b.alsMaxTpsParam = "als_maxtps";
            b.knockCylChannelPrefix = "knock_cyl";
            b.cylindersParam = "nCylinders";
            b.knockThresholdTable = "knock_thresholds";
            b.knockRpmBins = "knock_rpms";
            b.knockGainPrefix = "knock_gain";
            b.knockPerCylParam = "knock_conf_percyl";
            b.knockPerCylOnOption = "On";
            b.knockControlParam = "knk_option";
            b.knockControlOffOption = "Disabled";
            b.knockMinLoadParam = "knk_minload";
            b.knockLoRpmParam = "knk_lorpm";
            b.knockHiRpmParam = "knk_hirpm";
        } else if (SPEEDUINO.equals(name)) {
            b.rpmChannel = "rpm";
            b.tpsChannel = "tps";
            b.mapChannel = "map";
            b.targetChannel = "boostTarget";
            b.dutyChannel = "boostDuty";
            b.cltChannel = "coolant";
            b.gearChannel = "gear";
            b.boostCutChannel = "boostCutOut";
            b.boostCutMask = 0;
            b.targetTable = "boostTable";
            b.targetXBins = "rpmBinsBoost";
            b.targetYBins = "tpsBinsBoost";
            b.targetLoadSource = LoadSource.TPS;
            b.biasTable = "boostTableDutyLookup";
            b.biasXBins = "rpmBinsDutyLookup";
            b.biasYBins = "loadBinsDutyLookup";
            // Speeduino's open-loop duty lives in boostTable itself (same table as the targets):
            // characterization would overwrite the targets, so it is disabled by default.
            b.openLoopTable = "";
            b.openLoopXBins = "";
            b.openLoopYBins = "";
            b.pidP = "boostKP";
            b.pidI = "boostKI";
            b.pidD = "boostKD";
            b.minDuty = "boostMinDuty";
            b.maxDuty = "boostMaxDuty";
            b.overboostLimit = "boostLimit";
            b.modeParam = "boostType";
            b.openLoopOption = "Open Loop";
            b.closedLoopOption = "Closed Loop";
            b.enableParam = "boostEnabled";
            b.enableOption = "On";
            b.advanceChannel = "advance";
            b.knockRetardChannel = "";
            b.afrChannel = "afr";
            b.sparkTable = "advTable1";
            b.sparkXBins = "rpmBins2";
            b.sparkYBins = "mapBins2";
            b.sparkLoadSource = LoadSource.MAP;
            b.vvtAngleChannel = "vvt1Angle";
            b.vvtTargetChannel = "vvt1Target";
            b.vvtTable = "vvtTable";
            b.vvtXBins = "rpmBinsVVT";
            b.vvtYBins = "loadBinsVVT";
            b.vvtLoadSource = LoadSource.MAP;
            b.vvtPidP = "vvtCLKP";
            b.vvtPidI = "vvtCLKI";
            b.vvtPidD = "vvtCLKD";
        } else if (RUSEFI.equals(name)) {
            b.rpmChannel = "RPMValue";
            b.tpsChannel = "TPSValue";
            b.mapChannel = "MAPValue";
            b.targetChannel = "boostControlTarget";
            b.dutyChannel = "boostOutput";
            b.cltChannel = "coolant";
            b.gearChannel = "detectedGear";
            b.boostCutChannel = "";
            b.targetTable = "boostTableClosedLoop";
            b.targetXBins = "boostRpmBins";
            b.targetYBins = "boostClosedLoopLoadBins";
            b.targetLoadSource = LoadSource.TPS;
            // rusEFI adds the open-loop table to the PID output: there is no separate bias table.
            b.biasTable = "";
            b.biasXBins = "";
            b.biasYBins = "";
            b.openLoopTable = "boostTableOpenLoop";
            b.openLoopXBins = "boostRpmBins";
            b.openLoopYBins = "boostOpenLoopLoadBins";
            b.openLoopLoadSource = LoadSource.TPS;
            b.pidP = "boostPid_pFactor";
            b.pidI = "boostPid_iFactor";
            b.pidD = "boostPid_dFactor";
            b.minDuty = "boostPid_minValue";
            b.maxDuty = "boostPid_maxValue";
            b.overboostLimit = "boostCutPressure";
            b.modeParam = "boostType";
            b.openLoopOption = "Open Loop";
            b.closedLoopOption = "Open + Closed Loop";
            b.enableParam = "isBoostControlEnabled";
            b.enableOption = "enabled";
            b.advanceChannel = "ignitionAdvance";
            b.knockRetardChannel = "knockRetard";
            b.afrChannel = "AFRValue";
            b.sparkTable = "ignitionTable";
            b.sparkXBins = "ignitionRpmBins";
            b.sparkYBins = "ignitionLoadBins";
            b.sparkLoadSource = LoadSource.MAP;
            b.vvtAngleChannel = "vvtPositionB1I";
            b.vvtTargetChannel = "vvtTargetB1I";
            b.vvtTable = "vvtTable1";
            b.vvtXBins = "vvtTable1RpmBins";
            b.vvtYBins = "vvtTable1LoadBins";
            b.vvtLoadSource = LoadSource.MAP;
            b.vvtPidP = "auxPid1_pFactor";
            b.vvtPidI = "auxPid1_iFactor";
            b.vvtPidD = "auxPid1_dFactor";
        }
        return b;
    }

    /** Guesses the best preset from the signature and the names the ECU actually exposes. */
    public static String detect(String signature, List<String> params, List<String> channels) {
        String sig = signature == null ? "" : signature.toLowerCase(Locale.US);
        if (sig.contains("dm00") || sig.contains("stealth")) {
            return STEALTH_PCM;
        }
        if (sig.startsWith("ms3")) {
            return MS3;
        }
        if (sig.contains("speeduino")) {
            return SPEEDUINO;
        }
        if (sig.contains("rusefi")) {
            return RUSEFI;
        }
        String best = CUSTOM;
        int bestScore = 0;
        for (String name : names()) {
            if (CUSTOM.equals(name)) {
                continue;
            }
            int score = score(create(name), params, channels);
            if (score > bestScore) {
                bestScore = score;
                best = name;
            }
        }
        return bestScore >= 4 ? best : CUSTOM;
    }

    static int score(EcuBinding b, List<String> params, List<String> channels) {
        int s = 0;
        for (String n : new String[]{b.targetTable, b.biasTable, b.openLoopTable, b.pidP, b.pidI, b.modeParam}) {
            if (b.has(n) && params != null && params.contains(n)) {
                s++;
            }
        }
        for (String n : new String[]{b.targetChannel, b.dutyChannel, b.mapChannel}) {
            if (b.has(n) && channels != null && channels.contains(n)) {
                s++;
            }
        }
        return s;
    }

    /** Fuzzy fallback: find a name containing all the given fragments (case-insensitive). */
    public static String find(List<String> names, String... fragments) {
        List<String> hits = new ArrayList<String>();
        for (String n : names) {
            String l = n.toLowerCase(Locale.US);
            boolean ok = true;
            for (String f : fragments) {
                if (!l.contains(f.toLowerCase(Locale.US))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                hits.add(n);
            }
        }
        if (hits.isEmpty()) {
            return "";
        }
        // shortest match is usually the plain one (e.g. "map" rather than "map_vacboost")
        String best = hits.get(0);
        for (String h : hits) {
            if (h.length() < best.length()) {
                best = h;
            }
        }
        return best;
    }
}
