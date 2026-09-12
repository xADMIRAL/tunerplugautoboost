package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.model.Sample;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SampleClassifierTest {
    private static AutotuneConfig cfg() {
        AutotuneConfig c = new AutotuneConfig();
        c.wotLoadThreshold = 80;
        c.minCltC = 70;
        c.minRpm = 1800;
        c.maxRpm = 7000;
        c.settleDelaySec = 0.5;
        c.maxMapSlopeKpaPerSec = 60;
        c.maxBoostKpa = 200;
        c.minPullDurationSec = 1.0;
        c.pullEndHoldSec = 0.3;
        return c;
    }

    private static Sample s(double t, double tps, double rpm, double map, double clt, boolean cut) {
        return Sample.builder().time(t).tps(tps).rpm(rpm).map(map).clt(clt).boostCut(cut).target(150).duty(50).build();
    }

    @Test
    void filtersInPriorityOrder() {
        SampleClassifier c = new SampleClassifier(cfg());
        assertEquals(SampleState.NOT_WOT, c.classify(s(0, 20, 3000, 100, 90, false)));
        assertEquals(SampleState.OVERBOOST, c.classify(s(0.1, 100, 3000, 210, 90, false)));
        assertEquals(SampleState.BOOST_CUT, c.classify(s(0.2, 100, 3000, 150, 90, true)));
        assertEquals(SampleState.COLD, c.classify(s(0.3, 100, 3000, 150, 50, false)));
        assertEquals(SampleState.RPM_OUT_OF_RANGE, c.classify(s(0.4, 100, 1000, 150, 90, false)));
        assertEquals(SampleState.TRANSIENT, c.classify(s(0.5, 100, 3000, 150, 90, false))); // settling
        assertEquals(SampleState.STEADY, c.classify(s(0.8, 100, 3000, 150.5, 90, false)));
        assertEquals(SampleState.TRANSIENT, c.classify(s(0.9, 100, 3000, 195, 90, false))); // slope over the window
    }

    @Test
    void segmenterBuildsPullsAndIgnoresShortOnes() {
        AutotuneConfig cfg = cfg();
        PullSegmenter seg = new PullSegmenter(cfg, new SampleClassifier(cfg));
        double t = 0;
        for (int i = 0; i < 10; i++, t += 0.05) seg.feed(s(t, 10, 2500, 90, 90, false));
        for (int i = 0; i < 10; i++, t += 0.05) seg.feed(s(t, 100, 3000, 150, 90, false)); // 0.5 s: too short
        for (int i = 0; i < 20; i++, t += 0.05) seg.feed(s(t, 10, 2500, 90, 90, false));
        for (int i = 0; i < 40; i++, t += 0.05) seg.feed(s(t, 100, 3000 + i * 40, 150, 90, false)); // 2 s pull
        assertTrue(seg.pullInProgress());
        for (int i = 0; i < 20; i++, t += 0.05) seg.feed(s(t, 10, 2500, 90, 90, false));
        assertFalse(seg.pullInProgress());
        List<Pull> pulls = seg.pulls();
        assertEquals(1, pulls.size());
        assertEquals(40, pulls.get(0).size());
        assertTrue(pulls.get(0).steadyCount() > 20);
        assertTrue(seg.idleSeconds() > 0.9);
        seg.reset();
        assertTrue(Double.isNaN(seg.idleSeconds()));
    }
}
