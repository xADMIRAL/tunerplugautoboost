package io.github.xadmiral.boostautotune.plugin.assistant;

import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPort;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One conversation with Claude about the connected ECU. Keeps the message history as the API
 * returned it (thinking blocks included, replayed untouched), runs the tool loop: every
 * {@code tool_use} block is executed through {@link AssistantTools} and all results go back in a
 * single user turn, until the model stops. Network work happens on a worker thread; the listener
 * is called on that thread.
 */
public final class AssistantSession {

    /** UI callbacks; called on the worker thread. */
    public interface Listener {
        void assistantText(String text);

        /** One line about a tool call, e.g. "read_parameters(fc_rpm) -> ok". */
        void toolActivity(String line);

        void status(String text);

        void busy(boolean busy);

        void error(String message);

        /** The pending-changes list changed (a proposal, an auto-apply). */
        void changesUpdated();
    }

    private final AssistantConfig cfg;
    private final ClaudeTransport transport;
    private final AssistantTools tools;
    private final Listener listener;
    private final String ecuSignature;
    private final List<Object> messages = new ArrayList<Object>();
    private final Object lock = new Object();
    private volatile boolean busy;
    private volatile boolean cancel;
    private long inputTokens;
    private long outputTokens;
    private long cacheReadTokens;

    public AssistantSession(AssistantConfig cfg, ClaudeTransport transport, EcuPort port, EcuBinding binding, Listener listener) {
        this.cfg = cfg;
        this.transport = transport;
        this.listener = listener;
        this.ecuSignature = port == null ? "none" : port.signature();
        String config = port == null ? "" : new io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter(port, binding).config();
        this.tools = new AssistantTools(port, binding, cfg, new PendingChanges(), new LiveChannels(port, config));
    }

    public AssistantConfig config() {
        return cfg;
    }

    public AssistantTools tools() {
        return tools;
    }

    public PendingChanges changes() {
        return tools.changes();
    }

    public boolean isBusy() {
        return busy;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    public long cacheReadTokens() {
        return cacheReadTokens;
    }

    public int turns() {
        return messages.size();
    }

    /** Forgets the conversation (the pending changes stay). */
    public void reset() {
        synchronized (lock) {
            messages.clear();
            inputTokens = 0;
            outputTokens = 0;
            cacheReadTokens = 0;
        }
    }

    /** Asks the running turn to stop after the current API call. */
    public void cancel() {
        cancel = true;
    }

    public void dispose() {
        cancel = true;
        tools.live().dispose();
    }

    /** The system prompt: what the assistant is, the tools, the safety rules and the user's profile. */
    public String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the tuning assistant inside Boost Autotune, a TunerStudio plugin, talking to the owner of a turbocharged car ")
                .append("whose ECU (MegaSquirt MS3 family: Stealth PCM, MS3 1.5+, or a similar firmware) is connected right now. ")
                .append("Connected ECU signature: ").append(ecuSignature).append(".\n\n");
        sb.append("What you can do through the tools: find and read any ECU parameter or table (list_parameters, read_parameters), ")
                .append("watch live output channels and their recent history (list_channels, read_channels, channel_history), and propose ")
                .append("changes (propose_change for scalars, options, axis lists and table fills; propose_table_cells for individual cells). ")
                .append("A proposal is queued on the Assistant tab; the user applies it, or it is written at once when auto-apply is on. ")
                .append("You cannot burn: the user burns in TunerStudio after checking.\n\n");
        sb.append("Rules:\n")
                .append("- Read before you propose: never guess a parameter name or its current value; use list_parameters to find exact INI names.\n")
                .append("- Small, reversible steps: ignition no more than +2 deg per step, boost target no more than +20 kPa per step, ")
                .append("fuel (VE) no more than 5 % per step; ask for a log or live data before the next step.\n")
                .append("- Safety first: a rising knock signal, a lean AFR under boost, EGT or MAT out of range, or a low fuel or oil pressure ")
                .append("means back off and say so. Rev limiter, overboost protection and knock control stay in place; never disable them ")
                .append("or widen them without an explicit request, and say what the risk is.\n")
                .append("- One thing at a time: when a change needs a confirming drive, say exactly what to do and what to look at.\n")
                .append("- Give every proposal a one-sentence reason the user can judge.\n")
                .append("- Be concise. Numbers in short tables when useful. Answer in the language the user writes in.\n\n");
        sb.append("MS3 essentials: fuel = reqFuel x VE table (veTable1, axes frpm_table1 / fmap_table1) x MAP x corrections; the AFR table ")
                .append("only steers EGO/closed loop unless 'incorporate AFR' is on; spark = advanceTable1 (srpm_table1 / smap_table1) plus corrections; ")
                .append("boost = boost_ctl_settings_* with target (boost_ctl_load_targets), open-loop duty (boost_ctl_pwm_targets) and closed-loop ")
                .append("bias (boost_ctl_cl_pwm_targs1) tables; overboost cut OverBoostKpa; rev limit RevLimNormal2; idle in closed loop pwmidle_*; ")
                .append("over-run fuel cut OvrRunC / fc_*; anti-lag als_*; launch / flat shift launch_* / flats_*; knock knk_* and knock_*.\n\n");
        String profile = cfg.profile == null ? "" : cfg.profile.trim();
        if (!profile.isEmpty()) {
            sb.append("The user's car and notes (from the Assistant tab):\n").append(profile).append('\n');
        }
        return sb.toString();
    }

