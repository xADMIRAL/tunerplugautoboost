package io.github.xadmiral.boostautotune.plugin.assistant;

import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuException;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The tools Claude can call from the Assistant tab, executed against the ECU through the same
 * {@link EcuAdapter} the presets use. Reads are immediate; every write becomes a
 * {@link PendingChanges} entry that the user applies (or auto-apply is on). No tool burns.
 */
public final class AssistantTools {
    /** Outcome of one tool call: JSON text for the model and whether it is an error. */
    public static final class Result {
        public final String text;
        public final boolean error;

        Result(String text, boolean error) {
            this.text = text;
            this.error = error;
        }
    }

    public static final String LIST_PARAMETERS = "list_parameters";
    public static final String READ_PARAMETERS = "read_parameters";
    public static final String PROPOSE_CHANGE = "propose_change";
    public static final String PROPOSE_TABLE_CELLS = "propose_table_cells";
    public static final String PENDING_CHANGES = "pending_changes";
    public static final String LIST_CHANNELS = "list_channels";
    public static final String READ_CHANNELS = "read_channels";
    public static final String CHANNEL_HISTORY = "channel_history";
    public static final String SESSION_INFO = "session_info";

    private final EcuPort port;
    private final EcuBinding binding;
    private final PendingChanges changes;
    private final LiveChannels live;
    private final AssistantConfig cfg;
    private List<EcuPort.UiTableInfo> tables;

    public AssistantTools(EcuPort port, EcuBinding binding, AssistantConfig cfg, PendingChanges changes, LiveChannels live) {
        this.port = port;
        this.binding = binding;
        this.cfg = cfg;
        this.changes = changes;
        this.live = live;
    }

    public PendingChanges changes() {
        return changes;
    }

    public LiveChannels live() {
        return live;
    }

    private EcuAdapter adapter() throws EcuException {
        if (port == null) {
            throw new EcuException("no ECU connection: open a project in TunerStudio first");
        }
        return new EcuAdapter(port, binding);
    }

    // ---- definitions ---------------------------------------------------------------------------

    /** The tool list for the request body. */
    public List<Object> definitions() {
        List<Object> t = new ArrayList<Object>();
        t.add(tool(LIST_PARAMETERS,
                "List ECU parameter (constant) names from the INI that contain a text, case-insensitive; a name starting with ^ is a regex. "
                        + "Use it to find exact names before reading or proposing. Returns at most 'limit' names (default 100).",
                schema(new String[]{"pattern", "string", "text or ^regex to match against parameter names"},
                        new String[]{"limit", "integer", "maximum names to return, default 100, max 400"}), null));
        t.add(tool(READ_PARAMETERS,
                "Read the current values of ECU parameters (RAM, as TunerStudio shows them): scalars with units and range, "
                        + "options with the allowed choices, 1-D arrays as a list, 2-D tables as rows with their axis bins when the INI "
                        + "defines a table editor for them (rows follow the y axis, columns the x axis, index 0 first).",
                schema(new String[]{"names", "array:string", "exact parameter names"}), new String[]{"names"}));
        t.add(tool(PROPOSE_CHANGE,
                "Propose a new value for one parameter. Scalar: a number. Option: one of the allowed choices exactly. "
                        + "1-D array: a space separated list of all bins. 2-D table: a single number fills the whole table "
                        + "(use propose_table_cells for individual cells). The change is queued; the user applies it from the "
                        + "Assistant tab (or it is written at once when auto-apply is on). Say why in 'reason'.",
                schema(new String[]{"name", "string", "exact parameter name"},
                        new String[]{"value", "string", "the new value as text"},
                        new String[]{"reason", "string", "one sentence for the user"}), new String[]{"name", "value", "reason"}));
        t.add(tool(PROPOSE_TABLE_CELLS,
                "Propose new values for individual cells of a 2-D table, by row (y axis index) and column (x axis index) as "
                        + "returned by read_parameters. Queued for the user like propose_change.",
                schemaCells(), new String[]{"name", "cells", "reason"}));
        t.add(tool(PENDING_CHANGES, "The list of proposed changes and their status (pending, applied, rejected, failed, restored).",
                schema(), null));
        t.add(tool(LIST_CHANNELS,
                "List the ECU's live output channel names (datalog fields) that contain a text, case-insensitive.",
                schema(new String[]{"pattern", "string", "text to match"},
                        new String[]{"limit", "integer", "maximum names, default 100"}), null));
        t.add(tool(READ_CHANNELS,
                "Latest live value of output channels (rpm, map, afr1, coolant, ...). Channels not followed yet are subscribed on "
                        + "the first call; their value may arrive a moment later, so call again if you get 'pending'.",
                schema(new String[]{"names", "array:string", "exact channel names"}), new String[]{"names"}));
        t.add(tool(CHANNEL_HISTORY,
                "Recent history of followed channels: points of the last N seconds (default 30, max 300), thinned to at most "
                        + "'points' per channel (default 60, max 300). Each point is [seconds ago, value]. Subscribe with read_channels first.",
                schema(new String[]{"names", "array:string", "channel names"},
                        new String[]{"seconds", "number", "how far back"},
                        new String[]{"points", "integer", "points per channel"}), new String[]{"names"}));
        t.add(tool(SESSION_INFO,
                "ECU signature, configuration name, the plugin's channel binding (which channel is rpm, map, afr...), auto-apply "
                        + "state and the number of pending changes.", schema(), null));
        return t;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> schema, String[] required) {
        Map<String, Object> t = Json.object();
        t.put("name", name);
        t.put("description", description);
        if (required != null) {
            schema.put("required", new ArrayList<Object>(Arrays.asList((Object[]) required)));
        }
        t.put("input_schema", schema);
        return t;
    }

