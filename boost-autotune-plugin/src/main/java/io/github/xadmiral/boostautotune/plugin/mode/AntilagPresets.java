package io.github.xadmiral.boostautotune.plugin.mode;

import io.github.xadmiral.boostautotune.core.als.AlsConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Ready-made drift modes for MS3-based firmware (Stealth PCM): light, medium and hard, plus an
 * "off" preset for the street. Every entry is a parameter name from the INI and the value to
 * write, grouped so the user can leave the flat shift or the over-run part alone. Each mode also
 * carries the three autotune goals (boost off throttle, RPM to hold, seconds to hold it).
 * Values are starting points for a 1JZ-GTE drift car, not gospel.
 */
public final class AntilagPresets {
    public static final String GROUP_ALS = "Anti-lag";
    public static final String GROUP_FLATSHIFT = "Flat shift";
    public static final String GROUP_OVERRUN = "Over-run";

    public static final class Setting {
        public final String group;
        public final String param;
        public final String value;
        public final String note;

        public Setting(String group, String param, String value, String note) {
            this.group = group;
            this.param = param;
            this.value = value;
            this.note = note;
        }
    }

    /** The three autotune goals of a mode. */
    public static final class Goals {
        public final double targetKpa;
        public final double holdRpm;
        public final double holdSec;

        Goals(double targetKpa, double holdRpm, double holdSec) {
            this.targetKpa = targetKpa;
            this.holdRpm = holdRpm;
            this.holdSec = holdSec;
        }

        public void applyTo(AlsConfig cfg) {
            cfg.targetKpa = targetKpa;
            cfg.holdRpm = holdRpm;
            cfg.holdSec = holdSec;
        }
    }

    public static final String OFF = "Off (street): anti-lag off, over-run cut on";
    public static final String DRIFT_LIGHT = "Drift - light";
    public static final String DRIFT_MEDIUM = "Drift - medium";
    public static final String DRIFT_HARD = "Drift - hard";

    private AntilagPresets() {
    }

    public static List<String> names() {
        return Arrays.asList(DRIFT_LIGHT, DRIFT_MEDIUM, DRIFT_HARD, OFF);
    }

    /** Autotune goals of a drift mode; null for the "off" preset. */
    public static Goals goals(String name) {
        if (DRIFT_LIGHT.equals(name)) {
            return new Goals(120, 2500, 2);
        }
        if (DRIFT_MEDIUM.equals(name)) {
            return new Goals(135, 3000, 3);
        }
        if (DRIFT_HARD.equals(name)) {
            return new Goals(150, 3500, 5);
        }
        return null;
    }

    public static List<Setting> create(String name) {
        List<Setting> s = new ArrayList<Setting>();
        if (OFF.equals(name)) {
            s.add(new Setting(GROUP_ALS, "als_in_pin", "Off", "anti-lag disabled"));
            s.add(new Setting(GROUP_FLATSHIFT, "launch_opt_on", "Off", "launch / flat shift off"));
            s.add(new Setting(GROUP_OVERRUN, "OvrRunC", "On", "over-run fuel cut back on"));
            return s;
        }
        int level = DRIFT_HARD.equals(name) ? 2 : DRIFT_MEDIUM.equals(name) ? 1 : 0;
        Goals g = goals(level == 2 ? DRIFT_HARD : level == 1 ? DRIFT_MEDIUM : DRIFT_LIGHT);
        String maxTps = pick(level, "10", "12", "15");
        String maxRpm = pick(level, "6000", "6500", "7000");
        String pause = pick(level, "4", "3", "2");
        String maxMat = pick(level, "60", "65", "75");
        String iac = pick(level, "90", "100", "140");
        String timing = pick(level, "-12", "-16", "-22");
        String addFuel = pick(level, "10", "15", "25");
        String sparkCut = pick(level, "25", "30", "50");
        String flatsHard = pick(level, "6000", "6500", "7000");
        // ---- anti-lag ----
        s.add(new Setting(GROUP_ALS, "als_in_pin", "Always ON", "or pick the switch input pin"));
        s.add(new Setting(GROUP_ALS, "als_acttps", "60", "arm above this TPS %"));
        s.add(new Setting(GROUP_ALS, "als_maxtps", maxTps, "operate below this TPS %"));
        s.add(new Setting(GROUP_ALS, "als_minrpm", fmt(cfgMinRpm(g)), "cut-off RPM: below the hold RPM (autotune rewrites it)"));
        s.add(new Setting(GROUP_ALS, "als_maxrpm", maxRpm, ""));
        s.add(new Setting(GROUP_ALS, "als_maxtime", fmt(g.holdSec), "seconds per activation = hold time (autotune rewrites it)"));
        s.add(new Setting(GROUP_ALS, "als_pausetime", pause, "seconds between activations"));
        s.add(new Setting(GROUP_ALS, "als_minclt_C", "70", ""));
        s.add(new Setting(GROUP_ALS, "als_maxclt_C", "105", ""));
        s.add(new Setting(GROUP_ALS, "als_maxmat_C", maxMat, "ECU's own MAT cut-off"));
        s.add(new Setting(GROUP_ALS, "als_opt_sc", "On", "cyclic spark cut"));
        s.add(new Setting(GROUP_ALS, "als_opt_fc", "Off", "cyclic fuel cut: sequential only, unsafe with staging"));
        s.add(new Setting(GROUP_ALS, "als_opt_idle", "On", "extra air through the idle valve"));
        s.add(new Setting(GROUP_ALS, "als_opt_ri", "Off", "roving idle fuel cut"));
        s.add(new Setting(GROUP_ALS, "als_iac_steps", iac, "stepper valve: steps of air during ALS (autotune refines for the RPM hold)"));
        s.add(new Setting(GROUP_ALS, "als_rpms", "2000 3000 4000 5000 6000 7000", "RPM axis of the ALS tables"));
        s.add(new Setting(GROUP_ALS, "als_tpss", "0 4 8 12 16 20", "TPS axis of the ALS tables"));
        s.add(new Setting(GROUP_ALS, "als_timing", timing, "absolute timing during ALS, whole table (autotune refines per RPM)"));
        s.add(new Setting(GROUP_ALS, "als_addfuel", addFuel, "% fuel added during ALS"));
        s.add(new Setting(GROUP_ALS, "als_sparkcut", sparkCut, "% of sparks dropped"));
        s.add(new Setting(GROUP_ALS, "als_fuelcut", "0", "cyclic fuel cut %"));
        // ---- flat shift ----
        s.add(new Setting(GROUP_FLATSHIFT, "launch_opt_on", "Launch/Flatshift", "needs the clutch switch input"));
        s.add(new Setting(GROUP_FLATSHIFT, "launchlimopt", "Spark Cut", ""));
        s.add(new Setting(GROUP_FLATSHIFT, "flats_arm", "3500", "flat shift arming RPM"));
        s.add(new Setting(GROUP_FLATSHIFT, "flats_hrd", flatsHard, "flat shift hard limit"));
        s.add(new Setting(GROUP_FLATSHIFT, "flats_deg", "-5", "retard to (absolute) while the clutch is in"));
        // ---- over-run ----
        s.add(new Setting(GROUP_OVERRUN, "OvrRunC", "Off", "over-run fuel cut fights the anti-lag; off while drifting"));
        return s;
    }

    private static double cfgMinRpm(Goals g) {
        AlsConfig c = new AlsConfig();
        g.applyTo(c);
        return c.ecuMinRpm();
    }

    private static String pick(int level, String light, String medium, String hard) {
        return level == 2 ? hard : level == 1 ? medium : light;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(java.util.Locale.US, "%.1f", v);
    }
}
