package io.github.xadmiral.boostautotune.plugin.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader / writer, enough for the Claude Messages API: objects become
 * {@link LinkedHashMap}, arrays {@link ArrayList}, numbers {@link Long} or {@link Double},
 * plus {@link String}, {@link Boolean} and {@code null}. Kept in the plugin so the jar stays
 * free of third-party libraries (TunerStudio loads plugins next to its own ones).
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Parses one JSON value; throws {@link IllegalArgumentException} on malformed text. */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("null JSON");
        }
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw p.err("trailing characters");
        }
        return v;
    }

    public static Map<String, Object> object() {
        return new LinkedHashMap<String, Object>();
    }

    /** The value as an object, or null when it is not one. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /** The value as an array, or null when it is not one. */
    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) {
        return v instanceof List ? (List<Object>) v : null;
    }

    /** String member of an object, or null. */
    public static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : v instanceof String ? (String) v : String.valueOf(v);
    }

    /** Numeric member of an object, or the default. */
    public static double num(Map<String, Object> m, String key, double def) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    // ---- writer -------------------------------------------------------------------------------

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote((String) v, sb);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Number) {
            number((Number) v, sb);
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(o, sb);
            }
            sb.append(']');
        } else if (v instanceof double[]) {
            sb.append('[');
            double[] a = (double[]) v;
            for (int k = 0; k < a.length; k++) {
                if (k > 0) {
                    sb.append(',');
                }
                number(a[k], sb);
            }
            sb.append(']');
        } else if (v instanceof Object[]) {
            sb.append('[');
            Object[] a = (Object[]) v;
            for (int k = 0; k < a.length; k++) {
                if (k > 0) {
                    sb.append(',');
                }
                write(a[k], sb);
            }
            sb.append(']');
        } else {
            quote(String.valueOf(v), sb);
        }
    }

    private static void number(Number n, StringBuilder sb) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append(Long.toString((long) d));
            } else {
                sb.append(Double.toString(d));
            }
        } else {
            sb.append(n.toString());
        }
    }

    private static void quote(String str, StringBuilder sb) {
        sb.append('"');
        for (int k = 0; k < str.length(); k++) {
            char c = str.charAt(k);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ---- reader -------------------------------------------------------------------------------

    private IllegalArgumentException err(String what) {
        int from = Math.max(0, i - 20);
        int to = Math.min(s.length(), i + 20);
        return new IllegalArgumentException("JSON: " + what + " at " + i + " near '" + s.substring(from, to) + "'");
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                i++;
            } else {
                break;
            }
        }
    }

    private Object value() {
        if (i >= s.length()) {
            throw err("unexpected end");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object0();
            case '[':
                return array0();
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number0();
                }
                throw err("unexpected character '" + c + "'");
        }
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) {
            throw err("expected " + word);
        }
        i += word.length();
    }

    private Map<String, Object> object0() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++; // {
        ws();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') {
                throw err("expected member name");
            }
            String key = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') {
                throw err("expected ':'");
            }
            i++;
            ws();
            m.put(key, value());
            ws();
            if (i >= s.length()) {
                throw err("unterminated object");
            }
            char c = s.charAt(i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw err("expected ',' or '}'");
            }
        }
    }

    private List<Object> array0() {
        List<Object> a = new ArrayList<Object>();
        i++; // [
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return a;
        }
        while (true) {
            ws();
            a.add(value());
            ws();
            if (i >= s.length()) {
                throw err("unterminated array");
            }
            char c = s.charAt(i++);
            if (c == ']') {
                return a;
            }
            if (c != ',') {
                throw err("expected ',' or ']'");
            }
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++; // opening quote
        while (true) {
            if (i >= s.length()) {
                throw err("unterminated string");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw err("bad escape");
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (i + 4 > s.length()) {
                        throw err("bad unicode escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw err("bad unicode escape");
                    }
                    i += 4;
                    break;
                default:
                    throw err("bad escape '\\" + e + "'");
            }
        }
    }

    private Number number0() {
        int start = i;
        boolean floating = false;
        if (s.charAt(i) == '-') {
            i++;
        }
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                floating = true;
                i++;
            } else {
                break;
            }
        }
        String t = s.substring(start, i);
        try {
            if (!floating) {
                try {
                    return Long.valueOf(t);
                } catch (NumberFormatException e) {
                    return Double.valueOf(t);
                }
            }
            return Double.valueOf(t);
        } catch (NumberFormatException e) {
            throw err("bad number '" + t + "'");
        }
    }
}
