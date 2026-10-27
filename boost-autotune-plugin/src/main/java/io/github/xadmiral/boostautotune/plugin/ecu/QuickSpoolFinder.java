package io.github.xadmiral.boostautotune.plugin.ecu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Finds the ECU's quick spool parameters (valve held shut until boost nears the target) by name.
 * Stealth PCM and the MS3 builds name them differently, so the switch is the bits parameter whose
 * name speaks of spool and whose options read off / on, and the hand-over is the kPa scalar next
 * to it. Everything else with such a name (RPM or TPS windows, a duty) is listed but never written.
 */
public final class QuickSpoolFinder {
    private QuickSpoolFinder() {
    }

    /** What was found; empty names when nothing was. */
    public static final class Result {
        public String switchParam = "";
        public String onOption = "";
        public String kpaParam = "";
        public String kpaUnits = "";
        /** Other quick spool parameters, for the log. */
        public final List<String> related = new ArrayList<String>();

        public boolean found() {
            return !switchParam.isEmpty();
        }

        public String describe() {
            if (!found()) {
                return "no quick spool parameter in this ECU definition";
            }
            StringBuilder sb = new StringBuilder("switch " + switchParam + " (on = '" + onOption + "')");
            sb.append(kpaParam.isEmpty() ? ", no hand-over parameter" : ", hand-over " + kpaParam
                    + (kpaUnits.isEmpty() ? "" : " [" + kpaUnits + "]"));
            if (!related.isEmpty()) {
                sb.append(", also ").append(related);
            }
            return sb.toString();
        }
    }

    /** True for a parameter name that belongs to quick spool (and not to anti-lag or launch). */
    public static boolean isQuickSpoolName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("als") || n.contains("antilag") || n.contains("anti_lag") || n.contains("launch") || n.contains("respool")) {
            return false;
        }
        if (n.contains("spool")) {
            return true;
        }
        if (n.contains("boost")) {
            for (String t : n.split("[_\\-.]")) {
                if (t.equals("qs")) {
                    return true; // boost_ctl_qs_on, boost_qs_kpa
                }
            }
        }
        return false;
    }

    public static Result find(EcuPort port, String config) {
        Result r = new Result();
        if (port == null) {
            return r;
        }
        List<String> names = new ArrayList<String>();
        for (String n : port.parameterNames(config)) {
            if (isQuickSpoolName(n)) {
                names.add(n);
            }
        }
        // the plain name first: boost_ctl_quickspool before boost_ctl_quickspool_mode
        Collections.sort(names, new Comparator<String>() {
            public int compare(String a, String b) {
                return a.length() != b.length() ? a.length() - b.length() : a.compareTo(b);
            }
        });
        int bestKpa = 0;
        for (String n : names) {
            EcuPort.ParamInfo info;
            try {
                info = port.parameterInfo(config, n);
            } catch (EcuException e) {
                continue;
            }
            if (info == null) {
                continue;
            }
            if (info.options != null && !info.options.isEmpty()) {
                String off = OptionMatcher.match(info.options, "Off");
                String on = onOption(info.options, off);
                if (r.switchParam.isEmpty() && off != null && on != null) {
                    r.switchParam = n;
                    r.onOption = OptionMatcher.plain(on);
                } else {
                    r.related.add(n);
                }
                continue;
            }
            boolean scalar = info.shapeWidth <= 1 && info.shapeHeight <= 1;
            int score = scalar ? kpaScore(n, info.units) : 0;
            if (score > bestKpa) {
                if (!r.kpaParam.isEmpty()) {
                    r.related.add(r.kpaParam);
                }
                r.kpaParam = n;
                r.kpaUnits = info.units == null ? "" : info.units.trim();
                bestKpa = score;
            } else {
                r.related.add(n);
            }
        }
        if (!r.found()) {
            r.kpaParam = "";
            r.kpaUnits = "";
        }
        return r;
    }

    /** The option that switches it on: "On" (or its wording), otherwise the one that is not off. */
    static String onOption(List<String> options, String off) {
        String on = OptionMatcher.match(options, "On");
        if (on != null && (off == null || !OptionMatcher.plain(on).equals(OptionMatcher.plain(off)))) {
            return on;
        }
        for (String o : options) {
            if (o != null && !OptionMatcher.plain(o).equalsIgnoreCase("invalid")
                    && (off == null || !OptionMatcher.plain(o).equals(OptionMatcher.plain(off)))) {
                return o;
            }
        }
        return null;
    }

    /** 2 = the units say kPa, 1 = no units but the name speaks of a pressure, 0 = not a hand-over. */
    static int kpaScore(String name, String units) {
        String u = units == null ? "" : units.trim().toLowerCase(Locale.ROOT);
        if (u.contains("kpa")) {
            return 2;
        }
        if (!u.isEmpty()) {
            return 0; // rpm, %, s, psi...: something else, or a unit the plugin does not convert
        }
        String n = name.toLowerCase(Locale.ROOT);
        for (String t : new String[]{"rpm", "tps", "duty", "dc", "time", "sec", "gear", "vss", "pct", "on", "en"}) {
            for (String part : n.split("[_\\-.]")) {
                if (part.equals(t)) {
                    return 0;
                }
            }
        }
        for (String t : new String[]{"kpa", "thresh", "thr", "delta", "diff", "press", "lim", "limit", "targ", "target", "margin", "below", "offset", "end"}) {
            if (n.contains(t)) {
                return 1;
            }
        }
        return 0;
    }
}
