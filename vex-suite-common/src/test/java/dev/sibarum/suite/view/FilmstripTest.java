package dev.sibarum.suite.view;

import dev.vexelray.codec.image.Picture;
import dev.vexelray.gui.core.ImageRegion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A thumbnail is the middle square of the first frame, whatever the picture's shape and however many frames. */
class FilmstripTest {

    @Test
    void aPortraitStillIsCroppedTopAndBottom() {
        ImageRegion r = Filmstrip.cover(picture(100, 200, 1, 1));
        assertEquals(new ImageRegion(0f, 0.25f, 1f, 0.75f), r);
    }

    @Test
    void aLandscapeStillIsCroppedLeftAndRight() {
        ImageRegion r = Filmstrip.cover(picture(400, 100, 1, 1));
        assertEquals(new ImageRegion(0.375f, 0f, 0.625f, 1f), r);
    }

    @Test
    void anAnimationShowsTheMiddleOfItsFirstCell() {
        // Four 200 x 100 cells, two across and two down: the first cell is the sheet's top-left quarter.
        ImageRegion r = Filmstrip.cover(picture(200, 100, 2, 2));
        assertEquals(new ImageRegion(0.125f, 0f, 0.375f, 0.5f), r);
    }

    private static Picture picture(int fw, int fh, int columns, int rows) {
        int frames = columns * rows;
        return new Picture(null, new byte[0], fw, fh, columns, rows, new int[frames], fw, fh, false);
    }
}
