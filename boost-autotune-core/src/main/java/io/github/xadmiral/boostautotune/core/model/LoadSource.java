package io.github.xadmiral.boostautotune.core.model;

/** Which runtime value feeds the Y axis of a table. */
public enum LoadSource {
    TPS("Throttle position (%)"),
    BOOST_TARGET("Boost target (kPa)"),
    MAP("Manifold pressure (kPa)"),
    GEAR("Gear");

    private final String label;

    LoadSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
