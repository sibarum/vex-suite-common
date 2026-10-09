package dev.sibarum.suite.view;

import dev.sibarum.suite.pictures.Picture;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Where the viewer's steps land, which side a picture comes in from, and how much fits: all pure. */
class PictureViewerTest {

    private static final Path A = Path.of("a.png");
    private static final Path B = Path.of("b.jpg");
    private static final Path C = Path.of("c.gif");
    private static final List<Path> PICTURES = List.of(A, B, C);

    @Test
    void stepsStopAtTheEnds() {
        assertEquals(B, PictureViewer.target(PICTURES, A, 1));
        assertEquals(A, PictureViewer.target(PICTURES, A, -1), "no wrap: the first stays the first");
        assertEquals(C, PictureViewer.target(PICTURES, C, 1), "and the last the last");
        assertEquals(C, PictureViewer.target(PICTURES, A, 5));
    }

    @Test
    void homeAndEndAreTheFirstAndLast() {
        assertEquals(A, PictureViewer.target(PICTURES, B, Integer.MIN_VALUE));
        assertEquals(C, PictureViewer.target(PICTURES, B, Integer.MAX_VALUE));
    }

    @Test
    void fromNothingAStepStartsAtTheFirst() {
        assertEquals(A, PictureViewer.target(PICTURES, Path.of("notes.txt"), 1));
        assertEquals(A, PictureViewer.target(PICTURES, null, -1));
        assertNull(PictureViewer.target(List.of(), A, 1));
    }

    @Test
    void aPictureComesInFromTheSideItIsOn() {
        assertEquals(1, PictureViewer.direction(PICTURES, A, C), "later in the folder: in from the right");
        assertEquals(-1, PictureViewer.direction(PICTURES, C, B));
        assertEquals(0, PictureViewer.direction(PICTURES, B, B), "a sharper copy of the same file does not slide");
        assertEquals(0, PictureViewer.direction(PICTURES, null, A), "the first picture shown just appears");
        assertEquals(0, PictureViewer.direction(PICTURES, Path.of("gone.png"), A));
    }

    @Test
    void aPictureIsShownWholeAndNeverEnlarged() {
        // 400 x 200 in a 1000 x 1000 stage: its own size, so 40% by 20%.
        var small = new Picture(null, new byte[0], 400, 200, 1, 1, new int[1], 400, 200, false);
        float[] f = ImageView.fraction(small, 1000f, 1000f);
        assertEquals(0.4f, f[0], 1e-6f);
        assertEquals(0.2f, f[1], 1e-6f);
        // The same in a 200 x 200 stage: limited by its width, so all of it across and half down.
        f = ImageView.fraction(small, 200f, 200f);
        assertEquals(1f, f[0], 1e-6f);
        assertEquals(0.5f, f[1], 1e-6f);
    }

    @Test
    void thumbnailsTakeWhatTheTwoSidesLeave() {
        // 72 px tiles and 8 px gaps, as drawn at the design's size: the middle slab is 16 + 72 + (n + 1) * 80.
        float tile = 72f;
        float gap = 8f;
        float side = 300f;
        float middleFor7 = 2 * gap + 9 * tile + 8 * gap;
        assertEquals(7, PictureViewer.thumbnails(2 * side + 2 * 16f + middleFor7, side, tile, gap, 16f));
        assertEquals(6, PictureViewer.thumbnails(2 * side + 2 * 16f + middleFor7 - 1f, side, tile, gap, 16f));
        assertEquals(0, PictureViewer.thumbnails(500f, side, tile, gap, 16f), "no room: Previous and Next only");
    }
}
