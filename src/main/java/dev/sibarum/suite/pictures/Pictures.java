package dev.sibarum.suite.pictures;

import sibarum.imagelib.Frames;
import sibarum.imagelib.Imagelib;
import sibarum.probe.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Pixels from a file: anything imagelib can decode (PNG, JPEG, GIF, WebP, SVG and the rest), animated where the file
 * is, as a {@link Picture} ready to upload.
 *
 * <p><b>Nothing executed.</b> The first {@link #SAMPLE} bytes say whether imagelib wants the file; only then is the
 * whole of it read, up to {@link #MAX_IMAGE}, and handed to imagelib, whose decoders are pure Rust that turn bytes into
 * pixels and run nothing the file names.
 *
 * <p><b>Every failure is a reason, not an exception.</b> A file that says it is an image and will not decode, one too
 * large to hold, or a machine where the native decoder will not load: each comes back as a {@link Decoded} with no
 * picture and a line saying why, so the viewer says so and the next file is unaffected.
 *
 * <p>Vexplore's preview ladder has this as its first tier; Pix shows nothing else. Blocking: run it on the
 * offload lane.
 */
public final class Pictures {

    /** How much of a file is read to decide what it is: enough for an SVG whose first element is far in. */
    public static final int SAMPLE = 64 * 1024;

    /** The largest image file decoded. Past it the file is refused; its pixels would not fit anyway. */
    static final long MAX_IMAGE = 128L * 1024 * 1024;

    /** The long side a vector document is rasterised at for a thumbnail, since a vector has no size. */
    static final int VECTOR_SIDE = Picture.FRAME_SIDE;

    /**
     * What a folder is listed for: the extensions of what imagelib decodes. A guess from the name, which is what a
     * listing can afford; the bytes decide when the file is shown.
     */
    static final Set<String> EXTENSIONS = Set.of("png", "apng", "jpg", "jpeg", "jfif", "gif", "webp", "bmp", "tif",
            "tiff", "svg", "ico", "tga", "qoi", "pnm", "pbm", "pgm", "ppm", "dds", "exr", "hdr", "avif");

    private static final Log LOG = Log.of("suite.pictures");

    /** Said once: a decoder that will not load fails the same way for every file after the first. */
    private static volatile boolean decoderReported;

    private Pictures() {
    }

    /** Whether {@code path} is named like an image this decodes: what a folder of pictures is listed for. */
    public static boolean named(Path path) {
        Path n = path.getFileName();
        if (n == null) {
            return false;
        }
        String s = n.toString();
        int dot = s.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(s.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** {@code path} at the thumbnail's size only. */
    public static Decoded of(Path path) {
        return of(path, false);
    }

    /**
     * {@code path} at the thumbnail's size, and also at the viewer's when {@code large}: both from one decode, which is
     * the expensive part.
     */
    public static Decoded of(Path path, boolean large) {
        try {
            if (Files.isDirectory(path)) {
                return Decoded.none(path, "a folder");
            }
            byte[] sample;
            try (InputStream in = Files.newInputStream(path)) {
                sample = in.readNBytes(SAMPLE);
            }
            return decode(path, sample, Files.size(path), large);
        } catch (IOException | RuntimeException e) {
            return Decoded.none(path, "unreadable: " + e.getClass().getSimpleName());
        }
    }

    /**
     * {@code path} from {@code sample}, its first bytes (at least {@link #SAMPLE} of them, or the whole file), and its
     * {@code size}: for a caller that has read the start of the file already to decide what else it might be.
     */
    public static Decoded decode(Path path, byte[] sample, long size, boolean large) {
        Imagelib.Kind kind;
        try {
            kind = Imagelib.probe(sample);
        } catch (RuntimeException | LinkageError e) {
            unavailable(e);
            return Decoded.none(path, "the image decoder is unavailable");
        }
        if (kind == Imagelib.Kind.UNKNOWN) {
            return Decoded.none(path, "not an image");
        }
        if (size > MAX_IMAGE) {
            return Decoded.none(path, "too large to show");
        }
        try {
            Stamp stamp = Stamp.of(path);
            byte[] bytes = Files.readAllBytes(path);
            Picture picture;
            Picture big = null;
            String what;
            if (kind == Imagelib.Kind.VECTOR) {
                Imagelib.Size intrinsic = Imagelib.svgSize(bytes);
                // Rasterised once, at the larger size asked for; the thumbnail is a shrink of it.
                int[] box = vectorBox(intrinsic.width(), intrinsic.height(), large ? Picture.VIEW_SIDE : VECTOR_SIDE);
                Frames frames = Imagelib.rasterize(bytes, box[0], box[1]);
                int w = Math.round(intrinsic.width());
                int h = Math.round(intrinsic.height());
                picture = Picture.of(stamp, frames, w, h, true);
                if (large) {
                    big = Picture.of(stamp, frames, w, h, true, Picture.VIEW_SIDE);
                }
                what = "SVG image";
            } else {
                Frames frames = Imagelib.decode(bytes);
                picture = Picture.of(stamp, frames, frames.width(), frames.height(), false);
                if (large) {
                    big = Picture.of(stamp, frames, frames.width(), frames.height(), false, Picture.VIEW_SIDE);
                }
                what = identify(sample);
            }
            return new Decoded(path, describe(what, picture), picture, big);
        } catch (OutOfMemoryError e) {
            // The one error worth catching: it is this file's pixels that did not fit, and the next file's will.
            LOG.warn("{} is too large to decode", path);
            return Decoded.none(path, "too large to decode");
        } catch (IOException | RuntimeException | LinkageError e) {
            if (e instanceof LinkageError) {
                unavailable(e);
            }
            return Decoded.none(path, "does not decode as an image");
        }
    }

    /** A vector's raster size: its own aspect, with the long side at {@code side}, and never zero. */
    static int[] vectorBox(float width, float height, int side) {
        float w = width > 0f ? width : 1f;
        float h = height > 0f ? height : 1f;
        float scale = side / Math.max(w, h);
        return new int[] {Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale))};
    }

    /** "PNG image · 1920 × 1080", and for an animation its frame count and length. */
    static String describe(String what, Picture p) {
        StringBuilder s = new StringBuilder(what).append(" · ").append(p.sourceWidth()).append(" × ")
                .append(p.sourceHeight());
        if (p.animated()) {
            long ms = 0;
            for (int d : p.delays()) {
                ms += d;
            }
            s.append(" · ").append(p.count()).append(" frames");
            if (ms > 0) {
                s.append(String.format(Locale.ROOT, ", %.1f s", ms / 1000d));
            }
        }
        return s.toString();
    }

    /** What the first bytes say the image is. Small on purpose: a signature table is a promise to keep it right. */
    static String identify(byte[] b) {
        if (starts(b, 0x89, 'P', 'N', 'G')) {
            return "PNG image";
        }
        if (starts(b, 0xFF, 0xD8, 0xFF)) {
            return "JPEG image";
        }
        if (starts(b, 'G', 'I', 'F', '8')) {
            return "GIF image";
        }
        if (starts(b, 'R', 'I', 'F', 'F') && b.length >= 12 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B'
                && b[11] == 'P') {
            return "WebP image";
        }
        if (starts(b, 'B', 'M')) {
            return "BMP image";
        }
        return "image";
    }

    private static boolean starts(byte[] b, int... signature) {
        if (b.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((b[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static void unavailable(Throwable e) {
        if (!decoderReported) {
            decoderReported = true;
            LOG.warn("no pictures: the image decoder is unavailable ({})", e.toString());
        }
    }
}
