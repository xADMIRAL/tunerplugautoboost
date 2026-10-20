package io.github.xadmiral.boostautotune.plugin.ecu;

import io.github.xadmiral.boostautotune.core.session.EcuState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** An ECU whose boost enable switch says "Disabled" / "Enabled" instead of "Off" / "On", like TunerStudio refuses unknown texts. */
class OptionWordingTest {
    /** Wraps the demo port: one parameter gets other option words and rejects anything else. */
    private static final class Worded implements EcuPort {
        private final SimEcuPort d = new SimEcuPort();
        private final String param;
        private final List<String> options;
        String written;

        Worded(String param, List<String> options) {
            this.param = param;
            this.options = options;
        }

        public String signature() { return d.signature(); }
        public List<String> configurationNames() { return d.configurationNames(); }
        public List<String> parameterNames(String c) { return d.parameterNames(c); }
        public List<String> channelNames(String c) { return d.channelNames(c); }
        public ParamInfo parameterInfo(String c, String n) throws EcuException {
            ParamInfo i = d.parameterInfo(c, n);
            return n.equals(param) ? new ParamInfo(i.paramClass, i.units, i.min, i.max, i.decimals, i.shapeWidth, i.shapeHeight, options) : i;
        }
        public double readScalar(String c, String n) throws EcuException { return d.readScalar(c, n); }
        public String readOption(String c, String n) throws EcuException { return n.equals(param) && written != null ? written : d.readOption(c, n); }
        public double[] readArray1D(String c, String n) throws EcuException { return d.readArray1D(c, n); }
        public double[][] readArray2D(String c, String n) throws EcuException { return d.readArray2D(c, n); }
        public void writeScalar(String c, String n, double v) throws EcuException { d.writeScalar(c, n, v); }
        public void writeOption(String c, String n, String o) throws EcuException {
            if (n.equals(param)) {
                if (!options.contains(o)) {
                    throw new EcuException("No options found for Bit EcuParameter:" + n + " equal to the proposed " + o);
                }
                written = o;
                return;
            }
            d.writeOption(c, n, o);
        }
        public void writeArray2D(String c, String n, double[][] r) throws EcuException { d.writeArray2D(c, n, r); }
        public void writeArray1D(String c, String n, double[] v) throws EcuException { d.writeArray1D(c, n, v); }
        public void burn(String c) throws EcuException { d.burn(c); }
        public void subscribe(String c, List<String> ch, ChannelListener l) throws EcuException { d.subscribe(c, ch, l); }
        public void unsubscribe(ChannelListener l) { d.unsubscribe(l); }
        public double[] pollChannels(String c, List<String> ch) { return d.pollChannels(c, ch); }
        public List<UiTableInfo> uiTables(String c) { return d.uiTables(c); }
    }

    @Test
    void theBoostEnableSwitchIsWrittenWithTheIniWording() throws Exception {
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        Worded port = new Worded(b.enableParam, Arrays.asList("Disabled", "Enabled"));
        EcuAdapter a = new EcuAdapter(port, b);
        EcuState s = a.read(new ArrayList<String>());
        s.closedLoop = true;
        List<String> done = a.write(s, false, false, false);
        assertEquals("Enabled", port.written, "the preset says On, this INI says Enabled");
        assertTrue(done.contains(b.enableParam + " = Enabled"), done.toString());
        assertEquals("Enabled", a.resolveOption(a.config(), b.enableParam, "вкл"));
        // generic writes (drift presets) go through the same resolution
        a.writeAny(b.enableParam, "Off");
        assertEquals("Disabled", port.written);
        assertEquals("Disabled", a.readAny(b.enableParam));
    }

    @Test
    void aTextNothingInTheListMeansIsRefusedWithTheList() throws Exception {
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        Worded port = new Worded(b.modeParam, Arrays.asList("Manual", "Automatic"));
        EcuAdapter a = new EcuAdapter(port, b);
        EcuState s = a.read(new ArrayList<String>());
        try {
            a.write(s, false, false, false);
            fail("expected a refusal");
        } catch (EcuException e) {
            assertTrue(e.getMessage().contains("Manual"), e.getMessage());
            assertTrue(e.getMessage().contains(b.modeParam), e.getMessage());
            assertTrue(e.getMessage().contains("Setup"), e.getMessage());
        }
        assertNull(port.written, "nothing was written with a guessed text");
    }
}
