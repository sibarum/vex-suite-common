package dev.sibarum.suite.view;

import dev.vexelray.codec.image.gui.ImageView.Fitted;
import dev.vexelray.codec.image.gui.ImageView.Pose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the picture is looked at: where it may go, how it comes back, and how it zooms and turns. */
class LensTest {

    /** A 1000 x 600 stage with a 800 x 600 picture fitted on it. */
    private static Lens lens() {
        Lens l = new Lens();
        l.measure(new Fitted(1000f, 600f, 800f, 600f));
        return l;
    }

    /** Run it until it stops, at most ten seconds; how long that took. */
    private static float settle(Lens l) {
        float t = 0f;
        while (l.step(1f / 60f) && t < 10f) {
            t += 1f / 60f;
        }
        return t;
    }

    @Test
    void aPictureNoLargerThanTheStageIsHeldInsideIt() {
        assertEquals(100f, Lens.limit(800f, 1000f), 1e-4f);
        assertEquals(0f, Lens.limit(1000f, 1000f), 1e-4f);
    }

    @Test
    void aLargerOneKeepsAQuarterOfTheStage() {
        // Five times the stage: it may go until its edge is three quarters of the way across.
        float limit = Lens.limit(5000f, 1000f);
        float edge = limit - 5000f / 2f;   // its left edge, from the stage's centre, moved as far right as it goes
        assertEquals(1000f / 2f - Lens.KEEP * 1000f, edge, 1e-3f);
    }

    @Test
    void theTwoLimitsMeetWhereThePictureIsTheStagesSize() {
        assertEquals(Lens.limit(999.9f, 1000f), Lens.limit(1000.1f, 1000f), 0.2f);
    }

    @Test
    void theBandIsUndoneByTheHand() {
        for (float at : new float[] {-180f, -40f, 0f, 30f, 99f, 140f, 260f}) {
            assertEquals(at, Lens.band(Lens.hand(at, 100f, 1000f), 100f, 1000f), 0.01f);
        }
        assertTrue(Lens.band(10_000f, 100f, 1000f) < 100f + 500f, "never more than half the stage past");
    }

    @Test
    void aDragPastTheLimitSpringsBackAndStops() {
        Lens l = lens();
        l.grab(0L);
        l.drag(400f, 0f, 10_000_000L);
        float pulled = l.pose().x();
        assertTrue(pulled > 100f, "past its limit of 100");
        assertTrue(pulled < 500f, "but short of the hand");
        l.release(20_000_000L);
        float t = settle(l);
        // The spring carries it back over the limit, and inside it the momentum it gathered glides out.
        assertTrue(l.pose().x() <= 100f && l.pose().x() > 0f, "back inside its limit: " + l.pose().x());
        assertTrue(t < 2f, "and quickly: " + t);
        assertFalse(l.moving());
    }

    @Test
    void zoomKeepsThePointUnderThePointer() {
        Lens l = lens();
        l.wheel(3f, 0f, 0f);
        settle(l);
        // Larger than the stage, so free to move: a point 200 px right of the centre stays under the pointer.
        float px = 200f;
        float before = (px - l.pose().x()) / l.pose().zoom();
        l.wheel(3f, px, 0f);
        settle(l);
        Pose p = l.pose();
        assertEquals((float) Math.pow(Lens.NOTCH, 6), p.zoom(), 1e-3f);
        assertEquals(before, (px - p.x()) / p.zoom(), 0.01f);
    }

    @Test
    void zoomingOutPullsThePictureBackInside() {
        Lens l = lens();
        l.wheel(6f, 400f, 0f);
        settle(l);
        l.grab(0L);
        l.drag(-2000f, 0f, 10_000_000L);
        l.release(500_000_000L);
        settle(l);
        l.wheel(-12f, 0f, 0f);
        settle(l);
        Pose p = l.pose();
        assertTrue(p.zoom() < 1f);
        assertTrue(Math.abs(p.x()) <= Lens.limit(800f * p.zoom(), 1000f) + 0.01f, "held inside: " + p);
    }

    @Test
    void aQuarterTurnRefitsAFittedPicture() {
        Lens l = lens();
        l.quarter(1);
        settle(l);
        Pose p = l.pose();
        assertEquals(90f, p.degrees(), 1e-3f);
        // 800 x 600 turned is 600 x 800 on a 600-high stage: it fits at 600/800.
        assertEquals(0.75f, p.zoom(), 1e-3f);
        l.quarter(-1);
        l.quarter(-1);
        settle(l);
        assertEquals(-90f, l.pose().degrees(), 1e-3f);
    }

    @Test
    void aTwistTurnsWithThePointer() {
        Lens l = lens();
        l.twistFrom(100f, 0f);
        l.twist(0f, 100f);   // a quarter clockwise, on a Y-down screen
        l.twistEnd();
        assertEquals(90f, l.pose().degrees(), 1e-3f);
        l.quarter(1);
        settle(l);
        assertEquals(180f, l.pose().degrees(), 1e-3f, "a quarter turn goes on from the nearest quarter");
    }