    /** Sends a user message on a worker thread; returns at once. */
    public void send(final String text) {
        if (busy) {
            listener.error("Still working on the previous message");
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                sendAndWait(text);
            }
        }, "boost-autotune-assistant");
        t.setDaemon(true);
        t.start();
    }

    /** Sends a user message and runs the whole tool loop on the calling thread (tests, scripts). */
    public void sendAndWait(String text) {
        synchronized (lock) {
            busy = true;
            cancel = false;
            listener.busy(true);
            try {
                run(text);
            } catch (IOException e) {
                listener.error(e.getMessage());
            } catch (RuntimeException e) {
                listener.error(e.toString());
            } finally {
                busy = false;
                listener.busy(false);
            }
        }
    }

    private void run(String text) throws IOException {
        ClaudeClient client = new ClaudeClient(transport, cfg.baseUrl, cfg.apiKey);
        int turnStart = messages.size();
        messages.add(message("user", text));
        int rounds = 0;
        while (true) {
            listener.status(rounds == 0 ? "Asking " + cfg.model + "..." : "Tool round " + rounds + ", asking " + cfg.model + "...");
            Map<String, Object> body = ClaudeClient.request(cfg.model, cfg.maxTokens, systemPrompt(), tools.definitions(),
                    new ArrayList<Object>(messages), cfg.effort, cfg.fallbacks);
            Map<String, Object> resp;
            try {
                resp = client.send(body, cfg.fallbacks);
            } catch (IOException e) {
                dropTurn(turnStart);
                throw e;
            }
            usage(resp);
            List<Object> content = Json.arr(resp.get("content"));
            if (content == null) {
                content = new ArrayList<Object>();
            }
            String stop = Json.str(resp, "stop_reason");
            if ("max_tokens".equals(stop)) {
                // a cut-off answer may end in a half-written tool call: keep the text, not the call
                content = withoutToolUse(content);
            }
            messages.add(message("assistant", content));
            for (Object o : content) {
                Map<String, Object> block = Json.obj(o);
                if (block != null && "text".equals(Json.str(block, "type"))) {
                    String t = Json.str(block, "text");
                    if (t != null && !t.trim().isEmpty()) {
                        listener.assistantText(t);
                    }
                }
            }
            if ("refusal".equals(stop)) {
                Map<String, Object> details = Json.obj(resp.get("stop_details"));
                String cat = details == null ? null : Json.str(details, "category");
                listener.error("The model declined this request" + (cat == null ? "" : " (" + cat + ")") + ". Rephrase or split it up.");
                return;
            }
            if ("max_tokens".equals(stop)) {
                listener.error("The answer hit the token limit (" + cfg.maxTokens + "); ask it to continue or raise Max tokens.");
                return;
            }
            List<Object> results = new ArrayList<Object>();
            for (Object o : content) {
                Map<String, Object> block = Json.obj(o);
                if (block == null || !"tool_use".equals(Json.str(block, "type"))) {
                    continue;
                }
                String name = Json.str(block, "name");
                String id = Json.str(block, "id");
                Map<String, Object> input = Json.obj(block.get("input"));
                if (input == null) {
                    input = Json.object();
                }
                AssistantTools.Result r = tools.execute(name, input);
                listener.toolActivity(name + "(" + brief(input) + ") -> " + (r.error ? "ERROR " + r.text : brief(r.text)));
                if (!r.error && (AssistantTools.PROPOSE_CHANGE.equals(name) || AssistantTools.PROPOSE_TABLE_CELLS.equals(name))) {
                    listener.changesUpdated();
                }
                Map<String, Object> tr = Json.object();
                tr.put("type", "tool_result");
                tr.put("tool_use_id", id);
                tr.put("content", r.text);
                if (r.error) {
                    tr.put("is_error", Boolean.TRUE);
                }
                results.add(tr);
            }
            if (!"tool_use".equals(stop) && !"pause_turn".equals(stop)) {
                listener.status(usageLine());
                return;
            }
            if ("tool_use".equals(stop) && results.isEmpty()) {
                listener.status(usageLine());
                return;
            }
            if (!results.isEmpty()) {
                messages.add(message("user", results));
            }
            rounds++;
            if (cancel) {
                listener.error("Stopped after " + rounds + " tool rounds");
                return;
            }
            if (rounds > cfg.maxToolRounds) {
                listener.error("Stopped after " + cfg.maxToolRounds + " tool rounds; send another message to continue");
                return;
            }
        }
    }

    /** A failed request leaves no half turn behind (no tool_use without its result), so the next message starts clean. */
    private void dropTurn(int turnStart) {
        while (messages.size() > turnStart) {
            messages.remove(messages.size() - 1);
        }
    }

    private static List<Object> withoutToolUse(List<Object> content) {
        List<Object> out = new ArrayList<Object>();
        for (Object o : content) {
            Map<String, Object> block = Json.obj(o);
            if (block == null || !"tool_use".equals(Json.str(block, "type"))) {
                out.add(o);
            }
        }
        return out;
    }

    private void usage(Map<String, Object> resp) {
        Map<String, Object> u = Json.obj(resp.get("usage"));
        if (u == null) {
            return;
        }
        inputTokens += (long) Json.num(u, "input_tokens", 0);
        outputTokens += (long) Json.num(u, "output_tokens", 0);
        cacheReadTokens += (long) Json.num(u, "cache_read_input_tokens", 0);
    }

    public String usageLine() {
        return "Tokens: " + inputTokens + " in (" + cacheReadTokens + " cached), " + outputTokens + " out, " + messages.size() + " turns";
    }

    private static Map<String, Object> message(String role, Object content) {
        Map<String, Object> m = Json.object();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static String brief(Object v) {
        String s = v instanceof String ? (String) v : Json.write(v);
        s = s.replace('\n', ' ');
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }
}
