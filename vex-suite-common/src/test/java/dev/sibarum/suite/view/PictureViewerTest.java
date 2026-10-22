package dev.sibarum.suite.view;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the viewer's steps land, the side a picture comes in from, and the strip's room: all pure. */
class PictureViewerTest {

    private static final Path A = Path.of("a.png");
    private static final Path B = Path.of("b.jpg");
    private static final Path C = Path.of("c.gif");
    private static final List<Path> PICTURES = List.of(A, B, C);

    @Test
    void theClockSaysMinutesSecondsAndTenths() {
        assertEquals("0:00.0 / 0:04.0", PictureViewer.clockText(0, 4_000));
        assertEquals("0:01.2 / 1:05.9", PictureViewer.clockText(1_250, 65_999));
        assertEquals("12:00.0 / 0:00.0", PictureViewer.clockText(720_000, -5));
    }

    @Test
    void aFileThatWillNotPlaySaysWhyWhereTheClockWas() {
        assertEquals("0:01.2 / 0:04.0  13/40", PictureViewer.readout(1_250, 4_000, 12, 40, null));
        assertEquals("0:00.0 / 0:04.0  1/40", PictureViewer.readout(0, 4_000, -1, 40, null), "before the first frame");
        assertEquals("0:00.0 / 0:04.0  1/40", PictureViewer.readout(0, 4_000, -1, 40, " "), "a blank reason is none");
        assertEquals("Invalid data found when processing input",
                PictureViewer.readout(0, 4_000, 0, 40, "Invalid data found\nwhen processing input"), "on one line");
        String cut = PictureViewer.readout(0, 0, 0, 0, "x".repeat(200));
        assertEquals(PictureViewer.WHY_CHARS, cut.length(), "a long reason is cut, so the deck stays a size");
        assertTrue(cut.endsWith("…"));
    }

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
    void thumbnailsTakeWhatTheTwoSidesLeave() {
        // 72 px tiles and 8 px gaps, as drawn at the design's size: the middle slab is 16 + n * 80 - 8.
        float tile = 72f;
        float gap = 8f;
        float side = 300f;
        float middleFor7 = 2 * gap + 7 * tile + 6 * gap;
        assertEquals(7, PictureViewer.thumbnails(2 * side + 2 * 16f + middleFor7, side, tile, gap, 16f));
        assertEquals(6, PictureViewer.thumbnails(2 * side + 2 * 16f + middleFor7 - 1f, side, tile, gap, 16f));
        assertEquals(0, PictureViewer.thumbnails(700f, side, tile, gap, 16f), "no room for one");
    }

    @Test
    void aNarrowBarKeepsWhatFits() {
        // 72 px tiles: the title slab is 72 + 160 + 24 + 2 = 258 px, the tools slab 144 + 24 + 2 = 170 px.
        PictureViewer.Fit wide = PictureViewer.fit(1400f, 72f);
        assertTrue(wide.thumbnails() > 0 && wide.title() && wide.tools());
        assertTrue(wide.sides(), "Previous and Next are at the sides however wide the window");

        PictureViewer.Fit noFilm = PictureViewer.fit(700f, 72f);
        assertEquals(new PictureViewer.Fit(0, true, true, true), noFilm, "the filmstrip goes first");

        PictureViewer.Fit narrow = PictureViewer.fit(400f, 72f);
        assertEquals(new PictureViewer.Fit(0, true, true, false), narrow, "then Background and Keep shown");

        PictureViewer.Fit tiny = PictureViewer.fit(200f, 72f);
        assertEquals(new PictureViewer.Fit(0, false, false, false), tiny, "and then everything");
    }

    @Test
    void aPlayingPictureHasTheTransportWhereTheFilmstripWas() {
        // 72 px tiles, a 600 px transport: each side group needs 258 px of what the transport leaves, halved.
        assertEquals(new PictureViewer.Fit(0, true, true, true), PictureViewer.fit(1400f, 72f, 600f),
                "no filmstrip, and the arrows at the sides, however wide the bar");
        assertEquals(new PictureViewer.Fit(0, true, false, false), PictureViewer.fit(1000f, 72f, 600f),
                "both side groups go together, so the transport stays centred");
    }
}
