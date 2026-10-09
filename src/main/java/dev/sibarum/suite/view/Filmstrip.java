package dev.sibarum.suite.view;

import dev.sibarum.suite.pictures.Picture;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.ImageRegion;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The viewer's filmstrip: the shown image in the middle and its neighbours either side, each a {@link Tile}-sized
 * square that shows that image when clicked. The shown one is edged in the accent.
 *
 * <p><b>Thumbnails are what is already decoded.</b> The strip asks for nothing itself: it shows the thumbnail-sized picture
 * the application keeps for a neighbour, and an empty square until there is one. The application reads the neighbours
 * ahead and, when each lands, has {@link #show} called again.
 *
 * <p><b>The squares stay; the pictures glide.</b> A step moves every picture one square along, eased over the same time
 * as the viewer's own transition, so the strip reads as film being pulled through rather than a set of cards that
 * changed. Only the drawing moves ({@code Node.translate}): each square is laid out, and clicked, where it always is,
 * and the strip is clipped so nothing glides over Previous or Next. A slot past either end of the folder stays in its
 * place, empty and inert. How many slots show is decided by the width the bar has ({@link #fit}), not by where in the
 * folder the user is.
 */
public final class Filmstrip {

    /** The most slots: the shown image and three either side. */
    static final int MOST = 7;

    private static final Length INSET = Length.rem(0.3125f);
    private static final Length INNER_CORNER = Length.rem(0.8125f);

    /** One slot along, in root em: a tile and the gap after it. */
    static final float SLOT_EM = 5f;
    /** The furthest a glide starts from: the strip's half-width, past which nothing on it would be seen anyway. */
    private static final float REACH_EM = SLOT_EM * (MOST / 2);

    private final Gui gui;
    private final KronoGui krono;
    private final Textures textures;
    private final Node track;
    private final AtomicInteger glide = new AtomicInteger();
    private volatile float offset;
    private final Function<Path, Picture> thumbnail;
    private final Consumer<Path> pick;
    private final Node[] slots = new Node[MOST];
    private final Node[] plates = new Node[MOST];
    private final InteractionState[] states = new InteractionState[MOST];

    // Guarded by this.
    private final Path[] paths = new Path[MOST];
    private final Picture[] pictures = new Picture[MOST];
    private final int[] generations = new int[MOST];
    private int count = MOST;
    private List<Path> folder = List.of();
    private int at = -1;

    /**
     * @param thumbnail the picture already decoded for an image, or null; called off the GUI thread
     * @param pick      show this image
     */
    public Filmstrip(Gui gui, KronoGui krono, Textures textures, Function<Path, Picture> thumbnail, Consumer<Path> pick) {
        this.gui = gui;
        this.krono = krono;
        this.textures = textures;
        this.thumbnail = thumbnail;
        this.pick = pick;
        for (int i = 0; i < MOST; i++) {
            int slot = i;
            states[i] = InteractionState.NORMAL;
            plates[i] = gui.box().size(Length.FILL, Length.FILL).corner(INNER_CORNER).hitInert(true);
            slots[i] = gui.box().role("button").size(Tile.SIDE, Tile.SIDE).padding(INSET).corner(Tile.CORNER)
                    .scroll(false, false).children(plates[i]);
            gui.onClick(slots[i], () -> press(slot));
            gui.onState(slots[i], s -> {
                states[slot] = s;
                paint(slot);
            });
            paint(i);
        }
        // Clipped, so a square gliding in from beyond the ends is not drawn over Previous or Next.
        this.track = gui.row().width(Length.AUTO).height(Length.AUTO).gap(Tile.GAP).clip(true).scroll(false, false)
                .children(slots);
    }

    /** The strip, for the caller to put between its Previous and Next. */
    public Node node() {
        return track;
    }

    /**
     * Show {@code folder}'s images around the one at {@code at} (or nothing shown, at -1). A step within the same
     * folder glides: every square takes its new picture and starts that many slots along, back where it was, then
     * moves into place.
     */
    public synchronized void show(List<Path> folder, int at) {
        int shift = this.at >= 0 && at >= 0 && folder.equals(this.folder) ? at - this.at : 0;
        this.folder = List.copyOf(folder);
        this.at = at;
        boolean[] emptied = new boolean[MOST];
        int[] expected = new int[MOST];
        for (int i = 0; i < MOST; i++) {
            Path p = pathAt(i);
            Picture pic = p == null ? null : thumbnail.apply(p);
            expected[i] = generations[i];
            if (Objects.equals(p, paths[i]) && pic == pictures[i]) {
                continue;
            }
            paths[i] = p;
            pictures[i] = pic;
            int g = ++generations[i];
            expected[i] = g;
            Node plate = plates[i];
            if (pic == null) {
                emptied[i] = true;
                continue;
            }
            // The old picture stays until the new one is handed over, so a square is never blank for a frame.
            ImageRegion crop = cover(pic);
            int slot = i;
            textures.show(pic, texture -> {
                synchronized (this) {
                    if (generations[slot] == g) {
                        plate.image(texture, crop);
                    }
                }
            });
        }
        // Everything else lands in the frame the pictures do: an emptied square, the moved highlight, the glide.
        textures.then(() -> {
            synchronized (this) {
                for (int i = 0; i < MOST; i++) {
                    if (emptied[i] && generations[i] == expected[i]) {
                        plates[i].image(null);
                    }
                    paint(i);
                }
            }
            if (shift != 0) {
                glide(shift);
            }
        });
    }

    /** Start {@code shift} slots along from wherever the strip is now, and ease back into place. */
    private void glide(int shift) {
        int g = glide.incrementAndGet();
        float from = Math.max(-REACH_EM, Math.min(REACH_EM, offset + SLOT_EM * shift));
        move(from);
        krono.ramp(Dur.ms(ImageView.STEP_MS), Ease.LINEAR, p -> {
            if (glide.get() == g) {
                move(from * (1f - Ease.OUT_CUBIC.at((float) p)));
            }
        }, () -> { });
    }

    private void move(float em) {
        offset = em;
        for (Node n : slots) {
            n.translate(em, 0f);
        }
    }

    /** Show {@code wanted} slots (odd, at most {@link #MOST}), centred on the shown image. Zero hides the strip. */
    public synchronized void fit(int wanted) {
        int n = Math.max(0, Math.min(MOST, wanted));
        if (n > 0 && n % 2 == 0) {
            n--;
        }
        if (n == count) {
            return;
        }
        count = n;
        for (int i = 0; i < MOST; i++) {
            slots[i].visible(Math.abs(i - MOST / 2) <= (n - 1) / 2 && n > 0);
        }
    }

    /** The image slot {@code i} shows, or null past either end. Holding this. */
    private Path pathAt(int i) {
        if (at < 0) {
            return null;
        }
        int j = at + i - MOST / 2;
        return j >= 0 && j < folder.size() ? folder.get(j) : null;
    }

    private void press(int slot) {
        Path p;
        synchronized (this) {
            p = paths[slot];
        }
        if (p != null && slot != MOST / 2) {
            pick.accept(p);
        }
    }

    private void paint(int slot) {
        Path p;
        synchronized (this) {
            p = paths[slot];
        }
        boolean here = slot == MOST / 2 && p != null;
        boolean live = p != null && !here;
        InteractionState s = live ? states[slot] : InteractionState.NORMAL;
        Node n = slots[slot];
        gui.focusable(n, false);
        gui.cursor(n, live ? CursorShape.POINTER : CursorShape.DEFAULT);
        if (p == null) {
            n.background(Tile.glass(gui.theme().color(Role.CHROME))).border(Length.dp(1), gui.theme().color(Role.LINE))
                    .lit(false).elevation(Length.ZERO);
            plates[slot].background(gui.theme().color(Role.NONE));
            return;
        }
        Color fill = gui.theme().color(here ? Role.ACCENT : Role.RAISED, s);
        n.background(here ? fill : Tile.glass(fill))
                .border(Length.dp(1), gui.theme().color(here ? SuiteLook.CHOSEN_INK : SuiteLook.LINE_STRONG))
                .lit(true).elevation(Length.ZERO);
        plates[slot].background(gui.theme().color(Role.WELL));
    }

    /** The middle square of the first frame: a thumbnail fills its square, cropped rather than letterboxed. */
    static ImageRegion cover(Picture p) {
        float sw = p.sheetWidth();
        float sh = p.sheetHeight();
        float side = Math.min(p.frameWidth(), p.frameHeight());
        float x = (p.frameWidth() - side) / 2f;
        float y = (p.frameHeight() - side) / 2f;
        return new ImageRegion(x / sw, y / sh, (x + side) / sw, (y + side) / sh);
    }
}
