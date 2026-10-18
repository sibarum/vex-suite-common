package dev.sibarum.suite.view;

import dev.vexelray.codec.image.gui.ImageView.Fitted;
import dev.vexelray.codec.image.gui.ImageView.Pose;

/**
 * How the picture is looked at: zoomed, moved and turned by the user, and held where it can be seen. It keeps no
 * tree and no clock; the viewer feeds it the gestures and the frames, and draws its {@link #pose}.
 *
 * <p>Everything is in layout px from the stage's centre: the picture's centre is at {@code (x, y)}, and its size is
 * {@code zoom} times the size it fits at ({@link Fitted}), upright.
 *
 * <p><b>Held where it can be seen.</b> Along each axis the picture's bounding box, turned, is measured against the
 * stage. One no larger than the stage is held inside it; a larger one may be moved until only {@link #KEEP} of the
 * stage still shows it, and never further. The two meet where the picture is the stage's size, so zooming across
 * that size never jumps. A drag may pull past the limit, against a resistance that grows the further it goes; let
 * go, or zoom out so the limit closes in, and a spring brings the picture back, carrying the momentum it had.
 * Inside the limit a fling glides and slows.
 *
 * <p><b>Turned any way.</b> A quarter turn ({@link #quarter}) goes to the next multiple of 90° and, if the picture
 * was fitted, fits it again the new way round; a twist ({@link #twist}) or a fine turn ({@link #turn}) leaves it at
 * any angle. A mirror ({@link #mirror}) swaps the sides as they are seen, whichever way it is turned, and
 * {@link #upright} undoes the turn and the mirror both.
 *
 * <p>Not thread-safe: the viewer calls it under its own lock.
 */
final class Lens {

    /** The least of the stage, along each axis, that a picture larger than the stage must still cover. */
    static final float KEEP = 0.25f;
    /** How far one wheel notch zooms. */
    static final float NOTCH = 1.25f;
    /** How far a fine turn goes per wheel notch, in degrees. */
    static final float NOTCH_DEGREES = 5f;
    /** The smallest and largest zoom, over the size the picture fits at. */
    static final float MIN_ZOOM = 0.1f;
    static final float MAX_ZOOM = 50f;

    /** The spring that brings a picture back inside its limit: stiffness and damping, per second. */
    private static final float SPRING = 260f;
    private static final float DAMPING = 25f;
    /** How fast a fling inside the limit slows: its velocity's time constant, in seconds. */
    private static final float GLIDE = 0.28f;
    /** How fast zoom and a quarter turn close on where they are going: time constants, in seconds. */
    private static final float ZOOM_EASE = 0.07f;
    private static final float TURN_EASE = 0.09f;
    /** How stiffly a drag past the limit resists: the iOS rubber band's coefficient. */
    private static final float BAND = 0.55f;
    /** The fastest a fling may leave the hand, in px a second. */
    private static final float FLING_MAX = 8000f;
    /** How long a drag's velocity is averaged over, in seconds. */
    private static final float VELOCITY_WINDOW = 0.05f;
    /** Slower than this, in px a second, a picture in place is still. */
    private static final float STILL = 6f;
    /** The longest physics step: a stalled frame is taken in pieces, so the spring never blows up. */
    private static final float SUBSTEP = 1f / 120f;
    /** Within this of a quarter turn, in degrees, a pinch that lets go settles onto the quarter. */
    static final float SNAP_DEGREES = 8f;
    /** Within this fraction of the size it fits at, a pinch that lets go settles onto fitted. */
    static final float SNAP_FIT = 0.06f;
    /** How stiffly a pinch past the zoom limits resists: the share of each further doubling asked for it gives. */
    private static final float ZOOM_BAND = 0.25f;
    /**
     * How closely the picture follows two fingers: the time constant, in seconds, of its chase after where they
     * want it. Long enough to smooth a touchpad's jitter away, short enough that it still feels held.
     */
    static final float FOLLOW = 0.05f;

    // The measures, from the view.
    private float stageW;
    private float stageH;
    private float baseW;
    private float baseH;

