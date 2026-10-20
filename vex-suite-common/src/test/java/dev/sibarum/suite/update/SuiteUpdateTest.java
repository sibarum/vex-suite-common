package dev.sibarum.suite.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Closing the other applications before an update, with stand-ins for them: a process that sleeps is an application
 * that stays open (asking about unsaved changes, say), and one that sleeps a moment is one that closes when asked.
 */
class SuiteUpdateTest {

    @TempDir
    Path dir;

    private final UpdatesTest installs = new UpdatesTest();
    private final List<Process> started = new ArrayList<>();
    /** Which stand-in process each application is running as. */
    private final Map<String, List<ProcessHandle>> open = new ConcurrentHashMap<>();
    private final List<String> said = new CopyOnWriteArrayList<>();

    private Process sleeping(int seconds) throws IOException {
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep " + seconds).start();
        started.add(p);
        return p;
    }

    private SuiteUpdate suite(Updates u) {
        return new SuiteUpdate(u, app -> open.getOrDefault(app.id(), List.of()).stream()
                .filter(ProcessHandle::isAlive).toList(), p -> 1, Duration.ofMillis(300));
    }

    private SuiteUpdate.Progress progress(Runnable onStillOpen) {
        return progress("", onStillOpen);
    }

    /** Runs {@code onStillOpen} when {@code id} is still open, or any application when it is blank. */
    private SuiteUpdate.Progress progress(String id, Runnable onStillOpen) {
        return new SuiteUpdate.Progress() {
            @Override
            public void closing(Install app) {
                said.add("closing " + app.id());
            }

            @Override
            public void stillOpen(Install app, boolean asked) {
                said.add("still open " + app.id());
                if (id.isEmpty() || id.equals(app.id())) {
                    onStillOpen.run();
                }
            }
        };
    }

    private static Updates.Available available(Install i) {
        return new Updates.Available(i, new Release("0.2.0", "", 0));
    }

    private Updates setUp() throws IOException {
        installs.dir = dir;
        return installs.updates(installs.app("pix", "Pix", "exit 0\r\n"));
    }

    private void stop() {
        started.forEach(Process::destroyForcibly);
    }

    @Test
    void waitsForEachToCloseThenHandsOver() throws Exception {
        assumeTrue(UpdatesTest.windows());
        Updates u = setUp();
        Install calc = installs.app("calculator", "Calculator",
                "Set-Content -LiteralPath (Join-Path $PSScriptRoot 'ran.txt') -Value yes\r\nexit 0\r\n");
        open.put("calculator", List.of(sleeping(1).toHandle()));
        try {
            SuiteUpdate.Result r = suite(u).run(List.of(available(calc)), List.of(), progress(() -> { }));
            SuiteUpdate.Result.Started s = assertInstanceOf(SuiteUpdate.Result.Started.class, r);
            assertFalse(s.self());
            assertEquals(List.of("calculator"), s.update().stream().map(Install::id).toList());
            UpdatesTest.finish(s.script());
        } finally {
            stop();
        }
        assertEquals("closing calculator", said.getFirst());
        assertTrue(Files.exists(calc.dir().resolve("ran.txt")), "it was not updated");
        UpdatesTest.awaitStarted(calc.dir().resolve("started.txt"));
        assertTrue(u.outcome().orElseThrow().ok());
    }

    @Test
    void cancelUpdatesNothingAndOpensAgainWhatClosed() throws Exception {
        assumeTrue(UpdatesTest.windows());
        Updates u = setUp();
        Install calc = installs.app("calculator", "Calculator", "exit 0\r\n");
        Install vex = installs.app("vex", "Vex", "exit 0\r\n");
        open.put("calculator", List.of(sleeping(1).toHandle()));
        open.put("vex", List.of(sleeping(60).toHandle()));
        SuiteUpdate s = suite(u);
        SuiteUpdate.Result r;
        try {
            r = s.run(List.of(available(calc), available(vex)), List.of(), progress("vex", s::cancel));
        } finally {
            stop();
        }
        assertInstanceOf(SuiteUpdate.Result.Cancelled.class, r);
        assertEquals(List.of("closing calculator", "closing vex", "still open vex"),
                said.stream().filter(w -> !w.equals("still open calculator")).toList());
        UpdatesTest.awaitStarted(calc.dir().resolve("started.txt"));
        assertFalse(Files.exists(vex.dir().resolve("started.txt")), "Vex never closed, and is not opened again");
        assertFalse(Files.exists(dir.resolve("updates/pix/plan.txt")), "the script was started");
        assertTrue(u.lock().isPresent(), "the lock was kept");
    }

    @Test
    void skipUpdatesTheRestWithoutIt() throws Exception {
        assumeTrue(UpdatesTest.windows());
        Updates u = setUp();
        Install vex = installs.app("vex", "Vex",
                "Set-Content -LiteralPath (Join-Path $PSScriptRoot 'ran.txt') -Value yes\r\nexit 0\r\n");
        Install calc = installs.app("calculator", "Calculator", "exit 0\r\n");
        open.put("vex", List.of(sleeping(60).toHandle()));
        SuiteUpdate s = suite(u);
        try {
            SuiteUpdate.Result r = s.run(List.of(available(vex), available(calc)), List.of(), progress(s::skip));
            SuiteUpdate.Result.Started st = assertInstanceOf(SuiteUpdate.Result.Started.class, r);
            assertEquals(List.of("calculator"), st.update().stream().map(Install::id).toList());
            UpdatesTest.finish(st.script());
        } finally {
            stop();
        }
        assertFalse(Files.exists(vex.dir().resolve("ran.txt")), "the skipped one was updated");
        assertFalse(Files.exists(calc.dir().resolve("started.txt")), "Calculator was not open, and is not opened");
        assertEquals(List.of("calculator"), u.outcome().orElseThrow().apps().stream()
                .map(Updates.Outcome::id).toList());
    }

    @Test
    void skippingEverythingIsNothing() throws Exception {
        assumeTrue(UpdatesTest.windows());
        Updates u = setUp();
        Install vex = installs.app("vex", "Vex", "exit 0\r\n");
        open.put("vex", List.of(sleeping(60).toHandle()));
        SuiteUpdate s = suite(u);
        try {
            assertInstanceOf(SuiteUpdate.Result.Nothing.class,
                    s.run(List.of(available(vex)), List.of(), progress(s::skip)));
        } finally {
            stop();
        }
    }

    @Test
    void anUpdateUnderWayIsBusy() throws Exception {
        Updates u = setUp();
        try (Updates.Lock held = u.lock().orElseThrow()) {
            assertInstanceOf(SuiteUpdate.Result.Busy.class,
                    suite(u).run(List.of(available(u.install())), List.of(), progress(() -> { })));
        }
    }

    @Test
    void aWindowlessProcessHasNothingToAsk() throws Exception {
        assumeTrue(UpdatesTest.windows());
        Process p = sleeping(30);
        try {
            assertTrue(p.isAlive());
            assertEquals(0, Updates.askToClose(p.toHandle()));
            assertFalse(p.waitFor(300, TimeUnit.MILLISECONDS), "it was closed");
        } finally {
            stop();
        }
    }
}
