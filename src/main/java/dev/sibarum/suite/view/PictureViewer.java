package dev.sibarum.suite.view;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.ImageFilter;
import dev.vexelray.codec.image.Picture;
import dev.vexelray.codec.image.gui.ImageView;
import dev.vexelray.codec.image.gui.ImageView.Backdrop;
import dev.vexelray.codec.image.gui.Playback;
import dev.vexelray.codec.image.gui.Textures;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.icons.IconView;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.krono.Scheduled;
import dev.vexelray.gui.widget.Slider;
import dev.vexelray.gui.widget.TitleBar;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Time;
import sibarum.kronometer.anim.Ease;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
 * <p><b>Every control is a {@link Tile}</b>: one size, an icon alone. They sit in one bar along the bottom, in three
 * groups: Close and what this is on the left, moving through the folder in the middle (Previous, the
 * {@link Filmstrip}, Next), and how it is shown on the right (Background, Settings, Keep shown). When the host
 * {@link Host#opens opens}, the words that say what this is are its Open button, shaded on hover like a tile, and say
 * {@value #PROMPT} while nothing is open.
 *
 * <p><b>A narrow window keeps what fits</b> ({@link #fit}). With no room for a thumbnail between Previous and Next,
 * the two move out of the bar to the window's left and right edges; with no room for both side groups, Background and
 * Keep shown go, and then Close and the words. Nothing is ever drawn overlapping or cut off.
 *
 * <p><b>The picture has the whole window</b> under the title bar, and the controls float over it: when the pointer
 * rests they fade, after {@link #IDLE_MS}, and moving the mouse, clicking or pressing Tab brings them back. <b>Keep
 * shown</b> pins them: they stop fading, the pin stands upright on the chosen fill, and they take room of their own,
 * the picture shrinking to the rest so that no control covers any of it. The backdrop menu is drawn over the picture
 * either way; it closes on a choice, a second press, a click on the picture, or Escape.
 *
 * <p><b>The picture can be looked at closely</b> ({@link Lens}): the wheel zooms about the pointer, a drag moves the
 * picture, Ctrl and a drag turn it to any angle about its centre, Shift and the wheel turn it a little, R and Shift+R
 * turn it a quarter, and a double click fits it again. It is held where it can be seen: no larger than the stage, it
 * stays inside; larger, it keeps at least a quarter of the stage covered, and pulled past that it springs back with
 * the momentum it had. Another picture starts fitted and upright.
 *
 * <p><b>Active only while shown.</b> The idle fade runs, and a picture is uploaded, only between {@link #active
 * active(true)} and {@code active(false)}: the window it is in is up, and its {@link Textures} are bound. A picture
 * handed over before then is drawn when it becomes active, which matters to an application whose first picture is
 * decoded while its window is still being made.
 *
 * <p>Landmarks, each with the prefix given to the constructor: {@link #NAME}, {@link #INFO}, {@link #COUNT} (written at
 * once on every step, so scripts await them), the tiles {@link #CLOSE}, {@link #OPEN}, {@link #PREVIOUS}, {@link #NEXT},
 * {@link #BACKGROUND}, {@link #SETTINGS}, {@link #KEEP}, the backdrop swatches {@link #BACKDROP}{@code checker} and so
 * on, the scaling choices {@link #MOVING}{@code lanczos} and {@link #RESTING}{@code lanczos} and so on, and the
 * transport's {@link #PLAY}, {@link #FRAME_BACK}, {@link #FRAME_ON}, {@link #SEEK}, {@link #POSITION} and {@link #LOOP}.
 *
 * <p><b>A picture that plays is played like a movie</b> ({@code Playback}): a transport floats above the bar while it
 * is up, with play and pause, a frame back and on, a seek bar to drag, the clock and frame, and loop, and fades with
 * the bar. Space plays and pauses, comma and period step a frame, with Shift a second, and L turns looping on and off.
 * It plays as soon as it is shown, round and round, and holds while the window is not focused.
 *
 * <p><b>Settings</b> (the gear) opens a menu of how the picture is scaled: while it moves, by the GPU every frame from
 * its texture, and at rest, made again from the file's own pixels (see {@code Sharpen}). The same filters in each, from
 * {@link ImageFilter}; the application keeps the choice ({@link Host#scaled}) and gives it back ({@link #scaling}).
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

        /**
         * Whether the viewer offers to open something else: the words beside Close become a button. Asked once, when the viewer is built. No by default: Vexplore's viewer shows what its explorer
         * chose.
         */
        default boolean opens() {
            return false;
        }

        /** Open something else: a click on the words, or Enter or Space on them. Only called when {@link #opens}. */
        default void open() {
        }

        /**
         * The user chose how pictures are scaled in the Settings menu: {@code moving}, as they are drawn every frame,
         * and {@code resting}, as they are made again once still. Already applied; this is for keeping it.
         */
        default void scaled(ImageFilter moving, ImageFilter resting) {
        }
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
    public static final String OPEN = "open";
    public static final String PREVIOUS = "previous";
    public static final String NEXT = "next";
    public static final String BACKGROUND = "background";
    public static final String KEEP = "keep";
    public static final String BACKDROP = "backdrop.";
    public static final String SETTINGS = "settings";
    public static final String MOVING = "moving.";
    public static final String RESTING = "resting.";
    /** The transport, shown while the picture plays: play and pause, a frame back and on, the seek bar, the clock,
     *  and loop. */
    public static final String PLAY = "play";
    public static final String FRAME_BACK = "frame-back";
    public static final String FRAME_ON = "frame-on";
    public static final String SEEK = "seek";
    public static final String POSITION = "position";
    public static final String LOOP = "loop";
    /** How far Shift with comma or period moves the clock, in ms. */
    static final long JUMP_MS = 1000;

    /** How long the pointer rests before the controls fade, and how long the fade takes. */
    public static final int IDLE_MS = 1200;
    private static final int FADE_MS = 240;
    /** A frame of the picture's motion: zoom easing, a fling, the spring back. */
    private static final int FRAME_MS = 16;
    /** How long the picture rests before the sharper copy of it is made. */
    private static final int SETTLE_MS = 150;

    private static final Length SLAB_CORNER = Length.rem(1.625f);
    /** The title's text column is this wide and clips, so a long name never pushes the filmstrip aside. */
    private static final float TITLE_REM = 10f;
    private static final Length TITLE_W = Length.rem(TITLE_REM);
    /** Wider than any name the title has room for: one line, cut off at the column's edge. */
    private static final Length LINE_W = Length.rem(60f);
    /** Between the window's edges and the controls along them. */
    private static final Length MARGIN = Length.rem(1f);
    /** The room Previous or Next takes at the window's side when the controls are pinned: the tile and its margins. */
    private static final Length SIDE_ROOM = Length.rem(Tile.SIDE_REM + 2f);
    /** How far up the backdrop menu sits while the bar floats, so it opens above the bar and not over it. */
    private static final Length BAR_ROOM = Length.rem(Tile.SIDE_REM + 1.75f);
    private static final Length SWATCH = Length.rem(1.75f);
    private static final Length WORDS_CORNER = Length.rem(0.75f);
    /** What the words say, as the Open button, while nothing is open. */
    public static final String PROMPT = "Open a picture…";
    private static final Shortcut ENTER = Shortcut.of(Key.ENTER);
    private static final Shortcut SPACE = Shortcut.of(Key.SPACE);
    /** How much of a panel's fill is drawn. Its tiles are see-through too ({@link Tile#GLASS}), so together they cover
     *  about four fifths: the picture shows behind the controls and the words on them stay legible. */
    static final float PANEL_ALPHA = 0.5f;

    private final Gui gui;
    private final KronoGui krono;
    private final Host host;
    private final boolean opens;
    private final ImageView image;
    private final TitleBar bar;
    private final Node name;
    private final Node info;
    private final Node count;
    private final Tile prev;
    private final Tile next;
    private final Tile background;
    private final Tile keep;
    private final Tile settings;
    private final Filmstrip strip;
    private final Node controls;
    private final Node title;
    private final Node browse;
    private final Node tools;
    /** Where Previous and Next go when the bar has no room for them: the window's left and right edges. */
    private final Node leftSide;
    private final Node rightSide;
    /** The transport over the picture, above the bar, while the picture plays; and what it is made of. */
    private final Node transport;
    private final Node transportLift;
    private final Tile play;
    private final Tile loop;
    private final Slider seek;
    private final Node clock;
    /** Everything that fades: the bar, and the two sides. */
    private final List<Node> chrome;
    private final Node stage;
    /** The two menus over the picture, Background's and Settings', of which one at most is open. */
    private final Menu backdropMenu;
    private final Menu settingsMenu;
    private final Map<ImageFilter, Choice> movingChoices = new EnumMap<>(ImageFilter.class);
    private final Map<ImageFilter, Choice> restingChoices = new EnumMap<>(ImageFilter.class);
    private final Map<Backdrop, Tile> swatches = new EnumMap<>(Backdrop.class);

    /** The window's box, for drawing the checkerboard across it, and the size it was last drawn at. */
    private volatile NodeLayout windowBox = NodeLayout.ABSENT;
    private long checkered = -1;

    /** How the picture is looked at, and its motion while it moves. Guarded by the lens. */
    private final Lens lens = new Lens();
    private Scheduled motion;
    /** Bumped by every motion started and stopped, so a motion that was stopped never steps again. */
    private int moves;
    /** Bumped by every change to the lens, so a sharper copy is asked for only after the last of them. */
    private int settles;
    /** The sharper copy of what is shown, made at rest. */
    private final Sharpen sharpen;
    /** Whether the drag in hand turns the picture rather than moving it: Ctrl was held as it started. */
    private volatile boolean twisting;

    // Guarded by this; volatile, since the lens reads it under its own lock.
    private volatile boolean active;
    private Showing last;
    private Picture shown;
    /** The file {@link #shown} is of: where the next picture is coming from, for the side it slides in on. */
    private Path shownPath;

    /** The bar's idle fade. Guarded by this, except the volatiles, which input reads on its own thread. */
    private volatile long stirred;
    private volatile boolean hidden;
    private volatile boolean keepShown;
    /** The menu open, or null. */
    private volatile Menu open;
    /** How the picture is scaled moving (the GPU, every frame) and at rest (made again, {@link Sharpen}). */
    private volatile ImageFilter moving = ImageFilter.BILINEAR;
    private volatile ImageFilter resting = ImageFilter.LANCZOS;
    /** Which of {@link #chrome} the pointer is on, a bit each: none fades from under the pointer. */
    private final AtomicInteger over = new AtomicInteger();
    private final AtomicInteger fade = new AtomicInteger();
    private Scheduled idle;

    /** What {@link #fit} last decided, and the tile size it was measured from. Layout callbacks only. */
    private boolean arrowsInBar = true;
    private boolean arrowsAtSides;
    private float tilePx;

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
        this.opens = host.opens();

        this.name = text("", SuiteType.BODY, Role.INK, SuiteType.UI);
        this.info = text("", SuiteType.SMALL, SuiteLook.TEXT, SuiteType.MONO);
        this.count = text("", SuiteType.META, SuiteLook.TEXT, SuiteType.UI);
        // Static text wraps at its own width, so each line is given more than the column, which clips it.
        for (Node line : List.of(name, info, count)) {
            line.width(LINE_W);
        }
        gui.landmark(landmarks + NAME, name);
        gui.landmark(landmarks + INFO, info);
        gui.landmark(landmarks + COUNT, count);

        Tile close = Tile.of(gui, Icon.CLOSE, "Close").onPress(host::close);
        this.prev = Tile.of(gui, Icon.CHEVRON_LEFT, "Previous").onPress(() -> host.step(-1));
        this.next = Tile.of(gui, Icon.CHEVRON_RIGHT, "Next").onPress(() -> host.step(1));
        this.background = Tile.of(gui, Icon.PALETTE, "Background").toggle().onPress(this::toggleBackdrop);
        this.settings = Tile.of(gui, Icon.SETTINGS, "Settings").toggle().onPress(this::toggleSettings);
        this.keep = Tile.of(gui, Icon.PIN_UP, "Keep shown").toggle().onPress(this::toggleKeep);
        gui.landmark(landmarks + CLOSE, close.node());
        gui.landmark(landmarks + PREVIOUS, prev.node());
        gui.landmark(landmarks + NEXT, next.node());
        gui.landmark(landmarks + BACKGROUND, background.node());
        gui.landmark(landmarks + SETTINGS, settings.node());
        gui.landmark(landmarks + KEEP, keep.node());

        this.strip = new Filmstrip(gui, krono, textures, thumbnail, host::pick);

        Node words = gui.column().width(TITLE_W).height(Length.AUTO).gap(Length.rem(0.375f))
                .padding(Length.rem(0.25f), Length.rem(0.625f)).clip(true).scroll(false, false)
                .children(name, info, count);
        if (opens) {
            gui.landmark(landmarks + OPEN, words);
            button(words, host::open);
        }
        this.title = slab(gui.row()).alignItems(AlignItems.CENTER).children(close.node(), words);
        this.browse = slab(gui.row()).alignItems(AlignItems.CENTER).children(prev.node(), strip.node(), next.node());
        this.tools = slab(gui.row()).alignItems(AlignItems.CENTER)
                .children(background.node(), settings.node(), keep.node());

        Node left = gui.row().width(Length.grow(1f)).height(Length.AUTO).scroll(false, false).children(title);
        Node right = gui.row().width(Length.grow(1f)).height(Length.AUTO).justify(Justify.END).scroll(false, false)
                .children(tools);
        this.controls = gui.row().width(Length.percent(100f)).height(Length.AUTO).alignItems(AlignItems.CENTER)
                .padding(Length.rem(0.75f), MARGIN).gap(MARGIN).scroll(false, false)
                .children(left, browse, right);
        gui.onResize(controls, this::refit);
        this.leftSide = side(Length.ZERO);
        this.rightSide = side(Length.percent(100f));

        // The transport: shown only while the picture in front plays, above the bar, fading with it.
        this.play = Tile.of(gui, Icon.PLAY, "Play").onPress(() -> playback(Playback::toggle));
        Tile back = Tile.of(gui, Icon.STEP_BACK, "Previous frame").onPress(() -> playback(p -> p.step(-1)));
        Tile on = Tile.of(gui, Icon.STEP, "Next frame").onPress(() -> playback(p -> p.step(1)));
        this.loop = Tile.of(gui, Icon.REFRESH, "Loop").toggle().on(true)
                .onPress(() -> playback(p -> p.loop(!p.loop())));
        // A scrubber, not an edit: dragging it seeks as it goes, and nothing about it is undone.
        this.seek = new Slider(gui, 0f).undoable(false)
                .onChange(v -> playback(p -> p.seek(Math.round(v * p.duration()))));
        seek.node().width(Length.rem(16f));
        this.clock = text(clockText(0, 0), SuiteType.SMALL, SuiteLook.TEXT, SuiteType.MONO);
        gui.landmark(landmarks + PLAY, play.node());
        gui.landmark(landmarks + FRAME_BACK, back.node());
        gui.landmark(landmarks + FRAME_ON, on.node());
        gui.landmark(landmarks + SEEK, seek.node());
        gui.landmark(landmarks + POSITION, clock);
        gui.landmark(landmarks + LOOP, loop.node());
        Node deck = slab(gui.row()).alignItems(AlignItems.CENTER)
                .children(back.node(), play.node(), on.node(), seek.node(), clock, loop.node());
        this.transportLift = gui.box().size(Length.dp(1), BAR_ROOM);
        this.transport = gui.column().width(Length.percent(100f)).height(Length.AUTO).alignItems(AlignItems.CENTER)
                .padding(MARGIN).scroll(false, false).floatAt(Length.ZERO, Length.percent(100f)).visible(false)
                .children(deck, transportLift);

        this.chrome = List.of(controls, leftSide, rightSide, transport);
        for (int i = 0; i < chrome.size(); i++) {
            int bit = 1 << i;
            gui.onState(chrome.get(i), s -> over.getAndUpdate(o -> s == InteractionState.NORMAL ? o & ~bit : o | bit));
        }

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
        this.backdropMenu = menu(background, slab(gui.column()).alignItems(AlignItems.CENTER).children(caption, choices));

        // Settings: how the picture is scaled, moving and at rest, side by side, the same filters in each.
        Node movingList = gui.column().width(Length.AUTO).height(Length.AUTO).gap(Length.rem(0.125f))
                .scroll(false, false).children(heading(Icon.QUALITY_LOW, "WHILE MOVING"));
        Node restingList = gui.column().width(Length.AUTO).height(Length.AUTO).gap(Length.rem(0.125f))
                .scroll(false, false).children(heading(Icon.QUALITY_HIGH, "AT REST"));
        for (ImageFilter f : ImageFilter.values()) {
            String id = f.name().toLowerCase(Locale.ROOT).replace('_', '-');
            Icon icon = icon(f);
            Choice m = new Choice(gui, icon, f.label(), () -> chooseScaling(f, null));
            Choice r = new Choice(gui, icon, f.label(), () -> chooseScaling(null, f));
            gui.landmark(landmarks + MOVING + id, m.node());
            gui.landmark(landmarks + RESTING + id, r.node());
            movingChoices.put(f, m);
            restingChoices.put(f, r);
            movingList.append(m.node());
            restingList.append(r.node());
        }
        Node lists = gui.row().width(Length.AUTO).height(Length.AUTO).gap(MARGIN).scroll(false, false)
                .children(movingList, restingList);
        Node scalingCaption = text("SCALING", SuiteType.SMALL, Role.DIM, SuiteType.UI);
        this.settingsMenu = menu(settings, slab(gui.column()).alignItems(AlignItems.START)
                .padding(Length.rem(0.75f)).children(scalingCaption, lists));
        showScaling();

        this.image = new ImageView(gui, krono, textures, Length.ZERO, Length.ZERO).bare();
        this.sharpen = new Sharpen(gui.offload(), image);
        image.filter(moving);
        gui.onClick(image.node(), this::closeMenu);
        gui.cursor(image.node(), CursorShape.GRAB);
        gui.onDragUi(image.node(), this::drag);
        gui.onDoubleClick(image.node(), () -> look(Lens::fit));
        image.onFit(() -> look(l -> l.measure(image.fitted())));
        image.onPlayback(this::showTransport);
        this.stage = gui.column().width(Length.FILL).height(Length.grow(1f)).scroll(false, false)
                .children(image.node(), leftSide, rightSide, transport, backdropMenu.node(), settingsMenu.node());
        gui.root().direction(Direction.COLUMN).children(bar.node(), stage, controls);
        dock();
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
            if (open != null) {
                closeMenu();
            } else {
                host.close();
            }
        });
        gui.shortcut(Key.R, () -> look(l -> l.quarter(1)));
        gui.shortcut(Key.R, () -> look(l -> l.quarter(-1)), Modifier.SHIFT);
        // A picture that plays: Space plays and pauses, comma and period step a frame, with Shift a second, L loops.
        gui.shortcut(Key.SPACE, () -> playback(Playback::toggle));
        gui.shortcut(Key.COMMA, () -> playback(p -> p.step(-1)));
        gui.shortcut(Key.PERIOD, () -> playback(p -> p.step(1)));
        gui.shortcut(Key.COMMA, () -> playback(p -> p.seek(p.position() - JUMP_MS)), Modifier.SHIFT);
        gui.shortcut(Key.PERIOD, () -> playback(p -> p.seek(p.position() + JUMP_MS)), Modifier.SHIFT);
        gui.shortcut(Key.L, () -> playback(p -> p.loop(!p.loop())));

        // The framework has no per-node pointer-move hook, so the bar's idle fade reads input off the bus; and no
        // per-node wheel hook, so the wheel over the picture is read there too.
        gui.bus().subscribe(InputTopics.INPUT, e -> {
            if (e instanceof InputEvent.Scrolled s) {
                wheel(s);
            }
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
                still();
                sharpen.forget();
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
        name.text(opens && s.name().isEmpty() ? PROMPT : s.name());
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
                still();
                sharpen.forget();
                image.clear();
                look(l -> {
                    l.reset();
                    l.measure(null);
                });
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
            // A sharper copy of the picture up is looked at as it was; another picture starts fitted and upright,
            // and the one it replaces goes out as it was.
            boolean same = at != null && at.equals(shownPath);
            if (image.show(want, direction(s.pictures(), shownPath, at))) {
                shown = want;
                shownPath = at;
                look(l -> {
                    if (!same) {
                        l.reset();
                    }
                    l.measure(image.fitted());
                });
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

    /**
     * What the bar shows at a width.
     *
     * @param thumbnails  how many the filmstrip shows
     * @param arrowsInBar Previous and Next in the bar, either side of the filmstrip; otherwise at the window's sides
     * @param sides       whether they are shown at the window's sides
     * @param title       Close and the words
     * @param tools       Background, Settings and Keep shown
     */
    record Fit(int thumbnails, boolean arrowsInBar, boolean sides, boolean title, boolean tools) {
    }

    /**
     * What fits a bar {@code width} px across inside its margins, its tiles {@code tile} px square. The middle group
     * comes first while it has room for a thumbnail; then Previous and Next go to the window's sides, and the two side
     * groups share the bar, until they do not fit side by side, when Background, Settings and Keep shown go, and then
     * the rest.
     */
    static Fit fit(float width, float tile) {
        float gap = tile / 9f;
        float barGap = 2f * gap;
        // Each slab: its tiles, its padding and a gap between each two, and its 1 px edges.
        float title = tile + tile * TITLE_REM / Tile.SIDE_REM + 3f * gap + 2f;
        float tools = 3f * tile + 4f * gap + 2f;   // Background, Settings and Keep shown
        int n = thumbnails(width, Math.max(title, tools), tile, gap, barGap);
        if (n > 0) {
            return new Fit(n, true, false, true, true);
        }
        boolean sides = width >= 3f * tile;
        if (Math.max(title, tools) <= (width - barGap) / 2f) {
            return new Fit(0, false, sides, true, true);
        }
        return new Fit(0, false, sides, title <= width, false);
    }

    /** Apply {@link #fit} to the bar's new box. */
    private void refit(NodeLayout box) {
        for (Tile t : List.of(prev, next, keep, background)) {
            float w = t.node().layout().rect().w();
            if (w > 0f) {
                tilePx = w;
                break;
            }
        }
        if (!box.present() || tilePx <= 0f) {
            return;
        }
        Fit f = fit(box.content().w(), tilePx);
        strip.fit(f.thumbnails());
        if (f.arrowsInBar() != arrowsInBar) {
            arrowsInBar = f.arrowsInBar();
            if (arrowsInBar) {
                browse.insert(prev.node(), 0);
                browse.append(next.node());
            } else {
                leftSide.append(prev.node());
                rightSide.append(next.node());
            }
        }
        browse.visible(f.arrowsInBar());
        title.visible(f.title());
        tools.visible(f.tools());
        leftSide.visible(f.sides());
        rightSide.visible(f.sides());
        if (f.sides() != arrowsAtSides) {
            arrowsAtSides = f.sides();
            dock();
        }
    }

    /** A column down one side of the picture, Previous or Next centred in it, floating {@code x} across. */
    private Node side(Length x) {
        return gui.column().width(Length.AUTO).height(Length.percent(100f)).justify(Justify.CENTER)
                .padding(Length.ZERO, MARGIN).scroll(false, false).floatAt(x, Length.ZERO).visible(false);
    }

    /**
     * Float the controls over the picture, or, pinned, give them room of their own: the bar its height under the
     * picture and, when Previous and Next are at the sides, those their width either side of it.
     */
    private void dock() {
        boolean pinned = keepShown;
        if (pinned) {
            controls.unfloat();
            // Pinned, the transport takes room of its own under the picture, as the bar does under the stage.
            transport.unfloat();
        } else {
            controls.floatAt(Length.ZERO, Length.percent(100f));
            transport.floatAt(Length.ZERO, Length.percent(100f));
        }
        stage.padding(Length.ZERO, pinned && arrowsAtSides ? SIDE_ROOM : Length.ZERO);
        transportLift.visible(!pinned);
        backdropMenu.lift().visible(!pinned);
        settingsMenu.lift().visible(!pinned);
    }

    // ---------------------------------------------------------------- looking: zoom, pan, turn

    /**
     * Change how the picture is looked at, draw it, and keep it moving for as long as it moves. From any thread.
     * The lens is the lock, taken after this one and before the view's, never the other way round.
     */
    private void look(java.util.function.Consumer<Lens> change) {
        synchronized (lens) {
            change.accept(lens);
            image.pose(lens.pose());
            if (motion == null && active && lens.moving()) {
                int g = ++moves;
                motion = krono.spork("picture-motion", () -> move(g));
            }
            settle();
        }
    }

    /**
     * Something changed how the picture is looked at: if it is at rest, ask for it sharper once it has stayed so for
     * {@link #SETTLE_MS}; if not, the motion asks when it stops. Under the lens.
     */
    private void settle() {
        int g = ++settles;
        if (active && lens.still()) {
            krono.after(Dur.ms(SETTLE_MS), () -> settled(g));
        }
    }

    /** At rest since settle {@code g}, unless something has happened since: make what is shown sharper. */
    private void settled(int g) {
        Path path;
        Picture of;
        synchronized (this) {
            path = shownPath;
            of = shown;
        }
        // Where the stage is on the screen, so the part made can land on whole pixels.
        NodeLayout box = image.node().layout();
        float originX = box.present() ? box.content().x() : 0f;
        float originY = box.present() ? box.content().y() : 0f;
        float[] visible;
        synchronized (lens) {
            if (g != settles || !lens.still()) {
                return;
            }
            visible = lens.visible(Sharpen.MARGIN, originX, originY);
        }
        sharpen.request(path, of, visible, resting);
    }

    /** Motion {@code g}'s frames: step the lens and draw it, until it is still or another has taken over. */
    private void move(int g) {
        while (true) {
            Time.advance(Dur.ms(FRAME_MS));
            synchronized (lens) {
                if (g != moves) {
                    return;   // stopped, and perhaps started again since
                }
                boolean moving = lens.step(FRAME_MS / 1000f);
                image.pose(lens.pose());
                if (!moving) {
                    motion = null;
                    settle();
                    return;
                }
            }
        }
    }

    /** Stop the motion where it is. */
    private void still() {
        synchronized (lens) {
            moves++;
            if (motion != null) {
                motion.cancel();
                motion = null;
            }
        }
    }

    /**
     * The wheel, anywhere over the picture that no control covers: zoom about the pointer, or with Shift held a
     * fine turn. Up is in, and clockwise.
     */
    private void wheel(InputEvent.Scrolled s) {
        if (open != null || over.get() != 0) {
            return;
        }
        float[] p = onStage(s.x(), s.y());
        if (p == null) {
            return;
        }
        if (gui.modifiers().value().contains(Modifier.SHIFT)) {
            // Some platforms send Shift and the wheel as a sideways scroll.
            float notches = (float) (s.yOffset() != 0 ? s.yOffset() : s.xOffset());
            look(l -> l.turn(notches));
        } else if (s.yOffset() != 0) {
            float notches = (float) s.yOffset();
            look(l -> l.wheel(notches, p[0], p[1]));
        }
    }

    /** A drag on the picture moves it, or with Ctrl held at the start, turns it about its centre with the pointer. */
    private void drag(DragEvent e) {
        long now = System.nanoTime();
        float[] p = onStage(e.x(), e.y());
        if (e.phase() == DragEvent.Phase.START) {
            twisting = e.has(Modifier.CONTROL);
            gui.cursor(image.node(), CursorShape.GRABBING);
            look(l -> {
                if (!l.measured() || p == null) {
                    return;
                }
                if (twisting) {
                    l.twistFrom(p[0], p[1]);
                } else {
                    l.grab(now);
                }
            });
        } else if (e.phase() == DragEvent.Phase.MOVE) {
            look(l -> {
                if (!l.measured()) {
                    return;
                }
                if (!twisting) {
                    l.drag(e.dx(), e.dy(), now);
                } else if (p != null) {
                    l.twist(p[0], p[1]);
                }
            });
        } else {
            gui.cursor(image.node(), CursorShape.GRAB);
            look(l -> {
                l.twistEnd();
                l.release(now);
            });
        }
    }

    /** The window point {@code (x, y)} from the picture stage's centre, or null when it is not over the stage. */
    private float[] onStage(float x, float y) {
        NodeLayout box = image.node().layout();
        if (!box.present()) {
            return null;
        }
        var r = box.content();
        if (x < r.x() || y < r.y() || x > r.x() + r.w() || y > r.y() + r.h()) {
            return null;
        }
        return new float[] {x - (r.x() + r.w() / 2f), y - (r.y() + r.h() / 2f)};
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

    /**
     * A menu over the picture, opened by its tile: its panel, floating at the stage's bottom right, and a spacer under
     * it that lifts it above the bar while the bar floats.
     */
    private record Menu(Node node, Node lift, Tile tile) {
    }

    /** A menu of {@code panel}, opened by {@code tile}. */
    private Menu menu(Tile tile, Node panel) {
        Node lift = gui.box().size(Length.dp(1), BAR_ROOM);
        Node node = gui.column().height(Length.AUTO).padding(MARGIN).alignItems(AlignItems.END).scroll(false, false)
                .floatAt(Length.percent(100f), Length.percent(100f)).visible(false).children(panel, lift);
        return new Menu(node, lift, tile);
    }

    private void toggleBackdrop() {
        toggle(backdropMenu);
    }

    private void toggleSettings() {
        toggle(settingsMenu);
    }

    /** Open {@code m}, closing any other; or close it, if it is the one open. */
    private synchronized void toggle(Menu m) {
        Menu was = open;
        closeMenu();
        if (was != m) {
            open = m;
            m.node().visible(true);
            m.tile().on(true);
        }
    }

    private synchronized void closeMenu() {
        Menu m = open;
        if (m == null) {
            return;
        }
        open = null;
        m.node().visible(false);
        m.tile().on(false);
        stir();
    }

    // ---------------------------------------------------------------- scaling

    /**
     * Scale pictures with {@code moving} as they are drawn every frame, and with {@code resting} when they are made
     * again at rest: the application's kept choice, given back. Null leaves that one as it is. Does not call
     * {@link Host#scaled}.
     */
    public void scaling(ImageFilter moving, ImageFilter resting) {
        if (moving != null) {
            this.moving = moving;
            image.filter(moving);
        }
        if (resting != null) {
            this.resting = resting;
            look(l -> { });   // made again, the new way, once at rest
        }
        showScaling();
    }

    public ImageFilter moving() {
        return moving;
    }

    public ImageFilter resting() {
        return resting;
    }

    /** A choice from the Settings menu: apply it, and tell the application. */
    private void chooseScaling(ImageFilter moving, ImageFilter resting) {
        scaling(moving, resting);
        host.scaled(this.moving, this.resting);
    }

    /** A list's heading: its icon, in the caption's colour, and its words. */
    private Node heading(Icon icon, String words) {
        Node glyph = IconView.of(gui, icon).size(Length.rem(1.125f)).tint(Role.DIM).node().hitInert(true);
        return gui.row().width(Length.AUTO).height(Length.AUTO).alignItems(AlignItems.CENTER).gap(Length.rem(0.5f))
                .padding(Length.rem(0.25f), Length.rem(0.625f)).scroll(false, false)
                .children(glyph, text(words, SuiteType.SMALL, Role.DIM, SuiteType.UI));
    }

    /** The icon set's drawing of how {@code filter} scales: its kernel. */
    static Icon icon(ImageFilter filter) {
        return switch (filter) {
            case NEAREST -> Icon.FILTER_NEAREST;
            case SHARP_BILINEAR -> Icon.FILTER_SHARP_BILINEAR;
            case BILINEAR -> Icon.FILTER_BILINEAR;
            case GAUSSIAN -> Icon.FILTER_GAUSSIAN;
            case NORMAL -> Icon.FILTER_NORMAL;
            case B_SPLINE -> Icon.FILTER_BSPLINE;
            case MITCHELL -> Icon.FILTER_MITCHELL;
            case LANCZOS -> Icon.FILTER_LANCZOS;
        };
    }

    /** Mark the chosen filter in each list. */
    private void showScaling() {
        ImageFilter m = moving;
        ImageFilter r = resting;
        movingChoices.forEach((f, c) -> c.on(f == m));
        restingChoices.forEach((f, c) -> c.on(f == r));
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
        keep.icon(keepShown ? Icon.PIN_DOWN : Icon.PIN_UP).on(keepShown);
        dock();
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
        if (keepShown || open != null || over.get() != 0) {
            return;
        }
        hidden = true;
        int g = fade.incrementAndGet();
        chrome.forEach(n -> n.hitInert(true));
        krono.ramp(Dur.ms(FADE_MS), Ease.LINEAR, p -> {
            if (fade.get() == g) {
                chrome.forEach(n -> n.opacity((float) (1.0 - p)));
            }
        }, () -> { });
    }

    private void reveal() {
        fade.incrementAndGet();
        hidden = false;
        chrome.forEach(n -> n.opacity(1f).hitInert(false));
    }

    // ---------------------------------------------------------------- building

    /** A group of tiles on a panel of its own. */
    private Node slab(Node n) {
        return n.width(Length.AUTO).height(Length.AUTO).padding(Tile.GAP).gap(Tile.GAP).corner(SLAB_CORNER)
                .scroll(false, false).background(Color.withAlpha(gui.theme().color(SuiteLook.CHROME), PANEL_ALPHA))
                .border(Length.dp(1), gui.theme().color(Role.LINE))
                .elevation(gui.theme().elevation(Relief.FLOATING));
    }

    /**
     * The words as a button that runs {@code action}: a click anywhere on them, or Enter or Space while focused, and
     * {@link Tile}'s shading on hover and press. At rest it is bare, so the words read as they do when not a button.
     */
    /** Do {@code act} to the picture's playing, if the picture in front plays. */
    private void playback(Consumer<Playback> act) {
        Playback p = image.playback();
        if (p != null) {
            act.accept(p);
            stir();
        }
    }

    /**
     * Show the transport as the picture's playing has it, or hide it when the picture does not play. Called whenever
     * the playing moves, a frame at a time, so it only writes.
     */
    private void showTransport() {
        Playback p = image.playback();
        transport.visible(p != null && active);
        if (p == null) {
            return;
        }
        play.icon(p.wanted() ? Icon.PAUSE : Icon.PLAY);
        loop.on(p.loop());
        long d = p.duration();
        long at = Math.min(p.position(), d);
        seek.show(d > 0 ? (float) at / d : 0f);
        clock.text(clockText(at, d) + "  " + (Math.max(0, p.frame()) + 1) + "/" + p.count());
    }

    /** "0:01.2 / 0:04.0": where the clock is, and how long a pass is. */
    static String clockText(long at, long duration) {
        return time(at) + " / " + time(duration);
    }

    private static String time(long ms) {
        long tenths = Math.max(0, ms) / 100;
        return String.format(Locale.ROOT, "%d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10);
    }

    private void button(Node words, Runnable action) {
        name.hitInert(true);
        info.hitInert(true);
        count.hitInert(true);
        words.role("button").corner(WORDS_CORNER).border(Length.dp(1), Color.TRANSPARENT);
        gui.focusable(words, true);
        gui.cursor(words, CursorShape.POINTER);
        gui.onClick(words, action);
        gui.claim(words, ENTER, ClaimScope.FOCUSED, action);
        gui.claim(words, SPACE, ClaimScope.FOCUSED, action);
        gui.onState(words, s -> {
            Theme theme = gui.theme();
            boolean lit = s != InteractionState.NORMAL;
            words.background(lit ? Tile.glass(theme.color(Role.RAISED, s)) : Color.TRANSPARENT)
                    .border(Length.dp(1), lit ? theme.color(SuiteLook.LINE_STRONG) : Color.TRANSPARENT)
                    .elevation(lit ? theme.elevation(Relief.RAISED, s) : Length.ZERO);
        });
    }

    private Node text(String s, Length size, Role ink, int face) {
        return gui.text(s).font(face).textSize(size).textColor(gui.theme().color(ink)).wordWrap(false);
    }
}