    // Where it is, and where it is going.
    private float zoom = 1f;
    private float zoomGoal = 1f;
    private float anchorX;
    private float anchorY;
    private float x;
    private float y;
    private float vx;
    private float vy;
    private float degrees;
    private float degreesGoal;
    /** Mirrored in the picture's own frame, before the turn ({@link Pose}). */
    private boolean mirrorX;
    private boolean mirrorY;
    /** Fitted: zoom follows the stage and the turn, until the user zooms. */
    private boolean fitting = true;
    /** Going back to the centre, not only inside the limit: after {@link #fit}. */
    private boolean homing;

    // A drag in hand.
    private boolean dragging;
    /** Where the hand has the picture, before the rubber band: what the band is applied to. */
    private float handX;
    private float handY;
    private long lastNanos;
    // A twist in hand: the angle the pointer started at, about the picture's centre, and the turn then.
    private boolean twisting;
    private float twistFrom;
    private float twistStart;
    /** Where the pointer is in the twist in hand, in degrees about the picture's centre. */
    private float twistAt;
    // Two fingers in hand: the zoom they ask for, before the band at the limits, and whether they zoomed or turned.
    // Where they want the picture is the hand (handX, handY) and the goals (zoomGoal, degreesGoal).
    private boolean touching;
    private float touchZoom;
    private boolean touchZoomed;
    private boolean touchTurned;

    /** The pose to draw. */
    Pose pose() {
        return new Pose(zoom, x, y, degrees, mirrorX, mirrorY);
    }

    /** Whether there is a picture and a stage to measure it on. */
    boolean measured() {
        return stageW > 0f && baseW > 0f;
    }

    /** Back to rest: fitted, centred, upright, not mirrored, still. For a new picture. */
    void reset() {
        zoom = zoomGoal = 1f;
        x = y = vx = vy = 0f;
        degrees = degreesGoal = 0f;
        mirrorX = mirrorY = false;
        fitting = true;
        homing = false;
        dragging = twisting = touching = false;
    }

    /**
     * New measures: a new picture, or the stage changed size. A fitted picture is fitted again; one the user zoomed
     * keeps its size on the screen.
     */
    void measure(Fitted f) {
        if (f == null) {
            stageW = stageH = baseW = baseH = 0f;
            return;
        }
        if (fitting) {
            zoom = zoomGoal = fitZoom(f.pictureW(), f.pictureH(), f.stageW(), f.stageH(), degreesGoal);
        } else if (baseW > 0f && f.pictureW() > 0f) {
            float k = baseW / f.pictureW();
            zoom *= k;
            zoomGoal *= k;
        }
        stageW = f.stageW();
        stageH = f.stageH();
        baseW = f.pictureW();
        baseH = f.pictureH();
    }

    // ---------------------------------------------------------------- gestures

    /** Zoom by {@code notches} of the wheel (up is in) about {@code (px, py)}, which stays where it is. */
    void wheel(float notches, float px, float py) {
        if (!measured() || notches == 0f) {
            return;
        }
        fitting = false;
        homing = false;
        zoomGoal = clamp(zoomGoal * (float) Math.pow(NOTCH, notches), MIN_ZOOM, MAX_ZOOM);
        anchorX = px;
        anchorY = py;
    }

    /** Fitted again, the way it is turned, and back to the centre: a double click. */
    void fit() {
        fitting = true;
        homing = true;
        zoomGoal = fitZoom(baseW, baseH, stageW, stageH, degreesGoal);
        anchorX = x;
        anchorY = y;
    }

    /** A quarter turn, clockwise for 1 and back for -1, from the nearest quarter. A fitted picture is refitted. */
    void quarter(int direction) {
        degreesGoal = Math.round(degreesGoal / 90f) * 90f + 90f * Integer.signum(direction);
        if (fitting) {
            zoomGoal = fitZoom(baseW, baseH, stageW, stageH, degreesGoal);
            anchorX = x;
            anchorY = y;
            homing = true;
        }
    }

