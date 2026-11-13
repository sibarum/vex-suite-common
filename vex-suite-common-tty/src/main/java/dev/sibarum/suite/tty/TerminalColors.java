package dev.sibarum.suite.tty;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;

import java.util.List;
import java.util.Objects;

/**
 * What a {@link TerminalScreen} draws its colours in: the sixteen ANSI colours a program names by number (SGR 30–37,
 * 90–97 and their backgrounds, and 38;5 / 48;5 below 16), the default background and foreground, where a program asked
 * for no colour of its own, and the wash over a selection.
 *
 * <p>Each is a {@link Paint}, not a colour, and a cell keeps the number the program named, not the colour it stood for
 * when it was written ({@link Terminal}). So the screen resolves them every frame, as every other named colour in the
 * tree is resolved: hand it paints that answer per look ({@link #following}, or roles of the application's own) and
 * what is already on screen recolours when the look changes, fading with the rest of the tree. The 240 colours above the
 * sixteen, and a program's own 24-bit colours (SGR 38;2), are the program's, the same in every look.
 *
 * <p>{@link #CAMPBELL} is what a screen is drawn in until told otherwise: the dark terminal every console here has
 * worn. {@link #LIGHT} is a set for a light page.
 *
 * @param background where a program asked for no background: the node's own, and what shows between the cells
 * @param foreground where a program asked for no colour of its own
 * @param selection  the wash over selected cells, drawn over their backgrounds and under their glyphs
 * @param ansi       the sixteen, by number: black, red, green, yellow, blue, magenta, cyan, white, then bright of each
 */
public record TerminalColors(Paint background, Paint foreground, Paint selection, List<Paint> ansi) {

    /**
     * Windows Terminal's "Campbell", what every console here has worn: Gray on near-black, and the selection a
     * translucent blue. The colours a screen has until it is handed others, unchanged from before a screen took paints.
     */
    public static final TerminalColors CAMPBELL = of(0x0C0C0C, 0xCCCCCC, 0xA0264F78,
            0x0C0C0C, 0xC50F1F, 0x13A10E, 0xC19C00, 0x0037DA, 0x881798, 0x3A96DD, 0xCCCCCC,
            0x767676, 0xE74856, 0x16C60C, 0xF9F1A5, 0x3B78FF, 0xB4009E, 0x61D6D6, 0xF2F2F2);

    /**
     * A set for a light page, after Windows Terminal's "One Half Light", with every one of the sixteen held to 4.5:1
     * against a ground anywhere from white down to {@code #E8E8E8} (a card a shade under a light page), so that whatever
     * a program writes in reads as text. Where One Half Light's own would
     * not read it is darkened at its hue: its yellow, cyan and green, and its bright ones, which are pastels meant for a
     * dark page. White and bright white are the two that turn over. On a dark page they are the text colours (Gray,
     * and the emphasis above it), and programs write in them as text (PSReadLine draws numbers and members in them), so
     * here they are text too: a grey a shade lighter than the default ink, and near-black for the emphasis. Black stays
     * One Half Light's charcoal, so 30 still reads, and bright black is the quiet grey a program dims a part with.
     */
    public static final TerminalColors LIGHT = of(0xFAFAFA, 0x383A42, 0x60A8C8F0,
            0x383A42, 0xB93226, 0x327035, 0x845A00, 0x1565A8, 0xA626A4, 0x0A6E82, 0x474A52,
            0x5F626A, 0xB0283F, 0x2C711D, 0x7A5300, 0x3159C4, 0x8E3FB5, 0x0A6E69, 0x1F2023);

    public TerminalColors {
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(foreground, "foreground");
        Objects.requireNonNull(selection, "selection");
        ansi = List.copyOf(ansi);
        if (ansi.size() != 16) {
            throw new IllegalArgumentException("a terminal has sixteen ANSI colours, not " + ansi.size());
        }
    }

    /**
     * A set of colours the look does not decide: {@code background} and {@code foreground} as {@code 0xRRGGBB}, the
     * selection as {@code 0xAARRGGBB}, and the sixteen as {@code 0xRRGGBB} in ANSI order.
     */
    public static TerminalColors of(int background, int foreground, int selection, int... sixteen) {
        if (sixteen.length != 16) {
            throw new IllegalArgumentException("a terminal has sixteen ANSI colours, not " + sixteen.length);
        }
        Paint[] ansi = new Paint[16];
        for (int i = 0; i < 16; i++) {
            ansi[i] = Paint.of(Color.rgb(sixteen[i]));
        }
        return new TerminalColors(Paint.of(Color.rgb(background)), Paint.of(Color.rgb(foreground)),
                Paint.of(Color.argb(selection)), List.of(ansi));
    }

    /**
     * {@code dark} where the look's page is dark and {@code light} where it is light, decided every frame by the look
     * the tree is drawn in: hand a screen this once and it follows the person's choice of look with nothing run. Which
     * way a look faces is read as the stack reads it elsewhere ({@code LayeredIcon.dark}): its ink is light.
     */
    public static TerminalColors following(TerminalColors dark, TerminalColors light) {
        Paint[] ansi = new Paint[16];
        for (int i = 0; i < 16; i++) {
            ansi[i] = new ByGround(dark.ansi.get(i), light.ansi.get(i));
        }
        return new TerminalColors(new ByGround(dark.background, light.background),
                new ByGround(dark.foreground, light.foreground), new ByGround(dark.selection, light.selection),
                List.of(ansi));
    }

    /** These colours with another default background and foreground: an application's own ground and ink. */
    public TerminalColors ground(Paint background, Paint foreground) {
        return new TerminalColors(background, foreground, selection, ansi);
    }

    /** These colours with another wash over a selection. */
    public TerminalColors selection(Paint selection) {
        return new TerminalColors(background, foreground, selection, ansi);
    }

    /** ANSI colour {@code n}, 0 to 15. */
    public Paint ansi(int n) {
        return ansi.get(n);
    }

    /** One paint where the look's page is dark and another where it is light: see {@link #following}. */
    record ByGround(Paint dark, Paint light) implements Paint {
        @Override
        public Color in(Theme theme) {
            Color ink = theme.color(Role.INK);
            boolean onDark = 0.2126f * ink.r() + 0.7152f * ink.g() + 0.0722f * ink.b() > 0.5f;
            return (onDark ? dark : light).in(theme);
        }
    }

    /**
     * Dim text (SGR 2): {@code ink} six parts to {@code ground}'s four, mixed per channel in sRGB as the terminal always
     * has, in whichever look is drawing.
     */
    record Dimmed(Paint ink, Paint ground) implements Paint {
        @Override
        public Color in(Theme theme) {
            Color a = ink.in(theme);
            Color b = ground.in(theme);
            int r = (byte255(a.r()) * 6 + byte255(b.r()) * 4) / 10;
            int g = (byte255(a.g()) * 6 + byte255(b.g()) * 4) / 10;
            int bl = (byte255(a.b()) * 6 + byte255(b.b()) * 4) / 10;
            return Color.rgb((r << 16) | (g << 8) | bl);
        }

        private static int byte255(float channel) {
            return Math.round(channel * 255f);
        }
    }
}