    @Test
    void theVisiblePartIsTheStageTakenBackIntoThePicture() {
        Lens l = lens();
        // Fitted: 800 x 600 on a 1000 x 600 stage, the whole picture, drawn at its fitted size.
        float[] v = l.visible(0f);
        assertEquals(0f, v[0], 1e-4f);
        assertEquals(1f, v[2], 1e-4f);
        assertEquals(800f, v[4], 0.01f);
        // Four times as large about the centre: the middle quarter across and down, drawn 4 x 200 = 800 px across.
        l.wheel((float) (Math.log(4) / Math.log(Lens.NOTCH)), 0f, 0f);
        settle(l);
        v = l.visible(0f);
        assertEquals(0.5f - 1000f / 2f / 3200f, v[0], 1e-3f);
        assertEquals(0.5f - 600f / 2f / 2400f, v[1], 1e-3f);
        assertEquals(1000f, v[4], 0.5f, "the stage's width, at one picture px a screen px");
        // A quarter turn swaps which of the stage's sides falls across the picture.
        l.quarter(1);
        settle(l);
        v = l.visible(0f);
        assertEquals(600f, v[4], 0.5f);
        assertEquals(1000f, v[5], 0.5f);
    }

    @Test
    void uprightThePartLandsOnWholePixels() {
        Lens l = lens();
        l.wheel(2.3f, 37.3f, 11.9f);   // zoomed about an awkward point, so nothing falls on a whole pixel
        settle(l);
        float originX = 0.5f;
        float originY = 32.25f;
        float[] v = l.visible(0f, originX, originY);
        Pose p = l.pose();
        float k = p.zoom() * 800f;
        float left = originX + 500f + p.x() + k * (v[0] - 0.5f);
        float top = originY + 300f + p.y() + p.zoom() * 600f * (v[1] - 0.5f);
        assertEquals(Math.round(left), left, 1e-2f, "the left edge on a pixel boundary");
        assertEquals(Math.round(top), top, 1e-2f, "and the top");
        assertEquals(Math.round(v[4]), v[4], 1e-3f, "a whole number of pixels across");
        assertEquals((v[2] - v[0]) * k, v[4], 1e-2f, "which is the part's own width on the screen");
        assertTrue(v[0] >= 0f && v[2] <= 1f && v[1] >= 0f && v[3] <= 1f, "and never past the picture");
    }

    @Test
    void aMirrorSwapsTheSidesAsTheyAreSeen() {
        Lens l = lens();
        l.mirror(true);
        assertTrue(l.pose().mirrorX());
        assertFalse(l.pose().mirrorY());
        // Turned a quarter, a mirror across the screen is one down the picture's own frame, turned back the other way.
        l = lens();
        l.quarter(1);
        settle(l);
        l.mirror(true);
        assertEquals(-90f, l.pose().degrees(), 1e-3f);
        assertFalse(l.moving(), "at once, not spun through the angles between");
        l.mirror(true);
        assertEquals(90f, l.pose().degrees(), 1e-3f, "and twice is as it was");
        assertFalse(l.pose().mirrorX());
    }

    @Test
    void aMirroredPartIsCutFromThePictureAsItIs() {
        Lens l = lens();
        l.wheel((float) (Math.log(4) / Math.log(Lens.NOTCH)), 0f, 0f);
        settle(l);
        l.grab(0L);
        l.drag(1200f, 0f, 10_000_000L);   // the picture to the right: the stage shows its left part
        l.release(500_000_000L);
        settle(l);
        float[] plain = l.visible(0f);
        assertTrue(plain[0] == 0f && plain[2] < 1f, "its left part");
        l.mirror(true);
        float[] mirrored = l.visible(0f);
        // Drawn the same place, mirrored: what is shown there is the picture's right part.
        assertEquals(1f - plain[2], mirrored[0], 1e-4f);
        assertEquals(1f, mirrored[2], 1e-4f);
        assertEquals(plain[1], mirrored[1], 1e-4f);
        assertEquals(plain[4], mirrored[4], 1e-3f);
    }

    @Test
    void uprightUndoesTheTurnAndTheMirror() {
        Lens l = lens();
        l.quarter(1);
        l.mirror(false);
        l.wheel(3f, 0f, 0f);
        settle(l);
        l.upright();
        settle(l);
        Pose p = l.pose();
        assertEquals(0f, p.degrees() % 360f, 1e-3f);
        assertFalse(p.mirrorX() || p.mirrorY());
        assertEquals(1f, p.zoom(), 1e-3f, "fitted again");
        assertEquals(0f, p.x(), 1e-3f);
    }

    @Test
    void aResizeKeepsAZoomedPictureItsSizeOnTheScreen() {
        Lens l = lens();
        l.wheel(2f, 0f, 0f);
        settle(l);
        float shown = l.pose().zoom() * 800f;
        l.measure(new Fitted(500f, 300f, 400f, 300f));
        assertEquals(shown, l.pose().zoom() * 400f, 0.01f);
    }