    /**
     * Mirror the picture as it is seen on the stage: its left and right swapped for {@code across}, its top and bottom
     * otherwise, whichever way it is turned. Mirroring a turned picture on the screen is mirroring it in its own frame
     * and turning it back the other way ({@code H·R(θ) = R(-θ)·H}), so the turn changes sign, at once rather than
     * easing, which would spin it through every angle between. Its size and place stay as they are.
     */
    void mirror(boolean across) {
        if (across) {
            mirrorX = !mirrorX;
        } else {
            mirrorY = !mirrorY;
        }
        degrees = -degrees;
        degreesGoal = -degreesGoal;
        if (twisting) {
            // The twist in hand goes on from here, the pointer where it is.
            twistStart = degreesGoal;
            twistFrom = twistAt;
        }
    }

    /** Upright, not mirrored, and fitted again in the centre: as a new picture is shown. */
    void upright() {
        degreesGoal = Math.round(degreesGoal / 360f) * 360f;
        mirrorX = mirrorY = false;
        fit();
    }

    /** Whether the picture is mirrored across (left and right) and down (top and bottom), in its own frame. */
    boolean mirroredX() {
        return mirrorX;
    }

    boolean mirroredY() {
        return mirrorY;
    }

    /** A fine turn by {@code notches} of the wheel, clockwise for positive. */
    void turn(float notches) {
        degreesGoal += notches * NOTCH_DEGREES;
    }

    /** The hand takes the picture, at {@code nanos}. */
    void grab(long nanos) {
        dragging = true;
        homing = false;
        // Taken where it is, which may be past the limit still, on its way back: the hand is where it would
        // have had to be to hold it there.
        handX = hand(x, limit(true), stageW);
        handY = hand(y, limit(false), stageH);
        vx = vy = 0f;
        lastNanos = nanos;
    }

    /** The hand moved by {@code (dx, dy)} at {@code nanos}: the picture follows, resisting past its limit. */
    void drag(float dx, float dy, long nanos) {
        if (!dragging) {
            grab(nanos);
        }
        handX += dx;
        handY += dy;
        float nx = band(handX, limit(true), stageW);
        float ny = band(handY, limit(false), stageH);
        float dt = Math.max(1e-4f, (nanos - lastNanos) / 1e9f);
        lastNanos = nanos;
        // Averaged over the last few hundredths of a second, so one late event does not decide the fling.
        float w = Math.min(1f, dt / VELOCITY_WINDOW);
        vx += ((nx - x) / dt - vx) * w;
        vy += ((ny - y) / dt - vy) * w;
        x = nx;
        y = ny;
    }

    /** The hand lets go at {@code nanos}: the picture keeps its momentum, unless the hand had stopped. */
    void release(long nanos) {
        if (!dragging) {
            return;
        }
        dragging = false;
        if ((nanos - lastNanos) / 1e9f > 2f * VELOCITY_WINDOW) {
            vx = vy = 0f;
        }
        cap();
    }

    /** The fling no faster than {@link #FLING_MAX}. */
    private void cap() {
        float speed = (float) Math.hypot(vx, vy);
        if (speed > FLING_MAX) {
            vx *= FLING_MAX / speed;
            vy *= FLING_MAX / speed;
        }
    }

    /** A twist starts with the pointer at {@code (px, py)}. */
    void twistFrom(float px, float py) {
        twisting = true;
        twistFrom = twistAt = angle(px, py);
        twistStart = degreesGoal;
    }

    /** The pointer is at {@code (px, py)}: the picture turns with it about its own centre. */
    void twist(float px, float py) {
        if (!twisting) {
            twistFrom(px, py);
        }
        twistAt = angle(px, py);
        degrees = degreesGoal = twistStart + (twistAt - twistFrom);
    }

    void twistEnd() {
        twisting = false;
    }

    /**
     * Two fingers take the picture: it is theirs, where it is, and stops whatever it was doing — gliding, springing
     * back, easing to a zoom. From here it follows them, smoothed ({@link #FOLLOW}).
     */
    void touchStart() {
        touching = true;
        touchZoomed = touchTurned = false;
        homing = false;
        dragging = false;
        vx = vy = 0f;
        zoomGoal = zoom;
        degreesGoal = degrees;
        touchZoom = zoom;
        handX = hand(x, limit(true), stageW);
        handY = hand(y, limit(false), stageH);
    }

