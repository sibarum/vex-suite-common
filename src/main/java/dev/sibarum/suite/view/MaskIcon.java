package dev.sibarum.suite.view;

import dev.vexelray.canvas.Color;
import dev.vexelray.codec.image.Decoded;
import dev.vexelray.codec.image.Picture;
import dev.vexelray.codec.image.Pictures;
import dev.vexelray.codec.image.gui.Textures;
import dev.vexelray.gui.core.Node;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One of the suite's utility icons, from {@code vexelray-icons/utility/small}: a mask, one solid shape whose fill is
 * {@code currentColor}, copied into this module's resources ({@code icons/<name>-mask.svg}) as that folder's README
 * asks. Where {@link Icons} are strokes drawn here, these are the icon set's own art.
 *
 * <p>Drawn by decoding the SVG at the pixel size of its box, with the root's {@code color} set to the ink first, so it
 * is sharp at any size and in any colour a control's state asks for. Each size and colour is decoded once, and uploaded
 * once by the {@link Textures} it is drawn with.
 */
enum MaskIcon {

    /** A gear: the settings. */
    SETTINGS("settings");

    private final String svg;
    private final Map<String, Picture> decoded = new ConcurrentHashMap<>();

    MaskIcon(String name) {
        this.svg = read(name + "-mask.svg");
    }

    /**
     * Show this icon on {@code glyph}, {@code side} px square, in {@code ink}. False if it cannot be drawn: no window
     * to upload into yet, or the decoder is not there; the control is still a control.
     */
    boolean draw(Node glyph, Textures textures, int side, Color ink) {
        if (side <= 0) {
            return false;
        }
        String hex = hex(ink);
        String key = "icon:" + name().toLowerCase(Locale.ROOT) + ":" + hex + ":" + side;
        Picture p = decoded.computeIfAbsent(key, k -> {
            Decoded d = Pictures.of(k, tinted(hex), side);
            return d.image() ? d.picture() : null;
        });
        return p != null && textures.show(p, glyph::image);
    }

    /** The SVG with its root's {@code color}, which its {@code currentColor} fill takes, set to {@code hex}. */
    private byte[] tinted(String hex) {
        return svg.replaceFirst("<svg color=\"#[0-9a-fA-F]{3,8}\"", "<svg color=\"" + hex + "\"")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String hex(Color c) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", channel(c.r()), channel(c.g()), channel(c.b()));
    }

    private static int channel(float c) {
        return Math.round(Math.max(0f, Math.min(1f, c)) * 255f);
    }

    private static String read(String file) {
        try (InputStream in = MaskIcon.class.getResourceAsStream("icons/" + file)) {
            if (in == null) {
                throw new IllegalStateException("no icon " + file + " on the class path");
            }
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (!s.startsWith("<svg color=\"")) {
                throw new IllegalStateException(file + " is not a tintable mask: its root has no color");
            }
            return s;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read icon " + file + ": " + e.getMessage(), e);
        }
    }
}
