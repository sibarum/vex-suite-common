package dev.sibarum.suite.view;

import dev.vexelray.canvas.Color;
import dev.vexelray.codec.image.Glimpses;
import dev.vexelray.codec.image.Motion;
import dev.vexelray.codec.image.Picture;
import dev.vexelray.codec.image.gui.Playback;
import dev.vexelray.codec.image.gui.Textures;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.vulkan.present.SampledImage;
import sibarum.probe.Log;

import java.io.IOException;

/**
 * What a video's seek bar shows above itself while the pointer is on it: the frame at that point of the video, and its
 * time, on a small slab that follows the pointer along the bar. What the bar would seek to, seen before it is clicked.
 *
 * <p><b>The frames are {@link Glimpses}</b>: the video opened a second time, small, so looking along the bar never moves
 * what is playing. They are opened when the video is first in front, and closed when it is not. A picture that plays
 * but has no glimpses (an animated GIF) shows nothing here.
 *
 * <p><b>The slab is never the thing under the pointer</b>: it floats over the stage, hit-inert, so the bar keeps its
 * hover and its drag beneath it. It stays up while the bar is dragged, showing where the drag is, and the last frame
 * stays on it until the next one lands, so it never goes blank while the pointer moves.
 *
 * <p>Every method may be called from any thread.
 */
final class Glance {

    /** The long side the frames are made at, in px: sharp at the size they are shown, on a screen at twice density. */
    static final int SIDE = 400;
    /** The frame's long side on screen, in root em. */
    static final float LONG_REM = 11f;
    /** Above the transport by this much, in root em. */
    private static final float GAP_REM = 0.5f;
    /** The slab's padding around the frame and the time, in root em. */
    private static final float PAD_REM = 0.375f;
    /** The time's line under the frame, with its gap, in root em: for placing the slab before it has been measured. */
    private static final float LINE_REM = 1.25f;
    /** A slider's track is this many root em high, which is how a pixel is turned into an em here. */
    private static final float TRACK_REM = 1.1f;

    private static final Log LOG = Log.of("suite.view");

    private final Gui gui;
    private final Textures textures;
    private final Node stage;
    private final Node deck;
    private final Node track;
    private final Node plate;
    private final Node time;
    private final Node slab;

    // Guarded by this.
    /** The motion whose glimpses are open, or being opened, and they once they are. */
    private Motion motion;
    private Glimpses glimpses;
    private long duration;
    /** Whether the pointer is on the bar, and where across the window it is. */
    private boolean hovered;
    private float pointerX;
    /** The frame last handed over, the one on the plate, and its texture, which this owns. */
    private Picture asked;
    private Picture shown;
    private SampledImage texture;
    /** The frame's width over its height, which the plate is sized to. */
    private float aspect;

    /**
     * @param stage what the slab floats over: it is put last among the stage's children
     * @param deck  the transport's slab, which it sits above
     * @param track the seek bar, along which it follows the pointer
     */
    Glance(Gui gui, Textures textures, Node stage, Node deck, Node track) {
        this.gui = gui;
        this.textures = textures;
        this.stage = stage;
        this.deck = deck;
        this.track = track;
        this.plate = gui.box().corner(Length.rem(0.5f)).background(gui.theme().color(Role.WELL)).hitInert(true);
        this.time = gui.text("").font(SuiteType.MONO).textSize(SuiteType.SMALL)
                .textColor(gui.theme().color(SuiteLook.TEXT)).wordWrap(false).hitInert(true);
        this.slab = gui.column().width(Length.AUTO).height(Length.AUTO).alignItems(AlignItems.CENTER)
                .padding(Length.rem(PAD_REM)).gap(Length.rem(0.25f)).corner(Length.rem(0.875f))
                .scroll(false, false).hitInert(true).visible(false)
                .background(Color.withAlpha(gui.theme().color(SuiteLook.CHROME), 0.85f))
                .border(Length.dp(1), gui.theme().color(Role.LINE))
                .elevation(gui.theme().elevation(Relief.FLOATING))
                .floatAt(Length.ZERO, Length.ZERO).children(plate, time);
        size(16f / 9f);
        stage.append(slab);
    }

    /** The slab, for a landmark. */
    Node node() {
        return slab;
    }

    /**
     * What is in front plays as {@code p}, or nothing plays ({@code null}): glimpses are opened for a new motion, and
     * closed for one no longer in front. Called whenever the playing moves, so it does nothing when nothing changed.
     */
    void playing(Playback p) {
        Motion m = p == null ? null : p.motion();
        Glimpses old;
        synchronized (this) {
            if (p != null) {
                duration = p.duration();
            }
            if (m == motion) {
                return;
            }
            old = glimpses;
            motion = m;
            glimpses = null;
            asked = null;
            shown = null;
        }
        slab.visible(false);
        drop();
        if (old != null) {
            old.close();
        }
        if (m != null) {
            gui.offload().execute(() -> open(m));
        }
    }