    /**
     * The fingers moved {@code (dx, dy)} px, spread by {@code scale} and turned {@code turn} degrees clockwise, about
     * {@code (px, py)}: where they want the picture moves with them — scaled and turned about that point, which
     * stays under them — and the picture follows it, a few hundredths of a second behind, so the pad's jitter is
     * smoothed away and a turn or zoom that joins late is caught up rather than jumped to. Past the zoom limits it
     * gives against the fingers instead of stopping dead, and past the stage's, as a drag does.
     */
    void touch(float dx, float dy, float scale, float turn, float px, float py) {
        if (!measured()) {
            return;
        }
        if (!touching) {
            touchStart();
        }
        if (scale != 1f || turn != 0f) {
            fitting = false;
            touchZoomed |= scale != 1f;
            touchTurned |= turn != 0f;
        }
        touchZoom *= scale;
        float z = bandZoom(touchZoom);
        float k = z / zoomGoal;
        double r = Math.toRadians(turn);
        float cos = (float) Math.cos(r);
        float sin = (float) Math.sin(r);
        float ox = (handX - px) * k;
        float oy = (handY - py) * k;
        handX = px + ox * cos - oy * sin + dx;
        handY = py + ox * sin + oy * cos + dy;
        zoomGoal = z;
        degreesGoal += turn;
    }

    /**
     * The fingers lift, leaving at {@code (fx, fy)} px a second, the pointer at {@code (px, py)}. The picture keeps
     * that speed, caught by the limit's spring as any fling is; comes back inside the zoom limits if pulled past
     * them; settles onto the nearest quarter turn if it was turned to within {@link #SNAP_DEGREES} of one; and is
     * fitted if it was zoomed to within {@link #SNAP_FIT} of the size it fits at — each eased from where it was.
     * A picture left at an odd angle, or zoomed well off its fit, stays as it was put.
     */
    void touchEnd(float fx, float fy, float px, float py) {
        if (!touching) {
            return;
        }
        touching = false;
        vx = fx;
        vy = fy;
        cap();
        anchorX = px;
        anchorY = py;
        if (touchTurned) {
            float quarter = Math.round(degreesGoal / 90f) * 90f;
            if (Math.abs(degreesGoal - quarter) <= SNAP_DEGREES) {
                degreesGoal = quarter;
            }
        }
        float fit = fitZoom(baseW, baseH, stageW, stageH, degreesGoal);
        if (touchZoomed && degreesGoal % 90f == 0f && Math.abs(zoomGoal / fit - 1f) <= SNAP_FIT) {
            fit();
            return;
        }
        zoomGoal = clamp(zoomGoal, MIN_ZOOM, MAX_ZOOM);
    }

    /** Whether two fingers have the picture. */
    boolean touching() {
        return touching;
    }

    /**
     * The zoom at which a picture {@code sourceW} pixels wide shows each of its pixels on one of the screen's: the
     * size it fits at is in the same px. 1 (fitted) when there is nothing to measure.
     */
    float actual(int sourceW) {
        return baseW > 0f && sourceW > 0 ? sourceW / baseW : 1f;
    }

    /** The zoom a pinch asking for {@code z} gets: {@code z} inside the limits, past them a little of each doubling. */
    private static float bandZoom(float z) {
        if (z > MAX_ZOOM) {
            return MAX_ZOOM * (float) Math.pow(z / MAX_ZOOM, ZOOM_BAND);
        }
        if (z < MIN_ZOOM) {
            return MIN_ZOOM * (float) Math.pow(z / MIN_ZOOM, ZOOM_BAND);
        }
        return z;
    }

    /**
     * A double tap: from fitted, the picture's own pixels ({@code actual}, the zoom at which one of its pixels is one
     * on the screen) about {@code (px, py)}, or twice its fitted size when it already fits at that; from anything
     * else, fitted again.
     */
    void fitOrActual(float actual, float px, float py) {
        if (!measured()) {
            return;
        }
        float fit = fitZoom(baseW, baseH, stageW, stageH, degreesGoal);
        boolean fitted = fitting || Math.abs(zoomGoal / fit - 1f) < 0.01f;
        if (!fitted) {
            fit();
            return;
        }
        fitting = false;
        homing = false;
        zoomGoal = clamp(actual > fit * 1.01f ? actual : fit * 2f, MIN_ZOOM, MAX_ZOOM);
        anchorX = px;
        anchorY = py;
    }

    // ---------------------------------------------------------------- motion

