package io.github.xadmiral.boostautotune.plugin.mode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Ready-made anti-lag / drift setups for MS3-based firmware (Stealth PCM). Every entry is a
 * parameter name from the INI and the value to write, grouped so the user can leave the flat
 * shift or the over-run part alone. Values are starting points for a 1JZ-GTE drift car, not gospel.
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

    public static final String OFF = "Off (street): anti-lag off, over-run cut on";
    public static final String DRIFT_MILD = "Drift - mild (learn the car)";
    public static final String DRIFT_AGGRESSIVE = "Drift - aggressive (track)";

    private AntilagPresets() {
    }

    public static List<String> names() {
        return Arrays.asList(OFF, DRIFT_MILD, DRIFT_AGGRESSIVE);
    }

    public static List<Setting> create(String name) {
        List<Setting> s = new ArrayList<Setting>();
        boolean aggressive = DRIFT_AGGRESSIVE.equals(name);
        if (OFF.equals(name)) {
            s.add(new Setting(GROUP_ALS, "als_in_pin", "Off", "anti-lag disabled"));
            s.add(new Setting(GROUP_FLATSHIFT, "launch_opt_on", "Off", "launch / flat shift off"));
            s.add(new Setting(GROUP_OVERRUN, "OvrRunC", "On", "over-run fuel cut back on"));
            return s;
        }
        // ---- anti-lag ----
        s.add(new Setting(GROUP_ALS, "als_in_pin", "Always ON", "or pick the switch input pin"));
        s.add(new Setting(GROUP_ALS, "als_acttps", "60", "arm above this TPS %"));
        s.add(new Setting(GROUP_ALS, "als_maxtps", aggressive ? "15" : "12", "operate below this TPS %"));
        s.add(new Setting(GROUP_ALS, "als_minrpm", "2500", ""));
        s.add(new Setting(GROUP_ALS, "als_maxrpm", aggressive ? "7000" : "6500", ""));
        s.add(new Setting(GROUP_ALS, "als_maxtime", aggressive ? "5" : "3", "seconds per activation"));
        s.add(new Setting(GROUP_ALS, "als_pausetime", aggressive ? "2" : "3", "seconds between activations"));
        s.add(new Setting(GROUP_ALS, "als_minclt_C", "70", ""));
        s.add(new Setting(GROUP_ALS, "als_maxclt_C", "105", ""));
        s.add(new Setting(GROUP_ALS, "als_maxmat_C", aggressive ? "75" : "65", "ECU's own MAT cut-off"));
        s.add(new Setting(GROUP_ALS, "als_opt_sc", "On", "cyclic spark cut"));
        s.add(new Setting(GROUP_ALS, "als_opt_fc", "Off", "cyclic fuel cut: sequential only, unsafe with staging"));
        s.add(new Setting(GROUP_ALS, "als_opt_idle", "On", "extra air through the idle valve"));
        s.add(new Setting(GROUP_ALS, "als_opt_ri", "Off", "roving idle fuel cut"));
        s.add(new Setting(GROUP_ALS, "als_iac_steps", aggressive ? "140" : "100", "stepper valve: steps of air during ALS (idle open ~140)"));
        s.add(new Setting(GROUP_ALS, "als_rpms", "2000 3000 4000 5000 6000 7000", "RPM axis of the ALS tables"));
        s.add(new Setting(GROUP_ALS, "als_tpss", "0 4 8 12 16 20", "TPS axis of the ALS tables"));
        s.add(new Setting(GROUP_ALS, "als_timing", aggressive ? "-22" : "-16", "absolute timing during ALS, whole table (autotune refines per RPM)"));
        s.add(new Setting(GROUP_ALS, "als_addfuel", aggressive ? "25" : "15", "% fuel added during ALS"));
        s.add(new Setting(GROUP_ALS, "als_sparkcut", aggressive ? "50" : "30", "% of sparks dropped"));
        s.add(new Setting(GROUP_ALS, "als_fuelcut", "0", "cyclic fuel cut %"));
        // ---- flat shift ----
        s.add(new Setting(GROUP_FLATSHIFT, "launch_opt_on", "Launch/Flatshift", "needs the clutch switch input"));
        s.add(new Setting(GROUP_FLATSHIFT, "launchlimopt", "Spark Cut", ""));
        s.add(new Setting(GROUP_FLATSHIFT, "flats_arm", "3500", "flat shift arming RPM"));
        s.add(new Setting(GROUP_FLATSHIFT, "flats_hrd", aggressive ? "7000" : "6500", "flat shift hard limit"));
        s.add(new Setting(GROUP_FLATSHIFT, "flats_deg", "-5", "retard to (absolute) while the clutch is in"));
        // ---- over-run ----
        s.add(new Setting(GROUP_OVERRUN, "OvrRunC", "Off", "over-run fuel cut fights the anti-lag; off while drifting"));
        return s;
    }
}
