package dev.sibarum.suite.view;

import dev.sibarum.suite.pictures.Picture;
import dev.sibarum.suite.view.ImageView.Backdrop;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.krono.Scheduled;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * An image viewer's tree: one picture, as large as the window allows, and a bar under it to step through its folder.
 * Vexplore shows it in a second window that drives the explorer's selection; Pix shows it as the whole application.
 *
 * <p><b>It draws what it is told and keeps no place of its own.</b> The application hands it a {@link Showing} — the
 * folder's images, which one, the words, the picture once it has been read — and is told when the user steps, picks
 * from the filmstrip, or closes ({@link Host}). Where a step lands is {@link #target}, so every application steps the
 * same way; what a step changes is the application's.
 *
 * <p><b>Fast stepping costs nothing it should not.</b> A showing with no picture yet and {@link Showing#keep} leaves
 * the picture already up where it is, under the new name and count, so holding an arrow key moves the counter at once
 * and never blanks while the application reads only where the key stops.
 *
 * <p><b>Every control is a {@link Tile}</b>: one size, an icon over a word. They sit in one bar under the picture, in
 * three groups: what this is and Close on the left, moving through the folder in the middle (Previous, the
 * {@link Filmstrip}, Next), and how it is shown on the right (Background, Keep shown).
 *
 * <p><b>The picture is never covered, and never moves.</b> The bar has its own room under the picture. When the
 * pointer rests the bar fades out but keeps that room, so the picture does not grow and shrink as the mouse comes and
 * goes; moving the mouse, clicking or pressing Tab brings it back, and Keep shown stops it going. The one thing drawn
 * over the picture is the backdrop menu, which the user opens and which closes on a choice, a second press, a click on
 * the picture, or Escape.
 *
 * <p><b>Active only while shown.</b> The idle fade runs, and a picture is uploaded, only between {@link #active
 * active(true)} and {@code active(false)}: the window it is in is up, and its {@link Textures} are bound. A picture
 * handed over before then is drawn when it becomes active, which matters to an application whose first picture is
 * decoded while its window is still being made.
 *
 * <p>Landmarks, each with the prefix given to the constructor: {@link #NAME}, {@link #INFO}, {@link #COUNT} (written at
 * once on every step, so scripts await them), the tiles {@link #CLOSE}, {@link #PREVIOUS}, {@link #NEXT},
 * {@link #BACKGROUND}, {@link #KEEP}, and the backdrop swatches {@link #BACKDROP}{@code checker} and so on.
 */
public final class PictureViewer {

    /** What the viewer asks of the application. Called on input threads. */
    public interface Host {

        /** Step {@code delta} images along; {@link Integer#MIN_VALUE} and {@link Integer#MAX_VALUE} mean the ends. */
        void step(int delta);

        /** Show {@code path}, picked from the filmstrip. */
        void pick(Path path);

        /** Close the viewer: the Close tile, or Escape with no menu open. */
        void close();
    }

    /**
     * What to show.
     *
     * @param pictures the images stepped through, in order
     * @param index    where the one shown is among them, or -1
     * @param name     the line in bold: the file's name, or what is open when no file is
     * @param title    the title bar's text
     * @param count    "Image 3 of 17", or what stands for it
     * @param info     what the file is, "reading…", or why there is no picture
     * @param picture  the picture to show; null when there is none, or none yet
     * @param keep     with no picture, whether the one already up stays (it is still being read) or goes (there is
     *                 none to show)
     */
    public record Showing(List<Path> pictures, int index, String name, String title, String count, String info,
                          Picture picture, boolean keep) {
    }

    public static final String NAME = "name";
    public static final String INFO = "info";
    public static final String COUNT = "count";
    public static final String CLOSE = "close";
    public static final String PREVIOUS = "previous";
    public static final String NEXT = "next";
    public static final String BACKGROUND = "background";
    public static final String KEEP = "keep";
    public static final String BACKDROP = "backdrop.";

    /** How long the pointer rests before the bar fades, and how long the fade takes. */
    public static final int IDLE_MS = 2500;
    private static final int FADE_MS = 240;

    private static final Length SLAB_CORNER = Length.rem(1.625f);
    /** The title's text column is this wide and clips, so a long name never pushes the filmstrip aside. */
    private static final Length TITLE_W = Length.rem(10f);
    private static final Length SWATCH = Length.rem(1.75f);
    /** How much of a panel's fill is drawn. Its tiles are see-through too ({@link Tile#GLASS}), so together they cover
     *  about four fifths: the picture shows behind the controls and the words on them stay legible. */
    static final float PANEL_ALPHA = 0.5f;

    private final Gui gui;
    private final KronoGui krono;
    private final Host host;
    private final ImageView image;
    private final TitleBar bar;
    private final Node name;
    private final Node info;
    private final Node count;
    private final Tile prev;
    private final Tile next;
    private final Tile background;
    private final Tile keep;
    private final Filmstrip strip;
    private final Node controls;
    private final Node title;
    private final Node tools;
    private final Node menu;
    private final Map<Backdrop, Tile> swatches = new EnumMap<>(Backdrop.class);

    /** The window's box, for drawing the checkerboard across it, and the size it was last drawn at. */
    private volatile NodeLayout windowBox = NodeLayout.ABSENT;
    private long checkered = -1;

    // Guarded by this.
    private boolean active;
    private Showing last;
    private Picture shown;
    /** The file {@link #shown} is of: where the next picture is coming from, for the side it slides in on. */
    private Path shownPath;

    /** The bar's idle fade. Guarded by this, except the volatiles, which input reads on its own thread. */
    private volatile long stirred;
    private volatile boolean hidden;
    private volatile boolean keepShown;
    private volatile boolean menuOpen;
    private volatile boolean overBar;
    private final AtomicInteger fade = new AtomicInteger();
    private Scheduled idle;

    /**
     * Build the viewer into {@code gui}'s root, under {@code bar}, which it places and titles.
     *
     * @param thumbnail the picture already decoded for an image, or null; called off the GUI thread
     * @param landmarks put before every landmark name: {@code "viewer."} in a window that is not the viewer's own
     */
    public PictureViewer(Gui gui, KronoGui krono, Textures textures, TitleBar bar, Function<Path, Picture> thumbnail,
                         Host host, String landmarks) {
        this.gui = gui;
        this.krono = krono;
        this.host = host;
        this.bar = bar;

        this.name = text("", SuiteType.BODY, Role.INK, SuiteType.UI);
        this.info = text("", SuiteType.SMALL, SuiteLook.TEXT, SuiteType.MONO);
        this.count = text("", SuiteType.META, SuiteLook.TEXT, SuiteType.UI);
        gui.landmark(landmarks + NAME, name);
        gui.landmark(landmarks + INFO, info);
        gui.landmark(landmarks + COUNT, count);

        Tile close = Tile.of(gui, Icons.CLOSE, "Close").onPress(host::close);
        this.prev = Tile.of(gui, Icons.PREVIOUS, "Previous").onPress(() -> host.step(-1));
        this.next = Tile.of(gui, Icons.NEXT, "Next").onPress(() -> host.step(1));
        this.background = Tile.of(gui, Icons.BACKGROUND, "Background").toggle().onPress(this::toggleMenu);
        this.keep = Tile.of(gui, Icons.PIN, "Keep shown").toggle().onPress(this::toggleKeep);
        gui.landmark(landmarks + CLOSE, close.node());
        gui.landmark(landmarks + PREVIOUS, prev.node());
        gui.landmark(landmarks + NEXT, next.node());
        gui.landmark(landmarks + BACKGROUND, background.node());
        gui.landmark(landmarks + KEEP, keep.node());

        this.strip = new Filmstrip(gui, krono, textures, thumbnail, host::pick);

        Node words = gui.column().width(TITLE_W).height(Length.AUTO).gap(Length.rem(0.375f))
                .padding(Length.ZERO, Length.rem(0.375f)).clip(true).scroll(false, false)
                .children(name, info, count);
        this.title = slab(gui.row()).alignItems(AlignItems.CENTER).children(close.node(), words);
        Node browse = slab(gui.row()).alignItems(AlignItems.CENTER).children(prev.node(), strip.node(), next.node());
        this.tools = slab(gui.row()).alignItems(AlignItems.CENTER).children(background.node(), keep.node());

        Node left = gui.row().width(Length.grow(1f)).height(Length.AUTO).scroll(false, false).children(title);
        Node right = gui.row().width(Length.grow(1f)).height(Length.AUTO).justify(Justify.END).scroll(false, false)
                .children(tools);
        this.controls = gui.row().width(Length.FILL).height(Length.AUTO).alignItems(AlignItems.CENTER)
                .padding(Length.ZERO, Length.rem(1f)).gap(Length.rem(1f)).scroll(false, false)
                .children(left, browse, right);
        gui.onResize(controls, this::fitStrip);
        gui.onState(controls, s -> overBar = s != InteractionState.NORMAL);

        Node choices = gui.row().height(Length.AUTO).gap(Tile.GAP).scroll(false, false);
        for (Backdrop b : Backdrop.values()) {
            Tile t = Tile.of(gui, swatch(b), b.label()).onPress(() -> {
                backdrop(b);
                closeMenu();
            });
            gui.landmark(landmarks + BACKDROP + b.name().toLowerCase(Locale.ROOT), t.node());
            swatches.put(b, t);
            choices.append(t.node());
        }
        Node caption = text("BACKGROUND", SuiteType.SMALL, Role.DIM, SuiteType.UI);
        this.menu = gui.column().height(Length.AUTO).padding(Length.rem(1f)).scroll(false, false)
                .floatAt(Length.percent(100f), Length.percent(100f)).visible(false)
                .children(slab(gui.column()).alignItems(AlignItems.CENTER).children(caption, choices));

        this.image = new ImageView(gui, krono, textures, Length.rem(1f), Length.rem(1f)).bare();
        gui.onClick(image.node(), this::closeMenu);
        Node stage = gui.column().width(Length.FILL).height(Length.grow(1f)).scroll(false, false)
                .children(image.node(), menu);
        gui.root().direction(Direction.COLUMN).children(bar.node(), stage, controls);
        gui.onResize(gui.root(), box -> {
            windowBox = box;
            paintBackdrop();
        });
        backdrop(Backdrop.DARK);

        gui.shortcut(Key.LEFT, () -> host.step(-1));
        gui.shortcut(Key.RIGHT, () -> host.step(1));
        gui.shortcut(Key.PAGE_UP, () -> host.step(-1));
        gui.shortcut(Key.PAGE_DOWN, () -> host.step(1));
        gui.shortcut(Key.HOME, () -> host.step(Integer.MIN_VALUE));
        gui.shortcut(Key.END, () -> host.step(Integer.MAX_VALUE));
        gui.shortcut(Key.ESCAPE, () -> {
            if (menuOpen) {
                closeMenu();
            } else {
                host.close();
            }
        });

        // The framework has no per-node pointer-move hook, so the bar's idle fade reads input off the bus.
        gui.bus().subscribe(InputTopics.INPUT, e -> {
            if (e instanceof InputEvent.PointerMoved || e instanceof InputEvent.ButtonPressed
                    || e instanceof InputEvent.Scrolled
                    || e instanceof InputEvent.KeyPressed k && k.key() == Key.TAB) {
                stir();
            }
        });
    }

    /**
     * Whether the viewer is on screen with its textures bound. Becoming active draws the last showing again, picture
     * and all, and starts the bar's idle count; becoming inactive stops it and lets go of the picture.
     */
    public void active(boolean on) {
        synchronized (this) {
            if (on == active) {
                return;
            }
            active = on;
            shown = null;
            shownPath = null;
            if (!on) {
                if (idle != null) {
                    idle.cancel();
                    idle = null;
                }
                image.clear();
            } else if (last != null) {
                show(last);
            }
        }
        if (on) {
            stir();
        } else {
            closeMenu();
            reveal();
        }
    }

    /** Draw {@code s}: the words at once, the picture when it is given. */
    public synchronized void show(Showing s) {
        last = Objects.requireNonNull(s);
        count.text(s.count());
        name.text(s.name());
        info.text(s.info());
        bar.title(s.title());
        prev.enabled(s.index() > 0);
        next.enabled(s.index() >= 0 && s.index() < s.pictures().size() - 1);
        strip.show(s.pictures(), s.index());
        if (!active) {
            return;
        }
        Picture want = s.picture();
        if (want == null) {
            if (!s.keep()) {
                shown = null;
                shownPath = null;
                image.clear();
            }
            return;
        }
        if (want != shown) {
            if (shown == null) {
                // The first picture, or the first after one that was not: the bar comes with it, so the controls are
                // seen once there is something to use them on.
                stir();
            }
            Path at = s.index() < 0 ? null : s.pictures().get(s.index());
            if (image.show(want, direction(s.pictures(), shownPath, at))) {
                shown = want;
                shownPath = at;
            }
        }
    }

    /** Bring the filmstrip up to date: a neighbour's thumbnail may have been read since the last showing. */
    public synchronized void film() {
        if (last != null) {
            strip.show(last.pictures(), last.index());
        }
    }

    /**
     * Which side the picture of {@code to} comes in from, after the picture of {@code from}: 1 if it is later in the
     * folder, -1 if earlier, 0 if it is the same file (a sharper copy arriving) or either is not among {@code pictures}.
     */
    public static int direction(List<Path> pictures, Path from, Path to) {
        int a = from == null ? -1 : pictures.indexOf(from);
        int b = to == null ? -1 : pictures.indexOf(to);
        return a < 0 || b < 0 ? 0 : Integer.signum(b - a);
    }

    /**
     * Where a step of {@code delta} from {@code at} lands among {@code pictures}, stopping at the ends, or null when
     * there are none. {@link Integer#MIN_VALUE} and {@link Integer#MAX_VALUE} mean the first and the last; from
     * nothing, or from something not among them, a step starts at the first.
     */
    public static Path target(List<Path> pictures, Path at, int delta) {
        if (pictures.isEmpty()) {
            return null;
        }
        int i = at == null ? -1 : pictures.indexOf(at);
        long to;
        if (delta == Integer.MIN_VALUE) {
            to = 0;
        } else if (delta == Integer.MAX_VALUE) {
            to = pictures.size() - 1;
        } else if (i < 0) {
            to = 0;
        } else {
            to = (long) i + delta;
        }
        return pictures.get((int) Math.max(0, Math.min(pictures.size() - 1, to)));
    }

    /**
     * How many thumbnails fit between the bar's two side groups, which the middle group must not crowd: the strip
     * takes what is left once both sides have their width, so the middle stays centred.
     */
    static int thumbnails(float barWidth, float sideWidth, float tile, float gap, float barGap) {
        float middle = barWidth - 2f * sideWidth - 2f * barGap;
        // The middle slab: its padding, Previous and Next, and a gap after each tile but the last.
        float room = middle - 2f * gap - 2f * tile - gap;
        return room <= 0f ? 0 : (int) (room / (tile + gap));
    }

    private void fitStrip(NodeLayout box) {
        float tile = prev.node().layout().rect().w();
        if (!box.present() || tile <= 0f) {
            return;
        }
        float side = Math.max(title.layout().rect().w(), tools.layout().rect().w());
        float gap = tile / 9f;
        strip.fit(thumbnails(box.content().w(), side, tile, gap, 2f * gap));
    }

    // ---------------------------------------------------------------- background

    private void backdrop(Backdrop b) {
        image.backdrop(b);
        paintBackdrop();
        swatches.forEach((k, t) -> t.on(k == b));
    }

    /**
     * The backdrop is the whole window's, not only the picture's: the window's own fill, and for the checkerboard its
     * drawing, redrawn when the window changes size. The picture's frames are bare, so one pattern runs behind it all.
     */
    private synchronized void paintBackdrop() {
        Backdrop b = image.backdrop();
        Node root = gui.root();
        root.background(ImageView.color(gui.theme(), b));
        if (b != Backdrop.CHECKER) {
            root.picture(null);
            checkered = -1;
            return;
        }
        NodeLayout box = windowBox;
        int w = Math.round(box.rect().w());
        int h = Math.round(box.rect().h());
        long size = (long) w << 32 | h;
        if (box.present() && w > 0 && h > 0 && size != checkered) {
            checkered = size;
            root.picture(ImageView.checks(w, h).picture());
        }
    }

    private void toggleMenu() {
        if (menuOpen) {
            closeMenu();
        } else {
            menuOpen = true;
            menu.visible(true);
            background.on(true);
        }
    }

    private void closeMenu() {
        if (!menuOpen) {
            return;
        }
        menuOpen = false;
        menu.visible(false);
        background.on(false);
        stir();
    }

    /** A swatch of what {@code b} shows behind a picture: its colour, or a small checkerboard. */
    private Node swatch(Backdrop b) {
        Node s = gui.box().size(SWATCH, SWATCH).corner(Length.rem(0.5f)).clip(true)
                .background(ImageView.color(gui.theme(), b))
                .border(Length.dp(1.5f), gui.theme().color(Role.INK));
        if (b == Backdrop.CHECKER) {
            gui.onResize(s, box -> {
                int w = Math.round(box.rect().w());
                int h = Math.round(box.rect().h());
                if (w > 0 && h > 0) {
                    s.picture(ImageView.checks(w, h, Math.max(3, w / 4)).picture());
                }
            });
        }
        return s;
    }

    // ---------------------------------------------------------------- the idle fade

    private void toggleKeep() {
        keepShown = !keepShown;
        keep.on(keepShown);
        stir();
    }

    /** The user did something: show the bar, and start counting again. */
    private void stir() {
        stirred = System.nanoTime();
        if (hidden) {
            reveal();
        }
        arm(IDLE_MS);
    }

    private synchronized void arm(long ms) {
        if (idle == null && active) {
            idle = krono.after(Dur.ms(ms), this::rest);
        }
    }

    /** The timer ran out: fade the bar if nothing has happened since, or wait out what is left. */
    private void rest() {
        synchronized (this) {
            idle = null;
            if (!active) {
                return;
            }
        }
        if (hidden) {
            return;
        }
        long quiet = (System.nanoTime() - stirred) / 1_000_000L;
        if (quiet < IDLE_MS) {
            arm(IDLE_MS - quiet);
            return;
        }
        if (keepShown || menuOpen || overBar) {
            return;
        }
        hidden = true;
        int g = fade.incrementAndGet();
        controls.hitInert(true);
        krono.ramp(Dur.ms(FADE_MS), Ease.LINEAR, p -> {
            if (fade.get() == g) {
                controls.opacity((float) (1.0 - p));
            }
        }, () -> { });
    }

    private void reveal() {
        fade.incrementAndGet();
        hidden = false;
        controls.opacity(1f).hitInert(false);
    }

    // ---------------------------------------------------------------- building

    /** A group of tiles on a panel of its own. */
    private Node slab(Node n) {
        return n.width(Length.AUTO).height(Length.AUTO).padding(Tile.GAP).gap(Tile.GAP).corner(SLAB_CORNER)
                .scroll(false, false).background(Color.withAlpha(gui.theme().color(SuiteLook.CHROME), PANEL_ALPHA))
                .border(Length.dp(1), gui.theme().color(Role.LINE))
                .elevation(gui.theme().elevation(Relief.FLOATING));
    }

    private Node text(String s, Length size, Role ink, int face) {
        return gui.text(s).font(face).textSize(size).textColor(gui.theme().color(ink)).wordWrap(false);
    }
}
