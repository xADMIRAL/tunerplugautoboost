package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Sample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TorqueProxyTest {
    @Test
    void regressionRecoversConstantAcceleration() {
        List<Sample> s = new ArrayList<Sample>();
        for (int i = 0; i < 100; i++) {
            double t = i * 0.04;
            s.add(Sample.builder().time(t).rpm(2000 + 1200 * t).tps(100).map(150).build());
        }
        double[] a = TorqueProxy.accelSeries(s, 0.3);
        assertEquals(1200, a[50], 1e-6);
        assertEquals(1200, a[0], 1e-6);
    }

    @Test
    void profileBinsByRpmAndSkipsSettleAndLift() {
        Pull p = new Pull();
        double t = 0;
        for (int i = 0; i < 120; i++, t += 0.04) {
            double rpm = 2000 + 1000 * t;
            p.add(Sample.builder().time(t).rpm(rpm).tps(100).map(150).gear(3).build(), SampleState.STEADY);
        }
        for (int i = 0; i < 20; i++, t += 0.04) {
            p.add(Sample.builder().time(t).rpm(6800 - 500 * i * 0.04).tps(10).map(90).gear(3).build(), SampleState.NOT_WOT);
        }
        Axis rpm = Axis.of(2000, 3000, 4000, 5000, 6000, 7000);
        TorqueProxy.AccelProfile prof = TorqueProxy.profile(rpm, p, 0.3, 0.4, 80, 2000, 7000, 3);
        assertTrue(prof.n[0] < 15, "settle time removes early samples: " + prof.n[0]);
        assertEquals(1000, prof.mean(3), 1);
        assertTrue(prof.std(3) < 1);
        TorqueProxy.AccelProfile wrongGear = TorqueProxy.profile(rpm, p, 0.3, 0.4, 80, 2000, 7000, 4);
        assertEquals(0, wrongGear.totalSamples());
        assertEquals(10, TorqueProxy.gainPct(1000, 1100), 1e-9);
    }
}