    /** Follow the fingers until the picture is where they want it, at most a second. */
    private static void follow(Lens l) {
        for (int i = 0; i < 60 && l.moving(); i++) {
            l.step(1f / 60f);
        }
    }

    @Test
    void twoFingersZoomAndTurnAboutTheirPointWhichStaysUnderThem() {
        Lens l = lens();
        l.touchStart();
        // A point on the picture 100px right of the centre, under the fingers: twice the size and a quarter turn.
        l.touch(0f, 0f, 2f, 90f, 100f, 0f);
        follow(l);
        Pose p = l.pose();
        assertEquals(2f, p.zoom(), 1e-3f);
        assertEquals(90f, p.degrees(), 0.05f);
        // The picture's centre was 100px left of the point; doubled and turned clockwise it is 200px above it.
        assertEquals(100f, p.x(), 0.1f);
        assertEquals(-200f, p.y(), 0.1f);
        assertFalse(l.still(), "in the fingers, not at rest");
    }

    @Test
    void thePictureFollowsTheFingersSmoothedNotAtOnce() {
        Lens l = lens();
        l.touch(0f, 0f, 2f, 0f, 0f, 0f);
        l.step(1f / 60f);
        float z = l.pose().zoom();
        assertTrue(z > 1.1f && z < 1.9f, "on its way, a frame later: " + z);
        follow(l);
        assertEquals(2f, l.pose().zoom(), 1e-3f);
    }

    @Test
    void lettingGoNearAQuarterTurnSettlesOntoItAndAskewStays() {
        Lens near = lens();
        near.touch(0f, 0f, 1.5f, 85f, 0f, 0f);
        near.touchEnd(0f, 0f, 0f, 0f);
        settle(near);
        assertEquals(90f, near.pose().degrees(), 1e-3f);

        Lens askew = lens();
        askew.touch(0f, 0f, 1.5f, 30f, 0f, 0f);
        askew.touchEnd(0f, 0f, 0f, 0f);
        settle(askew);
        assertEquals(30f, askew.pose().degrees(), 1e-3f);
    }

    @Test
    void lettingGoNearFittedFitsAndCentres() {
        Lens l = lens();
        l.touch(40f, 20f, 1.04f, 3f, 0f, 0f);
        l.touchEnd(0f, 0f, 0f, 0f);
        settle(l);
        Pose p = l.pose();
        assertEquals(1f, p.zoom(), 1e-4f);
        assertEquals(0f, p.degrees(), 1e-4f);
        assertEquals(0f, p.x(), 0.25f);
        assertEquals(0f, p.y(), 0.25f);
    }

    @Test
    void aPurePanOfAFittedPictureIsNotRefittedOnLettingGo() {
        Lens l = new Lens();
        l.measure(new Fitted(1000f, 600f, 600f, 600f));   // 200px of room either side
        l.touch(150f, 0f, 1f, 0f, 0f, 0f);
        follow(l);
        l.touchEnd(0f, 0f, 0f, 0f);
        settle(l);
        assertEquals(150f, l.pose().x(), 0.5f, "a pan moves it; only a zoom near fitted fits it again");
    }

    @Test
    void zoomingPastTheLimitGivesAndComesBack() {
        Lens l = lens();
        l.touch(0f, 0f, Lens.MAX_ZOOM * 16f, 0f, 0f, 0f);   // four doublings past the limit
        follow(l);
        assertEquals(Lens.MAX_ZOOM * 2f, l.pose().zoom(), 1e-2f, "a quarter of each doubling asked for: one of four");
        l.touchEnd(0f, 0f, 0f, 0f);
        settle(l);
        assertEquals(Lens.MAX_ZOOM, l.pose().zoom(), 1e-3f);
    }

    @Test
    void lettingGoFlingsAtTheFingersSpeed() {
        Lens l = lens();
        l.touch(0f, 0f, 4f, 0f, 0f, 0f);
        l.touchEnd(0f, 0f, 0f, 0f);
        settle(l);
        l.touchStart();
        l.touchEnd(2000f, 0f, 0f, 0f);
        l.step(1f / 60f);
        assertTrue(l.pose().x() > 20f, "carried on at the speed it was let go: " + l.pose().x());
        settle(l);
        assertTrue(l.still());
    }

    @Test
    void aDoubleTapGoesFromFittedToActualPixelsAndBack() {
        Lens l = lens();                    // 800px fitted, of a 2400px picture: its own pixels are 3x
        l.fitOrActual(l.actual(2400), 0f, 0f);
        settle(l);
        assertEquals(3f, l.pose().zoom(), 1e-3f);
        l.fitOrActual(l.actual(2400), 0f, 0f);
        settle(l);
        assertEquals(1f, l.pose().zoom(), 1e-3f);

        l.fitOrActual(l.actual(800), 0f, 0f);   // already its own size: twice it, so the tap does something
        settle(l);
        assertEquals(2f, l.pose().zoom(), 1e-3f);
    }
}
