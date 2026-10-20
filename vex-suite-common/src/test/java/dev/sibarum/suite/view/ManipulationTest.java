package dev.sibarum.suite.view;

import org.junit.jupiter.api.Test;
import sibarum.tactroller.api.TouchpadContact;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two fingers as one manipulation: always a pan, a zoom and a turn only once they are meant. */
class ManipulationTest {

    private final Manipulation m = new Manipulation();
    private final List<Manipulation.Out> out = new ArrayList<>();
    /** Whether the picture is turned off a quarter as the fingers begin. */
    private boolean askew;

    /** Two fingers about {@code (cx, cy)}, {@code r} mm from it, the pair at {@code deg} degrees, at {@code ms}. */
    private void two(double cx, double cy, double r, double deg, long ms) {
        double a = Math.toRadians(deg);
        double dx = r * Math.cos(a);
        double dy = r * Math.sin(a);
        out.addAll(m.feed(List.of(new TouchpadContact(1, cx - dx, cy - dy, 0, 0),
                new TouchpadContact(2, cx + dx, cy + dy, 0, 0)), ms * 1_000_000L, askew));
    }

    private void lift(long ms) {
        out.addAll(m.feed(List.of(), ms * 1_000_000L, askew));
    }

    private List<Manipulation.Moved> moves() {
        return out.stream().filter(o -> o instanceof Manipulation.Moved).map(o -> (Manipulation.Moved) o).toList();
    }

    private double scale() {
        return moves().stream().mapToDouble(Manipulation.Moved::scale).reduce(1, (a, b) -> a * b);
    }

    private double turn() {
        return moves().stream().mapToDouble(Manipulation.Moved::turn).sum();
    }

    @Test
    void aPanWhoseFingersWobbleIsOnlyAPan() {
        for (int i = 0; i <= 20; i++) {
            // 40 mm along, the spread wandering by a millimetre and the pair by ten degrees
            two(30 + 2 * i, 35, 15 + (i % 2), (i % 3) * 5, 8L * i);
        }
        lift(170);
        assertEquals(1, out.stream().filter(o -> o instanceof Manipulation.Began).count());
        assertEquals(1.0, scale(), 1e-9, "no zoom");
        assertTrue(moves().stream().noneMatch(Manipulation.Moved::free), "no turn: the wobble never leaves the lock");
        assertEquals(40.0, moves().stream().mapToDouble(Manipulation.Moved::dx).sum(), 1e-6);
        Manipulation.Ended end = assertInstanceOf(Manipulation.Ended.class, out.getLast());
        assertTrue(end.vx() > 100, "a pan leaves with its speed: " + end.vx());
    }

    @Test
    void aTwistShortOfThirtyDegreesIsToldButNotFreeAndPastItIs() {
        for (int d = 0; d <= 25; d += 5) {
            two(50, 35, 15, d, d);
        }
        assertTrue(moves().stream().noneMatch(Manipulation.Moved::free), "twenty-five degrees is not a turn yet");
        assertEquals(Math.toRadians(25), turn(), 1e-9, "though every degree of it is told, for the lean");
        two(50, 35, 15, 35, 35);
        assertTrue(moves().getLast().free(), "past thirty, free");
        two(50, 35, 15, 45, 45);
        assertTrue(moves().getLast().free(), "and from then on");
        assertEquals(Math.toRadians(45), turn(), 1e-9);
    }

    @Test
    void aSmoothTwistOfSixtyDegreesTurnsSixty() {
        for (int i = 0; i <= 37; i++) {
            two(50, 35, 12, 60.0 * i / 37, 8L * i);
        }
        lift(310);
        assertEquals(Math.toRadians(60), turn(), 1e-6);
    }

    @Test
    void onAPictureAlreadyAskewTheTurnIsImmediate() {
        askew = true;
        two(50, 35, 15, 0, 0);
        two(50, 35, 15, 10, 8);
        two(50, 35, 15, 15, 16);
        assertTrue(moves().stream().allMatch(Manipulation.Moved::free), "free from the first");
        assertEquals(Math.toRadians(15), turn(), 1e-9, "every degree");
    }

    @Test
    void aSpreadJoinsAsAZoomAndCatchesUp() {
        two(50, 35, 15, 0, 0);
        two(50, 35, 16, 0, 8);
        assertEquals(1.0, scale(), 1e-9, "a millimetre is not a zoom");
        two(50, 35, 18, 0, 16);
        assertEquals(18.0 / 15.0, scale(), 1e-9, "three is, with what came before it");
        lift(24);
        assertEquals(new Manipulation.Ended(0, 0), out.getLast(), "a zoom is not thrown when it lifts");
    }

    @Test
    void aLongPanNeedsADeliberateSpreadToZoom() {
        for (int i = 0; i <= 10; i++) {
            two(20 + 4 * i, 35, 15 + 0.6 * i, 0, 8L * i);   // 40 mm along, spreading 6
        }
        assertEquals(1.0, scale(), 1e-9, "six of spread over forty of travel is drift");
    }

    @Test
    void theFingersOfAThreeFingerSwipeLiftingAreNotAPan() {
        out.addAll(m.feed(List.of(new TouchpadContact(1, 30, 35, 0, 0), new TouchpadContact(2, 45, 35, 0, 0),
                new TouchpadContact(3, 60, 35, 0, 0)), 0, false));
        two(30, 35, 15, 0, 8);
        two(50, 35, 15, 0, 16);
        assertEquals(List.of(), out, "after three, two begin nothing until at most one is left");
    }
}
