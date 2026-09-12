package io.github.xadmiral.boostautotune.plugin.settings;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Persists the config and the ECU binding as a properties file in the user's home directory. */
public final class SettingsStore {
    private final File file;

    public SettingsStore() {
        this(defaultFile());
    }

    public SettingsStore(File file) {
        this.file = file;
    }

    public static File defaultFile() {
        File dir = new File(System.getProperty("user.home"), ".efianalytics" + File.separator + "BoostAutotune");
        return new File(dir, "settings.properties");
    }

    public File file() {
        return file;
    }

    public void save(AutotuneConfig cfg, EcuBinding binding, Properties uiPrefs) throws IOException {
        Properties p = new Properties();
        configTo(cfg, p, "tune.");
        binding.store(p, "ecu.");
        if (uiPrefs != null) {
            for (String k : uiPrefs.stringPropertyNames()) {
                p.setProperty("ui." + k, uiPrefs.getProperty(k));
            }
        }
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        OutputStream out = new FileOutputStream(file);
        try {
            p.store(out, "Boost Autotune settings");
        } finally {
            out.close();
        }
    }

    public boolean exists() {
        return file.exists();
    }

    public Properties loadRaw() throws IOException {
        Properties p = new Properties();
        if (!file.exists()) {
            return p;
        }
        InputStream in = new FileInputStream(file);
        try {
            p.load(in);
        } finally {
            in.close();
        }
        return p;
    }

    public void load(AutotuneConfig cfg, EcuBinding binding, Properties uiPrefs) throws IOException {
        Properties p = loadRaw();
        configFrom(cfg, p, "tune.");
        binding.load(p, "ecu.");
        if (uiPrefs != null) {
            for (String k : p.stringPropertyNames()) {
                if (k.startsWith("ui.")) {
                    uiPrefs.setProperty(k.substring(3), p.getProperty(k));
                }
            }
        }
    }

    public static void configTo(AutotuneConfig c, Properties p, String pre) {
        StringBuilder stages = new StringBuilder();
        for (double t : c.targetStagesKpa) {
            if (stages.length() > 0) {
                stages.append(", ");
            }
            stages.append(fmt(t));
        }
        p.setProperty(pre + "targetStagesKpa", stages.toString());
        p.setProperty(pre + "wastegateKpa", fmt(c.wastegateKpa));
        p.setProperty(pre + "spoolStartRpm", fmt(c.spoolStartRpm));
        p.setProperty(pre + "fullTargetRpm", fmt(c.fullTargetRpm));
        p.setProperty(pre + "highRpmTaperKpa", fmt(c.highRpmTaperKpa));
        p.setProperty(pre + "highRpmTaperStartRpm", fmt(c.highRpmTaperStartRpm));
        p.setProperty(pre + "wotLoadThreshold", fmt(c.wotLoadThreshold));
        p.setProperty(pre + "scalePartThrottleRows", Boolean.toString(c.scalePartThrottleRows));
        p.setProperty(pre + "partThrottleFloorKpa", fmt(c.partThrottleFloorKpa));
        p.setProperty(pre + "maxBoostKpa", fmt(c.maxBoostKpa));
        p.setProperty(pre + "predictionMarginKpa", fmt(c.predictionMarginKpa));
        p.setProperty(pre + "minCltC", fmt(c.minCltC));
        p.setProperty(pre + "minRpm", fmt(c.minRpm));
        p.setProperty(pre + "maxRpm", fmt(c.maxRpm));
        p.setProperty(pre + "settleDelaySec", fmt(c.settleDelaySec));
        p.setProperty(pre + "maxMapSlopeKpaPerSec", fmt(c.maxMapSlopeKpaPerSec));
        p.setProperty(pre + "maxDutySlopePctPerSec", fmt(c.maxDutySlopePctPerSec));
        p.setProperty(pre + "minSamplesPerCell", Integer.toString(c.minSamplesPerCell));
        p.setProperty(pre + "minPullDurationSec", fmt(c.minPullDurationSec));
        p.setProperty(pre + "pullEndHoldSec", fmt(c.pullEndHoldSec));
        p.setProperty(pre + "plantLagSec", fmt(c.plantLagSec));
        p.setProperty(pre + "characterizeFirst", Boolean.toString(c.characterizeFirst));
        p.setProperty(pre + "characterizeStartDuty", fmt(c.characterizeStartDuty));
        p.setProperty(pre + "characterizeStepPct", fmt(c.characterizeStepPct));
        p.setProperty(pre + "characterizeHeadroomKpa", fmt(c.characterizeHeadroomKpa));
        p.setProperty(pre + "characterizeMinRuns", Integer.toString(c.characterizeMinRuns));
        p.setProperty(pre + "characterizeMaxDuty", fmt(c.characterizeMaxDuty));
        p.setProperty(pre + "biasMaxStepPct", fmt(c.biasMaxStepPct));
        p.setProperty(pre + "biasInitialFillUnlimited", Boolean.toString(c.biasInitialFillUnlimited));
        p.setProperty(pre + "biasMaxExtrapolationPct", fmt(c.biasMaxExtrapolationPct));
        p.setProperty(pre + "defaultGainKpaPerPct", fmt(c.defaultGainKpaPerPct));
        p.setProperty(pre + "observationDecayPerRun", fmt(c.observationDecayPerRun));
        p.setProperty(pre + "closedLoopObservationWeight", fmt(c.closedLoopObservationWeight));
        p.setProperty(pre + "closedLoopLearnWindowKpa", fmt(c.closedLoopLearnWindowKpa));
        p.setProperty(pre + "overshootThresholdKpa", fmt(c.overshootThresholdKpa));
        p.setProperty(pre + "steadyStateTolKpa", fmt(c.steadyStateTolKpa));
        p.setProperty(pre + "oscillationAmplitudeKpa", fmt(c.oscillationAmplitudeKpa));
        p.setProperty(pre + "maxRiseTimeSec", fmt(c.maxRiseTimeSec));
        p.setProperty(pre + "pidStepFraction", fmt(c.pidStepFraction));
        p.setProperty(pre + "tuneP", Boolean.toString(c.tuneP));
        p.setProperty(pre + "tuneI", Boolean.toString(c.tuneI));
        p.setProperty(pre + "tuneD", Boolean.toString(c.tuneD));
        p.setProperty(pre + "allowDerivativeInit", Boolean.toString(c.allowDerivativeInit));
        p.setProperty(pre + "softStartClosedLoop", Boolean.toString(c.softStartClosedLoop));
        p.setProperty(pre + "trimUnreachableTargets", Boolean.toString(c.trimUnreachableTargets));
        p.setProperty(pre + "runsRequiredPerStage", Integer.toString(c.runsRequiredPerStage));
        p.setProperty(pre + "biasSettledPct", fmt(c.biasSettledPct));
        p.setProperty(pre + "autoEndRunIdleSec", fmt(c.autoEndRunIdleSec));
    }

