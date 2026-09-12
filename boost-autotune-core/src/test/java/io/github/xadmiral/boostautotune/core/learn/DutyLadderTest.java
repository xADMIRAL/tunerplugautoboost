package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.model.Axis;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DutyLadderTest {
    private static AutotuneConfig cfg() {
        AutotuneConfig c = new AutotuneConfig();
        c.targetStagesKpa = new ArrayList<Double>(Arrays.asList(150.0, 170.0));
        c.characterizeStartDuty = 20;
        c.characterizeStepPct = 20;
        c.characterizeHeadroomKpa = 10;
        c.characterizeMinRuns = 2;
        c.characterizeMaxDuty = 90;
        c.maxBoostKpa = 200;
        c.predictionMarginKpa = 15;
        return c;
    }

    @Test
    void startsAtConfiguredDutyAndStopsWhenCovered() {
        PlantModel m = new PlantModel(Axis.of(3000, 5000), 2, 1.5);
        DutyLadder.Step first = DutyLadder.next(m, cfg(), 0, 100, new ArrayList<Double>(), Double.NaN);
        assertFalse(first.done);
        assertEquals(20, first.duty, 1e-9);
        m.addObservation(5000, 20, 140, 1);
        DutyLadder.Step second = DutyLadder.next(m, cfg(), 0, 100, Arrays.asList(20.0), 141);
        assertFalse(second.done);
        assertEquals(40, second.duty, 1e-9);
        m.addObservation(5000, 40, 185, 1);
        DutyLadder.Step done = DutyLadder.next(m, cfg(), 0, 100, Arrays.asList(20.0, 40.0), 186);
        assertTrue(done.done);
    }

    @Test
    void shrinksStepWhenPredictionExceedsLimit() {
        PlantModel m = new PlantModel(Axis.of(3000, 5000), 2, 1.5);
        m.addObservation(5000, 20, 150, 1);
        m.addObservation(5000, 40, 175, 1);   // 1.25 kPa/% -> extrapolates with default gain 1.5
        List<Double> done = Arrays.asList(20.0, 40.0);
        DutyLadder.Step s = DutyLadder.next(m, cfg(), 0, 100, done, 176);
        // limit 185 kPa: 175 + (d-40)*1.5 <= 185 -> d <= 46.7
        assertFalse(s.done);
        assertTrue(s.duty > 44 && s.duty < 47, "duty " + s.duty);
        m.addObservation(5000, 46, 183, 1);
        DutyLadder.Step s2 = DutyLadder.next(m, cfg(), 0, 100, Arrays.asList(20.0, 40.0, 46.0), 184);
        assertTrue(s2.done, s2.reason);
    }

    @Test
    void respectsDutyCeiling() {
        PlantModel m = new PlantModel(Axis.of(3000, 5000), 2, 1.5);
        m.addObservation(5000, 80, 140, 1);
        DutyLadder.Step s = DutyLadder.next(m, cfg(), 0, 100, Arrays.asList(80.0), 141);
        assertFalse(s.done);
        assertEquals(90, s.duty, 1e-9);
        DutyLadder.Step s2 = DutyLadder.next(m, cfg(), 0, 100, Arrays.asList(80.0, 90.0), 150);
        assertTrue(s2.done);
    }
}
