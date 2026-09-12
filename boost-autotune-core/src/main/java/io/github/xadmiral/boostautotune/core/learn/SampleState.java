package io.github.xadmiral.boostautotune.core.learn;

/** Classification of one sample by the steady-state filter. */
public enum SampleState {
    STEADY("steady"),
    TRANSIENT("settling / MAP changing"),
    NOT_WOT("below WOT load"),
    COLD("coolant below minimum"),
    RPM_OUT_OF_RANGE("RPM out of range"),
    BOOST_CUT("boost cut active"),
    OVERBOOST("above hard limit");

    private final String label;

    SampleState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isPullSample() {
        return this == STEADY || this == TRANSIENT;
    }
}