    public static void configFrom(AutotuneConfig c, Properties p, String pre) {
        String stages = p.getProperty(pre + "targetStagesKpa");
        if (stages != null) {
            List<Double> list = parseStages(stages);
            if (!list.isEmpty()) {
                c.targetStagesKpa = list;
            }
        }
        c.wastegateKpa = d(p, pre + "wastegateKpa", c.wastegateKpa);
        c.spoolStartRpm = d(p, pre + "spoolStartRpm", c.spoolStartRpm);
        c.fullTargetRpm = d(p, pre + "fullTargetRpm", c.fullTargetRpm);
        c.highRpmTaperKpa = d(p, pre + "highRpmTaperKpa", c.highRpmTaperKpa);
        c.highRpmTaperStartRpm = d(p, pre + "highRpmTaperStartRpm", c.highRpmTaperStartRpm);
        c.wotLoadThreshold = d(p, pre + "wotLoadThreshold", c.wotLoadThreshold);
        c.scalePartThrottleRows = bool(p, pre + "scalePartThrottleRows", c.scalePartThrottleRows);
        c.partThrottleFloorKpa = d(p, pre + "partThrottleFloorKpa", c.partThrottleFloorKpa);
        c.maxBoostKpa = d(p, pre + "maxBoostKpa", c.maxBoostKpa);
        c.predictionMarginKpa = d(p, pre + "predictionMarginKpa", c.predictionMarginKpa);
        c.minCltC = d(p, pre + "minCltC", c.minCltC);
        c.minRpm = d(p, pre + "minRpm", c.minRpm);
        c.maxRpm = d(p, pre + "maxRpm", c.maxRpm);
        c.settleDelaySec = d(p, pre + "settleDelaySec", c.settleDelaySec);
        c.maxMapSlopeKpaPerSec = d(p, pre + "maxMapSlopeKpaPerSec", c.maxMapSlopeKpaPerSec);
        c.maxDutySlopePctPerSec = d(p, pre + "maxDutySlopePctPerSec", c.maxDutySlopePctPerSec);
        c.minSamplesPerCell = (int) d(p, pre + "minSamplesPerCell", c.minSamplesPerCell);
        c.minPullDurationSec = d(p, pre + "minPullDurationSec", c.minPullDurationSec);
        c.pullEndHoldSec = d(p, pre + "pullEndHoldSec", c.pullEndHoldSec);
        c.plantLagSec = d(p, pre + "plantLagSec", c.plantLagSec);
        c.characterizeFirst = bool(p, pre + "characterizeFirst", c.characterizeFirst);
        c.characterizeStartDuty = d(p, pre + "characterizeStartDuty", c.characterizeStartDuty);
        c.characterizeStepPct = d(p, pre + "characterizeStepPct", c.characterizeStepPct);
        c.characterizeHeadroomKpa = d(p, pre + "characterizeHeadroomKpa", c.characterizeHeadroomKpa);
        c.characterizeMinRuns = (int) d(p, pre + "characterizeMinRuns", c.characterizeMinRuns);
        c.characterizeMaxDuty = d(p, pre + "characterizeMaxDuty", c.characterizeMaxDuty);
        c.biasMaxStepPct = d(p, pre + "biasMaxStepPct", c.biasMaxStepPct);
        c.biasInitialFillUnlimited = bool(p, pre + "biasInitialFillUnlimited", c.biasInitialFillUnlimited);
        c.biasMaxExtrapolationPct = d(p, pre + "biasMaxExtrapolationPct", c.biasMaxExtrapolationPct);
        c.defaultGainKpaPerPct = d(p, pre + "defaultGainKpaPerPct", c.defaultGainKpaPerPct);
        c.observationDecayPerRun = d(p, pre + "observationDecayPerRun", c.observationDecayPerRun);
        c.closedLoopObservationWeight = d(p, pre + "closedLoopObservationWeight", c.closedLoopObservationWeight);
        c.closedLoopLearnWindowKpa = d(p, pre + "closedLoopLearnWindowKpa", c.closedLoopLearnWindowKpa);
        c.overshootThresholdKpa = d(p, pre + "overshootThresholdKpa", c.overshootThresholdKpa);
        c.steadyStateTolKpa = d(p, pre + "steadyStateTolKpa", c.steadyStateTolKpa);
        c.oscillationAmplitudeKpa = d(p, pre + "oscillationAmplitudeKpa", c.oscillationAmplitudeKpa);
        c.maxRiseTimeSec = d(p, pre + "maxRiseTimeSec", c.maxRiseTimeSec);
        c.pidStepFraction = d(p, pre + "pidStepFraction", c.pidStepFraction);
        c.tuneP = bool(p, pre + "tuneP", c.tuneP);
        c.tuneI = bool(p, pre + "tuneI", c.tuneI);
        c.tuneD = bool(p, pre + "tuneD", c.tuneD);
        c.allowDerivativeInit = bool(p, pre + "allowDerivativeInit", c.allowDerivativeInit);
        c.softStartClosedLoop = bool(p, pre + "softStartClosedLoop", c.softStartClosedLoop);
        c.trimUnreachableTargets = bool(p, pre + "trimUnreachableTargets", c.trimUnreachableTargets);
        c.runsRequiredPerStage = (int) d(p, pre + "runsRequiredPerStage", c.runsRequiredPerStage);
        c.biasSettledPct = d(p, pre + "biasSettledPct", c.biasSettledPct);
        c.autoEndRunIdleSec = d(p, pre + "autoEndRunIdleSec", c.autoEndRunIdleSec);
    }

    public static List<Double> parseStages(String text) {
        List<Double> out = new ArrayList<Double>();
        for (String part : text.split("[,;\\s]+")) {
            if (part.trim().isEmpty()) {
                continue;
            }
            try {
                out.add(Double.parseDouble(part.trim().replace(',', '.')));
            } catch (NumberFormatException e) {
                // skip garbage
            }
        }
        return out;
    }

    public static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) {
            return Long.toString((long) v);
        }
        return String.format(Locale.US, "%.4f", v);
    }

    private static double d(Properties p, String k, double def) {
        String s = p.getProperty(k);
        if (s == null) {
            return def;
        }
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean bool(Properties p, String k, boolean def) {
        String s = p.getProperty(k);
        return s == null ? def : Boolean.parseBoolean(s.trim());
    }
}
