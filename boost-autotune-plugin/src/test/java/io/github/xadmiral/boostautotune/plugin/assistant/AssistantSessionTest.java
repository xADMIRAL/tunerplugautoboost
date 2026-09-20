package io.github.xadmiral.boostautotune.plugin.assistant;

import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPresets;
import io.github.xadmiral.boostautotune.plugin.ecu.SimEcuPort;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The conversation and the tool loop against the simulated MS3, with a scripted API. */
class AssistantSessionTest {

    /** Answers with the scripted bodies in order and records every request. */
    static final class FakeTransport implements ClaudeTransport {
        final List<String> urls = new ArrayList<String>();
        final List<Map<String, String>> headers = new ArrayList<Map<String, String>>();
        final List<Map<String, Object>> bodies = new ArrayList<Map<String, Object>>();
        final List<Object> script = new ArrayList<Object>();

        FakeTransport reply(String json) {
            script.add(json);
            return this;
        }

        FakeTransport fail(int status, String body) {
            script.add(new ClaudeApiException(status, body));
            return this;
        }

        public String post(String url, Map<String, String> h, String body) throws IOException {
            urls.add(url);
            headers.add(h);
            bodies.add(Json.obj(Json.parse(body)));
            if (script.isEmpty()) {
                throw new IOException("no scripted reply");
            }
            Object next = script.remove(0);
            if (next instanceof IOException) {
                throw (IOException) next;
            }
            return (String) next;
        }
    }

    static final class Events implements AssistantSession.Listener {
        final List<String> texts = new ArrayList<String>();
        final List<String> tools = new ArrayList<String>();
        final List<String> errors = new ArrayList<String>();
        final List<String> statuses = new ArrayList<String>();
        int changesUpdated;
        boolean busy;

        public void assistantText(String text) {
            texts.add(text);
        }

        public void toolActivity(String line) {
            tools.add(line);
        }

        public void status(String text) {
            statuses.add(text);
        }

        public void busy(boolean b) {
            busy = b;
        }

        public void error(String message) {
            errors.add(message);
        }

        public void changesUpdated() {
            changesUpdated++;
        }
    }

    private static String textReply(String text) {
        return "{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":" + Json.write(text)
                + "}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":100,\"output_tokens\":10,\"cache_read_input_tokens\":40}}";
    }

    private static String toolReply(String thinkingSig, String... nameAndInputPairs) {
        StringBuilder content = new StringBuilder();
        if (thinkingSig != null) {
            content.append("{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":").append(Json.write(thinkingSig)).append("},");
        }
        content.append("{\"type\":\"text\",\"text\":\"Let me look.\"}");
        for (int i = 0; i < nameAndInputPairs.length; i += 2) {
            content.append(",{\"type\":\"tool_use\",\"id\":\"tu_").append(i / 2).append("\",\"name\":\"").append(nameAndInputPairs[i])
                    .append("\",\"input\":").append(nameAndInputPairs[i + 1]).append('}');
        }
        return "{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[" + content
                + "],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":200,\"output_tokens\":30}}";
    }

    private static AssistantConfig config() {
        AssistantConfig c = new AssistantConfig();
        c.apiKey = "sk-test";
        c.model = "claude-opus-5";
        c.baseUrl = "https://example.test/";
        c.profile = "Toyota Altezza, 2JZ-GTE, G30-900";
        return c;
    }

