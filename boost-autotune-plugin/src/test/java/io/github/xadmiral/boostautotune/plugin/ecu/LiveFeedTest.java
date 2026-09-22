package io.github.xadmiral.boostautotune.plugin.ecu;

import io.github.xadmiral.boostautotune.core.model.Sample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LiveFeedTest {
    private static final class Sink implements LiveFeed.SampleListener {
        final List<Sample> out = new ArrayList<Sample>();

        public void sample(Sample s) {
            out.add(s);
        }
    }

    @Test
    void emitsOnMapAndReportsWhatDelivers() {
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        b.timeChannel = "seconds";
        Sink sink = new Sink();
        LiveFeed f = new LiveFeed(b, sink);
        f.setSubscribed(f.requiredChannels().size());
        assertTrue(f.requiredChannels().contains("map"));
        assertTrue(f.optionalChannels().contains("knock_cyl06"));
        assertEquals(0, f.status().samples);
        f.channelValue("rpm", 3000);
        f.channelValue("tps", 100);
        f.channelValue("seconds", 10);
        assertTrue(sink.out.isEmpty(), "no sample before the MAP channel updates");
        f.channelValue("map", 150);
        assertEquals(1, sink.out.size());
        assertEquals(3000, sink.out.get(0).rpm, 1e-9);
        assertEquals(150, sink.out.get(0).map, 1e-9);
        LiveFeed.Status st = f.status();
        assertEquals(1, st.samples);
        assertEquals("callbacks", st.source);
        assertEquals(4, st.delivering);
        assertTrue(st.silent.contains("coolant"));
        assertFalse(st.silent.contains("map"));
        assertTrue(f.secondsSinceLastCallback() < 1);
    }

    @Test
    void keepsFlowingWhenMapStaysSilent() throws Exception {
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        Sink sink = new Sink();
        LiveFeed f = new LiveFeed(b, sink);
        f.mapSilentSec = 0.1;
        f.channelValue("rpm", 900);
        assertTrue(sink.out.isEmpty());
        Thread.sleep(150);
        f.channelValue("rpm", 950);
        assertEquals(1, sink.out.size(), "another channel emits once MAP has been silent for a while");
        assertTrue(Double.isNaN(sink.out.get(0).map));
        assertTrue(f.status().silent.contains("map"));
    }

    @Test
    void polledValuesMakeSamplesWithoutCountingAsCallbacks() throws Exception {
        EcuBinding b = EcuPresets.create(EcuPresets.STEALTH_PCM);
        Sink sink = new Sink();
        LiveFeed f = new LiveFeed(b, sink);
        List<String> names = Arrays.asList("map", "rpm", "tps", "coolant");
        f.pushPolled(names, new double[]{120, 2500, 40, 85});
        assertEquals(1, sink.out.size());
        assertEquals(2500, sink.out.get(0).rpm, 1e-9);
        assertEquals(120, sink.out.get(0).map, 1e-9);
        assertEquals(85, sink.out.get(0).clt, 1e-9);
        Thread.sleep(20);
        f.pushPolled(names, new double[]{121, 2510, Double.NaN, 85});
        assertEquals(2, sink.out.size());
        assertEquals(40, sink.out.get(1).tps, 1e-9, "a channel that failed to poll keeps its last value");
        assertTrue(f.secondsSinceLastCallback() >= 0.0 && f.status().samples == 2);
        assertEquals(0, f.status().silent.indexOf("boost_targ_1") >= 0 ? 0 : 1, "channels never polled are reported silent");
    }
}
