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
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.icons.IconView;
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

    /** The icon before the words: a little larger than the text, so its detail reads. */
    private static final Length ICON = Length.rem(1.375f);

    private final Gui gui;
    private final Node node;
    private final Node words;
    private final IconView icon;
    private volatile boolean on;
    private volatile InteractionState state = InteractionState.NORMAL;

    /** A choice named {@code label}, after {@code icon}, or with the icon's room left empty for null. */
    Choice(Gui gui, Icon icon, String label, Runnable onPress) {
        this.gui = gui;
        this.icon = IconView.of(gui, icon).size(ICON);
        this.words = gui.text(label).font(SuiteType.UI).textSize(SuiteType.BODY).wordWrap(false).hitInert(true);
        this.node = gui.row().role("radio").width(Length.FILL).height(Length.AUTO).alignItems(AlignItems.CENTER)
                .gap(Length.rem(0.5f)).padding(Length.rem(0.3125f), Length.rem(0.625f)).corner(CORNER)
                .scroll(false, false).children(this.icon.node().hitInert(true), words);
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

    /** An action rather than an option: pressing it does something, and it is never shown chosen. */
    Choice action() {
        node.role("button");
        return this;
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
        Color ink;
        if (on) {
            node.background(Tile.glass(theme.color(SuiteLook.CHOSEN, s)))
                    .border(Length.dp(1), theme.color(Role.ACCENT))
                    .elevation(theme.elevation(Relief.RAISED, s));
            ink = theme.color(SuiteLook.CHOSEN_INK);
        } else {
            node.background(lit ? Tile.glass(theme.color(Role.RAISED, s)) : Color.TRANSPARENT)
                    .border(Length.dp(1), lit ? theme.color(SuiteLook.LINE_STRONG) : Color.TRANSPARENT)
                    .elevation(lit ? theme.elevation(Relief.RAISED, s) : Length.ZERO);
            ink = theme.color(lit ? Role.INK : SuiteLook.TEXT);
        }
        words.textColor(ink);
        // The chosen one glows in its ink; under the pointer, an icon lifts off its own shadow.
        icon.tint(ink).glow(on ? 0.2f : 0f).shadow(!on && lit ? 0.08f : 0f);
    }
}
