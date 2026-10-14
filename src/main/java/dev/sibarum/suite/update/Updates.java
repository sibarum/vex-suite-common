package dev.sibarum.suite.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Whether a newer version of the running application has been published, and installing it without the user going to
 * a prompt: the application closes, the installer's own updater replaces it, and it opens again.
 *
 * <p><b>The installer does the installing.</b> Nothing here downloads a binary or writes into the install folder.
 * {@code update.ps1}, which the installer put beside the executable, fetches the published {@code install.ps1}, refuses
 * it unless it matches the SHA-256 in the manifest, and runs it; that installer in turn refuses any download whose
 * hash does not match. So an update from inside the application is exactly {@code update.cmd -Yes}, checked exactly
 * as carefully, and keeps the install record, shortcuts, file types and Apps entry as the installer keeps them.
 *
 * <p><b>Why the application closes first.</b> Windows will not replace an executable that is running. So
 * {@link #restart} starts a small script, hidden, that waits for this process to exit, runs the updater, writes down
 * how it went and starts the application again (the old version, if the update failed: the user is never left with
 * nothing open). The caller closes its main window as soon as {@code restart} returns. The next start reads what the
 * script wrote with {@link #outcome}, so it can say whether the update took.
 *
 * <p><b>Only an installed copy is updated.</b> {@link #of} is empty when the application is not running from the
 * folder an install record names: a build run from source, a jar, a copy elsewhere. Windows only, as the installer is.
 *
 * <p><b>Every method that touches the network or a file blocks.</b> Callers run them off the frame loop.
 */
public final class Updates {

    /** How long the check may take. It is one small file; a slow network is no reason to hold a thread. */
    static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** How long the script waits for the application to exit before it gives up and leaves it as it is. */
    static final int EXIT_WAIT_S = 60;

    /** What the script writes when the application never exited, so nothing was updated. */
    static final int NOT_CLOSED = 1001;

    private final Install install;
    private final Path work;

    /**
     * @param work where the script, its log and its result are kept: a folder of this install's own, outside the
     *             install folder, which the installer owns
     */
    Updates(Install install, Path work) {
        this.install = install;
        this.work = work;
    }

    /**
     * Updates for the application installed as {@code id}, when this process is that install; empty otherwise.
     *
     * @param registryDir where the installer keeps its records: {@code Apps.registryDir()} from
     *                    {@code vexelray-framework-shell}, which honours the installer's test override
     */
    public static Optional<Updates> of(Path registryDir, String id) {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return Optional.empty();
        }
        return Install.find(registryDir, id).filter(Install::running)
                .map(i -> new Updates(i, registryDir.toAbsolutePath().getParent().resolve("updates").resolve(id)));
    }

    public Install install() {
        return install;
    }

    // ---------------------------------------------------------------- checking

    /** What a check found. */
    public sealed interface Check {

        /** The version installed is the latest, or newer than it. */
        record Current(String version) implements Check {
        }

        /** {@code release} is newer than what is installed. */
        record Available(Release release) implements Check {
        }

        /** The manifest could not be read: no network, or nothing usable there. Says why, for a log. */
        record Failed(String why) implements Check {
        }
    }

    /** Fetch the published manifest and compare its latest version with the one installed. Never throws. */
    public Check check() {
        String manifest;
        try {
            manifest = fetch(install.manifestUrl());
        } catch (IOException e) {
            return new Check.Failed(install.manifestUrl() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Check.Failed("interrupted");
        }
        return compare(install.version(), manifest);
    }

    /** {@link #check}'s verdict on {@code manifest}, given that {@code installed} is installed. */
    static Check compare(String installed, String manifest) {
        Optional<Release> latest;
        try {
            latest = Release.latest(manifest);
        } catch (RuntimeException e) {
            return new Check.Failed("the manifest is not JSON: " + e.getMessage());
        }
        if (latest.isEmpty()) {
            return new Check.Failed("the manifest names no latest Windows release");
        }
        Release r = latest.get();
        return Versions.compare(r.version(), installed) > 0 ? new Check.Available(r) : new Check.Current(installed);
    }

    private static String fetch(String url) throws IOException, InterruptedException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("not a URL", e);
        }
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            return response.body();
        }
    }

    // ---------------------------------------------------------------- installing

    /**
     * Start the update, to run once this process has exited, then start the application again with {@code args}.
     * The caller closes the application straight after; until it does, nothing is replaced.
     *
     * @throws IOException if the installer's updater is not in the install folder, or the script could not be
     *                     written or started. Nothing has changed, and the application can carry on.
     */
    public void restart(List<String> args) throws IOException {
        start(args, ProcessHandle.current().pid(), EXIT_WAIT_S).getOutputStream().close();
    }

    /** {@link #restart}, waiting up to {@code waitS} seconds for process {@code pid}, rather than this one. */
    Process start(List<String> args, long pid, int waitS) throws IOException {
        if (!Files.isRegularFile(install.updater())) {
            throw new IOException("the installer's updater is not in " + install.dir());
        }
        Files.createDirectories(work);
        Path script = work.resolve("restart.ps1");
        Files.writeString(script, SCRIPT, StandardCharsets.US_ASCII);
        Files.deleteIfExists(result());
        Files.deleteIfExists(log());

        List<String> command = new ArrayList<>(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString()));
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).directory(work.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("VEX_UPDATE_PID", Long.toString(pid));
        pb.environment().put("VEX_UPDATE_WAIT", Integer.toString(waitS));
        pb.environment().put("VEX_UPDATE_UPDATER", install.updater().toString());
        pb.environment().put("VEX_UPDATE_EXE", install.executable().toString());
        pb.environment().put("VEX_UPDATE_LOG", log().toString());
        pb.environment().put("VEX_UPDATE_RESULT", result().toString());
        return pb.start();
    }

    /** How the last update went, once: it is forgotten as it is read. Empty when there was none since. */
    public Optional<Outcome> outcome() {
        Path result = result();
        if (!Files.isRegularFile(result)) {
            return Optional.empty();
        }
        String text;
        try {
            text = Files.readString(result, StandardCharsets.US_ASCII).strip();
            Files.deleteIfExists(result);
        } catch (IOException e) {
            return Optional.empty();
        }
        int code;
        try {
            code = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            code = -1;
        }
        return Optional.of(new Outcome(code, install.version(), log()));
    }

    /**
     * How an update went.
     *
     * @param exit    the updater's exit code; 0 when it installed (or found nothing to install), {@link #NOT_CLOSED}
     *                when the application never closed and nothing was tried
     * @param version the version installed now, as the record says
     * @param log     everything the updater said
     */
    public record Outcome(int exit, String version, Path log) {

        public boolean ok() {
            return exit == 0;
        }
    }

    private Path log() {
        return work.resolve("update.log");
    }

    private Path result() {
        return work.resolve("result.txt");
    }

    /**
     * Waits for the application to exit, updates it, and starts it again. Pure ASCII, as the installer's own scripts
     * are, and for Windows PowerShell 5.1. Its inputs come in the environment, not spliced into the text, so a path
     * with a quote in it is only a path. The application's own arguments are the script's.
     */
    static final String SCRIPT = """
            # Written by vex-suite-common's Updates: update an application once it has closed, then start it again.
            $ErrorActionPreference = 'Continue'
            $log = $env:VEX_UPDATE_LOG
            function Say([string]$m) { Add-Content -LiteralPath $log -Value ((Get-Date).ToString('u') + ' ' + $m) }

            $appPid = [int]$env:VEX_UPDATE_PID
            Say "waiting for process $appPid to exit"
            $p = Get-Process -Id $appPid -ErrorAction SilentlyContinue
            if ($p) { $null = $p.WaitForExit(1000 * [int]$env:VEX_UPDATE_WAIT) }
            if (Get-Process -Id $appPid -ErrorAction SilentlyContinue) {
                Say 'it is still running; nothing was updated'
                Set-Content -LiteralPath $env:VEX_UPDATE_RESULT -Value 1001
                exit 1
            }

            Say "running $env:VEX_UPDATE_UPDATER -Yes"
            & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $env:VEX_UPDATE_UPDATER -Yes 2>&1 |
                ForEach-Object { Say ([string]$_) }
            $code = $LASTEXITCODE
            Say "the updater exited with $code"
            Set-Content -LiteralPath $env:VEX_UPDATE_RESULT -Value $code

            # Start it again whether or not the update took: the old version is better than nothing open.
            $quoted = @($args | ForEach-Object { '"' + ([string]$_ -replace '(\\\\*)"', '$1$1\\"' -replace '(\\\\+)$', '$1$1') + '"' })
            if ($quoted.Count -gt 0) { Start-Process -FilePath $env:VEX_UPDATE_EXE -ArgumentList ($quoted -join ' ') }
            else { Start-Process -FilePath $env:VEX_UPDATE_EXE }
            Say 'started it again'
            """;
}