    private static Map<String, Object> schema(String[]... props) {
        Map<String, Object> s = Json.object();
        s.put("type", "object");
        Map<String, Object> p = Json.object();
        for (String[] prop : props) {
            Map<String, Object> d = Json.object();
            if (prop[1].startsWith("array:")) {
                d.put("type", "array");
                Map<String, Object> items = Json.object();
                items.put("type", prop[1].substring(6));
                d.put("items", items);
            } else {
                d.put("type", prop[1]);
            }
            d.put("description", prop[2]);
            p.put(prop[0], d);
        }
        s.put("properties", p);
        return s;
    }

    private static Map<String, Object> schemaCells() {
        Map<String, Object> s = schema(new String[]{"name", "string", "exact table parameter name"},
                new String[]{"reason", "string", "one sentence for the user"});
        Map<String, Object> cell = Json.object();
        cell.put("type", "object");
        Map<String, Object> cp = Json.object();
        cp.put("row", typed("integer", "y axis index, 0 first"));
        cp.put("col", typed("integer", "x axis index, 0 first"));
        cp.put("value", typed("number", "new value"));
        cell.put("properties", cp);
        cell.put("required", new ArrayList<Object>(Arrays.asList((Object) "row", "col", "value")));
        Map<String, Object> cells = Json.object();
        cells.put("type", "array");
        cells.put("items", cell);
        cells.put("description", "cells to change");
        Json.obj(s.get("properties")).put("cells", cells);
        return s;
    }

    private static Map<String, Object> typed(String type, String description) {
        Map<String, Object> d = Json.object();
        d.put("type", type);
        d.put("description", description);
        return d;
    }

    // ---- execution -----------------------------------------------------------------------------

    public Result execute(String name, Map<String, Object> input) {
        try {
            Object out;
            if (LIST_PARAMETERS.equals(name)) {
                out = listParameters(input);
            } else if (READ_PARAMETERS.equals(name)) {
                out = readParameters(input);
            } else if (PROPOSE_CHANGE.equals(name)) {
                out = proposeChange(input);
            } else if (PROPOSE_TABLE_CELLS.equals(name)) {
                out = proposeCells(input);
            } else if (PENDING_CHANGES.equals(name)) {
                out = pending();
            } else if (LIST_CHANNELS.equals(name)) {
                out = listChannels(input);
            } else if (READ_CHANNELS.equals(name)) {
                out = readChannels(input);
            } else if (CHANNEL_HISTORY.equals(name)) {
                out = channelHistory(input);
            } else if (SESSION_INFO.equals(name)) {
                out = sessionInfo();
            } else {
                return new Result("unknown tool " + name, true);
            }
            return new Result(Json.write(out), false);
        } catch (EcuException e) {
            return new Result(e.getMessage(), true);
        } catch (IllegalArgumentException e) {
            return new Result(e.getMessage(), true);
        } catch (RuntimeException e) {
            return new Result(e.toString(), true);
        }
    }

