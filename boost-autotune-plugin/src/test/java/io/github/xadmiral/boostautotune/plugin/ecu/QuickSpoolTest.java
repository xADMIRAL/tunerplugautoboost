package io.github.xadmiral.boostautotune.plugin.ecu;

import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.session.EcuState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Quick spool: found by name in whatever INI, read and written in kPa below the target, off in the safe state. */
class QuickSpoolTest {
    /** The demo ECU with its quick spool hidden and other parameters added, the way another INI would name them. */
    private static final class OtherIni implements EcuPort {
        final SimEcuPort d = new SimEcuPort();
        final Set<String> hidden = new HashSet<String>(Arrays.asList(SimEcuPort.QS_SWITCH, SimEcuPort.QS_KPA, SimEcuPort.QS_RPM));
        final Map<String, ParamInfo> infos = new HashMap<String, ParamInfo>();
        final Map<String, String> opts = new HashMap<String, String>();
        final Map<String, Double> scal = new HashMap<String, Double>();

        void option(String name, String value, String... options) {
            infos.put(name, new ParamInfo("bits", "", 0, 0, 0, 1, 1, Arrays.asList(options)));
            opts.put(name, value);
        }

        void scalar(String name, double value, String units, double min, double max) {
            infos.put(name, new ParamInfo("scalar", units, min, max, 0, 1, 1, Collections.<String>emptyList()));
            scal.put(name, value);
        }

        public String signature() { return d.signature(); }
        public List<String> configurationNames() { return d.configurationNames(); }
        public List<String> parameterNames(String c) {
            List<String> n = new ArrayList<String>(d.parameterNames(c));
            n.removeAll(hidden);
            n.addAll(infos.keySet());
            return n;
        }
        public List<String> channelNames(String c) { return d.channelNames(c); }
        public ParamInfo parameterInfo(String c, String n) throws EcuException {
            if (infos.containsKey(n)) {
                return infos.get(n);
            }
            if (hidden.contains(n)) {
                throw new EcuException("Unknown parameter " + n);
            }
            return d.parameterInfo(c, n);
        }
        public double readScalar(String c, String n) throws EcuException { return scal.containsKey(n) ? scal.get(n) : d.readScalar(c, n); }
        public String readOption(String c, String n) throws EcuException { return opts.containsKey(n) ? opts.get(n) : d.readOption(c, n); }
        public double[] readArray1D(String c, String n) throws EcuException { return d.readArray1D(c, n); }
        public double[][] readArray2D(String c, String n) throws EcuException { return d.readArray2D(c, n); }
        public void writeScalar(String c, String n, double v) throws EcuException {
            if (scal.containsKey(n)) {
                scal.put(n, v);
            } else {
                d.writeScalar(c, n, v);
            }
        }
        public void writeOption(String c, String n, String o) throws EcuException {
            if (opts.containsKey(n)) {
                if (!infos.get(n).options.contains(o)) {
                    throw new EcuException("No options found for Bit EcuParameter:" + n + " equal to the proposed " + o);
                }
                opts.put(n, o);
            } else {
                d.writeOption(c, n, o);
            }
        }
        public void writeArray2D(String c, String n, double[][] r) throws EcuException { d.writeArray2D(c, n, r); }
        public void writeArray1D(String c, String n, double[] v) throws EcuException { d.writeArray1D(c, n, v); }
        public void burn(String c) throws EcuException { d.burn(c); }
        public void subscribe(String c, List<String> ch, ChannelListener l) throws EcuException { d.subscribe(c, ch, l); }
        public void unsubscribe(ChannelListener l) { d.unsubscribe(l); }
        public double[] pollChannels(String c, List<String> ch) { return d.pollChannels(c, ch); }
        public List<UiTableInfo> uiTables(String c) { return d.uiTables(c); }
    }

    private static double[][] filled(int rows, int cols, double v) {
        double[][] a = new double[rows][cols];
        for (double[] r : a) {
            Arrays.fill(r, v);
        }
        return a;
    }

    @Test
    void namesOfQuickSpoolAndOfOtherFeatures() {
        assertTrue(QuickSpoolFinder.isQuickSpoolName("boost_ctl_quickspool"));
        assertTrue(QuickSpoolFinder.isQuickSpoolName("QuickSpoolOn"));
        assertTrue(QuickSpoolFinder.isQuickSpoolName("boost_qs_kpa"));
        assertFalse(QuickSpoolFinder.isQuickSpoolName("als_respool_kpa"), "anti-lag");
        assertFalse(QuickSpoolFinder.isQuickSpoolName("launch_spool_rpm"), "launch control");
        assertFalse(QuickSpoolFinder.isQuickSpoolName("qs_on"), "a bare qs is not boost");
        assertFalse(QuickSpoolFinder.isQuickSpoolName("boost_ctl_Kp"));
    }

