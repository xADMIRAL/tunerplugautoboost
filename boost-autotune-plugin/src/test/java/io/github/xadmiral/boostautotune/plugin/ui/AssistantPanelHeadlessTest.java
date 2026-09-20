package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.plugin.assistant.AssistantConfig;
import io.github.xadmiral.boostautotune.plugin.assistant.ClaudeTransport;
import io.github.xadmiral.boostautotune.plugin.assistant.Json;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPresets;
import io.github.xadmiral.boostautotune.plugin.ecu.SimEcuPort;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** The Assistant tab headless: settings round-trip, a scripted chat, the proposed-changes table. */
class AssistantPanelHeadlessTest {

    static final class ScriptedTransport implements ClaudeTransport {
        final List<String> replies = new ArrayList<String>();
        final List<Map<String, Object>> bodies = new ArrayList<Map<String, Object>>();

        public synchronized String post(String url, Map<String, String> headers, String body) throws IOException {
            bodies.add(Json.obj(Json.parse(body)));
            if (replies.isEmpty()) {
                throw new IOException("no reply scripted");
            }
            return replies.remove(0);
        }
    }

    private static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    @Test
    void settingsRoundTripAndScriptedChatFillsTheTable() throws Exception {
        final AssistantConfig cfg = new AssistantConfig();
        cfg.apiKey = "sk-test";
        final ScriptedTransport t = new ScriptedTransport();
        t.replies.add("{\"id\":\"m\",\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":\"Reading.\"},"
                + "{\"type\":\"tool_use\",\"id\":\"tu_0\",\"name\":\"propose_change\",\"input\":{\"name\":\"fc_rpm\",\"value\":\"2500\",\"reason\":\"pops above idle\"}}],"
                + "\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}");
        t.replies.add("{\"id\":\"m\",\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":\"Queued fc_rpm = 2500.\"}],"
                + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}");
        final SimEcuPort port = new SimEcuPort();
        final EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        final List<String> log = new ArrayList<String>();
        final int[] saved = new int[1];
        final AssistantPanel[] holder = new AssistantPanel[1];
        edt(new Runnable() {
            public void run() {
                holder[0] = new AssistantPanel(cfg, b, port, new Runnable() {
                    public void run() {
                        saved[0]++;
                    }
                }, new AssistantPanel.Log() {
                    public void line(String s) {
                        log.add(s);
                    }
                }, t);
                holder[0].setSize(1000, 700);
                holder[0].doLayout();
            }
        });
        final AssistantPanel p = holder[0];
        // settings: the fields show the config and apply back into it
        edt(new Runnable() {
            public void run() {
                p.autoApplyCheckbox().setSelected(false);
                assertTrue(p.applySettings());
            }
        });
        assertEquals(1, saved[0]);
        assertEquals(AssistantConfig.DEFAULT_MODEL, cfg.model);
        Properties props = new Properties();
        cfg.store(props, "assistant.");
        assertEquals("claude-opus-5", props.getProperty("assistant.model"));
        assertEquals("false", props.getProperty("assistant.autoApply"));
        AssistantConfig back = new AssistantConfig();
        back.load(props, "assistant.");
        assertEquals(cfg.baseUrl, back.baseUrl);
        assertEquals(cfg.profile, back.profile);
        assertFalse(props.containsKey("assistant.apiKey"), "the key never goes into the settings file");
        // a chat turn on the worker thread
        edt(new Runnable() {
            public void run() {
                p.inputArea().setText("make it pop");
                p.send();
            }
        });
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline && (p.currentSession() == null || p.currentSession().isBusy() || t.bodies.size() < 2)) {
            Thread.sleep(20);
        }
        edt(new Runnable() {
            public void run() {
                // flush the EDT queue
            }
        });
        String text = p.transcriptArea().getText();
        assertTrue(text.contains("You: make it pop"), text);
        assertTrue(text.contains("Claude: Reading."), text);
        assertTrue(text.contains("[propose_change("), text);
        assertTrue(text.contains("Claude: Queued fc_rpm = 2500."), text);
        assertEquals(1, p.changesTable().getRowCount());
        assertEquals("pending", p.changesTable().getValueAt(0, 3));
        assertEquals(2000, port.readScalar(SimEcuPort.CONFIG, "fc_rpm"), 1e-9, "nothing written before Apply");
        // apply all, then restore
        final int[] n = new int[1];
        edt(new Runnable() {
            public void run() {
                n[0] = p.applyAll(false);
            }
        });
        assertEquals(1, n[0]);
        assertEquals(2500, port.readScalar(SimEcuPort.CONFIG, "fc_rpm"), 1e-9);
        assertEquals("applied", p.changesTable().getValueAt(0, 3));
        edt(new Runnable() {
            public void run() {
                p.restoreAll();
            }
        });
        assertEquals(2000, port.readScalar(SimEcuPort.CONFIG, "fc_rpm"), 1e-9);
        assertEquals("restored", p.changesTable().getValueAt(0, 3));
        assertTrue(log.size() >= 4, log.toString());
        edt(new Runnable() {
            public void run() {
                p.dispose();
            }
        });
    }

    @Test
    void refusesToSendWithoutAKeyOrText() throws Exception {
        final AssistantConfig cfg = new AssistantConfig();
        cfg.apiKey = "";
        final ScriptedTransport t = new ScriptedTransport();
        final AssistantPanel[] holder = new AssistantPanel[1];
        edt(new Runnable() {
            public void run() {
                holder[0] = new AssistantPanel(cfg, EcuPresets.create(EcuPresets.STEALTH_PCM), new SimEcuPort(), new Runnable() {
                    public void run() {
                    }
                }, new AssistantPanel.Log() {
                    public void line(String s) {
                    }
                }, t);
                holder[0].inputArea().setText("hello");
                holder[0].send();
            }
        });
        assertTrue(holder[0].statusLabel().getText().contains("API key"));
        assertTrue(t.bodies.isEmpty());
        assertEquals("hello", holder[0].inputArea().getText(), "the text is kept for after the key is set");
    }
}
