package dev.sibarum.suite.view;

import dev.sibarum.suite.update.Release;
import dev.sibarum.suite.update.Updates;
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
import sibarum.probe.Log;
import sibarum.tactroller.api.Key;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * A word from the application about its own updates, in the window's top right corner: that a newer version is out,
 * with <b>Update and restart</b> and <b>Later</b>; and, the first time it opens after one, whether the update took.
 * Nothing at all while there is nothing to say, which is nearly always: a check that finds the latest installed, or
 * cannot reach the network, says nothing.
 *
 * <p>The application makes one, appends {@link #node} to its root as the last child (a floating node draws over the
 * ones before it), and calls {@link #start} once its window is up. The check runs on {@link Gui#offload}, so a slow
 * network never holds a frame. <b>Update and restart</b> hands over to {@link Updates#restart} and then asks the
 * application to close, through {@code close}, as its own close button would.
 *
 * <p>Landmarks, each with the prefix given to the constructor: {@link #NOTICE} on the panel, {@link #TITLE} on its
 * first line (which scripts await), and the buttons {@link #UPDATE}, {@link #LATER} and {@link #DISMISS}.
 */
public final class UpdateNotice {

    private static final Log LOG = Log.of("suite.update");

    public static final String NOTICE = "update";
    public static final String TITLE = "update.title";
    public static final String UPDATE = "update.restart";
    public static final String LATER = "update.later";
    public static final String DISMISS = "update.dismiss";

    private static final Length WIDTH = Length.rem(20f);
    private static final Length CORNER = Length.rem(1.125f);
    private static final Length BUTTON_CORNER = Length.rem(0.625f);
    private static final Shortcut ENTER = Shortcut.of(Key.ENTER);
    private static final Shortcut SPACE = Shortcut.of(Key.SPACE);

    private final Gui gui;
    private final Updates updates;
    private final Runnable close;
    private final Supplier<List<String>> args;
    private final String appName;

    private final Node node;
    private final IconView icon;
    private final Node title;
    private final Node detail;
    private final Node notes;
    private final Node update;
    private final Node later;
    private final Node dismiss;

    /** Whether a restart has been asked for: the buttons do nothing more once it has. */
    private volatile boolean restarting;

    /**
     * @param updates   the running application's updates: {@link Updates#of}, when it is installed
     * @param close     closes the application, as its own close button does
     * @param args      what the application is started again with: what it has open, say. Asked on the press.
     * @param landmarks put before every landmark name
     */
    public UpdateNotice(Gui gui, Updates updates, Runnable close, Supplier<List<String>> args, String landmarks) {
        this.gui = gui;
        this.updates = updates;
        this.close = close;
        this.args = args;
        this.appName = updates.install().name();

        Theme theme = gui.theme();
        this.icon = IconView.of(gui, Icon.ARROW_DOWN).size(Length.rem(1.25f)).tint(Role.ACCENT);
        this.title = gui.text("").font(SuiteType.UI).textSize(SuiteType.BODY).textColor(theme.color(Role.INK))
                .width(Length.grow(1f)).wordWrap(true);
        this.detail = line(SuiteType.META, SuiteLook.TEXT);
        this.notes = line(SuiteType.META, SuiteLook.TEXT).visible(false);
        this.update = button("Update and restart", true, this::restart);
        this.later = button("Later", false, this::hide);
        this.dismiss = button("OK", false, this::hide);

        Node head = gui.row().width(Length.FILL).height(Length.AUTO).alignItems(AlignItems.START)
                .gap(Length.rem(0.625f)).scroll(false, false).children(icon.node().hitInert(true), title);
        Node buttons = gui.row().width(Length.FILL).height(Length.AUTO).gap(Length.rem(0.5f)).scroll(false, false)
                .children(update, later, dismiss);
        Node panel = gui.column().role("status").width(WIDTH).height(Length.AUTO).gap(Length.rem(0.5f))
                .padding(Length.rem(0.875f)).corner(CORNER).scroll(false, false)
                .background(theme.color(SuiteLook.CHROME))
                .border(Length.dp(1), theme.color(SuiteLook.LINE_STRONG))
                .elevation(theme.elevation(Relief.FLOATING))
                .children(head, detail, notes, buttons);
        // Floating at the root's right edge, clamped inside it; a spacer above keeps it clear of the title bar, and
        // the padding off the window's edge.
        Node clear = gui.box().size(Length.dp(1), Length.rem(2.25f));
        this.node = gui.column().width(Length.AUTO).height(Length.AUTO).padding(Length.ZERO, Length.rem(1f))
                .alignItems(AlignItems.END).scroll(false, false)
                .floatAt(Length.percent(100f), Length.ZERO).visible(false).children(clear, panel);

        gui.landmark(landmarks + NOTICE, panel);
        gui.landmark(landmarks + TITLE, title);
        gui.landmark(landmarks + UPDATE, update);
        gui.landmark(landmarks + LATER, later);
        gui.landmark(landmarks + DISMISS, dismiss);
    }

    public Node node() {
        return node;
    }

    /** Say how the last update went, if one ran since the last start, and then check for a newer version. */
    public void start() {
        gui.offload().execute(() -> {
            updates.outcome().ifPresent(o -> {
                if (o.ok()) {
                    say(Icon.SUCCESS, Role.ACCENT, appName + " was updated to " + o.version() + ".", "", false);
                } else {
                    say(Icon.ERROR, Role.DIM, appName + "'s update did not finish.",
                            "It is still " + o.version() + ". What the updater said is in " + o.log() + ".", false);
                }
            });
            switch (updates.check()) {
                case Updates.Check.Available a -> {
                    LOG.info("{} {} is available; {} is installed", appName, a.release().version(),
                            updates.install().version());
                    offer(a.release());
                }
                case Updates.Check.Current c -> LOG.debug("{} {} is the latest", appName, c.version());
                case Updates.Check.Failed f -> LOG.info("could not check for updates: {}", f.why());
            }
        });
    }

    private void offer(Release r) {
        String detail = "You have " + updates.install().version()
                + (r.bytes() > 0 ? " · " + megabytes(r.bytes()) + " to download" : "") + ".";
        say(Icon.ARROW_DOWN, Role.ACCENT, appName + " " + r.version() + " is available.", detail, true);
        notes.text(r.notes()).visible(!r.notes().isEmpty());
    }

    /** Show the panel with these words, and either the offer's two buttons or OK alone. */
    private synchronized void say(Icon glyph, Role tint, String first, String second, boolean offer) {
        if (restarting) {
            return;
        }
        icon.icon(glyph).tint(tint);
        title.text(first);
        detail.text(second).visible(!second.isEmpty());
        notes.visible(false);
        update.visible(offer);
        later.visible(offer);
        dismiss.visible(!offer);
        node.visible(true);
    }

    private synchronized void hide() {
        if (!restarting) {
            node.visible(false);
        }
    }

    private void restart() {
        synchronized (this) {
            if (restarting) {
                return;
            }
            restarting = true;
        }
        icon.icon(Icon.REFRESH).tint(Role.ACCENT);
        title.text("Updating " + appName + "…");
        detail.text(appName + " closes now and opens again when the update is done.").visible(true);
        notes.visible(false);
        update.visible(false);
        later.visible(false);
        dismiss.visible(false);
        List<String> again = args.get();
        gui.offload().execute(() -> {
            try {
                updates.restart(again);
                LOG.info("closing for the update; {} will start again", appName);
            } catch (IOException e) {
                LOG.warn("could not start the update", e);
                synchronized (this) {
                    restarting = false;
                }
                say(Icon.ERROR, Role.DIM, "The update could not start.", e.getMessage(), false);
                return;
            }
            close.run();
        });
    }

    static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
    }

    // ---------------------------------------------------------------- building

    private Node line(Length size, Role ink) {
        return gui.text("").font(SuiteType.UI).textSize(size).textColor(gui.theme().color(ink))
                .width(Length.FILL).wordWrap(true);
    }

    /**
     * Words as a button: a click on them, or Enter or Space while focused, shaded on hover as a {@link Tile} is. The
     * main one is filled in the chosen colour and edged in the accent, so it is the one the eye goes to.
     */
    private Node button(String words, boolean main, Runnable action) {
        Node label = gui.text(words).font(SuiteType.UI).textSize(SuiteType.META).wordWrap(false).hitInert(true);
        Node b = gui.row().role("button").width(Length.AUTO).height(Length.AUTO).alignItems(AlignItems.CENTER)
                .padding(Length.rem(0.375f), Length.rem(0.75f)).corner(BUTTON_CORNER).scroll(false, false)
                .children(label);
        gui.focusable(b, true);
        gui.cursor(b, CursorShape.POINTER);
        gui.onClick(b, action);
        gui.claim(b, ENTER, ClaimScope.FOCUSED, action);
        gui.claim(b, SPACE, ClaimScope.FOCUSED, action);
        gui.onState(b, s -> paint(b, label, main, s));
        paint(b, label, main, InteractionState.NORMAL);
        return b;
    }

    private void paint(Node b, Node label, boolean main, InteractionState s) {
        Theme theme = gui.theme();
        boolean lit = s != InteractionState.NORMAL;
        if (main) {
            b.background(theme.color(SuiteLook.CHOSEN, s)).border(Length.dp(1), theme.color(Role.ACCENT))
                    .elevation(theme.elevation(Relief.RAISED, s));
            label.textColor(theme.color(SuiteLook.CHOSEN_INK));
        } else {
            b.background(lit ? theme.color(Role.RAISED, s) : Color.TRANSPARENT)
                    .border(Length.dp(1), theme.color(lit ? SuiteLook.LINE_STRONG : Role.LINE))
                    .elevation(lit ? theme.elevation(Relief.RAISED, s) : Length.ZERO);
            label.textColor(theme.color(lit ? Role.INK : SuiteLook.TEXT));
        }
    }
}
