package io.github.xadmiral.boostautotune.plugin.mode;

import io.github.xadmiral.boostautotune.core.learn.BiasBuildResult;
import io.github.xadmiral.boostautotune.core.model.Grid;

/** A table to show on the Analysis tab: the next values, a reference to diff against, optional cell quality. */
public final class TableView {
    public final String title;
    public final String yLabel;
    public final Grid next;
    public final Grid reference;
    public final BiasBuildResult quality;
    /** Cell number format. */
    public final String format;

    public TableView(String title, String yLabel, Grid next, Grid reference, BiasBuildResult quality) {
        this(title, yLabel, next, reference, quality, "%.1f");
    }

    public TableView(String title, String yLabel, Grid next, Grid reference, BiasBuildResult quality, String format) {
        this.title = title;
        this.yLabel = yLabel;
        this.next = next;
        this.reference = reference;
        this.quality = quality;
        this.format = format;
    }
}
