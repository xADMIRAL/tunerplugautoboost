package io.github.xadmiral.boostautotune.core.sim;

import io.github.xadmiral.boostautotune.core.model.Grid;
import io.github.xadmiral.boostautotune.core.model.PidGains;
import io.github.xadmiral.boostautotune.core.session.EcuState;
import io.github.xadmiral.boostautotune.core.util.Stats;

/**
 * MS3-like boost controller: open loop uses the duty table; closed loop starts from the bias table
 * (duty vs RPM and target) and adds a PID correction once boost is within the lower-limit window.
 * Gains of 100 correspond to moderate physical gains so that the tuner's relative steps make sense.
 */
public final class SimEcu {
    private EcuState state;
    private double integral;
    private double lastError = Double.NaN;
    private double lastDuty;
    public double lowerLimitDeltaKpa = 30;
    public double wotTps = 85;
    /** Physical gain at P=100: percent duty per kPa of error. */
    public double pScale = 0.006;
    public double iScale = 0.02;
    public double dScale = 0.0006;

    public SimEcu(EcuState state) {
        apply(state);
    }

    public void apply(EcuState s) {
        this.state = s.copy();
        integral = 0;
        lastError = Double.NaN;
    }

    public EcuState state() {
        return state;
    }

    public double target(double rpm, double tps) {
        return state.targetTable.lookup(rpm, tps);
    }

    public double lastDuty() {
        return lastDuty;
    }

    public void resetController() {
        integral = 0;
        lastError = Double.NaN;
    }

    /** One control step; returns the duty. */
    public double control(double rpm, double tps, double map, double dt) {
        if (tps < wotTps) {
            resetController();
            lastDuty = state.minDuty;
            return lastDuty;
        }
        double duty;
        if (!state.closedLoop) {
            Grid ol = state.openLoopTable;
            duty = ol == null ? state.minDuty : ol.lookup(rpm, tps);
        } else {
            double target = target(rpm, tps);
            double bias = state.biasTable == null ? 0 : state.biasTable.lookup(rpm, target);
            double err = target - map;
            if (map < target - lowerLimitDeltaKpa) {
                // not yet in the window: bias only, no windup
                integral = 0;
                lastError = Double.NaN;
                duty = bias;
            } else {
                PidGains g = state.pid;
                integral += err * dt;
                double iTerm = g.i * iScale * integral;
                double iLimit = 25;
                if (iTerm > iLimit) { iTerm = iLimit; integral = iLimit / Math.max(1e-6, g.i * iScale); }
                if (iTerm < -iLimit) { iTerm = -iLimit; integral = -iLimit / Math.max(1e-6, g.i * iScale); }
                double dTerm = Double.isNaN(lastError) ? 0 : g.d * dScale * (err - lastError) / dt;
                lastError = err;
                duty = bias + g.p * pScale * err + iTerm + dTerm;
            }
        }
        lastDuty = Stats.clamp(duty, state.minDuty, state.maxDuty);
        return lastDuty;
    }
}
