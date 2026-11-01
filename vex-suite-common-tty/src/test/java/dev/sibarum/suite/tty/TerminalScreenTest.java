package dev.sibarum.suite.tty;

import dev.vexelray.gui.core.Addressed;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import dev.vexelray.gui.core.spi.Seams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sibarum.atchung.Atchung;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The terminal talks to whatever runs behind it by message, addressed by the widget: what the program wrote comes in
 * as a {@link TerminalScreen.Feed}, and what goes back to it leaves on {@link TerminalScreen#SENT}. Driven headless,
 * with no window and no atlas: a cell is 10 px wide and 20 high, and the tree is 800 by 600.
 */
class TerminalScreenTest {

    static {
        // An overflow under test is a failing test that names the topic, rather than the JVM halting mid-suite.
        Atchung.onFatal(sibarum.atchung.Fatal.THROW);
    }

    private static final TerminalScreen.Metrics CELL = new TerminalScreen.Metrics() {
        @Override
        public float advance(float px) {
            return 10f;
        }

        @Override
        public float ascent(float px) {
            return 15f;
        }

        @Override
        public float lineHeight(float px) {
            return 20f;
        }
    };

    /** A terminal holds no text node, so nothing is measured. */
    private static final TextMeasurer MEASURER = (node, axis, px) -> 0f;

    private final Atchung bus = Atchung.create();
    private Gui gui;
    private TerminalScreen screen;

    @BeforeEach
    void open() {
        gui = new Gui(bus);
        screen = new TerminalScreen(gui, CELL, 0, 16f).name("shell");
        gui.root().children(screen.node());
    }

    @AfterEach
    void close() {
        screen.close();
        gui.close();
    }

    private void frames(int n) {
        for (int i = 0; i < n; i++) {
            Seams.of(gui).frame(800f, 600f, MEASURER);
        }
    }

    private <T extends Addressed> List<T> heard(Topic<T> topic) {
        List<T> out = new ArrayList<>();
        gui.mailbox(topic, screen.address(), out::add);
        return out;
    }

    private void command(TerminalScreen.Command c) {
        bus.publish(TerminalScreen.COMMANDS, c);
        frames(2);
    }

    @Test
    void theGridIsFittedToTheNodeAndSaysSo() {
        List<TerminalScreen.Fitted> fitted = heard(TerminalScreen.FITTED);

        frames(3);

        assertEquals(List.of(new TerminalScreen.Fitted(screen.address(), 80, 30)), fitted);
        assertEquals(80, screen.cols());
        assertEquals(30, screen.rows());
    }

    @Test
    void whatTheProgramWroteIsFedByMessage() {
        frames(2);

        command(new TerminalScreen.Feed(screen.address(), "hello\r\nworld"));

        assertTrue(screen.allText().startsWith("hello\n"), screen.allText());
        assertTrue(screen.allText().endsWith("world"), screen.allText());
    }

    @Test
    void aFeedForAnotherTerminalIsNotThisOnes() {
        frames(2);

        command(new TerminalScreen.Feed(gui.address("other"), "not mine"));

        assertEquals("", screen.allText());
    }

    @Test
    void typingIsSentToTheProgramAsTheTerminalSpellsIt() {
        List<TerminalScreen.Sent> sent = heard(TerminalScreen.SENT);
        frames(2);
        gui.focus(screen.node());
        frames(1);

        bus.publish(gui.input(), new InputEvent.CharTyped('l', 0));
        bus.publish(gui.input(), new InputEvent.CharTyped('s', 0));
        frames(1);
        bus.publish(gui.input(), new InputEvent.KeyPressed(Key.ENTER, 0));
        frames(1);
        bus.publish(gui.input(), new InputEvent.KeyReleased(Key.ENTER, 0));
        frames(2);

        String a = screen.address();
        assertEquals(List.of(new TerminalScreen.Sent(a, "l", true), new TerminalScreen.Sent(a, "s", true),
                new TerminalScreen.Sent(a, "\r", true)), sent);
    }

    @Test
    void theTerminalsOwnAnswerIsSentButNotTyped() {
        List<TerminalScreen.Sent> sent = heard(TerminalScreen.SENT);
        frames(2);

        command(new TerminalScreen.Feed(screen.address(), "ab\u001b[6n"));   // where is the cursor?

        assertEquals(List.of(new TerminalScreen.Sent(screen.address(), "\u001b[1;3R", false)), sent);
    }

    @Test
    void theProgramsTitleIsPublished() {
        List<TerminalScreen.Titled> titled = heard(TerminalScreen.TITLED);
        frames(2);

        command(new TerminalScreen.Feed(screen.address(), "\u001b]0;pwsh in C:\\work\u0007"));

        assertEquals(List.of(new TerminalScreen.Titled(screen.address(), "pwsh in C:\\work")), titled);
    }

    @Test
    void copyOrInterruptWithNothingSelectedIsTheInterrupt() {
        List<TerminalScreen.Sent> sent = heard(TerminalScreen.SENT);
        frames(2);

        command(new TerminalScreen.CopyOrInterrupt(screen.address()));

        assertFalse(screen.hasSelection());
        assertEquals(List.of(new TerminalScreen.Sent(screen.address(), "\u0003", true)), sent);
    }

    @Test
    void aRenamedTerminalHearsItsCommandsAtItsNewAddress() {
        frames(2);
        String before = screen.address();

        screen.name("renamed");
        command(new TerminalScreen.Feed(before, "lost"));
        command(new TerminalScreen.Feed(screen.address(), "found"));

        assertEquals("found", screen.allText());
    }
}
