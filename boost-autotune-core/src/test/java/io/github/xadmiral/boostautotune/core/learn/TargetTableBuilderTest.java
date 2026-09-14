package io.github.xadmiral.boostautotune.core.learn;

import io.github.xadmiral.boostautotune.core.config.AutotuneConfig;
import io.github.xadmiral.boostautotune.core.model.Axis;
import io.github.xadmiral.boostautotune.core.model.Grid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TargetTableBuilderTest {
    private static Grid ecuTargets() {
        return Grid.filled(Axis.of(1500, 2500, 3500, 4500, 5500, 6500), Axis.of(0, 40, 80, 100), 100);
    }

    @Test
    void rampsFromWastegateToTargetAndKeepsPartThrottleWhenAsked() {
        AutotuneConfig cfg = new AutotuneConfig();
        cfg.fastSpool = false; // classic RPM ramp
        cfg.wastegateKpa = 130;
        cfg.spoolStartRpm = 2500;
        cfg.fullTargetRpm = 3500;
        cfg.wotLoadThreshold = 80;
        cfg.scalePartThrottleRows = false;
        Grid g = TargetTableBuilder.build(ecuTargets(), 160, cfg, 400);
        assertEquals(130, g.get(0, 3), 1e-9);  // 1500 rpm WOT: wastegate
        assertEquals(130, g.get(1, 3), 1e-9);  // 2500 rpm: spool start
        assertEquals(160, g.get(2, 3), 1e-9);  // 3500 rpm: full target
        assertEquals(160, g.get(5, 2), 1e-9);  // 80 % row is WOT too
        assertEquals(100, g.get(5, 1), 1e-9);  // part throttle row untouched
    }

    @Test
    void scalesPartThrottleRowsAndAppliesTaper() {
        AutotuneConfig cfg = new AutotuneConfig();
        cfg.wastegateKpa = 130;
        cfg.spoolStartRpm = 0;
        cfg.fullTargetRpm = 0;
        cfg.wotLoadThreshold = 80;
        cfg.scalePartThrottleRows = true;
        cfg.partThrottleFloorKpa = 100;
        cfg.highRpmTaperKpa = 10;
        cfg.highRpmTaperStartRpm = 5500;
        Grid g = TargetTableBuilder.build(ecuTargets(), 160, cfg, 400);
        assertEquals(160, g.get(0, 3), 1e-9);
        assertEquals(100, g.get(0, 0), 1e-9);
        assertEquals(130, g.get(0, 1), 1e-9); // half way between floor and 160
        assertEquals(150, g.get(5, 3), 1e-9); // tapered by 10 at the last bin
        assertEquals(160, g.get(4, 3), 1e-9); // taper starts at 5500
    }

    @Test
    void clampsToHardLimitAndTableMax() {
        AutotuneConfig cfg = new AutotuneConfig();
        cfg.maxBoostKpa = 150;
        cfg.spoolStartRpm = 0;
        cfg.fullTargetRpm = 0;
        Grid g = TargetTableBuilder.build(ecuTargets(), 180, cfg, 140);
        assertEquals(140, g.get(3, 3), 1e-9);
    }

    @Test
    void openLoopDutyFillsOnlyWotRows() {
        AutotuneConfig cfg = new AutotuneConfig();
        cfg.wotLoadThreshold = 80;
        Grid ol = Grid.filled(Axis.of(1000, 5000), Axis.of(0, 50, 80, 100), 7);
        Grid g = TargetTableBuilder.buildOpenLoopDuty(ol, 35, cfg, 0, 100);
        assertEquals(35, g.get(0, 2), 1e-9);
        assertEquals(35, g.get(1, 3), 1e-9);
        assertEquals(7, g.get(1, 1), 1e-9);
    }

    @Test
    void fastSpoolUsesAFlatTargetAboveTheSpoolStart() {
        AutotuneConfig cfg = new AutotuneConfig();
        cfg.wastegateKpa = 130;
        cfg.spoolStartRpm = 2500;
        cfg.fullTargetRpm = 4500;
        cfg.wotLoadThreshold = 80;
        cfg.scalePartThrottleRows = false;
        cfg.fastSpool = true;
        Grid g = TargetTableBuilder.build(ecuTargets(), 160, cfg, 400);
        assertEquals(130, g.get(0, 3), 1e-9);  // up to the spool start: wastegate pressure
        assertEquals(130, g.get(1, 3), 1e-9);
        assertEquals(160, g.get(2, 3), 1e-9);  // no ramp: the stage target right away (fullTargetRpm ignored)
        assertEquals(160, g.get(3, 3), 1e-9);
        assertEquals(100, g.get(4, 1), 1e-9);  // part throttle untouched
        cfg.spoolStartRpm = 0;
        assertEquals(160, TargetTableBuilder.build(ecuTargets(), 160, cfg, 400).get(0, 3), 1e-9);
        // the classic ramp is still there when fast spool is off
        cfg.fastSpool = false;
        cfg.spoolStartRpm = 2500;
        Grid legacy = TargetTableBuilder.build(ecuTargets(), 160, cfg, 400);
        assertEquals(130, legacy.get(1, 3), 1e-9);
        assertEquals(145, legacy.get(2, 3), 1e-9);
    }
}
