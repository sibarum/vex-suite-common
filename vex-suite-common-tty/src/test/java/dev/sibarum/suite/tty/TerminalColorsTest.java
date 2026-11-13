package dev.sibarum.suite.tty;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.core.style.Theme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a screen is drawn in: the dark set kept as it was, the light set readable, and a set that follows the look. */
class TerminalColorsTest {

    /** The sixteen a screen draws in by default are the ones it always drew in, and so are its defaults. */
    @Test
    void campbellIsUnchanged() {
        for (int n = 0; n < 16; n++) {
            assertEquals(Color.rgb(Terminal.Palette.xterm(n)), TerminalColors.CAMPBELL.ansi(n).in(Theme.DARK), "ANSI " + n);
        }
        assertEquals(Color.rgb(0x0C0C0C), TerminalColors.CAMPBELL.background().in(Theme.DARK));
        assertEquals(Color.rgb(0xCCCCCC), TerminalColors.CAMPBELL.foreground().in(Theme.DARK));
        assertEquals(Color.argb(0xA0264F78), TerminalColors.CAMPBELL.selection().in(Theme.DARK));
    }

    /** Every one of the light set reads as text on its own ground, the white ones included. */
    @Test
    void theLightSetReadsOnItsGround() {
        Color ground = TerminalColors.LIGHT.background().in(Theme.LIGHT);
        for (Color on : new Color[] {ground, Color.rgb(0xFFFFFF), Color.rgb(0xE8E8E8)}) {
            for (int n = 0; n < 16; n++) {
                double ratio = contrast(TerminalColors.LIGHT.ansi(n).in(Theme.LIGHT), on);
                assertTrue(ratio >= 4.5, "ANSI " + n + " is " + ratio + ":1 on " + on);
            }
        }
        assertTrue(contrast(TerminalColors.LIGHT.foreground().in(Theme.LIGHT), ground) >= 7.0);
    }

    /** One set handed once answers in whichever look the tree is drawn in. */
    @Test
    void aFollowingSetAnswersPerLook() {
        TerminalColors both = TerminalColors.following(TerminalColors.CAMPBELL, TerminalColors.LIGHT);
        for (int n = 0; n < 16; n++) {
            assertEquals(TerminalColors.CAMPBELL.ansi(n).in(Theme.DARK), both.ansi(n).in(Theme.DARK));
            assertEquals(TerminalColors.LIGHT.ansi(n).in(Theme.LIGHT), both.ansi(n).in(Theme.LIGHT));
        }
        assertEquals(TerminalColors.LIGHT.background().in(Theme.LIGHT), both.background().in(Theme.LIGHT));
        assertEquals(TerminalColors.CAMPBELL.selection().in(Theme.DARK), both.selection().in(Theme.DARK));
    }

    /** Dim text is mixed toward the ground per channel in sRGB, six parts to four, as it always was. */
    @Test
    void dimIsTheMixItAlwaysWas() {
        Paint dim = new TerminalColors.Dimmed(Paint.of(Color.rgb(0xCCCCCC)), Paint.of(Color.rgb(0x0C0C0C)));
        int expected = (0xCC * 6 + 0x0C * 4) / 10;
        assertEquals(Color.rgb((expected << 16) | (expected << 8) | expected), dim.in(Theme.DARK));
    }

    static double contrast(Color a, Color b) {
        double la = relative(a);
        double lb = relative(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double relative(Color c) {
        return 0.2126 * linear(c.r()) + 0.7152 * linear(c.g()) + 0.0722 * linear(c.b());
    }

    private static double linear(float channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }
}