    /**
     * Whether the pointer is on the seek bar: the slab shows while it is, and goes as it leaves, taking its frame with
     * it, so coming back elsewhere on the bar never shows where it was before.
     */
    void hover(boolean on) {
        synchronized (this) {
            hovered = on;
            if (!on) {
                asked = null;
                shown = null;
            }
        }
        if (on) {
            update();
        } else {
            slab.visible(false);
            drop();
        }
    }

    /** The pointer is at {@code x} across the window: followed while it is on the bar. */
    void pointer(float x) {
        synchronized (this) {
            pointerX = x;
            if (!hovered) {
                return;
            }
        }
        update();
    }

    private void open(Motion m) {
        Glimpses g;
        try {
            g = m.glimpses(SIDE);
        } catch (IOException | RuntimeException e) {
            LOG.debug("no glimpses of {}: {}", m.stamp().path(), e.toString());
            return;
        }
        if (g == null) {
            return;
        }
        synchronized (this) {
            if (motion != m) {
                g.close();
                return;
            }
            glimpses = g;
        }
        update();
    }

    /** Put the slab over the pointer, say the time there, and ask for the frame there. */
    private void update() {
        Glimpses g;
        float x;
        long d;
        synchronized (this) {
            if (!hovered || glimpses == null) {
                return;
            }
            g = glimpses;
            x = pointerX;
            d = duration;
        }
        NodeLayout t = track.layout();
        NodeLayout s = stage.layout();
        NodeLayout k = deck.layout();
        if (!t.present() || !s.present() || !k.present() || d <= 0) {
            return;
        }
        Rect tr = t.rect();
        Rect st = s.rect();
        // Where the slider would put its thumb for the pointer: the thumb's centre travels the track less one thumb,
        // and the thumb is as wide as the track is high.
        float travel = tr.w() - tr.h();
        float f = travel <= 0f ? 0f : Math.clamp((x - tr.x() - tr.h() / 2f) / travel, 0f, 1f);
        long ms = Math.round(f * d);
        time.text(PictureViewer.time(ms));

        // The slab's size, as laid out once it has been, and as it will be until then.
        float em = tr.h() / TRACK_REM;
        NodeLayout own = slab.layout();
        float w;
        float h;
        synchronized (this) {
            w = own.present() && own.rect().w() > 0f ? own.rect().w() : (plateW() + 2f * PAD_REM) * em;
            h = own.present() && own.rect().h() > 0f ? own.rect().h()
                    : (plateH() + LINE_REM + 2f * PAD_REM) * em;
        }
        float left = Math.clamp(x - w / 2f, st.x(), Math.max(st.x(), st.x() + st.w() - w));
        float top = Math.max(st.y(), k.rect().y() - GAP_REM * em - h);
        slab.floatAt(Length.percent((left - st.x()) / st.w() * 100f), Length.percent((top - st.y()) / st.h() * 100f));
        slab.visible(true);
        g.want(ms, this::landed);
    }

    /** A frame has been made for the latest ask: put it on the plate, unless it is there already. */
    private void landed(Picture p) {
        synchronized (this) {
            asked = p;
            if (p == shown || !hovered) {
                return;
            }
        }
        textures.upload(p, tex -> {
            SampledImage old;
            synchronized (this) {
                if (asked != p || !hovered) {
                    textures.release(tex);
                    return;
                }
                old = texture;
                texture = tex;
                shown = p;
                size((float) p.frameWidth() / p.frameHeight());
                plate.image(tex);
            }
            if (old != null) {
                textures.release(old);
            }
        });
    }

    /** Size the plate to a frame {@code aspect} wide for each unit high, its long side {@link #LONG_REM}. */
    private synchronized void size(float aspect) {
        if (aspect == this.aspect || !(aspect > 0f)) {
            return;
        }
        this.aspect = aspect;
        plate.size(Length.rem(plateW()), Length.rem(plateH()));
    }

    private float plateW() {
        return aspect >= 1f ? LONG_REM : LONG_REM * aspect;
    }

    private float plateH() {
        return aspect >= 1f ? LONG_REM / aspect : LONG_REM;
    }

    /** Take the frame off the plate and give its texture back. */
    private void drop() {
        SampledImage old;
        synchronized (this) {
            old = texture;
            texture = null;
            plate.image(null);
        }
        if (old != null) {
            textures.release(old);
        }
    }
}
