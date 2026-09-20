package io.github.xadmiral.boostautotune.plugin.ui;

import io.github.xadmiral.boostautotune.plugin.ecu.SimEcuPort;
import io.github.xadmiral.boostautotune.plugin.settings.SettingsStore;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.File;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Builds the whole UI headless: catches layout / renderer exceptions without a display. */
class MainPanelHeadlessTest {
    @Test
    void panelBuildsAndSettingsRoundTrip() throws Exception {
        final File f = File.createTempFile("boost-autotune", ".properties");
        f.delete();
        final MainPanel[] holder = new MainPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                holder[0] = new MainPanel(new SimEcuPort(), new SettingsStore(f));
            }
        });
        MainPanel p = holder[0];
        assertNotNull(p.controller().port());
        // text is scaled up by default, from every component's own base font, and the choice is remembered
        assertEquals(1.25, p.fontScale(), 1e-9);
        javax.swing.JLabel probe = new javax.swing.JLabel("x");
        float base = probe.getFont().getSize2D();
        assertEquals(Math.round(base * 1.25), Math.round(p.autotunePanel().getFont().getSize2D()));
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                holder[0].setFontScale(1.5);
                holder[0].setFontScale(1.5); // applying twice must not compound
            }
        });
        assertEquals(Math.round(base * 1.5), Math.round(p.autotunePanel().getFont().getSize2D()));
        assertEquals("150 %", new SettingsStore(f).loadRaw().getProperty("ui.fontScale"), "text size is remembered right away");
        assertEquals("boost_ctl_load_targets", p.binding().targetTable);
        p.config().targetStagesKpa.add(175.0);
        p.ignitionSweepConfig().maxAdvanceOverOriginalDeg = 3;
        p.alsConfig().targetKpa = 135;
        p.knockConfig().noiseTargetPct = 45;
        assertTrue(p.antilagPanel().presetRowCount() > 20);
        assertNotNull(p.assistantPanel());
        p.assistantConfig().profile = "Altezza 2JZ";
        p.assistantConfig().autoApply = true;
        p.assistantConfig().apiKey = "sk-secret";
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                holder[0].setFontScale(1.5); // any save carries the assistant settings along
            }
        });
        Properties again = new SettingsStore(f).loadRaw();
        assertEquals("Altezza 2JZ", again.getProperty("ui.assistant.profile"));
        assertEquals("true", again.getProperty("ui.assistant.autoApply"));
        assertEquals("claude-opus-5", again.getProperty("ui.assistant.model"));
        for (String k : again.stringPropertyNames()) {
            assertFalse(again.getProperty(k).contains("sk-secret"), "the API key must not be in the settings file: " + k);
        }
        SettingsStore store = new SettingsStore(f);
        store.save(p.config(), p.vvtSweepConfig(), p.ignitionSweepConfig(), p.vvtPidConfig(), p.alsConfig(), p.knockConfig(), p.binding(), new Properties());
        assertTrue(f.exists());
        Properties raw = store.loadRaw();
        assertEquals("150, 175", raw.getProperty("tune.targetStagesKpa"));
        assertEquals("boost_ctl_cl_pwm_targs1", raw.getProperty("ecu.biasTable"));
        assertEquals("advanceTable1", raw.getProperty("ecu.sparkTable"));
        assertEquals("3", raw.getProperty("ignsweep.maxAdvanceOverOriginalDeg"));
        assertEquals("135", raw.getProperty("als.targetKpa"));
        assertEquals("3000", raw.getProperty("als.holdRpm"));
        assertEquals("3", raw.getProperty("als.holdSec"));
        assertFalse(p.antilagPanel().advancedShown());
        assertEquals("true", raw.getProperty("tune.fastSpool"));
        assertEquals("boost_ctl_lowerlimit", raw.getProperty("ecu.closedLoopWindowParam"));
        assertEquals("als_timing", raw.getProperty("ecu.alsTimingTable"));
        assertEquals("45", raw.getProperty("knock.noiseTargetPct"));
        assertEquals("knock_thresholds", raw.getProperty("ecu.knockThresholdTable"));
        assertEquals("knock_gain", raw.getProperty("ecu.knockGainPrefix"));
        io.github.xadmiral.boostautotune.core.knock.KnockCalConfig kc = new io.github.xadmiral.boostautotune.core.knock.KnockCalConfig();
        SettingsStore.knockFrom(kc, raw, "knock.");
        assertEquals(45, kc.noiseTargetPct, 1e-9);
        assertEquals(30, kc.marginPct, 1e-9);
        io.github.xadmiral.boostautotune.core.als.AlsConfig als = new io.github.xadmiral.boostautotune.core.als.AlsConfig();
        SettingsStore.alsFrom(als, raw, "als.");
        assertEquals(135, als.targetKpa, 1e-9);
        assertEquals("0, -10, -5, 5, 10", raw.getProperty("vvtsweep.candidateOffsets"));
        io.github.xadmiral.boostautotune.core.sweep.SweepConfig ign = io.github.xadmiral.boostautotune.core.sweep.SweepConfig.ignitionDefaults();
        SettingsStore.sweepFrom(ign, raw, "ignsweep.");
        assertEquals(3, ign.maxAdvanceOverOriginalDeg, 1e-9);
        assertEquals(13.0, ign.maxWotAfr, 1e-9);
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                holder[0].dispose();
            }
        });
        f.delete();
    }
}