    @Test
    void requestCarriesHeadersSystemToolsAndTheProfile() {
        FakeTransport t = new FakeTransport().reply(textReply("Hello"));
        Events ev = new Events();
        SimEcuPort port = new SimEcuPort();
        AssistantSession s = new AssistantSession(config(), t, port, EcuPresets.create(EcuPresets.STEALTH_PCM), ev);
        s.sendAndWait("Привет");
        assertEquals("https://example.test/v1/messages", t.urls.get(0));
        Map<String, String> h = t.headers.get(0);
        assertEquals("sk-test", h.get("x-api-key"));
        assertEquals(ClaudeClient.API_VERSION, h.get("anthropic-version"));
        assertEquals(ClaudeClient.FALLBACK_BETA, h.get("anthropic-beta"));
        Map<String, Object> body = t.bodies.get(0);
        assertEquals("claude-opus-5", body.get("model"));
        assertEquals("default", body.get("fallbacks"));
        assertEquals(8192L, body.get("max_tokens"));
        assertFalse(body.containsKey("thinking"), "adaptive thinking is the default: no thinking field");
        assertFalse(body.containsKey("output_config"), "no effort chosen: API default");
        String system = Json.str(Json.obj(Json.arr(body.get("system")).get(0)), "text");
        assertTrue(system.contains("Toyota Altezza"));
        assertTrue(system.contains(port.signature()));
        assertEquals("ephemeral", Json.str(Json.obj(Json.obj(Json.arr(body.get("system")).get(0)).get("cache_control")), "type"));
        List<Object> tools = Json.arr(body.get("tools"));
        assertEquals(9, tools.size());
        assertEquals(AssistantTools.LIST_PARAMETERS, Json.str(Json.obj(tools.get(0)), "name"));
        List<Object> messages = Json.arr(body.get("messages"));
        assertEquals(1, messages.size());
        assertEquals("Привет", Json.str(Json.obj(messages.get(0)), "content"));
        assertEquals(1, ev.texts.size());
        assertEquals("Hello", ev.texts.get(0));
        assertTrue(ev.errors.isEmpty(), ev.errors.toString());
        assertEquals(100, s.inputTokens());
        assertEquals(40, s.cacheReadTokens());
        assertFalse(ev.busy);
        assertEquals(2, s.turns());
    }

    @Test
    void effortAndNoFallbacksAreHonoured() {
        AssistantConfig c = config();
        c.effort = "medium";
        c.fallbacks = false;
        FakeTransport t = new FakeTransport().reply(textReply("ok"));
        new AssistantSession(c, t, new SimEcuPort(), EcuPresets.create(EcuPresets.STEALTH_PCM), new Events()).sendAndWait("x");
        Map<String, Object> body = t.bodies.get(0);
        assertEquals("medium", Json.str(Json.obj(body.get("output_config")), "effort"));
        assertFalse(body.containsKey("fallbacks"));
        assertFalse(t.headers.get(0).containsKey("anthropic-beta"));
    }

    @Test
    void toolLoopReadsTheEcuAndReturnsEveryResultInOneUserTurn() throws Exception {
        FakeTransport t = new FakeTransport()
                .reply(toolReply("sig123", AssistantTools.READ_PARAMETERS, "{\"names\":[\"fc_rpm\",\"OvrRunC\",\"nope\"]}",
                        AssistantTools.LIST_PARAMETERS, "{\"pattern\":\"^fc_\"}"))
                .reply(textReply("fc_rpm is 2000."));
        Events ev = new Events();
        SimEcuPort port = new SimEcuPort();
        AssistantSession s = new AssistantSession(config(), t, port, EcuPresets.create(EcuPresets.STEALTH_PCM), ev);
        s.sendAndWait("what is the over-run cut rpm?");
        assertEquals(2, t.bodies.size());
        List<Object> messages = Json.arr(t.bodies.get(1).get("messages"));
        assertEquals(3, messages.size(), "user, assistant, tool results");
        Map<String, Object> assistant = Json.obj(messages.get(1));
        assertEquals("assistant", Json.str(assistant, "role"));
        List<Object> content = Json.arr(assistant.get("content"));
        assertEquals("thinking", Json.str(Json.obj(content.get(0)), "type"), "thinking block replayed untouched");
        assertEquals("sig123", Json.str(Json.obj(content.get(0)), "signature"));
        Map<String, Object> results = Json.obj(messages.get(2));
        assertEquals("user", Json.str(results, "role"));
        List<Object> blocks = Json.arr(results.get("content"));
        assertEquals(2, blocks.size(), "both tool results in one message");
        Map<String, Object> r0 = Json.obj(blocks.get(0));
        assertEquals("tool_result", Json.str(r0, "type"));
        assertEquals("tu_0", Json.str(r0, "tool_use_id"));
        assertFalse(r0.containsKey("is_error"));
        List<Object> read = Json.arr(Json.parse(Json.str(r0, "content")));
        assertEquals(3, read.size());
        assertEquals(2000.0, Json.num(Json.obj(read.get(0)), "value", -1), 1e-9);
        assertEquals("scalar", Json.str(Json.obj(read.get(0)), "class"));
        assertEquals("On", Json.str(Json.obj(read.get(1)), "value"));
        assertTrue(Json.arr(Json.obj(read.get(1)).get("options")).contains("Off"));
        assertTrue(Json.str(Json.obj(read.get(2)), "error").contains("not in this INI"));
        Map<String, Object> listed = Json.obj(Json.parse(Json.str(Json.obj(blocks.get(1)), "content")));
        assertTrue(Json.arr(listed.get("names")).contains("fc_timing"));
        assertEquals("tu_1", Json.str(Json.obj(blocks.get(1)), "tool_use_id"));
        assertEquals(2, ev.texts.size());
        assertEquals("fc_rpm is 2000.", ev.texts.get(1));
        assertEquals(2, ev.tools.size());
        assertTrue(ev.tools.get(0).startsWith("read_parameters("));
        assertEquals(300, s.inputTokens());
    }

