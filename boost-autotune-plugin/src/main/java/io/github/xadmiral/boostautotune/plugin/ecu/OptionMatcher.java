package io.github.xadmiral.boostautotune.plugin.ecu;

import java.util.List;
import java.util.Locale;

/**
 * Finds the option of a bit parameter that means what the binding asks for. Firmware INIs spell
 * their switches differently ("On" / "Enabled" / "Вкл"), and TunerStudio refuses a text that is
 * not literally in the list; so an option is matched exactly first, then ignoring case, then by
 * meaning (on / off synonyms), and for a two-way switch as "the other one" of a known pair.
 * TunerStudio hands the options over with their INI quotes ("On"): they are ignored when
 * comparing and kept in what is returned, because that is the text TunerStudio accepts back.
 */
public final class OptionMatcher {
    private static final String[] ON = {"on", "enabled", "enable", "yes", "true", "active", "вкл", "включено", "включен", "включить", "да"};
    private static final String[] OFF = {"off", "disabled", "disable", "no", "false", "none", "inactive", "выкл", "выключено", "выключен", "выключить", "нет"};

    private OptionMatcher() {
    }

    /** The option text without the quotes TunerStudio keeps around it ("On" -> On), trimmed. */
    public static String plain(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    private static String norm(String s) {
        return plain(s).toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    /** 1 = means "on", 0 = means "off", -1 = neither. */
    public static int meaning(String option) {
        String n = norm(option);
        for (String s : ON) {
            if (n.equals(s)) {
                return 1;
            }
        }
        for (String s : OFF) {
            if (n.equals(s)) {
                return 0;
            }
        }
        return -1;
    }

    /** True when the two texts are the same option: equal ignoring case, or both mean on / both mean off. */
    public static boolean same(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (norm(a).equals(norm(b))) {
            return true;
        }
        int ma = meaning(a);
        return ma >= 0 && ma == meaning(b);
    }

    /** The option of the list that {@code wanted} stands for, spelled as the list spells it; null when none does. */
    public static String match(List<String> options, String wanted) {
        if (options == null || options.isEmpty() || wanted == null) {
            return null;
        }
        String w = plain(wanted);
        for (String o : options) {
            if (o != null && plain(o).equals(w)) {
                return o;
            }
        }
        for (String o : options) {
            if (o != null && norm(o).equals(norm(w))) {
                return o;
            }
        }
        int m = meaning(w);
        if (m < 0) {
            return null;
        }
        for (String o : options) {
            if (meaning(o) == m) {
                return o;
            }
        }
        // a two-way switch where only the other side is recognisable ("Off" / "Boost control 1"):
        // the remaining option is the one that means the opposite of the recognised side
        String opposite = null;
        String other = null;
        int real = 0;
        for (String o : options) {
            if (o == null || norm(o).equals("invalid")) {
                continue;
            }
            real++;
            if (meaning(o) == 1 - m) {
                opposite = o;
            } else if (meaning(o) < 0) {
                other = o;
            }
        }
        return real == 2 && opposite != null && other != null ? other : null;
    }
}
