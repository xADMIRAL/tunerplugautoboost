package io.github.xadmiral.boostautotune.core.sim;

import io.github.xadmiral.boostautotune.core.model.Sample;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the sample stream of a full-throttle pull: a short cruise, WOT from a start RPM up to a
 * redline (RPM rate depends on gear), lift, and a cruise afterwards.
 */
public final class PullSimulator {
    public double sampleRateHz = 25;
    public double controlDt = 0.02;
    public double startRpm = 2000;
    public double redlineRpm = 6800;
    public double cruiseTps = 15;
    public double cltC = 88;
    public double preSec = 1.0;
    public double postSec = 1.5;

    private final BoostPlant plant;
    private final SimEcu ecu;
    private double time;

    public PullSimulator(BoostPlant plant, SimEcu ecu) {
        this.plant = plant;
        this.ecu = ecu;
    }

    public double time() {
        return time;
    }

    public void setTime(double t) {
        this.time = t;
    }

    /** RPM per second while at WOT in the given gear. */
    static double rpmRate(int gear, double rpm) {
        double base;
        switch (gear) {
            case 1: base = 3200; break;
            case 2: base = 2100; break;
            case 3: base = 1350; break;
            case 4: base = 950; break;
            default: base = 700; break;
        }
        double falloff = rpm > 5500 ? 1 - (rpm - 5500) / 4000 : 1;
        return base * Math.max(0.5, falloff);
    }

    public List<Sample> pull(int gear) {
        List<Sample> out = new ArrayList<Sample>();
        double dt = 1.0 / sampleRateHz;
        double rpm = startRpm;
        double tps = cruiseTps;
        plant.reset(32 + tps * 0.68);
        ecu.resetController();
        double phaseEnd = time + preSec;
        // cruise
        while (time < phaseEnd) {
            out.add(tick(rpm, tps, dt, gear));
        }
        // WOT
        tps = 100;
        while (rpm < redlineRpm) {
            out.add(tick(rpm, tps, dt, gear));
            rpm += rpmRate(gear, rpm) * dt;
        }
        // lift
        tps = cruiseTps;
        phaseEnd = time + postSec;
        while (time < phaseEnd) {
            out.add(tick(rpm, tps, dt, gear));
            rpm = Math.max(startRpm, rpm - 1500 * dt);
        }
        return out;
    }

    private Sample tick(double rpm, double tps, double dt, int gear) {
        double duty = 0;
        double map = plant.map();
        int steps = Math.max(1, (int) Math.round(dt / controlDt));
        double sub = dt / steps;
        double noisy = map;
        for (int i = 0; i < steps; i++) {
            duty = ecu.control(rpm, tps, plant.map(), sub);
            noisy = plant.step(rpm, tps, duty, sub);
        }
        time += dt;
        double target = ecu.state().closedLoop ? ecu.target(rpm, tps) : Double.NaN;
        return Sample.builder().time(time).rpm(rpm).tps(tps).map(noisy).target(target).duty(duty)
                .clt(cltC).gear(gear).boostCut(false).build();
    }
}
