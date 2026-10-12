package dev.sibarum.suite.view;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import sibarum.tactroller.api.Key;

/**
 * One choice among a few, as a row of words: an option in a list of them, of which one is chosen. The words are the
 * control, and what it is called.
 *
 * <p>Drawn as a {@link Tile} is: bare at rest, so a list reads as a list; shaded from the theme's ladder on hover and
 * press; and chosen, filled with {@link SuiteLook#CHOSEN} and edged in the accent. Enter and Space while focused, and a
 * click anywhere on it, choose it.
 */
final class Choice {

    private static final Length CORNER = Length.rem(0.5f);
    private static final Shortcut ENTER = Shortcut.of(Key.ENTER);
    private static final Shortcut SPACE = Shortcut.of(Key.SPACE);

    private final Gui gui;
    private final Node node;
    private final Node words;
    private volatile boolean on;
    private volatile InteractionState state = InteractionState.NORMAL;

    Choice(Gui gui, String label, Runnable onPress) {
        this.gui = gui;
        this.words = gui.text(label).font(SuiteType.UI).textSize(SuiteType.BODY).wordWrap(false).hitInert(true);
        this.node = gui.row().role("radio").width(Length.FILL).height(Length.AUTO).alignItems(AlignItems.CENTER)
                .padding(Length.rem(0.375f), Length.rem(0.75f)).corner(CORNER).scroll(false, false).children(words);
        gui.focusable(node, true);
        gui.cursor(node, CursorShape.POINTER);
        gui.onClick(node, onPress);
        gui.claim(node, ENTER, ClaimScope.FOCUSED, onPress);
        gui.claim(node, SPACE, ClaimScope.FOCUSED, onPress);
        gui.onState(node, s -> {
            state = s;
            paint();
        });
        paint();
    }

    Node node() {
        return node;
    }

    /** Show it as the one chosen, or not. Silent: the caller decides what choosing means. */
    Choice on(boolean value) {
        on = value;
        paint();
        return this;
    }

    private void paint() {
        Theme theme = gui.theme();
        InteractionState s = state;
        boolean lit = s != InteractionState.NORMAL;
        if (on) {
            node.background(Tile.glass(theme.color(SuiteLook.CHOSEN, s)))
                    .border(Length.dp(1), theme.color(Role.ACCENT))
                    .elevation(theme.elevation(Relief.RAISED, s));
            words.textColor(theme.color(SuiteLook.CHOSEN_INK));
        } else {
            node.background(lit ? Tile.glass(theme.color(Role.RAISED, s)) : Color.TRANSPARENT)
                    .border(Length.dp(1), lit ? theme.color(SuiteLook.LINE_STRONG) : Color.TRANSPARENT)
                    .elevation(lit ? theme.elevation(Relief.RAISED, s) : Length.ZERO);
            words.textColor(theme.color(lit ? Role.INK : SuiteLook.TEXT));
        }
    }
}