    @Test
    void tablesComeWithAxesAndCellEditsAreQueuedThenAppliedAndRestored() throws Exception {
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        FakeTransport t = new FakeTransport()
                .reply(toolReply(null, AssistantTools.READ_PARAMETERS, "{\"names\":[\"" + b.targetTable + "\"]}"))
                .reply(toolReply(null, AssistantTools.PROPOSE_TABLE_CELLS,
                        "{\"name\":\"" + b.targetTable + "\",\"cells\":[{\"row\":7,\"col\":3,\"value\":180},{\"row\":7,\"col\":4,\"value\":185.5}],\"reason\":\"more boost at 4500-5000\"}",
                        AssistantTools.PROPOSE_CHANGE, "{\"name\":\"fc_rpm\",\"value\":\"2500\",\"reason\":\"pops above idle\"}",
                        AssistantTools.PROPOSE_CHANGE, "{\"name\":\"OvrRunC\",\"value\":\"Maybe\",\"reason\":\"bad option\"}",
                        AssistantTools.PROPOSE_CHANGE, "{\"name\":\"fc_rpm\",\"value\":\"99999\",\"reason\":\"out of range\"}"))
                .reply(textReply("Queued two changes."));
        Events ev = new Events();
        SimEcuPort port = new SimEcuPort();
        AssistantSession s = new AssistantSession(config(), t, port, b, ev);
        s.sendAndWait("raise the target a bit");
        // the table read carries axes from the INI table editor
        Map<String, Object> firstResults = Json.obj(Json.arr(t.bodies.get(1).get("messages")).get(2));
        Map<String, Object> tableRead = Json.obj(Json.arr(Json.parse(Json.str(Json.obj(Json.arr(firstResults.get("content")).get(0)), "content"))).get(0));
        assertEquals(8L, tableRead.get("rows"));
        assertEquals("Boost Control Targets 1", Json.str(tableRead, "editor"));
        assertEquals(b.targetXBins, Json.str(Json.obj(tableRead.get("x_axis")), "param"));
        assertEquals(8, Json.arr(Json.obj(tableRead.get("x_axis")).get("bins")).size());
        // second round: two valid proposals queued, two rejected as errors
        Map<String, Object> secondResults = Json.obj(Json.arr(t.bodies.get(2).get("messages")).get(4));
        List<Object> blocks = Json.arr(secondResults.get("content"));
        assertEquals(4, blocks.size());
        assertFalse(Json.obj(blocks.get(0)).containsKey("is_error"));
        assertEquals("pending", Json.str(Json.obj(Json.parse(Json.str(Json.obj(blocks.get(0)), "content"))), "status"));
        assertEquals("2000", Json.str(Json.obj(Json.parse(Json.str(Json.obj(blocks.get(1)), "content"))), "previous"));
        assertEquals(Boolean.TRUE, Json.obj(blocks.get(2)).get("is_error"));
        assertTrue(Json.str(Json.obj(blocks.get(2)), "content").contains("not an option"));
        assertEquals(Boolean.TRUE, Json.obj(blocks.get(3)).get("is_error"));
        assertTrue(Json.str(Json.obj(blocks.get(3)), "content").contains("within"));
        PendingChanges pc = s.changes();
        assertEquals(2, pc.pendingCount());
        assertEquals(2, ev.changesUpdated);
        // nothing written until the user applies
        assertEquals(2000, port.readScalar(SimEcuPort.CONFIG, "fc_rpm"), 1e-9);
        assertEquals(100, port.readArray2D(SimEcuPort.CONFIG, b.targetTable)[7][3], 1e-9);
        EcuAdapter a = new EcuAdapter(port, b);
        assertEquals(2, pc.applyAll(a));
        assertEquals(2500, port.readScalar(SimEcuPort.CONFIG, "fc_rpm"), 1e-9);
        double[][] table = port.readArray2D(SimEcuPort.CONFIG, b.targetTable);
        assertEquals(180, table[7][3], 1e-9);
        assertEquals(185.5, table[7][4], 1e-9);
        assertEquals(100, table[7][2], 1e-9, "other cells untouched");
        assertTrue(pc.hasApplied());
        assertTrue(pc.restoreAll(a).isEmpty());
        assertEquals(2000, port.readScalar(SimEcuPort.CONFIG, "fc_rpm"), 1e-9);
        assertEquals(100, port.readArray2D(SimEcuPort.CONFIG, b.targetTable)[7][3], 1e-9);
        assertFalse(pc.hasApplied());
        assertEquals(PendingChanges.RESTORED, pc.list().get(0).status);
    }

