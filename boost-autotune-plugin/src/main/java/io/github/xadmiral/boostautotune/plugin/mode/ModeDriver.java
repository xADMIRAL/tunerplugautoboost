package io.github.xadmiral.boostautotune.plugin.mode;

import io.github.xadmiral.boostautotune.core.model.Sample;
import io.github.xadmiral.boostautotune.core.session.SessionState;
import io.github.xadmiral.boostautotune.plugin.ecu.EcuException;

import java.util.List;

/**
 * One tuning mode wired to the ECU: owns a core session plus the undo copy and knows how to
 * read and write its tables. The controller drives every mode through this interface.
 */
public interface ModeDriver {
    TuneMode mode();

    SessionState state();

    String abortReason();

    String phase();

    String planTitle();

    List<String> planNotes();

    String instructions();

    /** Writes the current plan to the ECU (RAM). */
    void writePlan() throws EcuException;

    void startRun();

    /** Called for every live sample while recording (under the controller lock). */
    void onSample(Sample s);

    boolean runLooksFinished();

    /** Ends the run and analyses it; returns the report object (mode specific). */
    Object endRun();

    /** Accepts the last report and writes the next plan (or the final result). */
    void commitAndWrite() throws EcuException;

    void repeatRun();

    void abort(String reason);

    /** Writes the undo copy back. */
    void restoreOriginal() throws EcuException;

    /** Lowest-risk state after a safety abort (original table, minimum duty, ...). */
    void writeSafeState() throws EcuException;

    boolean hasOriginal();

    int pullsInRun();

    double runPeak();

    String lastReportText();

    String analysisText();

    List<TableView> tables();

    String logPrefix();
}
