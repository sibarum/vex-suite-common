package dev.sibarum.suite.pictures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pictures across the native boundary: these decode for real, so a missing imagelib fails here rather than passing
 * quietly. The raster fixtures are encoded by ImageIO at test time, so each is a real file of the format it claims.
 */
class PicturesTest {

    @Test
    void aPngIsAPictureWithItsSizeAndItsPixels(@TempDir Path dir) throws IOException {
        BufferedImage img = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFFFF0000);
        Path f = dir.resolve("red.png");
        ImageIO.write(img, "png", f.toFile());

        Decoded p = Pictures.of(f);
        assertTrue(p.image());
        assertEquals("PNG image · 3 × 2", p.identity());
        Picture pic = p.picture();
        assertEquals(3, pic.frameWidth());
        assertEquals(255, pic.sheet()[0] & 0xFF, "the first pixel is red");
        assertEquals(0, pic.sheet()[7] & 0xFF, "the second is transparent");
        assertEquals(Stamp.of(f), pic.stamp());
        assertNull(p.large(), "only the thumbnail was asked for");
    }

    @Test
    void theViewersSizeComesFromTheSameDecode(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("huge.png");
        ImageIO.write(new BufferedImage(4000, 2000, BufferedImage.TYPE_INT_ARGB), "png", f.toFile());

        Decoded both = Pictures.of(f, true);
        assertEquals(Picture.FRAME_SIDE, both.picture().frameWidth());
        assertEquals(Picture.VIEW_SIDE, both.large().frameWidth());
        assertEquals(Picture.VIEW_SIDE / 2, both.large().frameHeight());
        assertEquals(2000, both.large().sourceHeight(), "the header still says what the file is");
    }

    @Test
    void aVectorIsRasterisedAtItsOwnAspect(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mark.svg");
        Files.writeString(f, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"20\">"
                + "<rect width=\"40\" height=\"20\" fill=\"#0a0\"/></svg>", StandardCharsets.UTF_8);
        Decoded p = Pictures.of(f, true);
        assertEquals("SVG image · 40 × 20", p.identity());
        assertEquals(Picture.VIEW_SIDE, p.large().frameWidth());
        assertEquals(Picture.FRAME_SIDE, p.picture().frameWidth(), "the thumbnail is a shrink of it");
    }

    @Test
    void whatIsNotAPictureSaysWhy(@TempDir Path dir) throws IOException {
        Path text = dir.resolve("notes.png");
        Files.writeString(text, "not a picture at all", StandardCharsets.UTF_8);
        Decoded p = Pictures.of(text, true);
        assertFalse(p.image());
        assertEquals("not an image", p.identity());

        Path broken = dir.resolve("broken.png");
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};
        Files.write(broken, head);
        assertEquals("does not decode as an image", Pictures.of(broken).identity());

        assertTrue(Pictures.of(dir.resolve("gone.png")).identity().startsWith("unreadable"));
    }

    @Test
    void namesAreMatchedByExtensionIgnoringCase() {
        assertTrue(Pictures.named(Path.of("IMG_1.JPG")));
        assertTrue(Pictures.named(Path.of("a.webp")));
        assertFalse(Pictures.named(Path.of("clip.mp4")));
        assertFalse(Pictures.named(Path.of(".png")), "a dotfile is not an image named png");
        assertNotNull(Pictures.of(Path.of("nowhere", "x.png")));
    }

    @Test
    void anAnimationIsDescribedByItsFramesAndLength() {
        Picture p = new Picture(new Stamp(Path.of("a.gif"), 1, 1), new byte[4 * 4 * 4], 2, 2, 2, 2,
                new int[] {500, 500, 500}, 2, 2, false);
        assertEquals("GIF image · 2 × 2 · 3 frames, 1.5 s", Pictures.describe("GIF image", p));
    }

    @Test
    void aVectorBoxKeepsItsAspectAndIsNeverEmpty() {
        int side = Pictures.VECTOR_SIDE;
        assertEquals(side, Pictures.vectorBox(10, 40, side)[1]);
        assertEquals(side / 4, Pictures.vectorBox(10, 40, side)[0]);
        assertEquals(1, Pictures.vectorBox(1, 100000, side)[0]);
        assertEquals(side, Pictures.vectorBox(0, 0, side)[0]);
    }
}
