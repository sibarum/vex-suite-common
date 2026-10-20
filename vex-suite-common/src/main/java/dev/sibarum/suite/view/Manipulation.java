package dev.sibarum.suite.view;

import sibarum.tactroller.api.TouchpadContact;

import java.util.ArrayList;
import java.util.List;

/**
 * Two fingers on the touchpad as one manipulation of the picture: always a pan, joined by a zoom and a turn only
 * once the fingers plainly mean them. Read from the raw contacts, not tactroller's pans and pinches, because those
 * are decided once — a pan, or a pinch — and a pan whose fingers drift a couple of millimetres apart is a pinch from
 * then on, every wobble of it zooming and turning the picture.
 *
 * <ul>
 *   <li><b>A pan is a pan</b> from the first {@link #START_MM}: the fingers' centre carries the picture.</li>
 *   <li><b>The zoom joins</b> once the fingers' spread has changed by at least half as far as their centre has
 *       travelled, and at least {@link #START_MM}: tactroller's own test for a pinch, applied to the whole gesture
 *       rather than to its first few millimetres, so a long pan needs a deliberate spread before it zooms.</li>
 *   <li><b>The turn is free</b> once the fingers have twisted {@link #TURN_GATE}, 30°. Short of that the twist is
 *       still reported, so the view can lean the picture against the lock and let it spring back ({@link Lens}),
 *       but not as {@code free}: nothing less is a turn on purpose — unless the picture is already turned off a
 *       quarter, when there is nothing for a wobble to disturb and the turn is free from the start
 *       ({@code turnFree}).</li>
 * </ul>
 *
 * <p>The zoom, joining, catches up: the first step after it joins carries all the zoom the fingers had made until
 * then, so the picture is as large as the hand has made it. The turn has been reported all along; once it is free
 * the view puts the picture as far round as the hand is, not lagging 30° behind it. The view smooths both in.
 *
 * <p>When the fingers lift, a pure pan leaves with the speed they were going; one that zoomed or turned does not,
 * since the drift of fingers lifting from a pinch is not a throw.
 *
 * <p>Not thread-safe: fed one ordered stream of contacts.
 */
final class Manipulation {

    /** How far the fingers move, in millimetres on the pad, before they are a manipulation at all. */
    static final double START_MM = 2.0;
    /** How far the fingers twist, in radians, before the picture turns with them: a twelfth of a turn. */
    static final double TURN_GATE = Math.PI / 6;
    /** The window the fingers' leaving speed is measured over. */
    private static final long VELOCITY_NANOS = 100_000_000L;

    /** What the fingers did. */
    sealed interface Out {
    }

    /** Two fingers have moved enough to be manipulating the picture. */
    record Began() implements Out {
    }

    /**
     * They moved: the centre by {@code (dx, dy)} millimetres, the spread by {@code scale} and the twist by
     * {@code turn} radians clockwise, since the last step. {@code scale} is 1 until the zoom joins; {@code turn} is
     * the fingers' twist always, and {@code free} whether it has passed the lock and is a turn on purpose.
     */
    record Moved(double dx, double dy, double scale, double turn, boolean free) implements Out {
    }

    /**
     * They lifted, or a finger was added or taken away: leaving at {@code (vx, vy)} millimetres a second, which is
     * zero unless this was a pure pan and they were still moving.
     */
    record Ended(double vx, double vy) implements Out {
    }

    private enum Phase { IDLE, PENDING, ACTIVE }

    private Phase phase = Phase.IDLE;
    /** Whether a new pair may begin: only once at most one finger is left, so a three-finger swipe lifting is not one. */
    private boolean armed = true;
    private int idA;
    private int idB;
    private double[] base;
    private double[] prev;
    private double travelX;
    private double travelY;
    private double scaled = 1;
    private double turned;
    private boolean zooming;
    private boolean turning;

    private final long[] sampleNanos = new long[32];
    private final double[] sampleX = new double[32];
    private final double[] sampleY = new double[32];
    private int samples;
    private int next;

    /** Whether two fingers are manipulating the picture now. */
    boolean active() {
        return phase == Phase.ACTIVE;
    }

