package dev.sibarum.suite.pictures;

/**
 * The two sizes the suite decodes a picture at, as {@code vexelray-codec}'s {@code Pictures} takes them: the longest
 * side a frame keeps. Both apps ask for the thumbnail always, and for the viewer's size too while a viewer is open;
 * then {@code Decoded.picture()} is the thumbnail and {@code Decoded.picture(1)} the viewer's, from one decode.
 */
public final class Sizes {

    /** A thumbnail frame: about twice what Vexplore's dock shows, which the filmstrip shrinks. */
    public static final int THUMBNAIL = 1024;

    /** A frame in the viewer: a 1440p window, or a 4K one at its usual density of 1.5. */
    public static final int VIEW = 2560;

    private Sizes() {
    }

    /** The sizes to decode at: the thumbnail's, and the viewer's as well when {@code large}. */
    public static int[] of(boolean large) {
        return large ? new int[] {THUMBNAIL, VIEW} : new int[] {THUMBNAIL};
    }
}
