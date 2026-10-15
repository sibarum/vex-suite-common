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

/** The install records, and the script that updates once the applications have closed, run for real on Windows. */
class UpdatesTest {

    @TempDir
    Path dir;

    private Path registry() throws IOException {
        return Files.createDirectories(dir.resolve("installs"));
    }

    private Path record(String id, String json) throws IOException {
        Path registry = registry();
        Files.writeString(registry.resolve(id + ".json"), json);
        return registry;
    }

    static String json(String id, String name, String publisher, Path installDir, Path exe) {
        return """
                {
                    "schemaVersion":  1, "id": "%s", "name": "%s", "publisher": "%s", "version": "0.1.0",
                    "installDir": "%s",
                    "manifestUrl": "https://example.invalid/apps/%s/manifest.json",
                    "commands": { "%s": { "path": "%s", "kind": "native", "version": "0.1.0" } }
                }""".formatted(id, name, publisher, esc(installDir), id, id, esc(exe));
    }

    private static String json(Path installDir, Path exe) {
        return json("pix", "Pix", "sibarum", installDir, exe);
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
        assertEquals("sibarum", i.publisher());
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

    @Test
    void theSuiteIsEveryInstallOfTheSamePublisher() throws IOException {
        Path registry = record("pix", json(dir.resolve("Pix"), dir.resolve("Pix/pix.exe")));
        record("vexplore", json("vexplore", "Vexplore", "sibarum", dir.resolve("V"), dir.resolve("V/vexplore.exe")));
        record("calculator", json("calculator", "Calculator", "sibarum", dir.resolve("C"), dir.resolve("C/c.exe")));
        record("other", json("other", "Other", "someone else", dir.resolve("O"), dir.resolve("O/o.exe")));
        record("broken", "{ not json");
        Files.writeString(registry.resolve("runtime.home"), "C:\\somewhere");
        Updates u = new Updates(Install.find(registry, "pix").orElseThrow(), registry, dir.resolve("updates"));
        assertEquals(List.of("calculator", "pix", "vexplore"), u.suite().stream().map(Install::id).toList());
    }

    @Test
    void noPublisherIsASuiteOfOne() throws IOException {
        Path registry = record("pix", json("pix", "Pix", "", dir.resolve("Pix"), dir.resolve("Pix/pix.exe")));
        record("vex", json("vex", "Vex", "", dir.resolve("V"), dir.resolve("V/vex.exe")));
        Updates u = new Updates(Install.find(registry, "pix").orElseThrow(), registry, dir.resolve("updates"));
        assertEquals(List.of("pix"), u.suite().stream().map(Install::id).toList());
    }

    @Test
    void oneUpdateAtATime() throws IOException {
        Path registry = record("pix", json(dir.resolve("Pix"), dir.resolve("Pix/pix.exe")));
        Updates u = new Updates(Install.find(registry, "pix").orElseThrow(), registry, dir.resolve("updates"));
        try (Updates.Lock held = u.lock().orElseThrow()) {
            assertEquals(Optional.empty(), u.lock());
        }
        u.lock().orElseThrow().close();
    }

    @Test
    void aResultFromBeforeTheSuiteIsThisApplicationsOwn() throws IOException {
        Path registry = record("pix", json(dir.resolve("Pix"), dir.resolve("Pix/pix.exe")));
        Updates u = new Updates(Install.find(registry, "pix").orElseThrow(), registry, dir.resolve("updates"));
        Files.createDirectories(dir.resolve("updates/pix"));
        Files.writeString(dir.resolve("updates/pix/result.txt"), "0\r\n");
        Updates.Report r = u.outcome().orElseThrow();
        assertEquals(List.of(new Updates.Outcome("pix", "Pix", 0, "0.1.0")), r.apps());
        assertTrue(r.ok());
    }

    // ---------------------------------------------------------------- the script

    /** An installed "application" with a fake updater: a .cmd that writes each argument it starts with to a file. */
    Install app(String id, String name, String updaterBody) throws IOException {
        Path install = Files.createDirectories(dir.resolve(name + " Folder"));
        Files.writeString(install.resolve("update.ps1"), updaterBody);
        Path exe = install.resolve(id + ".cmd");
        Files.writeString(exe, "@echo off\r\n(for %%a in (%*) do @echo %%~a) > \"%~dp0started.txt\"\r\n");
        record(id, json(id, name, "sibarum", install, exe));
        return Install.find(registry(), id).orElseThrow();
    }

    Updates fake(String updaterBody) throws IOException {
        return updates(app("pix", "Pix", updaterBody));
    }

    Updates updates(Install self) throws IOException {
        return new Updates(self, registry(), dir.resolve("updates"));
    }

    static long exited() throws Exception {
        Process p = new ProcessBuilder("cmd.exe", "/c", "exit").start();
        p.waitFor();
        return p.pid();
    }

    static void finish(Process p) throws InterruptedException {
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the script did not finish");
    }

    /** The relaunched application runs on its own, after the script has finished. */
    static String awaitStarted(Path started) throws Exception {
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
        Install self = u.install();
        finish(u.start(List.of(self), List.of(), List.of("C:\\Pictures\\a b.png", "second"), exited(), 30));
        assertEquals("C:\\Pictures\\a b.png\nsecond\n", awaitStarted(self.dir().resolve("started.txt")));
        Updates.Report r = u.outcome().orElseThrow();
        assertTrue(r.ok());
        assertEquals(List.of("pix"), r.apps().stream().map(Updates.Outcome::id).toList());
        assertTrue(Files.readString(r.log()).contains("installing 0.2.0"), Files.readString(r.log()));
        assertEquals(Optional.empty(), u.outcome(), "an outcome is read once");
    }

    @Test
    void aFailedUpdateStillStartsTheApplicationAgain() throws Exception {
        assumeTrue(windows());
        Updates u = fake("Write-Host 'ERROR: Checksum mismatch'\r\nexit 1\r\n");
        finish(u.start(List.of(u.install()), List.of(), List.of(), exited(), 30));
        awaitStarted(u.install().dir().resolve("started.txt"));
        Updates.Report r = u.outcome().orElseThrow();
        assertFalse(r.ok());
        assertEquals(1, r.of("pix").orElseThrow().exit());
        assertTrue(Files.readString(r.log()).contains("Checksum mismatch"));
    }

    @Test
    void anApplicationThatNeverClosesIsLeftAsItIs() throws Exception {
        assumeTrue(windows());
        Updates u = fake("Set-Content -LiteralPath (Join-Path $PSScriptRoot 'ran.txt') -Value yes\r\nexit 0\r\n");
        Process app = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep 60").start();
        try {
            finish(u.start(List.of(u.install()), List.of(), List.of(), app.pid(), 1));
        } finally {
            app.destroyForcibly();
        }
        Updates.Outcome o = u.outcome().orElseThrow().of("pix").orElseThrow();
        assertEquals(Updates.NOT_CLOSED, o.exit());
        assertTrue(o.stayedOpen());
        assertFalse(Files.exists(u.install().dir().resolve("ran.txt")), "the updater ran");
        assertFalse(Files.exists(u.install().dir().resolve("started.txt")), "a second copy was started");
    }

    @Test
    void theOthersAreUpdatedAndGivenBackEvenWhenThisOneStaysOpen() throws Exception {
        assumeTrue(windows());
        Updates u = fake("exit 0\r\n");
        Install vex = app("vex", "Vex", "Set-Content -LiteralPath (Join-Path $PSScriptRoot 'ran.txt') -Value yes\r\n"
                + "exit 0\r\n");
        Install calc = app("calculator", "Calculator", "Write-Host 'ERROR: offline'\r\nexit 1\r\n");
        Process app = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep 60").start();
        try {
            finish(u.start(List.of(vex, calc, u.install()), List.of(vex), List.of(), app.pid(), 1));
        } finally {
            app.destroyForcibly();
        }
        assertTrue(Files.exists(vex.dir().resolve("ran.txt")), "Vex was not updated");
        assertEquals("", awaitStarted(vex.dir().resolve("started.txt")), "Vex was open, and opens again");
        assertFalse(Files.exists(calc.dir().resolve("started.txt")), "Calculator was not open, and is not opened");
        Updates.Report r = u.outcome().orElseThrow();
        assertEquals(List.of("vex", "calculator", "pix"), r.apps().stream().map(Updates.Outcome::id).toList());
        assertTrue(r.of("vex").orElseThrow().ok());
        assertEquals(1, r.of("calculator").orElseThrow().exit());
        assertTrue(r.of("pix").orElseThrow().stayedOpen());
    }

    @Test
    void anotherUpdateUnderWayHoldsThisOneOff() throws Exception {
        assumeTrue(windows());
        Updates u = fake("Set-Content -LiteralPath (Join-Path $PSScriptRoot 'ran.txt') -Value yes\r\nexit 0\r\n");
        Files.createDirectories(dir.resolve("updates"));
        // Held as another application holds it while it closes the others: the script cannot have it.
        try (Updates.Lock held = u.lock().orElseThrow()) {
            finish(u.start(List.of(u.install()), List.of(), List.of(), exited(), 2));
        }
        assertFalse(Files.exists(u.install().dir().resolve("ran.txt")), "the updater ran");
        assertEquals(Updates.BUSY, u.outcome().orElseThrow().of("pix").orElseThrow().exit());
    }

    @Test
    void noUpdaterInTheInstallFolderIsRefusedBeforeAnythingStarts() throws Exception {
        Updates u = fake("");
        Files.delete(u.install().updater());
        assertThrows(IOException.class, () -> u.handover(List.of(u.install()), List.of(), List.of()));
    }

    @Test
    void anArgumentThatCannotBePassedIsRefusedBeforeAnythingStarts() throws Exception {
        Updates u = fake("");
        assertThrows(IOException.class, () -> u.handover(List.of(u.install()), List.of(), List.of("a\tb")));
    }

    static boolean windows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }
}
