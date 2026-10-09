package dev.sibarum.suite.view;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The palette, held to what a dark theme has to be true of.
 *
 * <p>Worth having from the first day rather than the day a colour goes wrong, because a palette is derived:
 * one anchor moved by a tenth changes every surface and every shade of text at once, and nothing on screen
 * says which change did it. These are the checks that would have caught it.
 *
 * <p>Since the design system arrived, the second half <b>pins the gap between the design and the
 * construction</b> -- each token against what the palette derives for the role standing in for it, with the
 * difference bounded. A gap that is measured is a known quantity; a gap that is absorbed is a surprise waiting for
 * a review.
 */
class SuiteLookTest {

    @Test
    void theInkReadsAgainstThePage() {
        Color page = SuiteLook.THEME.color(Role.PAGE);
        Color ink = SuiteLook.THEME.color(Role.INK);
        assertTrue(luminance(ink) - luminance(page) > 0.5f,
                "primary text should be far lighter than the page it sits on");
    }

    @Test
    void surfacesClimbAwayFromThePage() {
        float page = luminance(SuiteLook.THEME.color(Role.PAGE));
        float panel = luminance(SuiteLook.THEME.color(Role.PANEL));
        float raised = luminance(SuiteLook.THEME.color(Role.RAISED));
        assertTrue(panel > page, "a panel should sit above the page");
        assertTrue(raised > panel, "a raised surface should sit above a panel");
    }

    @Test
    void textFadesInSteps() {
        float ink = luminance(SuiteLook.THEME.color(Role.INK));
        float dim = luminance(SuiteLook.THEME.color(Role.DIM));
        float faint = luminance(SuiteLook.THEME.color(Role.FAINT));
        assertTrue(ink > dim, "dim text should be quieter than primary text");
        assertTrue(dim > faint, "faint text should be quieter than dim text");
    }

    @Test
    void theAccentIsNotTheInk() {
        assertNotEquals(SuiteLook.THEME.color(Role.INK), SuiteLook.THEME.color(Role.ACCENT),
                "the accent should be a colour, not the text shade");
    }

    // ------------------------------------------------------------ the gap between the tokens and the ladder

    /**
     * Each framework level against the design-system token it stands in for. The ladder is one straight line
     * and the tokens are not quite, so these are measured gaps, not equalities: a palette change that widens one
     * past its bound is a change to how the app looks, and should be a decision rather than a side effect.
     */
    @Test
    void theLadderLandsOnTheTokens() {
        assertNear(0x0e0c14, Role.PAGE, 0.001, "bg-0");
        assertNear(0x15121d, Role.CHROME, 0.001, "bg-1");
        assertNear(0x1c1827, Role.PANEL, 0.002, "card");
        assertNear(0x221d2f, Role.RAISED, 0.007, "raised");
        assertNear(0x2a2440, Role.LINE, 0.008, "tree-active");
        assertNear(0xece8f5, Role.INK, 0.001, "text-hi");
        assertNear(0x8e86a3, Role.DIM, 0.003, "text-dim");
        assertNear(0x5e5578, Role.FAINT, 0.02, "text-disabled");
        assertNear(0xb08fff, Role.ACCENT, 0.001, "accent");
        assertNear(0xb08fff, Role.ACTION, 0.001, "accent, as the primary button's fill");
        assertNear(0xe06a7a, Role.DANGER, 0.001, "danger");
    }

    @Test
    void thePrimaryButtonsLabelIsDark() {
        // contrastTo picks the page for a fill as light as the accent: bg-0, a shade off on-accent (#120e1f).
        assertNear(0x0e0c14, Role.ON_ACTION, 0.001, "on-accent");
    }

    private static void assertNear(int rgb, Role role, double bound, String token) {
        Oklab want = Oklab.of(Color.rgb(rgb));
        Oklab got = Oklab.of(SuiteLook.THEME.color(role));
        double gap = Math.abs(want.l() - got.l());
        assertTrue(gap <= bound, String.format("%s: lightness is %.4f off the token (bound %.4f)", token, gap, bound));
    }

    /** Rough perceived brightness -- enough to order two shades, which is all these assertions need. */
    private static float luminance(Color c) {
        return 0.2126f * c.r() + 0.7152f * c.g() + 0.0722f * c.b();
    }
}
