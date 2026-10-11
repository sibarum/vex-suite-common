package dev.sibarum.suite.view;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import sibarum.tactroller.api.Key;

/**
 * One of the viewer's controls: a square of one size for every control, an icon alone. The word that says what it
 * does is its accessible name, read by scripts and screen readers, and not drawn: the icons say it to the eye.
 *
 * <p><b>Why not {@code Button}.</b> {@code Button} is one text node and takes no icon or content of its own, so this is
 * {@code Popout.button}'s recipe with {@code Button}'s keyboard: a column that claims Enter and Space while focused,
 * leaves the focus order when disabled, and is shaded on hover from the theme's own ladder. Its content cannot take the
 * pointer, so a click anywhere on the square is a click on the control.
 *
 * <p><b>Chosen is opaque.</b> A tile that is on (a menu open, a mode held) fills with {@link SuiteLook#CHOSEN} and is edged in
 * the accent, which is what the design asks of a chosen control and what {@code Button}'s 35% highlight cannot give.
 */
public final class Tile {

    /** Every tile, a thumbnail included, is this square: 72 px at the design's size. */
    public static final float SIDE_REM = 4.5f;
    public static final Length SIDE = Length.rem(SIDE_REM);
    public static final Length CORNER = Length.rem(1.125f);
    /** Between tiles, and around them inside a slab. */
    public static final Length GAP = Length.rem(0.5f);
    private static final Length GLYPH = Length.rem(2f);

    /**
     * How much of a tile's fill is drawn: the picture and the backdrop show through it. Only the fill: the edge, the
     * icon and the word stay opaque, so a tile reads the same over anything. There is no backdrop blur to soften
     * what shows through (FN-25), so this stays well above half.
     */
    public static final float GLASS = 0.62f;

    private static final Shortcut ENTER = Shortcut.of(Key.ENTER);
    private static final Shortcut SPACE = Shortcut.of(Key.SPACE);

    private final Gui gui;
    private final Node node;
    private final Node glyph;
    private final Node label;
    private volatile Icons icon;

    private volatile boolean enabled = true;
    private volatile boolean on;
    private volatile InteractionState state = InteractionState.NORMAL;
    private volatile Runnable onPress = () -> { };
    private volatile float glyphPx;

    private Tile(Gui gui, Icons icon, Node glyph, String word) {
        this.gui = gui;
        this.icon = icon;
        this.glyph = glyph.hitInert(true);
        this.label = gui.text(word).font(SuiteType.UI).textSize(SuiteType.SMALL).wordWrap(false).hitInert(true)
                .visible(false);
        this.node = gui.column().role("button").size(SIDE, SIDE).corner(CORNER).scroll(false, false)
                .justify(Justify.CENTER).alignItems(AlignItems.CENTER)
                .children(this.glyph, label);
        gui.focusable(node, true);
        gui.cursor(node, CursorShape.POINTER);
        gui.onClick(node, this::activate);
        gui.onState(node, s -> {
            state = s;
            paint();
        });
        gui.claim(node, ENTER, ClaimScope.FOCUSED, this::activate);
        gui.claim(node, SPACE, ClaimScope.FOCUSED, this::activate);
        if (icon != null) {
            gui.onResize(this.glyph, box -> {
                glyphPx = box.rect().w();
                paint();
            });
        }
        paint();
    }

    /** A tile showing {@code icon}, named {@code word}. */
    public static Tile of(Gui gui, Icons icon, String word) {
        return new Tile(gui, icon, gui.box().size(GLYPH, GLYPH), word);
    }

    /** A tile showing a node of the caller's, named {@code word}: a swatch, a reading. It is not recoloured. */
    public static Tile of(Gui gui, Node glyph, String word) {
        return new Tile(gui, null, glyph, word);
    }

    /** The glyph size, for a caller drawing its own. */
    public static Length glyph() {
        return GLYPH;
    }

    public Node node() {
        return node;
    }

    public Tile onPress(Runnable handler) {
        onPress = handler == null ? () -> { } : handler;
        return this;
    }

    /** Draw {@code value} in place of the icon: a state shown in the icon as well as the fill. Not for a node glyph. */
    public Tile icon(Icons value) {
        icon = value;
        paint();
        return this;
    }

    /** A tile that holds a state: a menu open, a mode on. Only changes how it is announced. */
    public Tile toggle() {
        node.role("togglebutton");
        return this;
    }

    /** Show the tile as on or off. Silent: the caller decides what pressing it means. */
    public Tile on(boolean value) {
        on = value;
        paint();
        return this;
    }

    public boolean on() {
        return on;
    }

    public Tile enabled(boolean value) {
        enabled = value;
        gui.focusable(node, value);
        gui.cursor(node, value ? CursorShape.POINTER : CursorShape.DEFAULT);
        paint();
        return this;
    }

    /** {@code c} as a see-through fill: {@link #GLASS} of it. */
    public static Color glass(Color c) {
        return Color.withAlpha(c, c.a() * GLASS);
    }

    private void activate() {
        if (enabled) {
            onPress.run();
        }
    }

    private void paint() {
        Theme theme = gui.theme();
        InteractionState s = enabled ? state : InteractionState.NORMAL;
        Color fill;
        Color edge;
        Color ink;
        Color word;
        if (!enabled) {
            fill = theme.color(Role.CHROME);
            edge = theme.color(Role.LINE);
            ink = theme.color(Role.FAINT);
            word = ink;
        } else if (on) {
            fill = theme.color(SuiteLook.CHOSEN, s);
            edge = theme.color(Role.ACCENT);
            ink = theme.color(SuiteLook.CHOSEN_INK);
            word = ink;
        } else {
            fill = theme.color(Role.RAISED, s);
            edge = theme.color(SuiteLook.LINE_STRONG);
            ink = theme.color(Role.INK);
            word = theme.color(SuiteLook.TEXT);
        }
        node.background(glass(fill)).border(Length.dp(1), edge).lit(enabled)
                .elevation(enabled ? theme.elevation(Relief.RAISED, s) : Length.ZERO);
        label.textColor(word);
        float px = glyphPx;
        if (icon != null && px > 0f) {
            glyph.picture(icon.sketch(px, ink).picture());
        }
    }
}
