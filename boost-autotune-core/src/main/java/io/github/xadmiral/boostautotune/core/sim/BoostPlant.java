package io.github.xadmiral.boostautotune.core.sim;

import io.github.xadmiral.boostautotune.core.util.Stats;

import java.util.Random;

/**
 * Toy turbocharged engine used by tests and the plugin's demo mode. Boost follows a first-order
 * lag towards a steady state that depends on RPM (turbo capacity) and wastegate duty. It is not a
 * physical model, just believable enough to exercise the autotune end to end.
 */
public final class BoostPlant {
    /** Boost with the wastegate fully open (spring pressure), kPa absolute. */
    public double wastegateKpa = 132;
    /** Duty below which the valve has no effect and above which it saturates. */
    public double deadDuty = 12;
    public double saturationDuty = 92;
    public double noiseKpa = 0.8;
    private final double[] capRpm = {1500, 2500, 3500, 4500, 5500, 6500, 7500};
    private final double[] capKpa = {105, 150, 215, 245, 240, 228, 212};
    private final Random rnd;
    private double map = 100;

    public BoostPlant(long seed) {
        this.rnd = new Random(seed);
    }

    public double map() {
        return map;
    }

    public void reset(double mapKpa) {
        this.map = mapKpa;
    }

    /** Boost achievable at 100 % duty. */
    public double capacity(double rpm) {
        if (rpm <= capRpm[0]) {
            return capKpa[0];
        }
        for (int i = 1; i < capRpm.length; i++) {
            if (rpm <= capRpm[i]) {
                double t = (rpm - capRpm[i - 1]) / (capRpm[i] - capRpm[i - 1]);
                return Stats.lerp(capKpa[i - 1], capKpa[i], t);
            }
        }
        return capKpa[capKpa.length - 1];
    }

    /** Steady-state boost for a duty at WOT. */
    public double steadyState(double rpm, double duty) {
        double cap = capacity(rpm);
        double f = Stats.clamp((duty - deadDuty) / (saturationDuty - deadDuty), 0, 1);
        f = Math.pow(f, 1.25);
        double controlled = wastegateKpa + Math.max(0, cap - wastegateKpa) * f;
        return Math.min(cap, controlled);
    }

    private double timeConstant(double rpm) {
        return Stats.clamp(0.55 - (rpm - 2000) / 4000 * 0.35, 0.18, 0.55);
    }

    /** Advances the plant by dt seconds. */
    public double step(double rpm, double tps, double duty, double dt) {
        double target;
        if (tps >= 85) {
            target = steadyState(rpm, duty);
        } else {
            target = 32 + tps * 0.68;
        }
        double tau = tps >= 85 ? timeConstant(rpm) : 0.25;
        double maxRate = 260; // kPa/s, spool limit
        double delta = (target - map) * (dt / tau);
        delta = Stats.clamp(delta, -maxRate * 2 * dt, maxRate * dt);
        map += delta;
        return map + rnd.nextGaussian() * noiseKpa;
    }
}
