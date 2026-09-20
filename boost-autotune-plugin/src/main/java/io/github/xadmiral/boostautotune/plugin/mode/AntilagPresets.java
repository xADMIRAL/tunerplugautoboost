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
    /** Pops on lift from the over-run feature: a retarded, progressively cut window before the real fuel cut. */
    public static final String POPCORN_OVERRUN = "Popcorn - over-run window (street)";
    /** Pops on lift from the anti-lag itself, always armed, without the throttle opening. */
    public static final String POPCORN_ALS = "Popcorn - anti-lag always on (loud)";

    private AntilagPresets() {
    }

    public static List<String> names() {
        return Arrays.asList(DRIFT_LIGHT, DRIFT_MEDIUM, DRIFT_HARD, POPCORN_OVERRUN, POPCORN_ALS, OFF);
    }

    /** True for the two popcorn presets: no autotune goals, only ECU settings. */
    public static boolean isPopcorn(String name) {
        return POPCORN_OVERRUN.equals(name) || POPCORN_ALS.equals(name);
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
        return create(name, false);
    }

    /** @param driveByWire the ECU runs a DBW throttle: the anti-lag opens the throttle instead of an idle valve */
    public static List<Setting> create(String name, boolean driveByWire) {
        List<Setting> s = new ArrayList<Setting>();
        if (OFF.equals(name)) {
            s.add(new Setting(GROUP_ALS, "als_in_pin", "Off", "anti-lag disabled"));
            s.add(new Setting(GROUP_FLATSHIFT, "launch_opt_on", "Off", "launch / flat shift off"));
            s.add(new Setting(GROUP_OVERRUN, "OvrRunC", "On", "over-run fuel cut back on"));
            return s;
        }
        if (POPCORN_OVERRUN.equals(name)) {
            popcornOverrun(s);
            return s;
        }
        if (POPCORN_ALS.equals(name)) {
            popcornAls(s);
            return s;
        }
        int level = DRIFT_HARD.equals(name) ? 2 : DRIFT_MEDIUM.equals(name) ? 1 : 0;
        Goals g = goals(level == 2 ? DRIFT_HARD : level == 1 ? DRIFT_MEDIUM : DRIFT_LIGHT);
        String maxTps = pick(level, "10", "12", "15");
        String maxRpm = pick(level, "6000", "6500", "7000");
        String pause = pick(level, "4", "3", "2");
        String maxMat = pick(level, "60", "65", "75");
        String iac = pick(level, "90", "100", "140");
        String throttlePct = pick(level, "6", "8", "12");
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
        if (driveByWire) {
            s.add(new Setting(GROUP_ALS, "als_iac_pos", throttlePct, "drive-by-wire: throttle opening % during ALS (autotune refines for the RPM hold)"));
        } else {
            s.add(new Setting(GROUP_ALS, "als_iac_steps", iac, "stepper valve: steps of air during ALS (autotune refines for the RPM hold)"));
        }
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

    /**
     * Over-run popcorn: the fuel cut still happens, but only after a window in which the timing is
     * ramped to a retarded value and the injectors are dropped progressively. Unburnt mixture lights
     * in the manifold for the length of the window, then the normal quiet cut takes over. The ALS is
     * switched off so the two features do not fight. Loudness: window length, retard, VE at 30-45 kPa.
     */
    private static void popcornOverrun(List<Setting> s) {
        s.add(new Setting(GROUP_OVERRUN, "OvrRunC", "On", "the over-run feature does the pops"));
        s.add(new Setting(GROUP_OVERRUN, "fc_rpm", "2500", "pops only above this RPM: keeps them out of the idle return"));
        s.add(new Setting(GROUP_OVERRUN, "fc_kpa", "45", "and below this MAP (above idle MAP, below cruise)"));
        s.add(new Setting(GROUP_OVERRUN, "fc_tps", "1", "throttle / pedal closed"));
        s.add(new Setting(GROUP_OVERRUN, "fc_clt_C", "75", "warm engine only"));
        s.add(new Setting(GROUP_OVERRUN, "fc_delay", "0.3", "seconds after the lift before the window starts"));
        s.add(new Setting(GROUP_OVERRUN, "OvrRunC_progcut", "On", "injectors dropped one by one over the window"));
        s.add(new Setting(GROUP_OVERRUN, "OvrRunC_progign", "On", "timing ramped to the over-run value over the window"));
        s.add(new Setting(GROUP_OVERRUN, "fc_timing", "-20", "timing during the window (absolute; -10 quiet .. -30 loud)"));
        s.add(new Setting(GROUP_OVERRUN, "fc_transition_time", "2.5", "length of the popping window, s (1 .. 5)"));
        s.add(new Setting(GROUP_OVERRUN, "OvrRunC_progret", "On", "fuel comes back progressively"));
        s.add(new Setting(GROUP_OVERRUN, "OvrRunC_retign", "On", "timing comes back over the return time"));
        s.add(new Setting(GROUP_OVERRUN, "fc_trans_time_ret", "0.5", "return time, s (the INI minimum is 0.5)"));
        s.add(new Setting(GROUP_OVERRUN, "fc_rpm_lower", "1500", "fuel is back on by this RPM: well above idle"));
        s.add(new Setting(GROUP_OVERRUN, "fc_ae_time", "0.3", "fuel adder after the cut ends, s"));
        s.add(new Setting(GROUP_OVERRUN, "fc_ae_pct", "5", "size of that adder, % ReqFuel"));
        s.add(new Setting(GROUP_ALS, "als_in_pin", "Off", "anti-lag off: it would fight the over-run window"));
    }

    /**
     * Anti-lag popcorn: the ALS is armed permanently and fires on every lift after a pedal above
     * {@code als_acttps}, but without the idle-valve / throttle air, so it pops instead of holding
     * boost. Spark cut plus extra fuel with retarded timing, two seconds per lift, warm engine only.
     * The over-run cut is switched off: the pops need fuel.
     */
    private static void popcornAls(List<Setting> s) {
        s.add(new Setting(GROUP_ALS, "als_in_pin", "Always ON", "armed all the time (or pick a switch input pin)"));
        s.add(new Setting(GROUP_ALS, "als_acttps", "60", "arm above this TPS %"));
        s.add(new Setting(GROUP_ALS, "als_maxtps", "6", "operate below this TPS %: closed throttle only"));
        s.add(new Setting(GROUP_ALS, "als_minrpm", "2500", "no pops below: keeps them out of the idle return"));
        s.add(new Setting(GROUP_ALS, "als_maxrpm", "6000", ""));
        s.add(new Setting(GROUP_ALS, "als_maxtime", "2", "seconds per lift"));
        s.add(new Setting(GROUP_ALS, "als_pausetime", "1.5", "seconds between activations"));
        s.add(new Setting(GROUP_ALS, "als_minclt_C", "75", "warm engine only"));
        s.add(new Setting(GROUP_ALS, "als_maxclt_C", "105", ""));
        s.add(new Setting(GROUP_ALS, "als_maxmat_C", "70", "ECU's own MAT cut-off"));
        s.add(new Setting(GROUP_ALS, "als_opt_sc", "On", "cyclic spark cut: the bangs"));
        s.add(new Setting(GROUP_ALS, "als_opt_fc", "Off", "no cyclic fuel cut"));
        s.add(new Setting(GROUP_ALS, "als_opt_idle", "Off", "NO extra air: pops only, no boost hold, no glowing turbo"));
        s.add(new Setting(GROUP_ALS, "als_opt_fuel", "On", "extra fuel from als_addfuel"));
        s.add(new Setting(GROUP_ALS, "als_opt_ri", "Off", ""));
        s.add(new Setting(GROUP_ALS, "als_rpms", "2000 3000 4000 5000 6000 7000", "RPM axis of the ALS tables"));
        s.add(new Setting(GROUP_ALS, "als_tpss", "0 4 8 12 16 20", "TPS axis of the ALS tables"));
        s.add(new Setting(GROUP_ALS, "als_timing", "-18", "absolute timing while active, whole table (-15 quiet .. -25 loud)"));
        s.add(new Setting(GROUP_ALS, "als_addfuel", "12", "% fuel added while active"));
        s.add(new Setting(GROUP_ALS, "als_sparkcut", "30", "% of sparks dropped (20 .. 40)"));
        s.add(new Setting(GROUP_ALS, "als_fuelcut", "0", "cyclic fuel cut %"));
        s.add(new Setting(GROUP_OVERRUN, "OvrRunC", "Off", "over-run fuel cut off: the pops need fuel"));
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
