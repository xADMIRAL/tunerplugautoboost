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

    // ---- VVT: target table (rpm x fuel load), PID and a hydraulic phaser model ----
    public Grid vvtTable;
    public PidGains vvtPid = new PidGains(70, 15, 40);
    public double vvtPScale = 0.00043;  // % duty per degree of error at P = 100 (times actuator gain)
    public double vvtIScale = 0.0015;
    public double vvtDScale = 0.00006;
    public double camRatePerDuty = 4.0; // deg/s of cam movement per % of duty away from hold
    public double camLagSec = 0.08;
    /** Oil transport delay between the solenoid and the phaser: what makes too much P ring. */
    public double camDeadTimeSec = 0.05;
    public double camMax = 45;
    private double camAngle;
    private double camVel;
    private double vvtIntegral;
    private double vvtLastErr = Double.NaN;
    private double vvtTarget;
    private double vvtDuty = 50;
    private double vvtClock;
    private final java.util.ArrayDeque<double[]> dutyHistory = new java.util.ArrayDeque<double[]>();

    // ---- anti-lag: timing table (rpm x tps), idle valve air, arm/operate thresholds ----
    public Grid alsTiming;
    public double alsAir = 60;
    public boolean alsEnabled;
    public double alsArmTps = 50;
    public double alsOperateTps = 12;
    public double alsMinRpm = 2000;
    public double alsMaxRpm = 7500;
    public double alsMaxTimeSec = 4;
    private boolean alsArmed;
    private boolean alsActive;
    private double alsTimer;
    private double mat = 35;

    public boolean alsActive() {
        return alsActive;
    }

    /** Engine speed the anti-lag holds off throttle: more idle-valve air, less retard = more torque. */
    public double alsHoldRpm() {
        if (!alsActive || alsTiming == null) {
            return 1500;
        }
        double retard = Math.max(0, -alsTiming.lookup(alsHoldLookupRpm, 0) - 5);
        return 1000 + 12 * alsAir - 8 * retard;
    }

    private double alsHoldLookupRpm = 3000;

    /** A cool-down drive between runs. */
    public void coolDown(double seconds) {
        mat = Math.max(35, mat - 1.5 * seconds);
    }

    public double mat() {
        return mat;
    }

    /** Steady-state manifold pressure the anti-lag would hold off throttle. */
    public double alsSteadyMap(double rpm, double tps) {
        if (alsTiming == null) {
            return Double.NaN;
        }
        double t = alsTiming.lookup(rpm, tps);
        double retard = Math.max(0, -t - 5);          // effect starts around -5 deg
        double airEffect = Math.max(0, alsAir - 20) * 0.35;
        double cap = 0.8 * plantCapacity(rpm);
        return Math.min(cap, 90 + 1.4 * retard + airEffect);
    }

    private double plantCapacity(double rpm) {
        return torque == null ? 200 : 105 + Math.min(140, Math.max(0, rpm - 1500) * 0.045);
    }

    /** Advances the anti-lag state machine and the intake temperature model. */
    public void alsStep(double rpm, double tps, double dt) {
        if (!alsEnabled || alsTiming == null) {
            alsActive = false;
            mat = Math.max(35, mat - 1.0 * dt);
            return;
        }
        if (tps >= alsArmTps) {
            alsArmed = true;
            alsTimer = 0;
        }
        boolean cond = alsArmed && tps <= alsOperateTps && rpm >= alsMinRpm && rpm <= alsMaxRpm && alsTimer < alsMaxTimeSec;
        if (cond) {
            alsTimer += dt;
            alsActive = true;
            alsHoldLookupRpm = rpm;
            double retard = Math.max(0, -alsTiming.lookup(rpm, tps) - 5);
            mat += (2.0 + 0.12 * retard) * dt;
        } else {
            if (alsActive && tps <= alsOperateTps) {
                alsArmed = false; // timed out: must re-arm with throttle
            }
            alsActive = false;
            mat = Math.max(35, mat - 1.5 * dt);
        }
    }

    // ---- ignition: spark table (rpm x ign load) and a safe-mode style knock control ----
    public Grid sparkTable;
    public TorqueModel torque = new TorqueModel();
    public double knockRetardStep = 1.5;
    public double knockMaxRetard = 6;
    public double knockRecoverPerSec = 1.0;
    private double knockRetard;
    private double knockTimer;
    private double advance;
    private double knockLevel;

    public SimEcu(EcuState state) {
        apply(state);
    }

    public void apply(EcuState s) {
        this.state = s.copy();
        if (!Double.isNaN(s.closedLoopWindowKpa)) {
            lowerLimitDeltaKpa = s.closedLoopWindowKpa;
        }
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

    public double camAngle() {
        return camAngle;
    }

    public double vvtTarget() {
        return vvtTarget;
    }

    public double vvtDuty() {
        return vvtDuty;
    }

    public double advance() {
        return advance;
    }

    public double knockRetard() {
        return knockRetard;
    }

    public double knockLevel() {
        return knockLevel;
    }

    public void resetAuxiliaries() {
        alsArmed = false;
        alsActive = false;
        alsTimer = 0;
        camAngle = 0;
        camVel = 0;
        vvtIntegral = 0;
        vvtLastErr = Double.NaN;
        dutyHistory.clear();
        vvtClock = 0;
        knockRetard = 0;
        knockTimer = 0;
        knockLevel = 0;
    }

    /** Advances the cam phaser model one control step. */
    public void vvtStep(double rpm, double fuelLoad, double dt) {
        if (vvtTable == null) {
            return;
        }
        vvtTarget = vvtTable.lookup(rpm, fuelLoad);
        double err = vvtTarget - camAngle;
        vvtIntegral += err * dt;
        double iTerm = vvtPid.i * vvtIScale * vvtIntegral;
        double iLimit = 20;
        if (iTerm > iLimit) { iTerm = iLimit; vvtIntegral = iLimit / Math.max(1e-6, vvtPid.i * vvtIScale); }
        if (iTerm < -iLimit) { iTerm = -iLimit; vvtIntegral = -iLimit / Math.max(1e-6, vvtPid.i * vvtIScale); }
        double dTerm = Double.isNaN(vvtLastErr) ? 0 : vvtPid.d * vvtDScale * (err - vvtLastErr) / dt;
        vvtLastErr = err;
        vvtDuty = Stats.clamp(50 + (vvtPid.p * vvtPScale * 100) * err + iTerm + dTerm, 0, 100);
        vvtClock += dt;
        dutyHistory.addLast(new double[]{vvtClock, vvtDuty});
        double delayed = vvtDuty;
        while (!dutyHistory.isEmpty() && dutyHistory.peekFirst()[0] <= vvtClock - camDeadTimeSec) {
            delayed = dutyHistory.pollFirst()[1];
        }
        if (dutyHistory.size() > 0 && vvtClock < camDeadTimeSec) {
            delayed = 50;
        }
        double velTarget = camRatePerDuty * (delayed - 50);
        camVel += (velTarget - camVel) * (dt / camLagSec);
        camAngle = Stats.clamp(camAngle + camVel * dt, 0, camMax);
        if (camAngle <= 0 || camAngle >= camMax) {
            camVel = 0;
        }
    }

    /** Spark lookup plus a crude knock controller; returns the actual advance. */
    public double sparkStep(double rpm, double ignLoad, double tps, double dt) {
        if (sparkTable == null) {
            return Double.NaN;
        }
        double commanded = sparkTable.lookup(rpm, ignLoad);
        double limit = torque.knockLimit(rpm, ignLoad);
        boolean knocking = tps >= 85 && commanded - knockRetard > limit;
        knockLevel = knocking ? Stats.clamp(30 + (commanded - knockRetard - limit) * 20, 30, 100) : 5;
        knockTimer += dt;
        if (knocking && knockTimer >= 0.2) {
            knockRetard = Math.min(knockMaxRetard, knockRetard + knockRetardStep);
            knockTimer = 0;
        } else if (!knocking && knockRetard > 0) {
            knockRetard = Math.max(0, knockRetard - knockRecoverPerSec * dt);
        }
        advance = commanded - knockRetard;
        return advance;
    }

    /** Multiplier applied to the engine's acceleration from cam and spark. */
    public double torqueFactor(double rpm, double load) {
        double f = 1;
        if (vvtTable != null) {
            f *= torque.camFactor(rpm, camAngle);
        }
        if (sparkTable != null) {
            f *= torque.sparkFactor(rpm, load, advance);
        }
        return f;
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
