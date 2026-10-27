package io.github.xadmiral.boostautotune.core.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What the ECU must look like for the next run, plus instructions for the driver. */
public final class RunPlan {
    public final int runNumber;
    public final SessionPhase phase;
    public final int stageIndex;
    public final double stageTarget;
    /** Open-loop duty for characterization runs; NaN otherwise. */
    public final double openLoopDuty;
    public final EcuState ecu;
    public final List<String> notes = new ArrayList<String>();
    /** A characterization run at minimum duty whose only job is to read the wastegate spring pressure. */
    public boolean springRun;
    /** Gear the driver is asked to pull in (0 = any). */
    public int gear;

    RunPlan(int runNumber, SessionPhase phase, int stageIndex, double stageTarget, double openLoopDuty, EcuState ecu) {
        this.runNumber = runNumber;
        this.phase = phase;
        this.stageIndex = stageIndex;
        this.stageTarget = stageTarget;
        this.openLoopDuty = openLoopDuty;
        this.ecu = ecu;
    }

    private String gearText() {
        return gear > 0 ? gear + (gear == 1 ? "st" : gear == 2 ? "nd" : gear == 3 ? "rd" : "th") + " gear" : "a mid gear";
    }

    public String title() {
        switch (phase) {
            case CHARACTERIZE:
                if (springRun) {
                    return String.format(Locale.US, "Run %d: spring pressure measurement, valve fully open (%.0f%% duty)", runNumber, openLoopDuty);
                }
                return String.format(Locale.US, "Run %d: open-loop characterization at %.0f%% duty", runNumber, openLoopDuty);
            case CLOSED_LOOP:
                return String.format(Locale.US, "Run %d: closed loop, stage %d target %.0f kPa", runNumber, stageIndex + 1, stageTarget);
            default:
                return "Session complete";
        }
    }

    public String driverInstructions() {
        switch (phase) {
            case CHARACTERIZE:
                if (springRun) {
                    return "Do one or two full-throttle pulls in " + gearText() + " from below spool RPM to redline. The wastegate "
                            + "valve is held fully OPEN: the boost you see is what the spring holds, the plugin reads it off the pull.";
                }
                return "Do one or two full-throttle pulls in " + gearText() + " from below spool RPM to redline. "
                        + "Boost control is OPEN LOOP at a fixed duty; lift immediately if boost climbs past the limit.";
            case CLOSED_LOOP:
                return "Do one or two full-throttle pulls in " + gearText() + ". The controller is CLOSED LOOP on the "
                        + "new targets; the plugin measures overshoot, error and oscillation.";
            default:
                return "Nothing to do.";
        }
    }
}
