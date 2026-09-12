package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlantModelTest {
    private static final Axis RPM = Axis.of(2000, 3000, 4000, 5000);

    @Test
    void isotonicRegressionRemovesDips() {
        double[] y = {1, 3, 2, 5};
        double[] w = {1, 1, 1, 1};
        PlantModel.isotonic(y, w);
        assertEquals(1, y[0], 1e-9);
        assertEquals(2.5, y[1], 1e-9);
        assertEquals(2.5, y[2], 1e-9);
        assertEquals(5, y[3], 1e-9);
    }

    @Test
    void interpolatesBetweenDutyLevelsAndCapsExtrapolation() {
        PlantModel m = new PlantModel(RPM, 2, 1.5);
        for (int i = 0; i < 5; i++) {
            m.addObservation(4000, 30, 150 + i * 0.1, 1);
            m.addObservation(4000, 50, 180 + i * 0.1, 1);
        }
        DutyEstimate mid = m.dutyFor(2, 165, 0, 100, 15);
        assertEquals(DutyEstimate.Quality.INTERPOLATED, mid.quality);
        assertEquals(40, mid.duty, 0.5);
        DutyEstimate exact = m.dutyFor(2, 150.2, 0, 100, 15);
        assertEquals(DutyEstimate.Quality.MEASURED, exact.quality);
        DutyEstimate high = m.dutyFor(2, 260, 0, 100, 15);
        assertEquals(DutyEstimate.Quality.EXTRAPOLATED, high.quality);
        assertEquals(65, high.duty, 0.5); // 50 + 15 cap
        DutyEstimate low = m.dutyFor(2, 135, 0, 100, 15);
        assertEquals(20, low.duty, 0.5); // slope 1.5 kPa/% downwards
        assertEquals(1.5, m.gainKpaPerPct(), 0.05);
        assertEquals(180 + 10 * 1.5, m.predictBoost(2, 60), 0.5);
        assertEquals(165, m.predictBoost(2, 40), 0.5);
        assertTrue(m.hasData(2));
        assertFalse(m.hasData(0));
    }

    @Test
    void splitsObservationBetweenColumnsAndDecays() {
        PlantModel m = new PlantModel(RPM, 2, 1.5);
        m.addObservation(3500, 40, 160, 2);
        assertEquals(1.0, m.totalWeight(1), 1e-9);
        assertEquals(1.0, m.totalWeight(2), 1e-9);
        m.decay(0.5);
        assertEquals(0.5, m.totalWeight(1), 1e-9);
        m.decay(0.001);
        assertFalse(m.hasData(1));
    }

    @Test
    void biasBuilderBorrowsAndRespectsAuthority() {
        PlantModel m = new PlantModel(RPM, 2, 1.5);
        for (int i = 0; i < 4; i++) {
            m.addObservation(2000, 30, 140, 1);
            m.addObservation(2000, 50, 170, 1);
            m.addObservation(4000, 30, 150, 1);
            m.addObservation(4000, 50, 180, 1);
        }
        AutotuneConfig cfg = new AutotuneConfig();
        cfg.biasMaxStepPct = 5;
        Grid existing = Grid.filled(RPM, Axis.of(140, 160, 180), 100);
        BiasBuildResult unlimited = BiasTableBuilder.build(existing, m, cfg, 0, 100, true);
        assertEquals(30, unlimited.grid.get(0, 0), 0.5);      // 2000 rpm, 140 kPa measured
        assertEquals(50, unlimited.grid.get(2, 2), 0.5);      // 4000 rpm, 180 kPa measured
        assertEquals(DutyEstimate.Quality.BORROWED, unlimited.quality[0][1]); // 3000 rpm borrowed
        assertEquals(DutyEstimate.Quality.BORROWED, unlimited.quality[0][3]); // 5000 rpm borrowed from 4000
        assertEquals(unlimited.grid.get(2, 0), unlimited.grid.get(3, 0), 1e-9);
        // 4000 rpm extrapolates 140 kPa downwards (30 - 10/1.5 = 23.3); 3000 rpm sits half way
        assertEquals(26.7, unlimited.grid.get(1, 0), 0.2);
        assertEquals(0, unlimited.unchangedCells);
        BiasBuildResult limited = BiasTableBuilder.build(existing, m, cfg, 0, 100, false);
        assertEquals(95, limited.grid.get(0, 0), 1e-9); // 100 - 5 authority
        assertEquals(5, limited.maxChange, 1e-9);
        List<PlantModel.Pt> curve = m.curve(0);
        assertEquals(2, curve.size());
    }

    @Test
    void emptyModelLeavesTableUnchanged() {
        PlantModel m = new PlantModel(RPM, 2, 1.5);
        Grid existing = Grid.filled(RPM, Axis.of(140, 160), 42);
        BiasBuildResult r = BiasTableBuilder.build(existing, m, new AutotuneConfig(), 0, 100, true);
        assertEquals(0, r.maxAbsDiffFrom(existing), 1e-9);
        assertEquals(8, r.unchangedCells);
        assertFalse(r.notes.isEmpty());
    }
}