    /** Advance {@code dt} seconds. Whether anything is still moving. */
    boolean step(float dt) {
        if (!measured()) {
            return false;
        }
        boolean moving = false;
        for (float left = Math.max(0f, dt); left > 0f; left -= SUBSTEP) {
            moving = advance(Math.min(SUBSTEP, left));
        }
        return moving;
    }

    /** Whether anything is moving now, or out of place: a step would change something. */
    boolean moving() {
        if (!measured()) {
            return false;
        }
        if (touching) {
            // Following the fingers: moving until it is where they want it.
            return zoom != zoomGoal || degrees != degreesGoal
                    || x != band(handX, limit(true), stageW) || y != band(handY, limit(false), stageH);
        }
        // In the hand, the picture moves only as the hand does: its velocity is kept for the release.
        return zoom != zoomGoal || degrees != degreesGoal
                || (!dragging
                        && (vx != 0f || vy != 0f || x != rest(x, limit(true)) || y != rest(y, limit(false))));
    }

    /** Whether it has come to rest: nothing moving and nothing in hand. What a sharper copy waits for. */
    boolean still() {
        return measured() && !dragging && !twisting && !touching && !moving();
    }

    /**
     * What of the picture the stage shows, with {@code margin} of the stage's size more on every side, in fractions
     * of the picture's width and height, and the size that part is drawn at on the screen, in px:
     * {@code {u0, v0, u1, v1, w, h, zoom}}, the last the zoom it is for. The stage's corners are taken back through
     * the pose into the picture's own frame, so a turned picture's part is the box around the turned stage. Null when
     * none of it is on the stage.
     *
     * <p><b>On whole pixels, upright.</b> {@code (originX, originY)} is where the stage's top-left corner is on the
     * screen. When the picture is upright the part's edges are moved onto whole screen pixels, inward where they are
     * the picture's own edges, and its size made whole pixels, so it is drawn texel for pixel. Off by a fraction of
     * a pixel, every pixel of it would be a blend of two, and the sharp copy would be blurred on its way to the
     * screen. Turned, no grid lines up, and it is left as it is.
     */
    float[] visible(float margin, float originX, float originY) {
        float[] v = onPlate(margin);
        if (v == null || degrees % 360f != 0f) {
            return unmirrored(v);
        }
        float[] across = snap(v[0], v[2], baseW, stageW, x, originX);
        float[] down = snap(v[1], v[3], baseH, stageH, y, originY);
        if (across == null || down == null) {
            return unmirrored(v);
        }
        return unmirrored(new float[] {across[0], down[0], across[1], down[1], across[2], down[2], zoom});
    }

    /**
     * {@code v}, a part of the picture as it is drawn, as a part of the picture as it is: the same part read the
     * other way along each axis it is mirrored on. What the file's own pixels are cut by.
     */
    private float[] unmirrored(float[] v) {
        if (v == null || (!mirrorX && !mirrorY)) {
            return v;
        }
        float[] u = v.clone();
        if (mirrorX) {
            u[0] = 1f - v[2];
            u[2] = 1f - v[0];
        }
        if (mirrorY) {
            u[1] = 1f - v[3];
            u[3] = 1f - v[1];
        }
        return u;
    }

    /**
     * One axis of {@link #visible(float, float, float)}: the part from {@code a} to {@code b} of a picture
     * {@code base} long, on a stage {@code stage} long whose start is at {@code origin} on the screen, with the
     * picture's centre {@code centre} from the stage's: {@code {a, b, px}} on whole pixels, or null if none is left.
     */
    private float[] snap(float a, float b, float base, float stage, float centre, float origin) {
        float k = zoom * base;   // screen px across the whole picture
        float start = origin + stage / 2f + centre + k * (a - 0.5f);
        float end = origin + stage / 2f + centre + k * (b - 0.5f);
        // Outward where the part is cut from the picture's middle, inward at its own edges, which it cannot pass.
        float s = a <= 0f ? (float) Math.ceil(start - 1e-3f) : (float) Math.floor(start + 1e-3f);
        float e = b >= 1f ? (float) Math.floor(end + 1e-3f) : (float) Math.ceil(end - 1e-3f);
        if (!(e > s)) {
            return null;
        }
        float na = a + (s - start) / k;
        float nb = b + (e - end) / k;
        if (na < 0f || nb > 1f) {
            // Cut from the middle but within a pixel of an edge: inward there instead.
            s = na < 0f ? s + 1f : s;
            e = nb > 1f ? e - 1f : e;
            if (!(e > s)) {
                return null;
            }
            na = a + (s - start) / k;
            nb = b + (e - end) / k;
        }
        return new float[] {na, nb, e - s};
    }

