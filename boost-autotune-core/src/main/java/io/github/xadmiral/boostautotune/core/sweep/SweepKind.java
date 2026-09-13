package io.github.xadmiral.boostautotune.core.sweep;

/** What the sweep optimises. */
public enum SweepKind {
    /** Intake cam (VVT) target table, WOT rows. */
    VVT("VVT target"),
    /** Ignition advance table, WOT rows, with knock and AFR guards. */
    IGNITION("Ignition advance");

    private final String label;

    SweepKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
