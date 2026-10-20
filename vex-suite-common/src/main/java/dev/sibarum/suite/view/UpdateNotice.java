package dev.sibarum.suite.view;

import dev.sibarum.suite.update.Install;
import dev.sibarum.suite.update.Release;
import dev.sibarum.suite.update.SuiteUpdate;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * A word from the application about updates to it and the rest of its suite, in the window's top right corner: that
 * newer versions are out, with <b>Update and restart</b> (or <b>Update</b>, when this application is not among them)
 * and <b>Later</b>; and, the first time it opens after an update, whether it took. Nothing at all while there is
 * nothing to say, which is nearly always: a check that finds the latest installed, or cannot reach the network, says
 * nothing.
 *
 * <p>The application makes one, appends {@link #node} to its root as the last child (a floating node draws over the
 * ones before it), and calls {@link #start} once its window is up. The check runs on {@link Gui#offload}, so a slow
 * network never holds a frame.
 *
 * <p><b>Updating</b> is a {@link SuiteUpdate}: the other applications to be updated that are open are asked to close,
 * one at a time, and the panel says which it is waiting for. One that stays open is most likely asking its user about
 * unsaved changes; after a moment the panel says so, with <b>Show</b> (bring it to the front), <b>Skip</b> (update
 * the rest without it) and <b>Cancel</b> (update nothing, and open again what closed). Then the script takes over, and
 * this application closes through {@code close}, as its own close button would, if it is being updated too; if not,
 * it stays open and says how the update went when the script is done.
 *
 * <p>Landmarks, each with the prefix given to the constructor: {@link #NOTICE} on the panel, {@link #TITLE} on its
 * first line (which scripts await), and the buttons {@link #UPDATE}, {@link #LATER}, {@link #DISMISS}, {@link #SHOW},
 * {@link #SKIP} and {@link #CANCEL}.
 */
public final class UpdateNotice {

    private static final Log LOG = Log.of("suite.update");

    public static final String NOTICE = "update";
    public static final String TITLE = "update.title";
    public static final String UPDATE = "update.restart";
    public static final String LATER = "update.later";
    public static final String DISMISS = "update.dismiss";
    public static final String SHOW = "update.show";
    public static final String SKIP = "update.skip";
    public static final String CANCEL = "update.cancel";

    private static final Length WIDTH = Length.rem(20f);
    private static final Length CORNER = Length.rem(1.125f);
    private static final Length BUTTON_CORNER = Length.rem(0.625f);
    private static final Shortcut ENTER = Shortcut.of(Key.ENTER);
    private static final Shortcut SPACE = Shortcut.of(Key.SPACE);

    /** How long after closing itself for an update this application says it is waiting to close. */
    private static final long STILL_HERE_MS = 5_000;

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
    private final Button update;
    private final Button later;
    private final Button dismiss;
    private final Button show;
    private final Button skip;
    private final Button cancel;

    /** Whether an update has been asked for: the offer's buttons do nothing more, and no check speaks over it. */
    private volatile boolean restarting;
    /** What was last offered, offered again when an update is cancelled. */
    private volatile List<Updates.Available> offered = List.of();
    /** The update under way, which the Show, Skip and Cancel buttons answer. */
    private volatile SuiteUpdate running;

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
                .width(Length.FILL).wordWrap(true);
        this.detail = line(SuiteType.META, SuiteLook.TEXT);
        this.notes = line(SuiteType.META, SuiteLook.TEXT).visible(false);
        this.update = button("Update and restart", true, this::restart);
        this.later = button("Later", false, this::hide);
        this.dismiss = button("OK", false, this::hide);
        this.show = button("Show", true, () -> {
            SuiteUpdate u = running;
            if (u != null) {
                u.show();
            }
        });
        this.skip = button("Skip", false, () -> {
            SuiteUpdate u = running;
            if (u != null) {
                u.skip();
            }
        });
        this.cancel = button("Cancel", false, () -> {
            SuiteUpdate u = running;
            if (u != null) {
                u.cancel();
            }
        });

        // A column, not a row with the icon in it: wrapped text is measured at its width only in a column, and in a
        // row a second line of the title would land on the line below. So the title is inset on both sides by the
        // icon and its gap, and the icon floats in the inset on the left.
        Node head = gui.column().width(Length.FILL).height(Length.AUTO).padding(Length.ZERO, Length.rem(1.875f))
                .scroll(false, false).children(title, icon.node().hitInert(true).floatAt(Length.ZERO, Length.ZERO));
        Node buttons = gui.row().width(Length.FILL).height(Length.AUTO).gap(Length.rem(0.5f)).scroll(false, false)
                .children(update.node, later.node, dismiss.node, show.node, skip.node, cancel.node);
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
        gui.landmark(landmarks + UPDATE, update.node);
        gui.landmark(landmarks + LATER, later.node);
        gui.landmark(landmarks + DISMISS, dismiss.node);
        gui.landmark(landmarks + SHOW, show.node);
        gui.landmark(landmarks + SKIP, skip.node);
        gui.landmark(landmarks + CANCEL, cancel.node);
    }

    public Node node() {
        return node;
    }

    /** Say how the last update went, if one ran since the last start, and then check the suite for newer versions. */
    public void start() {
        gui.offload().execute(() -> {
            updates.outcome().ifPresent(this::report);
            Updates.Survey s = updates.survey();
            for (String why : s.failed()) {
                LOG.info("could not check for updates: {}", why);
            }
            if (s.available().isEmpty()) {
                LOG.debug("{} and its suite are the latest", appName);
                return;
            }
            for (Updates.Available a : s.available()) {
                LOG.info("{} {} is available; {} is installed", a.install().name(), a.release().version(),
                        a.install().version());
            }
            offer(s.available());
        });
    }

    // ---------------------------------------------------------------- what it says

    private void offer(List<Updates.Available> available) {
        offered = available;
        boolean self = available.stream().anyMatch(this::self);
        long bytes = available.stream().mapToLong(a -> a.release().bytes()).sum();
        String size = bytes > 0 ? megabytes(bytes) + " to download" : "";
        String first;
        String second;
        String list;
        if (available.size() == 1) {
            Updates.Available a = available.getFirst();
            first = a.install().name() + " " + a.release().version() + " is available.";
            second = "You have " + a.install().version() + (size.isEmpty() ? "" : " · " + size) + ".";
            list = a.release().notes();
        } else {
            first = available.size() + " updates are available.";
            second = size.isEmpty() ? "" : capitalised(size) + " in all.";
            List<String> lines = new ArrayList<>();
            for (Updates.Available a : available) {
                lines.add(a.install().name() + " " + a.install().version() + " → " + a.release().version());
            }
            list = String.join("\n", lines);
        }
        List<String> open = available.stream().filter(a -> !self(a) && !a.install().processes().isEmpty())
                .map(a -> a.install().name()).toList();
        if (!open.isEmpty()) {
            String they = open.size() == 1 ? "it" : "they";
            second = (second.isEmpty() ? "" : second + " ") + names(open) + (open.size() == 1 ? " is" : " are")
                    + " open: " + they + " will close first and open again after.";
        }
        String words = second;
        String more = list;
        synchronized (this) {
            if (restarting) {
                return;
            }
            panel(Icon.ARROW_DOWN, Role.ACCENT, first, words);
            notes.text(more).visible(!more.isEmpty());
            update.label.text(self ? "Update and restart" : "Update");
            buttons(update, later);
        }
    }

    /** How the last update went, application by application. */
    private void report(Updates.Report r) {
        List<Updates.Outcome> ok = r.apps().stream().filter(Updates.Outcome::ok).toList();
        List<Updates.Outcome> failed = r.apps().stream().filter(o -> !o.ok()).toList();
        if (failed.isEmpty()) {
            if (ok.size() == 1) {
                Updates.Outcome o = ok.getFirst();
                tell(Icon.SUCCESS, Role.ACCENT, o.name() + " was updated to " + o.version() + ".", "");
            } else {
                tell(Icon.SUCCESS, Role.ACCENT, ok.size() + " applications were updated.",
                        String.join(" · ", ok.stream().map(o -> o.name() + " " + o.version()).toList()) + ".");
            }
            return;
        }
        String first = failed.size() == 1 ? failed.getFirst().name() + "'s update did not finish."
                : failed.size() + " updates did not finish.";
        List<String> why = new ArrayList<>();
        for (Updates.Outcome o : failed) {
            String still = o.version().isEmpty() ? "was not updated" : "is still " + o.version();
            why.add(o.stayedOpen() ? o.name() + " stayed open, so it " + still + "." : o.name() + " " + still + ".");
        }
        if (!ok.isEmpty()) {
            why.add(names(ok.stream().map(o -> o.name() + " " + o.version()).toList())
                    + (ok.size() == 1 ? " was" : " were") + " updated.");
        }
        why.add((r.apps().size() == 1 ? "What the updater said is in " : "What the updaters said is in ") + r.log() + ".");
        tell(Icon.ERROR, Role.DIM, first, String.join(" ", why));
    }

    /** A word with an OK, when no update is under way to speak over. */
    private synchronized void tell(Icon glyph, Role tint, String first, String second) {
        if (restarting) {
            return;
        }
        panel(glyph, tint, first, second);
        buttons(dismiss);
    }

    /** Show the panel with these words, the notes hidden and no buttons yet. */
    private synchronized void panel(Icon glyph, Role tint, String first, String second) {
        icon.icon(glyph).tint(tint);
        title.text(first);
        detail.text(second).visible(!second.isEmpty());
        notes.visible(false);
        buttons();
        node.visible(true);
    }

    /** Exactly these buttons shown. */
    private void buttons(Button... shown) {
        for (Button b : List.of(update, later, dismiss, show, skip, cancel)) {
            boolean on = false;
            for (Button s : shown) {
                on |= s == b;
            }
            b.node.visible(on);
        }
    }

    private synchronized void hide() {
        if (!restarting) {
            node.visible(false);
        }
    }

    // ---------------------------------------------------------------- updating

    private void restart() {
        List<Updates.Available> plan;
        synchronized (this) {
            if (restarting || offered.isEmpty()) {
                return;
            }
            restarting = true;
            plan = offered;
            panel(Icon.REFRESH, Role.ACCENT, "Getting ready to update…", "");
        }
        List<String> again = args.get();
        SuiteUpdate u = new SuiteUpdate(updates);
        running = u;
        Thread.ofPlatform().daemon().name("suite-update").start(() -> run(u, plan, again));
    }

    private void run(SuiteUpdate u, List<Updates.Available> plan, List<String> again) {
        SuiteUpdate.Result result;
        try {
            result = u.run(plan, again, new SuiteUpdate.Progress() {
                @Override
                public void closing(Install app) {
                    panel(Icon.REFRESH, Role.ACCENT, "Closing " + app.name() + "…",
                            "So it can be updated. If it asks about unsaved changes, answer it there.");
                    buttons(cancel);
                }

                @Override
                public void stillOpen(Install app, boolean asked) {
                    if (asked) {
                        panel(Icon.INFO, Role.ACCENT, app.name() + " is still open.",
                                "It may be asking whether to save your changes. Answer it there, or close it "
                                        + "yourself, and the update goes on.");
                        buttons(show, skip, cancel);
                    } else {
                        panel(Icon.INFO, Role.ACCENT, app.name() + " has not closed.",
                                "It has no window to close, so it may be busy. The update goes on when it closes.");
                        buttons(skip, cancel);
                    }
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } finally {
            running = null;
        }
        switch (result) {
            case SuiteUpdate.Result.Started s -> handedOver(s);
            case SuiteUpdate.Result.Busy b -> stopped(Icon.INFO, "An update is already under way.",
                    "It was started from another application of the suite.");
            case SuiteUpdate.Result.Nothing n -> stopped(Icon.INFO, "Nothing was updated.",
                    "Everything that needed updating was skipped. The updates are offered again next time.");
            case SuiteUpdate.Result.Failed f -> {
                LOG.warn("could not start the update", f.why());
                stopped(Icon.ERROR, "The update could not start.", String.valueOf(f.why().getMessage()));
            }
            case SuiteUpdate.Result.Cancelled c -> {
                LOG.info("the update was cancelled");
                synchronized (this) {
                    restarting = false;
                }
                offer(offered);
            }
        }
    }

    private void stopped(Icon glyph, String first, String second) {
        synchronized (this) {
            restarting = false;
        }
        tell(glyph, Role.DIM, first, second);
    }

    /** The script has it: close, if this application is being updated too, and say how it went if still here. */
    private void handedOver(SuiteUpdate.Result.Started s) {
        List<String> names = s.update().stream().map(Install::name).toList();
        String updating = names.size() == 1 ? "Updating " + names.getFirst() + "…"
                : "Updating " + names.size() + " applications…";
        if (s.self()) {
            panel(Icon.REFRESH, Role.ACCENT, updating,
                    appName + " closes now and opens again when the update is done.");
            LOG.info("closing for the update; {} will start again", appName);
            close.run();
        } else {
            panel(Icon.REFRESH, Role.ACCENT, updating,
                    names(names) + ". Whatever closed for it opens again when it is done.");
        }
        try {
            if (s.self() && !s.script().waitFor(STILL_HERE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                // Still here: this application's own close is waiting on its user, or was refused.
                detail.text(appName + " is updated as soon as it closes; the others go ahead meanwhile.")
                        .visible(true);
            }
            s.script().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (this) {
            restarting = false;
        }
        updates.outcome().ifPresent(this::report);
    }

    private boolean self(Updates.Available a) {
        return a.install().id().equals(updates.install().id());
    }

    /** "A", "A and B", "A, B and C". */
    static String names(List<String> names) {
        return switch (names.size()) {
            case 0 -> "";
            case 1 -> names.getFirst();
            default -> String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
        };
    }

    private static String capitalised(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
    }

    // ---------------------------------------------------------------- building

    private Node line(Length size, Role ink) {
        return gui.text("").font(SuiteType.UI).textSize(size).textColor(gui.theme().color(ink))
                .width(Length.FILL).wordWrap(true);
    }

    /** A button, and the words on it, which some change. */
    private record Button(Node node, Node label) {
    }

    /**
     * Words as a button: a click on them, or Enter or Space while focused, shaded on hover as a {@link Tile} is. The
     * main one is filled in the chosen colour and edged in the accent, so it is the one the eye goes to.
     */
    private Button button(String words, boolean main, Runnable action) {
        Node label = gui.text(words).font(SuiteType.UI).textSize(SuiteType.META).wordWrap(false).hitInert(true);
        Node b = gui.row().role("button").width(Length.AUTO).height(Length.AUTO).alignItems(AlignItems.CENTER)
                .padding(Length.rem(0.375f), Length.rem(0.75f)).corner(BUTTON_CORNER).scroll(false, false)
                .children(label).visible(false);
        gui.focusable(b, true);
        gui.cursor(b, CursorShape.POINTER);
        gui.onClick(b, action);
        gui.claim(b, ENTER, ClaimScope.FOCUSED, action);
        gui.claim(b, SPACE, ClaimScope.FOCUSED, action);
        gui.onState(b, s -> paint(b, label, main, s));
        paint(b, label, main, InteractionState.NORMAL);
        return new Button(b, label);
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
