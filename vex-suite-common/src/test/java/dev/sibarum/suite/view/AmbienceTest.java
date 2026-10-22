package dev.sibarum.suite.view;

import dev.vexelray.canvas.Color;
import dev.vexelray.codec.image.Picture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmbienceTest {

    /** A {@code w x h} still of one colour, 0xRRGGBBAA. */
    private static Picture solid(int w, int h, int rgba) {
        byte[] px = new byte[w * h * 4];
        for (int i = 0; i < px.length; i += 4) {
            px[i] = (byte) (rgba >>> 24);
            px[i + 1] = (byte) (rgba >>> 16);
            px[i + 2] = (byte) (rgba >>> 8);
            px[i + 3] = (byte) rgba;
        }
        return new Picture("test", px, w, h, 1, 1, new int[] {0}, w, h, false);
    }

    @Test
    void transparencyHasNoRoom() {
        assertNull(Ambience.of(solid(8, 8, 0x336699FE)), "one step short of opaque is a hole");
        Picture mostly = solid(64, 64, 0x336699FF);
        mostly.sheet()[(63 * 64 + 63) * 4 + 3] = 0;
        assertNull(Ambience.of(mostly), "a single clear pixel, in a corner no sample lands on, is found");
    }

    @Test
    void theRoomIsDimAndKeepsTheHue() {
        Color red = Ambience.of(solid(16, 16, 0xE02020FF));
        assertNotNull(red);
        assertTrue(red.r() > red.g() && red.r() > red.b(), "red stays red: " + red);
        assertTrue(red.r() < 0.5f, "and dim: " + red);

        Color white = Ambience.of(solid(16, 16, 0xFFFFFFFF));
        Color black = Ambience.of(solid(16, 16, 0x000000FF));
        assertTrue(white.g() > black.g(), "a bright picture's room is lighter than a dark one's");
        assertTrue(white.g() < 0.4f, "but never a light wall: " + white);
        assertEquals(white.r(), white.b(), 1e-3f, "grey stays grey");
    }

    @Test
    void edgesCountMore() {
        // A dark frame round a bright middle: the room is nearer the frame's than a plain mean would be.
        int n = 64;
        Picture framed = solid(n, n, 0x101010FF);
        byte[] px = framed.sheet();
        for (int y = 16; y < 48; y++) {
            for (int x = 16; x < 48; x++) {
                int i = (y * n + x) * 4;
                px[i] = px[i + 1] = px[i + 2] = (byte) 0xF0;
            }
        }
        Color room = Ambience.of(framed);
        Color plain = Ambience.of(solid(n, n, 0x808080FF));
        assertTrue(room.g() < plain.g(), room + " against " + plain);
    }
}