    @Test
    void autoApplyWritesAtOnceAndReportsIt() throws Exception {
        AssistantConfig c = config();
        c.autoApply = true;
        FakeTransport t = new FakeTransport()
                .reply(toolReply(null, AssistantTools.PROPOSE_CHANGE, "{\"name\":\"fc_kpa\",\"value\":\"45\",\"reason\":\"window\"}"))
                .reply(textReply("Done."));
        SimEcuPort port = new SimEcuPort();
        AssistantSession s = new AssistantSession(c, t, port, EcuPresets.create(EcuPresets.STEALTH_PCM), new Events());
        s.sendAndWait("set fc_kpa 45");
        assertEquals(45, port.readScalar(SimEcuPort.CONFIG, "fc_kpa"), 1e-9);
        Map<String, Object> results = Json.obj(Json.arr(t.bodies.get(1).get("messages")).get(2));
        String content = Json.str(Json.obj(Json.arr(results.get("content")).get(0)), "content");
        assertEquals("applied", Json.str(Json.obj(Json.parse(content)), "status"));
        assertEquals(0, s.changes().pendingCount());
    }

    @Test
    void liveChannelsAreSubscribedOnFirstReadAndHistoryFollows() throws Exception {
        FakeTransport t = new FakeTransport()
                .reply(toolReply(null, AssistantTools.READ_CHANNELS, "{\"names\":[\"rpm\",\"map\",\"bogus\"]}"))
                .reply(textReply("waiting"));
        SimEcuPort port = new SimEcuPort();
        AssistantSession s = new AssistantSession(config(), t, port, EcuPresets.create(EcuPresets.STEALTH_PCM), new Events());
        s.sendAndWait("what are rpm and map?");
        Map<String, Object> results = Json.obj(Json.arr(t.bodies.get(1).get("messages")).get(2));
        Map<String, Object> values = Json.obj(Json.obj(Json.parse(Json.str(Json.obj(Json.arr(results.get("content")).get(0)), "content"))).get("values"));
        assertEquals("pending", values.get("rpm"));
        assertTrue(String.valueOf(values.get("bogus")).contains("unknown"));
        assertTrue(s.tools().live().isSubscribed("rpm"));
        assertFalse(s.tools().live().isSubscribed("bogus"));
        // a simulated pull feeds the cache
        port.simulatePull(3, 1000);
        long deadline = System.currentTimeMillis() + 10000;
        while (port.isPulling() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(Double.isNaN(s.tools().live().latest("rpm")));
        Map<String, Object> input = Json.object();
        input.put("names", new ArrayList<Object>(java.util.Arrays.asList((Object) "rpm", "map")));
        input.put("seconds", 60L);
        input.put("points", 20L);
        AssistantTools.Result r = s.tools().execute(AssistantTools.CHANNEL_HISTORY, input);
        assertFalse(r.error, r.text);
        Map<String, Object> hist = Json.obj(Json.parse(r.text));
        List<Object> rpm = Json.arr(hist.get("rpm"));
        assertTrue(rpm.size() > 5 && rpm.size() <= 20, "thinned history: " + rpm.size());
        List<Object> last = Json.arr(rpm.get(rpm.size() - 1));
        assertTrue(Json.num(Json.obj(hist), "x", 0) == 0);
        assertTrue(((Number) last.get(0)).doubleValue() <= 0, "seconds ago is not positive");
        AssistantTools.Result info = s.tools().execute(AssistantTools.SESSION_INFO, Json.object());
        assertTrue(Json.arr(Json.obj(Json.parse(info.text)).get("followed_channels")).contains("map"));
    }

    @Test
    void apiFailureIsReportedAndTheTurnIsDroppedRefusalIsExplained() {
        FakeTransport t = new FakeTransport()
                .fail(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}")
                .reply("{\"id\":\"m\",\"type\":\"message\",\"content\":[],\"stop_reason\":\"refusal\",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\"},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
        Events ev = new Events();
        AssistantSession s = new AssistantSession(config(), t, new SimEcuPort(), EcuPresets.create(EcuPresets.STEALTH_PCM), ev);
        s.sendAndWait("first");
        assertEquals(1, ev.errors.size());
        assertTrue(ev.errors.get(0).contains("API key rejected"), ev.errors.get(0));
        assertTrue(ev.errors.get(0).contains("invalid x-api-key"));
        assertEquals(0, s.turns(), "the failed user turn is dropped");
        s.sendAndWait("second");
        assertEquals(1, Json.arr(t.bodies.get(1).get("messages")).size());
        assertEquals(2, ev.errors.size());
        assertTrue(ev.errors.get(1).contains("declined"));
        assertTrue(ev.errors.get(1).contains("cyber"));
    }

    @Test
    void failureInsideTheToolLoopDropsTheWholeTurn() {
        FakeTransport t = new FakeTransport()
                .reply(toolReply(null, AssistantTools.READ_PARAMETERS, "{\"names\":[\"fc_rpm\"]}"))
                .fail(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}")
                .fail(500, "{}").fail(500, "{}")
                .reply(textReply("fresh"));
        Events ev = new Events();
        AssistantSession s = new AssistantSession(config(), t, new SimEcuPort(), EcuPresets.create(EcuPresets.STEALTH_PCM), ev);
        s.sendAndWait("first");
        assertEquals(1, ev.errors.size());
        assertEquals(0, s.turns(), "user, assistant tool_use and the tool results are all gone");
        s.sendAndWait("second");
        List<Object> msgs = Json.arr(t.bodies.get(t.bodies.size() - 1).get("messages"));
        assertEquals(1, msgs.size());
        assertEquals("second", Json.str(Json.obj(msgs.get(0)), "content"));
        assertEquals("fresh", ev.texts.get(ev.texts.size() - 1));
    }

    @Test
    void cutOffAnswerKeepsTheTextButNotTheHalfToolCall() {
        String cut = "{\"id\":\"m\",\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":\"Partial\"},"
                + "{\"type\":\"tool_use\",\"id\":\"tu_9\",\"name\":\"read_parameters\",\"input\":{}}],\"stop_reason\":\"max_tokens\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        FakeTransport t = new FakeTransport().reply(cut).reply(textReply("continued"));
        Events ev = new Events();
        AssistantSession s = new AssistantSession(config(), t, new SimEcuPort(), EcuPresets.create(EcuPresets.STEALTH_PCM), ev);
        s.sendAndWait("go");
        assertTrue(ev.errors.get(0).contains("token limit"));
        assertTrue(ev.tools.isEmpty(), "the half call is not executed");
        s.sendAndWait("continue");
        List<Object> msgs = Json.arr(t.bodies.get(1).get("messages"));
        assertEquals(3, msgs.size());
        List<Object> kept = Json.arr(Json.obj(msgs.get(1)).get("content"));
        assertEquals(1, kept.size());
        assertEquals("text", Json.str(Json.obj(kept.get(0)), "type"));
    }

    @Test
    void rateLimitIsRetriedThenGivenUp() {
        FakeTransport t = new FakeTransport()
                .fail(429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}")
                .reply(textReply("after retry"));
        ClaudeClient c = new ClaudeClient(t, "https://x.test", "k");
        c.setRetries(2, 1);
        try {
            Map<String, Object> resp = c.send(ClaudeClient.request("claude-opus-5", 100, "", null, new ArrayList<Object>(), "", false), false);
            assertEquals("end_turn", Json.str(resp, "stop_reason"));
        } catch (IOException e) {
            fail(e);
        }
        assertEquals(2, t.bodies.size());
        FakeTransport t2 = new FakeTransport().fail(529, "{}").fail(529, "{}").fail(529, "{}");
        ClaudeClient c2 = new ClaudeClient(t2, "https://x.test", "k");
        c2.setRetries(2, 1);
        try {
            c2.send(ClaudeClient.request("claude-opus-5", 100, "", null, new ArrayList<Object>(), "", false), false);
            fail("expected an exception");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("overloaded"), e.getMessage());
        }
        assertEquals(3, t2.bodies.size());
    }

    @Test
    void noKeyIsAClearMessage() {
        AssistantConfig c = config();
        c.apiKey = "";
        Events ev = new Events();
        new AssistantSession(c, new FakeTransport(), new SimEcuPort(), EcuPresets.create(EcuPresets.STEALTH_PCM), ev).sendAndWait("hi");
        assertEquals(1, ev.errors.size());
        assertTrue(ev.errors.get(0).contains("No API key"));
    }
}