    @Test
    void theDemoEcusQuickSpoolIsFoundAndReadAsKpaBelowTheTarget() throws Exception {
        SimEcuPort port = new SimEcuPort();
        QuickSpoolFinder.Result r = QuickSpoolFinder.find(port, SimEcuPort.CONFIG);
        assertEquals(SimEcuPort.QS_SWITCH, r.switchParam);
        assertEquals("On", r.onOption);
        assertEquals(SimEcuPort.QS_KPA, r.kpaParam);
        assertTrue(r.related.contains(SimEcuPort.QS_RPM), r.describe());

        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        EcuAdapter a = new EcuAdapter(port, b);
        assertNotNull(a.discoverQuickSpool());
        assertNull(a.discoverQuickSpool(), "named now: no second search");
        EcuState s = a.read(new ArrayList<String>());
        assertEquals(Boolean.TRUE, s.quickSpoolOn);
        assertEquals(6, s.quickSpoolDeltaKpa, 1e-9);
        assertEquals("below target", a.quickSpoolMeaning());
        s.quickSpoolDeltaKpa = 9;
        s.quickSpoolOn = false;
        List<String> done = a.write(s, false, false, false);
        assertEquals(9, port.readScalar(SimEcuPort.CONFIG, SimEcuPort.QS_KPA), 1e-9);
        assertEquals("Off", port.readOption(SimEcuPort.CONFIG, SimEcuPort.QS_SWITCH));
        assertTrue(done.contains(SimEcuPort.QS_SWITCH + " = Off"), done.toString());
        // an ECU state without quick spool (another mode, an old undo copy) leaves it alone
        EcuState none = s.copy();
        none.quickSpoolOn = null;
        a.write(none, false, false, false);
        assertEquals("Off", port.readOption(SimEcuPort.CONFIG, SimEcuPort.QS_SWITCH));
    }

    @Test
    void anotherIniWordingAnAbsoluteHandOverAndTheSafeState() throws Exception {
        OtherIni port = new OtherIni();
        port.option("boost_spool_mode", "\"Enabled\"", "\"Disabled\"", "\"Enabled\"");
        port.scalar("boost_spool_end", 160, "kPa", 100, 300);
        port.scalar("boost_spool_tps", 80, "%", 0, 100);
        port.scalar("boost_spool_psi", 5, "psi", 0, 30);
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        port.d.writeArray2D(SimEcuPort.CONFIG, b.targetTable, filled(8, 8, 170));
        EcuAdapter a = new EcuAdapter(port, b);
        QuickSpoolFinder.Result r = a.discoverQuickSpool();
        assertEquals("boost_spool_mode", r.switchParam);
        assertEquals("Enabled", r.onOption);
        assertEquals("boost_spool_end", r.kpaParam, r.describe());
        assertTrue(r.related.contains("boost_spool_tps") && r.related.contains("boost_spool_psi"), "not kPa: never written: " + r.describe());

        EcuState s = a.read(new ArrayList<String>());
        assertEquals(Boolean.TRUE, s.quickSpoolOn);
        assertEquals("absolute", a.quickSpoolMeaning(), "160 kPa can only be an absolute boost pressure");
        assertEquals(10, s.quickSpoolDeltaKpa, 1e-9, "target 170, valve shut up to 160");
        // untouched hand-over: the ECU's own value stays, whatever the targets are now
        EcuState stage = s.copy();
        stage.targetTable = Grid.filled(s.targetTable.xAxis(), s.targetTable.yAxis(), 150);
        a.write(stage, true, false, false);
        assertEquals(160, port.readScalar(SimEcuPort.CONFIG, "boost_spool_end"), 1e-9);
        // a tuned hand-over is written against the stage target
        stage.quickSpoolDeltaKpa = 12;
        a.write(stage, true, false, false);
        assertEquals(138, port.readScalar(SimEcuPort.CONFIG, "boost_spool_end"), 1e-9);
        assertEquals("\"Enabled\"", port.readOption(SimEcuPort.CONFIG, "boost_spool_mode"));
        // safety abort: quick spool off first, it would hold the valve shut whatever the tables say
        List<String> safe = a.writeSafeState(stage);
        assertEquals("\"Disabled\"", port.readOption(SimEcuPort.CONFIG, "boost_spool_mode"));
        assertTrue(safe.get(0).startsWith("boost_spool_mode = "), safe.toString());
        assertEquals(80, port.readScalar(SimEcuPort.CONFIG, "boost_spool_tps"), 1e-9);
    }

    @Test
    void aHandOverInPsiIsSwitchedOnlyAndTheMeaningCanBeForced() throws Exception {
        OtherIni port = new OtherIni();
        port.option("boost_spool_on", "Off", "Off", "On");
        port.scalar("boost_spool_psi", 5, "psi", 0, 30);
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        b.quickSpoolParam = "boost_spool_on";
        b.quickSpoolKpaParam = "boost_spool_psi";
        EcuAdapter a = new EcuAdapter(port, b);
        List<String> warnings = new ArrayList<String>();
        EcuState s = a.read(warnings);
        assertEquals(Boolean.FALSE, s.quickSpoolOn);
        assertTrue(Double.isNaN(s.quickSpoolDeltaKpa));
        assertTrue(warnings.toString().contains("not kPa"), warnings.toString());
        s.quickSpoolOn = true;
        a.write(s, false, false, false);
        assertEquals("On", port.readOption(SimEcuPort.CONFIG, "boost_spool_on"));
        assertEquals(5, port.readScalar(SimEcuPort.CONFIG, "boost_spool_psi"), 1e-9, "never written");

        assertFalse(EcuAdapter.absoluteHandOver("auto", "boost_qs_delta", 150), "the name says delta");
        assertTrue(EcuAdapter.absoluteHandOver("auto", "boost_qs_end", 160));
        assertFalse(EcuAdapter.absoluteHandOver("auto", "boost_qs_end", 12));
        assertFalse(EcuAdapter.absoluteHandOver("below target", "boost_qs_end", 160));
        assertTrue(EcuAdapter.absoluteHandOver("absolute", "boost_qs_end", 12));
    }
}
