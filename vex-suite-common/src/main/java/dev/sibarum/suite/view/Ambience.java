package dev.sibarum.suite.view;

import dev.vexelray.canvas.Color;
import dev.vexelray.codec.image.Picture;

/**
 * The colour of the room a picture is in: what {@link PictureViewer}'s Auto backdrop fills the window with behind an
 * opaque picture, so the picture seems lit by itself rather than cut out of a grey wall.
 *
 * <p><b>The picture's own colour, dimmed.</b> Its pixels are averaged in Oklab, where an average is what the eye takes
 * in at a glance: lightness as it is seen, and the hues as vectors, so a picture of one strong colour keeps it and one
 * of many cancels to grey. Pixels nearer the edges count more, since that is where the backdrop meets the picture.
 * The mean is then moved into a dim band ({@link #LIGHT_FLOOR} to {@link #LIGHT_FLOOR} + {@link #LIGHT_SPAN}), its
 * lightness keeping its order, so a bright picture sits on a lighter dusk than a dark one but never on a light wall;
 * and its colourfulness is given back some of what averaging took, up to {@link #MAX_CHROMA}, so the hue is felt and
 * never shouts.
 *
 * <p><b>A picture with any transparency has none</b> ({@link #of} is null): what shows through a hole means something,
 * and the checkerboard says it is a hole.
 */
final class Ambience {

    /** The dimmest the room is, and how much lighter a white picture's is, in Oklab lightness. */
    static final float LIGHT_FLOOR = 0.14f;
    static final float LIGHT_SPAN = 0.16f;
    /** How much of its colour averaging took is given back, and the most the room ever has, in Oklab chroma. */
    static final float CHROMA_GAIN = 1.5f;
    static final float MAX_CHROMA = 0.06f;
    /** At most this many pixels are read across and down for the colour; the transparency is read whole. */
    private static final int SAMPLES = 128;

    private static final float[] LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255d;
            LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    private Ambience() {
    }

    /** The room for {@code p}'s first frame, or null when it has a pixel that is not opaque, or no pixels. */
    static Color of(Picture p) {
        int w = p.frameWidth();
        int h = p.frameHeight();
        int stride = p.sheetWidth() * 4;
        byte[] px = p.sheet();
        if (w <= 0 || h <= 0) {
            return null;
        }
        for (int y = 0; y < h; y++) {
            for (int i = y * stride + 3, end = i + w * 4; i < end; i += 4) {
                if (px[i] != (byte) 0xFF) {
                    return null;
                }
            }
        }
        int nx = Math.min(w, SAMPLES);
        int ny = Math.min(h, SAMPLES);
        double sumL = 0, sumA = 0, sumB = 0, weights = 0;
        float[] lab = new float[3];
        for (int j = 0; j < ny; j++) {
            int y = (int) ((j + 0.5) * h / ny);
            float v = 2f * (j + 0.5f) / ny - 1f;
            for (int k = 0; k < nx; k++) {
                int x = (int) ((k + 0.5) * w / nx);
                float u = 2f * (k + 0.5f) / nx - 1f;
                // 1 in the middle, rising to 4 at the edges, steeply, so a border band counts most.
                float edge = Math.max(Math.abs(u), Math.abs(v));
                double weight = 1 + 3 * Math.pow(edge, 4);
                int i = y * stride + x * 4;
                oklab(LINEAR[px[i] & 0xFF], LINEAR[px[i + 1] & 0xFF], LINEAR[px[i + 2] & 0xFF], lab);
                sumL += weight * lab[0];
                sumA += weight * lab[1];
                sumB += weight * lab[2];
                weights += weight;
            }
        }
        return room((float) (sumL / weights), (float) (sumA / weights), (float) (sumB / weights));
    }

    /** The room for a picture whose mean is {@code (l, a, b)} in Oklab: dimmed, its colour tempered, in gamut. */
    static Color room(float l, float a, float b) {
        float light = LIGHT_FLOOR + LIGHT_SPAN * Math.clamp(l, 0f, 1f);
        float chroma = (float) Math.hypot(a, b);
        float scale = chroma <= 0f ? 0f : Math.min(chroma * CHROMA_GAIN, MAX_CHROMA) / chroma;
        float[] rgb = new float[3];
        // Less colour until it is a colour the screen has: at this lightness, rarely more than once.
        for (int tries = 0; tries < 8; tries++, scale *= 0.7f) {
            linear(light, a * scale, b * scale, rgb);
            if (rgb[0] >= 0f && rgb[1] >= 0f && rgb[2] >= 0f && rgb[0] <= 1f && rgb[1] <= 1f && rgb[2] <= 1f) {
                break;
            }
        }
        return Color.rgb(encode(rgb[0]), encode(rgb[1]), encode(rgb[2]));
    }

    /** Linear sRGB to Oklab (Björn Ottosson's), into {@code out}. */
    static void oklab(float r, float g, float b, float[] out) {
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        out[0] = (float) (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s);
        out[1] = (float) (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s);
        out[2] = (float) (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s);
    }

    /** Oklab to linear sRGB, into {@code out}; out of gamut, a channel is below 0 or above 1. */
    static void linear(float lightness, float a, float b, float[] out) {
        double l = lightness + 0.3963377774 * a + 0.2158037573 * b;
        double m = lightness - 0.1055613458 * a - 0.0638541728 * b;
        double s = lightness - 0.0894841775 * a - 1.2914855480 * b;
        l = l * l * l;
        m = m * m * m;
        s = s * s * s;
        out[0] = (float) (4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s);
        out[1] = (float) (-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s);
        out[2] = (float) (-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
    }

    private static float encode(float c) {
        c = Math.clamp(c, 0f, 1f);
        return (float) (c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055);
    }
}