    /** {@link #visible(float, float, float)} as it falls, not moved onto whole pixels. */
    float[] visible(float margin) {
        return unmirrored(onPlate(margin));
    }

    /** {@link #visible(float)} in fractions of the picture as it is drawn, mirrored as it is. */
    private float[] onPlate(float margin) {
        if (!measured()) {
            return null;
        }
        float mx = stageW * (0.5f + margin);
        float my = stageH * (0.5f + margin);
        double r = Math.toRadians(-degrees);
        float cos = (float) Math.cos(r);
        float sin = (float) Math.sin(r);
        float u0 = Float.POSITIVE_INFINITY;
        float v0 = Float.POSITIVE_INFINITY;
        float u1 = Float.NEGATIVE_INFINITY;
        float v1 = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            float px = (corner & 1) == 0 ? -mx : mx;
            float py = (corner & 2) == 0 ? -my : my;
            // Into the picture's frame: from its centre, turned back, at its fitted size.
            float qx = ((px - x) * cos - (py - y) * sin) / zoom;
            float qy = ((px - x) * sin + (py - y) * cos) / zoom;
            float u = qx / baseW + 0.5f;
            float v = qy / baseH + 0.5f;
            u0 = Math.min(u0, u);
            v0 = Math.min(v0, v);
            u1 = Math.max(u1, u);
            v1 = Math.max(v1, v);
        }
        u0 = clamp(u0, 0f, 1f);
        v0 = clamp(v0, 0f, 1f);
        u1 = clamp(u1, 0f, 1f);
        v1 = clamp(v1, 0f, 1f);
        if (!(u1 > u0) || !(v1 > v0)) {
            return null;
        }
        return new float[] {u0, v0, u1, v1, (u1 - u0) * baseW * zoom, (v1 - v0) * baseH * zoom, zoom};
    }

    private boolean advance(float dt) {
        if (touching) {
            follow(dt);
            return moving();
        }
        // Zoom eases to its goal about the anchor, which stays put on the stage.
        if (zoom != zoomGoal) {
            float z = zoom + (zoomGoal - zoom) * (1f - (float) Math.exp(-dt / ZOOM_EASE));
            if (Math.abs(zoomGoal - z) < 1e-4f * zoomGoal) {
                z = zoomGoal;
            }
            float k = z / zoom;
            x = anchorX + (x - anchorX) * k;
            y = anchorY + (y - anchorY) * k;
            if (dragging) {
                handX = anchorX + (handX - anchorX) * k;
                handY = anchorY + (handY - anchorY) * k;
            }
            zoom = z;
        }
        if (degrees != degreesGoal && !twisting) {
            float d = degrees + (degreesGoal - degrees) * (1f - (float) Math.exp(-dt / TURN_EASE));
            degrees = Math.abs(degreesGoal - d) < 0.02f ? degreesGoal : d;
        }
        if (!dragging) {
            float[] ax = axis(x, vx, limit(true), dt);
            float[] ay = axis(y, vy, limit(false), dt);
            x = ax[0];
            vx = ax[1];
            y = ay[0];
            vy = ay[1];
            if (homing && x == 0f && y == 0f && zoom == zoomGoal) {
                homing = false;
            }
        }
        return moving();
    }

    /**
     * One step of following two fingers: the zoom (by its ratio, so a doubling takes as long at any size), the turn
     * and the place each close the same share of the way to where the fingers want them, and are there once within
     * a hair of it. Closing together keeps the point under the fingers about where it was.
     */
    private void follow(float dt) {
        float a = 1f - (float) Math.exp(-dt / FOLLOW);
        float z = zoom * (float) Math.pow(zoomGoal / zoom, a);
        zoom = Math.abs(zoomGoal - z) < 1e-4f * zoomGoal ? zoomGoal : z;
        float d = degrees + (degreesGoal - degrees) * a;
        degrees = Math.abs(degreesGoal - d) < 0.02f ? degreesGoal : d;
        float bx = band(handX, limit(true), stageW);
        float by = band(handY, limit(false), stageH);
        float nx = x + (bx - x) * a;
        float ny = y + (by - y) * a;
        x = Math.abs(bx - nx) < 0.05f ? bx : nx;
        y = Math.abs(by - ny) < 0.05f ? by : ny;
    }

    /** One axis, one step: the spring outside the limit (or towards the centre, homing), the glide inside. */
    private float[] axis(float at, float v, float limit, float dt) {
        float off = at - rest(at, limit);
        if (off != 0f) {
            v += (-SPRING * off - DAMPING * v) * dt;
        } else {
            v *= (float) Math.exp(-dt / GLIDE);
        }
        at += v * dt;
        // Still once it is slow and as good as in place: within a quarter of a px of where it rests.
        float rest = rest(at, limit);
        if (Math.abs(v) < STILL && Math.abs(at - rest) < 0.25f) {
            return new float[] {rest, 0f};
        }
        return new float[] {at, v};
    }

    /** Where {@code at} comes to rest: inside the limit, or the centre while homing. */
    private float rest(float at, float limit) {
        return homing ? 0f : clamp(at, -limit, limit);
    }

    /** How far the picture's centre may be from the stage's along one axis, at the current zoom and turn. */
    float limit(boolean across) {
        double r = Math.toRadians(degrees);
        float cos = (float) Math.abs(Math.cos(r));
        float sin = (float) Math.abs(Math.sin(r));
        float p = zoom * (across ? baseW * cos + baseH * sin : baseW * sin + baseH * cos);
        float v = across ? stageW : stageH;
        return limit(p, v);
    }

    /**
     * How far a picture {@code p} long may move from the centre of a stage {@code v} long: held inside it while it
     * is no longer, and while it is longer, until only {@link #KEEP} of the stage shows it. The slack beyond the
     * picture's own overhang grows with that overhang, so the two cases meet where {@code p == v}.
     */
    static float limit(float p, float v) {
        if (p <= v) {
            return (v - p) / 2f;
        }
        return (p - v) / 2f + Math.min(p - v, (1f - KEEP) * v);
    }

    /**
     * Where a hand at {@code hand} puts the picture: with it inside the limit, and past it, short of the hand by
     * more the further it goes, never more than half the stage ({@code length}) past.
     */
    static float band(float hand, float limit, float length) {
        float edge = clamp(hand, -limit, limit);
        float over = hand - edge;
        if (over == 0f) {
            return hand;
        }
        float r = Math.max(1f, length / 2f);
        float pulled = (1f - 1f / (Math.abs(over) * BAND / r + 1f)) * r;
        return edge + Math.signum(over) * pulled;
    }

    /** The inverse of {@link #band}: where the hand must be to hold the picture at {@code at}. */
    static float hand(float at, float limit, float length) {
        float edge = clamp(at, -limit, limit);
        float over = at - edge;
        if (over == 0f) {
            return at;
        }
        float r = Math.max(1f, length / 2f);
        float a = Math.min(Math.abs(over), r * 0.999f);
        return edge + Math.signum(over) * (1f / (1f - a / r) - 1f) * r / BAND;
    }

    /** The zoom at which a picture fitting at {@code w x h} upright fits turned {@code deg}, never larger than 1. */
    static float fitZoom(float w, float h, float stageW, float stageH, float deg) {
        if (w <= 0f || h <= 0f || stageW <= 0f || stageH <= 0f) {
            return 1f;
        }
        double r = Math.toRadians(deg);
        float cos = (float) Math.abs(Math.cos(r));
        float sin = (float) Math.abs(Math.sin(r));
        float bw = w * cos + h * sin;
        float bh = w * sin + h * cos;
        return Math.min(1f, Math.min(stageW / bw, stageH / bh));
    }

    /** The angle of {@code (px, py)} about the picture's centre, in degrees clockwise. */
    private float angle(float px, float py) {
        return (float) Math.toDegrees(Math.atan2(py - y, px - x));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
