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
        assertEquals("boost_ctl_load_targets", p.binding().targetTable);
        p.config().targetStagesKpa.add(175.0);
        SettingsStore store = new SettingsStore(f);
        store.save(p.config(), p.binding(), new Properties());
        assertTrue(f.exists());
        Properties raw = store.loadRaw();
        assertEquals("150, 175", raw.getProperty("tune.targetStagesKpa"));
        assertEquals("boost_ctl_cl_pwm_targs1", raw.getProperty("ecu.biasTable"));
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                holder[0].dispose();
            }
        });
        f.delete();
    }
}
