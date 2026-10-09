package dev.sibarum.suite.view;

import dev.vexelray.gui.core.layout.Length;

/**
 * The faces and sizes the shared parts draw with. Faces are indices into the font table the framework loads; sizes
 * are in {@code rem}, so zoom scales all of them together, and each is the design's pixel size divided by sixteen.
 * An application's own type scale names these for the sizes it shares, so a tile reads the same in every window.
 */
public final class SuiteType {

    /** The face for text. */
    public static final int UI = 0;

    /** The face for paths, sizes in labels, status tags and anything tabular. */
    public static final int MONO = 1;

    /** A file's name over its picture: 14 px. */
    public static final Length BODY = Length.rem(0.875f);

    /** Secondary text, "Image 3 of 17": 12 px. */
    public static final Length META = Length.rem(0.75f);

    /** A tile's word, and what a picture is ("PNG image · 1920 × 1080"): 11 px. */
    public static final Length SMALL = Length.rem(0.6875f);

    private SuiteType() {
    }
}