    /**
     * The contacts on the pad now, at {@code nanos}: what the fingers did since the last. {@code turnFree} says the
     * picture is already turned off a quarter, so a manipulation beginning now turns with the fingers at once.
     */
    List<Out> feed(List<TouchpadContact> contacts, long nanos, boolean turnFree) {
        List<Out> out = new ArrayList<>(2);
        if (contacts.size() <= 1) {
            armed = true;
        } else if (contacts.size() > 2) {
            armed = false;   // more than a pair: what is left of them as they lift is not one
        }
        boolean pair = contacts.size() == 2;
        boolean same = pair && phase != Phase.IDLE && ids(contacts, idA, idB);
        if (!same) {
            if (phase == Phase.ACTIVE) {
                out.add(end(nanos));
            }
            phase = Phase.IDLE;
            if (pair && armed) {
                armed = false;
                phase = Phase.PENDING;
                idA = contacts.get(0).id();
                idB = contacts.get(1).id();
                base = prev = frame(contacts);
                travelX = travelY = turned = 0;
                scaled = 1;
                zooming = turning = false;
                samples = 0;
            }
            return out;
        }
        double[] now = frame(contacts);
        if (phase == Phase.PENDING) {
            double travel = Math.hypot(now[0] - base[0], now[1] - base[1]);
            double spread = Math.abs(now[2] - base[2]);
            double arc = Math.abs(angle(now[3] - base[3])) * now[2];
            if (Math.max(travel, Math.max(spread, arc)) < START_MM) {
                return out;
            }
            phase = Phase.ACTIVE;
            turning = turnFree;
            out.add(new Began());
            sample(base[0], base[1], nanos);
        }
        out.add(step(now));
        sample(now[0], now[1], nanos);
        prev = now;
        return out;
    }

    private Moved step(double[] now) {
        double dx = now[0] - prev[0];
        double dy = now[1] - prev[1];
        double ds = prev[2] > 0.5 && now[2] > 0.5 ? now[2] / prev[2] : 1;
        double dt = angle(now[3] - prev[3]);
        travelX += dx;
        travelY += dy;
        scaled *= ds;
        turned += dt;
        double scale = 1;
        if (zooming) {
            scale = ds;
        } else if (Math.abs(now[2] - base[2]) >= Math.max(START_MM, Math.hypot(travelX, travelY) / 2)) {
            zooming = true;
            scale = scaled;   // all of it so far, at once: the picture catches up with the fingers
        }
        turning |= Math.abs(turned) >= TURN_GATE;
        return new Moved(dx, dy, scale, dt, turning);
    }

    private Ended end(long nanos) {
        if (zooming || turning) {
            return new Ended(0, 0);
        }
        int newest = -1;
        int oldest = -1;
        for (int i = 0; i < samples; i++) {
            int k = Math.floorMod(next - 1 - i, sampleNanos.length);
            if (nanos - sampleNanos[k] > VELOCITY_NANOS) {
                break;
            }
            if (newest < 0) {
                newest = k;
            }
            oldest = k;
        }
        if (newest < 0 || newest == oldest) {
            return new Ended(0, 0);   // they had come to rest before they lifted
        }
        double s = (sampleNanos[newest] - sampleNanos[oldest]) / 1e9;
        return s <= 0 ? new Ended(0, 0)
                : new Ended((sampleX[newest] - sampleX[oldest]) / s, (sampleY[newest] - sampleY[oldest]) / s);
    }

    private void sample(double x, double y, long nanos) {
        sampleNanos[next] = nanos;
        sampleX[next] = x;
        sampleY[next] = y;
        next = (next + 1) % sampleNanos.length;
        samples = Math.min(samples + 1, sampleNanos.length);
    }

    private static boolean ids(List<TouchpadContact> c, int a, int b) {
        int x = c.get(0).id();
        int y = c.get(1).id();
        return (x == a && y == b) || (x == b && y == a);
    }

    /** The pair as {@code {centre x, centre y, spread, angle}}: spread is half their distance, angle from the first to the second by id. */
    private double[] frame(List<TouchpadContact> c) {
        TouchpadContact a = c.get(0).id() == idA || c.get(1).id() == idB ? c.get(0) : c.get(1);
        TouchpadContact b = a == c.get(0) ? c.get(1) : c.get(0);
        double ax = b.x() - a.x();
        double ay = b.y() - a.y();
        return new double[] {(a.x() + b.x()) / 2, (a.y() + b.y()) / 2, Math.hypot(ax, ay) / 2, Math.atan2(ay, ax)};
    }

    /** {@code a} as the shortest way round, in (-π, π]. */
    private static double angle(double a) {
        while (a > Math.PI) {
            a -= 2 * Math.PI;
        }
        while (a <= -Math.PI) {
            a += 2 * Math.PI;
        }
        return a;
    }
}
