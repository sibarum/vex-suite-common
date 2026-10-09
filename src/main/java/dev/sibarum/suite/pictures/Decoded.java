package dev.sibarum.suite.pictures;

import java.nio.file.Path;

/**
 * One file's pixels, decided once and read-only: what the file is, and its picture at two sizes; or, when there is no
 * picture, why not. Produced by {@link Pictures}; nothing in the file is ever executed.
 *
 * @param path     the file
 * @param identity what the file is ("PNG image · 1920 × 1080"), or why there is no picture of it ("not an image",
 *                 "unreadable: AccessDeniedException")
 * @param picture  the image at the thumbnail's size, {@link Picture#FRAME_SIDE}; null when there is none
 * @param large    the same image at the viewer's size, {@link Picture#VIEW_SIDE}, from the same decode; null when only
 *                 the thumbnail was asked for, and when there is no picture
 */
public record Decoded(Path path, String identity, Picture picture, Picture large) {

    /** A file with no picture, and the reason. */
    public static Decoded none(Path path, String why) {
        return new Decoded(path, why, null, null);
    }

    /** Whether there is a picture. */
    public boolean image() {
        return picture != null;
    }

    /** What this holds in decoded pixels: the weight a cache is bounded by. */
    public long bytes() {
        return (picture == null ? 0 : picture.bytes()) + (large == null ? 0 : large.bytes());
    }
}
