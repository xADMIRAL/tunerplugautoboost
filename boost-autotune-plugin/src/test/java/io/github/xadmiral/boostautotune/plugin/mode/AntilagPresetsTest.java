package io.github.xadmiral.boostautotune.plugin.mode;

import io.github.xadmiral.boostautotune.core.als.AlsConfig;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuAdapter;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuBinding;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuPresets;
import io.github.xadmiral.boostautotune.plugin.ecu.SimEcuPort;
import io.github.xadmiral.boostautotune.plugin.ui.AntilagPanel;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The popcorn presets: every parameter exists on the simulated MS3, writes and undo round-trip. */
class AntilagPresetsTest {

    private static boolean isNumber(String v) {
        try {
            Double.parseDouble(v);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String value(List<AntilagPresets.Setting> s, String param) {
        for (AntilagPresets.Setting x : s) {
            if (x.param.equals(param)) {
                return x.value;
            }
        }
        return null;
    }

    @Test
    void popcornPresetsAreListedAndCarryNoGoals() {
        assertTrue(AntilagPresets.names().contains(AntilagPresets.POPCORN_OVERRUN));
        assertTrue(AntilagPresets.names().contains(AntilagPresets.POPCORN_ALS));
        assertNull(AntilagPresets.goals(AntilagPresets.POPCORN_OVERRUN));
        assertNull(AntilagPresets.goals(AntilagPresets.POPCORN_ALS));
        assertTrue(AntilagPresets.isPopcorn(AntilagPresets.POPCORN_ALS));
        assertFalse(AntilagPresets.isPopcorn(AntilagPresets.DRIFT_LIGHT));
    }

    @Test
    void overrunPopcornKeepsTheCutButOpensARetardedWindow() {
        List<AntilagPresets.Setting> s = AntilagPresets.create(AntilagPresets.POPCORN_OVERRUN);
        assertEquals("On", value(s, "OvrRunC"));
        assertEquals("On", value(s, "OvrRunC_progcut"));
        assertEquals("On", value(s, "OvrRunC_progign"));
        assertEquals("-20", value(s, "fc_timing"));
        assertEquals("2.5", value(s, "fc_transition_time"));
        assertEquals("2500", value(s, "fc_rpm"));
        assertEquals("1500", value(s, "fc_rpm_lower"));
        assertEquals("Off", value(s, "als_in_pin"), "the anti-lag is switched off so it does not fight the window");
        assertNull(value(s, "launch_opt_on"), "flat shift is left alone");
    }

    @Test
    void alsPopcornArmsTheAntilagWithoutAir() {
        List<AntilagPresets.Setting> s = AntilagPresets.create(AntilagPresets.POPCORN_ALS, true);
        assertEquals("Always ON", value(s, "als_in_pin"));
        assertEquals("Off", value(s, "als_opt_idle"), "no throttle / idle air: pops, not boost hold");
        assertEquals("On", value(s, "als_opt_sc"));
        assertEquals("On", value(s, "als_opt_fuel"));
        assertEquals("Off", value(s, "OvrRunC"), "the pops need fuel");
        assertNull(value(s, "als_iac_pos"), "no throttle opening even on drive-by-wire");
        assertEquals("2", value(s, "als_maxtime"));
    }

    @Test
    void everyPopcornParameterExistsOnTheSimulatedEcuAndWritesRoundTrip() throws Exception {
        for (String name : new String[]{AntilagPresets.POPCORN_OVERRUN, AntilagPresets.POPCORN_ALS}) {
            SimEcuPort port = new SimEcuPort();
            EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
            EcuAdapter a = new EcuAdapter(port, b);
            for (AntilagPresets.Setting s : AntilagPresets.create(name)) {
                assertTrue(a.hasParameter(s.param), name + ": " + s.param + " missing on the simulated MS3");
                a.writeAny(s.param, s.value);
                String back = a.readAny(s.param);
                if (s.param.equals("als_timing") || s.param.equals("als_addfuel") || s.param.equals("als_sparkcut") || s.param.equals("als_fuelcut")) {
                    assertTrue(back.startsWith(s.value + " "), s.param + " filled with " + s.value + ": " + back);
                } else if (s.param.endsWith("_rpms") || s.param.endsWith("_tpss")) {
                    assertEquals(s.value, back.replace(";", "").trim());
                } else if (isNumber(s.value)) {
                    assertEquals(Double.parseDouble(s.value), Double.parseDouble(back), 1e-6, s.param);
                } else {
                    assertEquals(s.value, back, s.param);
                }
            }
        }
    }

    @Test
    void panelWritesThePopcornPresetAndRestoresIt() throws Exception {
        final SimEcuPort port = new SimEcuPort();
        final EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        final List<String> log = new ArrayList<String>();
        final AntilagPanel[] holder = new AntilagPanel[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                holder[0] = new AntilagPanel(new AlsConfig(), b, port, new Runnable() {
                    public void run() {
                    }
                }, new AntilagPanel.Log() {
                    public void line(String s) {
                        log.add(s);
                    }
                });
                holder[0].loadPreset(AntilagPresets.POPCORN_OVERRUN);
            }
        });
        AntilagPanel p = holder[0];
        assertEquals(17, p.presetRowCount());
        assertEquals(2000, port.readScalar(SimEcuPort.CONFIG, "fc_rpm"), 1e-9);
        final int[] written = new int[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                written[0] = holder[0].writeSelected(false);
            }
        });
        assertEquals(17, written[0]);
        assertEquals(2500, port.readScalar(SimEcuPort.CONFIG, "fc_rpm"), 1e-9);
        assertEquals(-20, port.readScalar(SimEcuPort.CONFIG, "fc_timing"), 1e-9);
        assertEquals("On", port.readOption(SimEcuPort.CONFIG, "OvrRunC_progign"));
        assertTrue(p.hasUndo());
        SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                holder[0].restoreOriginal();
            }
        });
        assertFalse(p.hasUndo());
        assertEquals(2000, port.readScalar(SimEcuPort.CONFIG, "fc_rpm"), 1e-9);
        assertEquals(0, port.readScalar(SimEcuPort.CONFIG, "fc_timing"), 1e-9);
        assertEquals("Off", port.readOption(SimEcuPort.CONFIG, "OvrRunC_progign"));
        assertFalse(log.isEmpty());
    }
}
