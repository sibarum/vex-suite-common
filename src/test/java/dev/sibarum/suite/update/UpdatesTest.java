package dev.sibarum.suite.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The install record, and the script that updates once the application has closed, run for real on Windows. */
class UpdatesTest {

    @TempDir
    Path dir;

    private Path record(String id, String json) throws IOException {
        Path registry = Files.createDirectories(dir.resolve("installs"));
        Files.writeString(registry.resolve(id + ".json"), json);
        return registry;
    }

    private static String json(Path installDir, Path exe) {
        return """
                {
                    "schemaVersion":  1, "id": "pix", "name": "Pix", "version": "0.1.0",
                    "installDir": "%s",
                    "manifestUrl": "https://example.invalid/apps/pix/manifest.json",
                    "commands": { "pix": { "path": "%s", "kind": "native", "version": "0.1.0" } }
                }""".formatted(esc(installDir), esc(exe));
    }

    private static String esc(Path p) {
        return p.toString().replace("\\", "\\\\");
    }

    @Test
    void readsTheInstallRecord() throws IOException {
        Path install = dir.resolve("Pix");
        Path registry = record("pix", "\uFEFF" + json(install, install.resolve("pix.exe")));
        Install i = Install.find(registry, "pix").orElseThrow();
        assertEquals("Pix", i.name());
        assertEquals("0.1.0", i.version());
        assertEquals(install, i.dir());
        assertEquals(install.resolve("update.ps1"), i.updater());
        assertEquals("https://example.invalid/apps/pix/manifest.json", i.manifestUrl());
        assertEquals(install.resolve("pix.exe"), i.executable());
    }

    @Test
    void noRecordOrAnUnreadableOneIsNoInstall() throws IOException {
        Path registry = record("pix", "{ not json");
        assertEquals(Optional.empty(), Install.find(registry, "pix"));
        assertEquals(Optional.empty(), Install.find(registry, "vexplore"));
        record("pix", "{ \"id\": \"pix\", \"version\": \"0.1.0\" }");
        assertEquals(Optional.empty(), Install.find(registry, "pix"));
    }

    @Test
    void aBuildThatIsNotTheInstalledCopyIsNotOffered() throws IOException {
        Path install = dir.resolve("Pix");
        Path registry = record("pix", json(install, install.resolve("pix.exe")));
        assertFalse(Install.find(registry, "pix").orElseThrow().running());
        assertEquals(Optional.empty(), Updates.of(registry, "pix"));
    }

    // ---------------------------------------------------------------- the script

    private Updates fake(String updaterBody) throws IOException {
        Path install = Files.createDirectories(dir.resolve("Pix Folder"));
        Files.writeString(install.resolve("update.ps1"), updaterBody);
        // The "application": writes each argument it was started with on a line of its own.
        Path exe = install.resolve("pix.cmd");
        Files.writeString(exe, "@echo off\r\n(for %%a in (%*) do @echo %%~a) > \"%~dp0started.txt\"\r\n");
        Path registry = record("pix", json(install, exe));
        return new Updates(Install.find(registry, "pix").orElseThrow(), dir.resolve("updates").resolve("pix"));
    }

    private static long exited() throws Exception {
        Process p = new ProcessBuilder("cmd.exe", "/c", "exit").start();
        p.waitFor();
        return p.pid();
    }

    private static void finish(Process p) throws InterruptedException {
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the script did not finish");
    }

    /** The relaunched application runs on its own, after the script has finished. */
    private static String awaitStarted(Path started) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < until) {
            if (Files.isRegularFile(started)) {
                Thread.sleep(200);
                return Files.readString(started).replace("\r", "");
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the application was not started again");
    }

    @Test
    void updatesOnceClosedThenStartsAgainWithItsArguments() throws Exception {
        assumeTrue(windows());
        Updates u = fake("Write-Host 'installing 0.2.0'\r\nexit 0\r\n");
        finish(u.start(List.of("C:\\Pictures\\a b.png", "second"), exited(), 30));
        assertEquals("C:\\Pictures\\a b.png\nsecond\n",awaitStarted(u.install().dir().resolve("started.txt")));
        Updates.Outcome o = u.outcome().orElseThrow();
        assertTrue(o.ok());
        assertTrue(Files.readString(o.log()).contains("installing 0.2.0"), Files.readString(o.log()));
        assertEquals(Optional.empty(), u.outcome(), "an outcome is read once");
    }

    @Test
    void aFailedUpdateStillStartsTheApplicationAgain() throws Exception {
        assumeTrue(windows());
        Updates u = fake("Write-Host 'ERROR: Checksum mismatch'\r\nexit 1\r\n");
        finish(u.start(List.of(), exited(), 30));
        awaitStarted(u.install().dir().resolve("started.txt"));
        Updates.Outcome o = u.outcome().orElseThrow();
        assertFalse(o.ok());
        assertEquals(1, o.exit());
        assertTrue(Files.readString(o.log()).contains("Checksum mismatch"));
    }

    @Test
    void anApplicationThatNeverClosesIsLeftAsItIs() throws Exception {
        assumeTrue(windows());
        Updates u = fake("Set-Content -LiteralPath (Join-Path $PSScriptRoot 'ran.txt') -Value yes\r\nexit 0\r\n");
        Process app = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep 60").start();
        try {
            finish(u.start(List.of(), app.pid(), 1));
        } finally {
            app.destroyForcibly();
        }
        assertEquals(Updates.NOT_CLOSED, u.outcome().orElseThrow().exit());
        assertFalse(Files.exists(u.install().dir().resolve("ran.txt")), "the updater ran");
        assertFalse(Files.exists(u.install().dir().resolve("started.txt")), "a second copy was started");
    }

    @Test
    void noUpdaterInTheInstallFolderIsRefusedBeforeAnythingStarts() throws Exception {
        Updates u = fake("");
        Files.delete(u.install().updater());
        assertThrows(IOException.class, () -> u.restart(List.of()));
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }
}
