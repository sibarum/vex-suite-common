package dev.sibarum.suite.tty.linux;

import dev.sibarum.suite.tty.Pty;
import dev.sibarum.suite.tty.PtyPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.LINUX)
class LinuxPtyTest {

    /** Everything a pty has said so far, read on a thread of its own. */
    private static final class Output {
        private final StringBuilder out = new StringBuilder();

        Output(Pty pty) {
            Thread reader = new Thread(() -> {
                byte[] b = new byte[8192];
                int n;
                while ((n = pty.read(b)) > 0) {
                    synchronized (out) { out.append(new String(b, 0, n, StandardCharsets.UTF_8)); }
                }
            });
            reader.setDaemon(true);
            reader.start();
        }

        boolean waitFor(String text) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                synchronized (out) { if (out.toString().contains(text)) return true; }
                Thread.sleep(50);
            }
            return false;
        }

        @Override
        public String toString() {
            synchronized (out) { return out.toString(); }
        }
    }

    @Test
    void theShellEchoesThroughThePty() throws Exception {
        try (Pty pty = Pty.start("/bin/sh", 100, 30, null)) {
            Output out = new Output(pty);
            pty.write("echo pty-$((6*7))\r");
            assertTrue(out.waitFor("pty-42"), out.toString());
        }
    }

    @Test
    void theProgramSeesItsSizeAndItsResize() throws Exception {
        try (Pty pty = Pty.start("/bin/sh", 100, 30, null)) {
            Output out = new Output(pty);
            pty.write("stty size\r");
            assertTrue(out.waitFor("30 100"), out.toString());
            pty.resize(120, 40);
            pty.write("stty size\r");
            assertTrue(out.waitFor("40 120"), out.toString());
        }
    }

    /** The pty is the program's controlling terminal, so a typed Ctrl+C is a SIGINT to what is in front. */
    @Test
    void controlCInterruptsTheProgramInFront() throws Exception {
        try (Pty pty = Pty.start("/bin/sh", 100, 30, null)) {
            Output out = new Output(pty);
            pty.write("sleep 30; echo slept\r");
            Thread.sleep(500);
            pty.write(new byte[]{3});
            pty.write("echo after-$((1+1))\r");
            long asked = System.nanoTime();
            assertTrue(out.waitFor("after-2"), out.toString());
            // Long before the sleep was due to end, and without its "slept": the terminal echoes the typed line, so
            // "slept" appears there, but never at the start of a line of its own.
            assertTrue(System.nanoTime() - asked < 20_000_000_000L);
            assertFalse(out.toString().contains("\nslept"), out.toString());
        }
    }

    @Test
    void aRunLineExitsWithItsStatusAndEndsTheOutput() throws Exception {
        String line = PtyPlatform.current().runLine("echo 'it''s' \"done\"; exit 3");
        try (Pty pty = Pty.start(line, 80, 24, null)) {
            Output out = new Output(pty);
            assertEquals(3, pty.waitFor());
            assertTrue(out.waitFor("its done"), out.toString());
        }
    }

    @Test
    void aWordIsQuotedOnlyWhenTheShellNeedsIt() {
        PtyPlatform p = PtyPlatform.current();
        assertEquals("/usr/bin/java", p.quote("/usr/bin/java"));
        assertEquals("'a b'", p.quote("a b"));
        assertEquals("'it'\\''s'", p.quote("it's"));
    }

    /** An executable file is a program only when it is a binary or a script: a shared folder marks every file so. */
    @Test
    void aProgramIsAnExecutableBinaryOrScript(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path script = java.nio.file.Files.writeString(dir.resolve("run me.sh"), "#!/bin/sh\necho hi\n");
        java.nio.file.Path notes = java.nio.file.Files.writeString(dir.resolve("notes.txt"), "just text\n");
        script.toFile().setExecutable(true);
        notes.toFile().setExecutable(true);
        PtyPlatform p = PtyPlatform.current();
        assertEquals(java.util.Optional.of("'" + script.toAbsolutePath() + "'"), p.programLine(script));
        assertTrue(p.programLine(notes).isEmpty());
        assertTrue(p.programLine(dir.resolve("absent")).isEmpty());
    }

    @Test
    void theShellIsOnThePath() {
        assertTrue(PtyPlatform.current().onPath("sh", System.getenv()).isPresent());
        assertTrue(PtyPlatform.current().onPath("no-such-program-here", System.getenv()).isEmpty());
        assertTrue(PtyPlatform.current().shortName(java.nio.file.Path.of("/tmp")).isEmpty());
    }

    @Test
    void theLoginShellIsOffered() {
        assertFalse(PtyPlatform.current().shells().isEmpty());
    }
}
