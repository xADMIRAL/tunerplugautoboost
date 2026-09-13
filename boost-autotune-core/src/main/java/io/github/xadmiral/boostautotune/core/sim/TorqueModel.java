package io.github.xadmiral.boostautotune.core.sim;

import io.github.xadmiral.boostautotune.core.util.Stats;

/**
 * How cam timing and ignition advance scale the engine's torque in the simulator. Not physics,
 * just shaped like the real thing: a cam optimum that moves with RPM, an MBT advance that falls
 * with load, a flat top around MBT and a knock limit that can sit below MBT at high load.
 */
public final class TorqueModel {
    private final double[] camRpm = {1500, 2500, 3500, 4500, 5500, 6500, 7500};
    private final double[] camOpt = {38, 35, 28, 20, 12, 6, 2};
    /** Torque loss per squared degree of cam error. */
    public double camLossPerDeg2 = 0.00012;
    /** Torque loss per squared degree below MBT. */
    public double sparkLossPerDeg2 = 0.0004;
    /** RPM from which the knock limit sits below MBT (0 = never). */
    public double knockLimitedFromRpm = 5000;
    /** Knock limit relative to MBT in the knock-limited region. */
    public double knockBelowMbtDeg = 7;

    public double camOptimum(double rpm) {
        if (rpm <= camRpm[0]) {
            return camOpt[0];
        }
        for (int i = 1; i < camRpm.length; i++) {
            if (rpm <= camRpm[i]) {
                double t = (rpm - camRpm[i - 1]) / (camRpm[i] - camRpm[i - 1]);
                return Stats.lerp(camOpt[i - 1], camOpt[i], t);
            }
        }
        return camOpt[camOpt.length - 1];
    }

    public double camFactor(double rpm, double camAngle) {
        double e = camAngle - camOptimum(rpm);
        return Math.max(0.8, 1 - camLossPerDeg2 * e * e);
    }

    /** Minimum advance for best torque. */
    public double mbt(double rpm, double load) {
        return 22 - 0.05 * (load - 100) + 0.001 * (rpm - 3000);
    }

    /** Advance above which the engine knocks. */
    public double knockLimit(double rpm, double load) {
        double m = mbt(rpm, load);
        if (knockLimitedFromRpm > 0 && rpm >= knockLimitedFromRpm) {
            return m - knockBelowMbtDeg;
        }
        return m + 3;
    }

    public double sparkFactor(double rpm, double load, double advance) {
        double m = mbt(rpm, load);
        if (advance >= m) {
            double over = advance - m;
            return Math.max(0.9, 1 - 0.00005 * over * over); // flat top, slow loss beyond MBT
        }
        double e = m - advance;
        return Math.max(0.7, 1 - sparkLossPerDeg2 * e * e);
    }
}