    private static List<String> names(Map<String, Object> input, String key) {
        List<Object> raw = Json.arr(input.get(key));
        List<String> out = new ArrayList<String>();
        if (raw == null) {
            String one = Json.str(input, key);
            if (one != null) {
                for (String s : one.split("[,\\s]+")) {
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
            return out;
        }
        for (Object o : raw) {
            if (o != null) {
                out.add(String.valueOf(o).trim());
            }
        }
        return out;
    }

    private static List<String> filter(List<String> all, String pattern, int limit) {
        List<String> out = new ArrayList<String>();
        Pattern re = null;
        String needle = pattern == null ? "" : pattern.trim();
        if (needle.startsWith("^")) {
            try {
                re = Pattern.compile(needle, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("bad regex: " + e.getDescription());
            }
        }
        String lower = needle.toLowerCase(Locale.ROOT);
        for (String n : all) {
            boolean hit = re != null ? re.matcher(n).find() : n.toLowerCase(Locale.ROOT).contains(lower);
            if (hit) {
                out.add(n);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    private Object listParameters(Map<String, Object> input) throws EcuException {
        EcuAdapter a = adapter();
        List<String> all = port.parameterNames(a.config());
        int limit = (int) Math.min(400, Math.max(1, Json.num(input, "limit", 100)));
        List<String> hits = filter(all, Json.str(input, "pattern"), limit);
        Map<String, Object> out = Json.object();
        out.put("total_parameters", Long.valueOf(all.size()));
        out.put("matches", Long.valueOf(hits.size()));
        out.put("names", new ArrayList<Object>(hits));
        if (hits.size() >= limit) {
            out.put("note", "list truncated at " + limit + "; narrow the pattern");
        }
        return out;
    }

    private synchronized List<EcuPort.UiTableInfo> tables(String cfg) {
        if (tables == null) {
            tables = port == null ? new ArrayList<EcuPort.UiTableInfo>() : port.uiTables(cfg);
        }
        return tables;
    }

    private Object readParameters(Map<String, Object> input) throws EcuException {
        EcuAdapter a = adapter();
        String cfg = a.config();
        List<Object> out = new ArrayList<Object>();
        for (String name : names(input, "names")) {
            Map<String, Object> m = Json.object();
            m.put("name", name);
            if (!a.hasParameter(name)) {
                m.put("error", "not in this INI (use list_parameters)");
                out.add(m);
                continue;
            }
            EcuPort.ParamInfo info = port.parameterInfo(cfg, name);
            m.put("class", info.paramClass);
            if (info.units != null && !info.units.isEmpty()) {
                m.put("units", info.units);
            }
            if ("bits".equalsIgnoreCase(info.paramClass)) {
                m.put("value", port.readOption(cfg, name));
                if (info.options != null && !info.options.isEmpty()) {
                    m.put("options", new ArrayList<Object>(info.options));
                }
            } else if ("array".equalsIgnoreCase(info.paramClass)) {
                double[][] raw = port.readArray2D(cfg, name);
                m.put("min", info.min);
                m.put("max", info.max);
                if (raw.length == 1 || raw[0].length == 1) {
                    double[] flat = raw.length == 1 ? raw[0] : new double[raw.length];
                    if (raw.length != 1) {
                        for (int i = 0; i < raw.length; i++) {
                            flat[i] = raw[i][0];
                        }
                    }
                    m.put("values", flat);
                } else {
                    m.put("rows", Long.valueOf(raw.length));
                    m.put("cols", Long.valueOf(raw[0].length));
                    List<Object> rows = new ArrayList<Object>();
                    for (double[] r : raw) {
                        rows.add(r);
                    }
                    m.put("table", rows);
                    for (EcuPort.UiTableInfo t : tables(cfg)) {
                        if (name.equals(t.zParam)) {
                            m.put("editor", t.name);
                            axis(m, "x_axis", t.xParam, t.xChannel, cfg);
                            axis(m, "y_axis", t.yParam, t.yChannel, cfg);
                            break;
                        }
                    }
                }
            } else {
                m.put("value", port.readScalar(cfg, name));
                m.put("min", info.min);
                m.put("max", info.max);
                m.put("decimals", Long.valueOf(info.decimals));
            }
            out.add(m);
        }
        return out;
    }

    private void axis(Map<String, Object> m, String key, String param, String channel, String cfg) {
        if (param == null || param.isEmpty()) {
            return;
        }
        Map<String, Object> ax = Json.object();
        ax.put("param", param);
        if (channel != null && !channel.isEmpty()) {
            ax.put("channel", channel);
        }
        try {
            ax.put("bins", port.readArray1D(cfg, param));
        } catch (EcuException e) {
            ax.put("error", e.getMessage());
        }
        m.put(key, ax);
    }

    private Object proposeChange(Map<String, Object> input) throws EcuException {
        String name = required(input, "name");
        String value = required(input, "value");
        EcuAdapter a = adapter();
        if (!a.hasParameter(name)) {
            throw new EcuException("parameter '" + name + "' is not in this INI");
        }
        EcuPort.ParamInfo info = port.parameterInfo(a.config(), name);
        if ("bits".equalsIgnoreCase(info.paramClass) && info.options != null && !info.options.isEmpty() && !info.options.contains(value)) {
            throw new EcuException("'" + value + "' is not an option of " + name + "; choices: " + info.options);
        }
        if ("scalar".equalsIgnoreCase(info.paramClass)) {
            double v;
            try {
                v = Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                throw new EcuException(name + " needs a number, got '" + value + "'");
            }
            if (info.max > info.min && (v < info.min || v > info.max)) {
                throw new EcuException(name + " must be within " + PendingChanges.fmt(info.min) + ".." + PendingChanges.fmt(info.max));
            }
        }
        String current = a.readAny(name);
        PendingChanges.Change c = changes.propose(name, value, Json.str(input, "reason"));
        return finish(a, c, current);
    }

    private Object proposeCells(Map<String, Object> input) throws EcuException {
        String name = required(input, "name");
        List<Object> raw = Json.arr(input.get("cells"));
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("cells must be a non-empty array of {row, col, value}");
        }
        EcuAdapter a = adapter();
        if (!a.hasParameter(name)) {
            throw new EcuException("parameter '" + name + "' is not in this INI");
        }
        double[][] table = port.readArray2D(a.config(), name);
        List<PendingChanges.Cell> cells = new ArrayList<PendingChanges.Cell>();
        for (Object o : raw) {
            Map<String, Object> c = Json.obj(o);
            if (c == null) {
                throw new IllegalArgumentException("each cell is an object {row, col, value}");
            }
            int row = (int) Json.num(c, "row", -1);
            int col = (int) Json.num(c, "col", -1);
            double v = Json.num(c, "value", Double.NaN);
            if (row < 0 || row >= table.length || col < 0 || col >= table[0].length) {
                throw new EcuException("cell [" + row + "," + col + "] is outside the " + table.length + "x" + table[0].length + " table");
            }
            if (Double.isNaN(v)) {
                throw new IllegalArgumentException("cell [" + row + "," + col + "] has no numeric value");
            }
            cells.add(new PendingChanges.Cell(row, col, v));
        }
        PendingChanges.Change c = changes.proposeCells(name, cells, Json.str(input, "reason"));
        return finish(a, c, null);
    }

    private Object finish(EcuAdapter a, PendingChanges.Change c, String current) {
        if (cfg.autoApply) {
            changes.apply(a, c.id);
        }
        Map<String, Object> out = Json.object();
        out.put("id", Long.valueOf(c.id));
        out.put("change", c.describe());
        if (current != null) {
            out.put("previous", current);
        }
        out.put("status", c.status);
        if (!c.error.isEmpty()) {
            out.put("error", c.error);
        }
        out.put("note", PendingChanges.APPLIED.equals(c.status) ? "written to ECU RAM; the user burns in TunerStudio"
                : PendingChanges.PENDING.equals(c.status) ? "queued: the user must click Apply on the Assistant tab" : "");
        return out;
    }

    private Object pending() {
        List<Object> out = new ArrayList<Object>();
        for (PendingChanges.Change c : changes.list()) {
            Map<String, Object> m = Json.object();
            m.put("id", Long.valueOf(c.id));
            m.put("change", c.describe());
            m.put("reason", c.reason);
            m.put("status", c.status);
            if (!c.error.isEmpty()) {
                m.put("error", c.error);
            }
            out.add(m);
        }
        return out;
    }

    private Object listChannels(Map<String, Object> input) {
        int limit = (int) Math.min(400, Math.max(1, Json.num(input, "limit", 100)));
        List<String> hits = filter(live.knownChannels(), Json.str(input, "pattern"), limit);
        Map<String, Object> out = Json.object();
        out.put("total_channels", Long.valueOf(live.knownChannels().size()));
        out.put("names", new ArrayList<Object>(hits));
        return out;
    }

    private Object readChannels(Map<String, Object> input) throws EcuException {
        List<String> names = names(input, "names");
        List<String> unknown = live.subscribe(names);
        Map<String, Object> out = Json.object();
        Map<String, Object> values = Json.object();
        for (String n : names) {
            if (unknown.contains(n)) {
                values.put(n, "unknown channel (use list_channels)");
                continue;
            }
            double v = live.latest(n);
            values.put(n, Double.isNaN(v) ? "pending" : (Object) Double.valueOf(v));
        }
        out.put("values", values);
        out.put("time", live.now());
        return out;
    }

    private Object channelHistory(Map<String, Object> input) throws EcuException {
        List<String> names = names(input, "names");
        double seconds = Math.min(300, Math.max(1, Json.num(input, "seconds", 30)));
        int points = (int) Math.min(300, Math.max(2, Json.num(input, "points", 60)));
        live.subscribe(names);
        double now = live.now();
        Map<String, Object> out = Json.object();
        for (String n : names) {
            List<Object> pts = new ArrayList<Object>();
            for (LiveChannels.Point p : live.history(n, seconds, points)) {
                pts.add(new double[]{Math.round((p.t - now) * 100) / 100.0, p.v});
            }
            out.put(n, pts);
        }
        return out;
    }

    private Object sessionInfo() {
        Map<String, Object> out = Json.object();
        out.put("ecu_signature", port == null ? "none" : port.signature());
        out.put("configuration", port == null ? "" : new EcuAdapter(port, binding).config());
        Map<String, Object> ch = Json.object();
        ch.put("rpm", binding.rpmChannel);
        ch.put("tps", binding.tpsChannel);
        ch.put("map", binding.mapChannel);
        ch.put("boost_target", binding.targetChannel);
        ch.put("boost_duty", binding.dutyChannel);
        ch.put("clt", binding.cltChannel);
        ch.put("mat", binding.matChannel);
        ch.put("afr", binding.afrChannel);
        ch.put("advance", binding.advanceChannel);
        ch.put("knock", binding.knockChannel);
        ch.put("knock_retard", binding.knockRetardChannel);
        ch.put("vvt_angle", binding.vvtAngleChannel);
        ch.put("vvt_target", binding.vvtTargetChannel);
        ch.put("fuel_load", binding.fuelLoadChannel);
        ch.put("ign_load", binding.ignLoadChannel);
        ch.put("gear", binding.gearChannel);
        out.put("channels", ch);
        out.put("auto_apply", cfg.autoApply);
        out.put("pending_changes", Long.valueOf(changes.pendingCount()));
        out.put("followed_channels", new ArrayList<Object>(live.subscribedChannels()));
        return out;
    }

    private static String required(Map<String, Object> input, String key) {
        String v = Json.str(input, key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException("'" + key + "' is required");
        }
        return v.trim();
    }
}
