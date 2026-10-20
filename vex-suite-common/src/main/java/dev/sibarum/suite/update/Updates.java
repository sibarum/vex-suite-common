package dev.sibarum.suite.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Whether newer versions of the running application and the rest of its suite have been published, and installing
 * them without the user going to a prompt: the applications close, the installer's own updaters replace them, and
 * the ones that were open open again.
 *
 * <p><b>The installer does the installing.</b> Nothing here downloads a binary or writes into an install folder.
 * {@code update.ps1}, which the installer put beside each executable, fetches the published {@code install.ps1},
 * refuses it unless it matches the SHA-256 in the manifest, and runs it; that installer in turn refuses any download
 * whose hash does not match. So an update from inside an application is exactly {@code update.cmd -Yes} for each
 * application updated, checked exactly as carefully, and keeps the install records, shortcuts, file types and Apps
 * entries as the installer keeps them.
 *
 * <p><b>The suite.</b> Every install whose record names the same publisher as this one's: the installer records each
 * application of a suite as an install of its own, and the publisher is what they share. {@link #survey} checks all
 * of them at once, so whichever application the user is in offers every update there is.
 *
 * <p><b>Why the applications close first.</b> Windows will not replace an executable that is running. The other
 * applications of the suite are asked to close first ({@link SuiteUpdate}), as their own close buttons would ask
 * them, so each can ask about unsaved changes. Then {@link #handover} starts a small script, hidden, that runs the
 * updaters of the ones that have closed, starts them again, then waits for this process to exit, updates it, and
 * starts it again too (the old version, if an update failed: the user is never left with less open than before).
 * The next start reads what the script wrote with {@link #outcome}, so it can say whether the updates took.
 *
 * <p><b>Only an installed copy is updated.</b> {@link #of} is empty when the application is not running from the
 * folder an install record names: a build run from source, a jar, a copy elsewhere. Windows only, as the installer is.
 *
 * <p><b>Every method that touches the network, a file or another process blocks.</b> Callers run them off the frame
 * loop.
 */
public final class Updates {

    /** How long a check may take. It is one small file; a slow network is no reason to hold a thread. */
    static final Duration TIMEOUT = Duration.ofSeconds(20);

    /**
     * How long the script waits for this application to exit before it gives up and leaves it as it is. Long, because
     * the application may be asking its user whether to save, and the user may be away; the other applications are
     * updated and open again meanwhile.
     */
    static final int EXIT_WAIT_S = 15 * 60;

    /** What the script records for an application that never exited, so was not updated. */
    static final int NOT_CLOSED = 1001;

    /** What the script records for an application it did not update because another update held the lock. */
    static final int BUSY = 1002;

    private final Install install;
    private final Path registry;
    private final Path root;

    /**
     * @param registry where the installer keeps its records
     * @param root     where the scripts, logs and results are kept, a folder for each install: outside the install
     *                 folders, which the installer owns
     */
    Updates(Install install, Path registry, Path root) {
        this.install = install;
        this.registry = registry;
        this.root = root;
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
                .map(i -> new Updates(i, registryDir, registryDir.toAbsolutePath().getParent().resolve("updates")));
    }

    /** This application's install. */
    public Install install() {
        return install;
    }

    /** This application and every other install of its suite, in the order of their ids. */
    public List<Install> suite() {
        List<Install> out = new ArrayList<>();
        boolean self = false;
        for (Install i : Install.all(registry)) {
            if (i.id().equals(install.id())) {
                out.add(install);
                self = true;
            } else if (install.suiteOf(i)) {
                out.add(i);
            }
        }
        if (!self) {
            out.addFirst(install);
        }
        return out;
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

    /** A newer version of one application of the suite. */
    public record Available(Install install, Release release) {
    }

    /**
     * What a check of the whole suite found.
     *
     * @param available the applications that have a newer version, in the order of {@link #suite}
     * @param failed    why the others that could not be checked could not, for a log
     */
    public record Survey(List<Available> available, List<String> failed) {
    }

    /** Fetch this application's published manifest and compare its latest version with the one installed. */
    public Check check() {
        return check(install);
    }

    /** {@link #check} for every application of the suite, all at once. Never throws. */
    public Survey survey() {
        List<Install> suite = suite();
        List<Available> available = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try (ExecutorService checks = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Check>> results = new ArrayList<>();
            for (Install i : suite) {
                results.add(checks.submit((Callable<Check>) () -> check(i)));
            }
            for (int k = 0; k < suite.size(); k++) {
                Check c;
                try {
                    c = results.get(k).get();
                } catch (ExecutionException e) {
                    c = new Check.Failed(String.valueOf(e.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    c = new Check.Failed("interrupted");
                }
                switch (c) {
                    case Check.Available a -> available.add(new Available(suite.get(k), a.release()));
                    case Check.Failed f -> failed.add(suite.get(k).name() + ": " + f.why());
                    case Check.Current ignored -> {
                    }
                }
            }
        }
        return new Survey(List.copyOf(available), List.copyOf(failed));
    }

    private static Check check(Install install) {
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

    // ---------------------------------------------------------------- other applications

    /**
     * Ask process {@code p}'s windows to close, as its close button would. Returns how many were asked; 0 when it has
     * no window to ask. Whether it closes is up to it.
     */
    public static int askToClose(ProcessHandle p) {
        return Windows.close(p.pid());
    }

    /** Bring process {@code p}'s window to the front, so its user sees what it is asking. Whether it had one. */
    public static boolean bringForward(ProcessHandle p) {
        return Windows.show(p.pid());
    }

    /**
     * Start {@code app} again, with nothing open: what an update would have done, for an application closed for an
     * update that did not happen after all.
     */
    public static void reopen(Install app) throws IOException {
        new ProcessBuilder(app.executable().toString()).directory(app.dir().toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().getOutputStream().close();
    }

    /**
     * The suite's update, held: while it is, no other application of the suite starts one, and neither does a
     * script another one started. Empty when an update is already under way.
     */
    public Optional<Lock> lock() {
        try {
            Files.createDirectories(root);
            FileChannel channel = FileChannel.open(lockFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock held;
            try {
                held = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                held = null;
            }
            if (held == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new Lock(channel));
        } catch (IOException e) {
            // A script holds the file open, shared with nobody: that is an update under way too.
            return Optional.empty();
        }
    }

    /** The suite's update, held until closed. Closing it twice is closing it once. */
    public static final class Lock implements AutoCloseable {

        private final FileChannel channel;

        private Lock(FileChannel channel) {
            this.channel = channel;
        }

        @Override
        public void close() {
            try {
                channel.close();
            } catch (IOException e) {
                // Closed or not, the lock goes with the channel.
            }
        }
    }

    // ---------------------------------------------------------------- installing

    /**
     * Start the script that updates {@code update}, then starts {@code again} again. Every application in
     * {@code update} other than this one must have closed already; this one, if it is there, is updated once it has
     * exited, and started again with {@code args}, so the caller closes it straight after. Until it does, nothing of
     * it is replaced. The script outlives this process; its {@link Process} says when it has finished, for a caller
     * that is still there to see.
     *
     * @throws IOException if an installer's updater is not in its install folder, an argument cannot be passed, or the
     *                     script could not be written or started. Nothing has changed.
     */
    public Process handover(List<Install> update, List<Install> again, List<String> args) throws IOException {
        Process p = start(update, again, args, ProcessHandle.current().pid(), EXIT_WAIT_S);
        p.getOutputStream().close();
        return p;
    }

    /** {@link #handover}, waiting up to {@code waitS} seconds for process {@code pid}, rather than this one. */
    Process start(List<Install> update, List<Install> again, List<String> args, long pid, int waitS)
            throws IOException {
        for (Install i : update) {
            if (!Files.isRegularFile(i.updater())) {
                throw new IOException("the installer's updater is not in " + i.dir());
            }
        }
        for (String a : args) {
            if (a.indexOf('\t') >= 0 || a.indexOf('\n') >= 0 || a.indexOf('\r') >= 0) {
                throw new IOException("cannot start " + install.name() + " again with " + a);
            }
        }
        StringBuilder plan = new StringBuilder();
        for (Install i : update) {
            boolean self = i.id().equals(install.id());
            boolean start = self || again.stream().anyMatch(a -> a.id().equals(i.id()));
            plan.append(String.join("\t", "app", i.id(), i.updater().toString(), i.executable().toString(),
                    start ? "1" : "0", self ? "1" : "0")).append('\n');
        }
        for (String a : args) {
            plan.append("arg\t").append(a).append('\n');
        }

        Path work = work();
        Files.createDirectories(work);
        Path script = work.resolve("restart.ps1");
        Path planFile = work.resolve("plan.txt");
        Files.writeString(script, SCRIPT, StandardCharsets.US_ASCII);
        Files.writeString(planFile, plan, StandardCharsets.UTF_8);
        Files.deleteIfExists(result());
        Files.deleteIfExists(log());

        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString())
                .directory(work.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("VEX_UPDATE_PID", Long.toString(pid));
        pb.environment().put("VEX_UPDATE_WAIT", Integer.toString(waitS));
        pb.environment().put("VEX_UPDATE_PLAN", planFile.toString());
        pb.environment().put("VEX_UPDATE_LOCK", lockFile().toString());
        pb.environment().put("VEX_UPDATE_LOG", log().toString());
        pb.environment().put("VEX_UPDATE_RESULT", result().toString());
        return pb.start();
    }

    /**
     * How the last update this application started went, once: it is forgotten as it is read. Empty when there was
     * none since.
     */
    public Optional<Report> outcome() {
        Path result = result();
        if (!Files.isRegularFile(result)) {
            return Optional.empty();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(result, StandardCharsets.UTF_8);
            Files.deleteIfExists(result);
        } catch (IOException e) {
            return Optional.empty();
        }
        List<Outcome> apps = new ArrayList<>();
        for (String line : lines) {
            String l = line.startsWith("﻿") ? line.substring(1).strip() : line.strip();
            if (l.isEmpty()) {
                continue;
            }
            // A script from before the suite wrote the exit code alone, for this application.
            String[] f = l.split("\t");
            String id = f.length > 1 ? f[0] : install.id();
            apps.add(outcome(id, code(f[f.length - 1])));
        }
        if (apps.isEmpty()) {
            apps.add(outcome(install.id(), -1));
        }
        return Optional.of(new Report(List.copyOf(apps), log()));
    }

    private Outcome outcome(String id, int exit) {
        Optional<Install> now = Install.find(registry, id);
        return new Outcome(id, now.map(Install::name).orElse(id), exit, now.map(Install::version).orElse(""));
    }

    private static int code(String text) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * How one application's update went.
     *
     * @param exit    its updater's exit code; 0 when it installed (or found nothing to install), {@link #NOT_CLOSED}
     *                when the application never closed and nothing was tried, {@link #BUSY} when another update was
     *                under way
     * @param version the version installed now, as its record says
     */
    public record Outcome(String id, String name, int exit, String version) {

        public boolean ok() {
            return exit == 0;
        }

        /** Whether it was left as it was because it never closed. */
        public boolean stayedOpen() {
            return exit == NOT_CLOSED;
        }
    }

    /**
     * How an update went, application by application.
     *
     * @param log everything the updaters said
     */
    public record Report(List<Outcome> apps, Path log) {

        public boolean ok() {
            return apps.stream().allMatch(Outcome::ok);
        }

        /** This application's outcome, when it was among those updated. */
        public Optional<Outcome> of(String id) {
            return apps.stream().filter(o -> o.id().equals(id)).findFirst();
        }
    }

    private Path work() {
        return root.resolve(install.id());
    }

    private Path log() {
        return work().resolve("update.log");
    }

    private Path result() {
        return work().resolve("result.txt");
    }

    private Path lockFile() {
        return root.resolve("suite.lock");
    }

    /**
     * Updates the applications that have closed and starts them again, then this one once it has exited. Pure ASCII,
     * as the installer's own scripts are, and for Windows PowerShell 5.1. Its inputs come in the environment and a
     * plan file of tab-separated lines, not spliced into the text, so a path with a quote in it is only a path.
     *
     * <p>Plan lines: {@code app <id> <updater> <exe> <start again: 1|0> <this application: 1|0>}, then
     * {@code arg <value>} for each argument this application starts again with. Result lines: {@code <id> <exit>}.
     */
    static final String SCRIPT = """
            # Written by vex-suite-common's Updates: update applications once they have closed, then start them again.
            $ErrorActionPreference = 'Continue'
            $log = $env:VEX_UPDATE_LOG
            function Say([string]$m) { Add-Content -LiteralPath $log -Encoding UTF8 -Value ((Get-Date).ToString('u') + ' ' + $m) }
            function Done([string]$id, [int]$code) { Add-Content -LiteralPath $env:VEX_UPDATE_RESULT -Encoding UTF8 -Value ($id + "`t" + $code) }

            $apps = @()
            $appArgs = @()
            foreach ($line in @(Get-Content -LiteralPath $env:VEX_UPDATE_PLAN -Encoding UTF8)) {
                $f = $line -split "`t"
                if ($f[0] -eq 'app') { $apps += [pscustomobject]@{ Id = $f[1]; Updater = $f[2]; Exe = $f[3]; Again = ($f[4] -eq '1'); Self = ($f[5] -eq '1') } }
                elseif ($f[0] -eq 'arg') { $appArgs += $line.Substring(4) }
            }
            $others = @($apps | Where-Object { -not $_.Self })
            $self = @($apps | Where-Object { $_.Self }) | Select-Object -First 1

            function Again($a, $with) {
                $dir = Split-Path -Parent $a.Exe
                $quoted = @($with | ForEach-Object { '"' + ([string]$_ -replace '(\\\\*)"', '$1$1\\"' -replace '(\\\\+)$', '$1$1') + '"' })
                if ($quoted.Count -gt 0) { Start-Process -FilePath $a.Exe -WorkingDirectory $dir -ArgumentList ($quoted -join ' ') }
                else { Start-Process -FilePath $a.Exe -WorkingDirectory $dir }
                Say "started $($a.Id) again"
            }

            function Update($a) {
                Say "updating $($a.Id): $($a.Updater) -Yes"
                & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $a.Updater -Yes 2>&1 |
                    ForEach-Object { Say ('  ' + [string]$_) }
                $code = $LASTEXITCODE
                Say "$($a.Id): the updater exited with $code"
                Done $a.Id $code
            }

            # One update at a time across the suite: the file is held, shared with nobody, until this script ends.
            $lock = $null
            $until = (Get-Date).AddSeconds([Math]::Min(30, [int]$env:VEX_UPDATE_WAIT))
            while (-not $lock) {
                try { $lock = [IO.File]::Open($env:VEX_UPDATE_LOCK, 'OpenOrCreate', 'ReadWrite', 'None') }
                catch {
                    if ((Get-Date) -gt $until) { break }
                    Start-Sleep -Milliseconds 250
                }
            }
            if (-not $lock) {
                Say 'another update is under way; nothing was updated'
                foreach ($a in $apps) { Done $a.Id 1002 }
                foreach ($a in $others) { if ($a.Again) { Again $a @() } }
                exit 1
            }

            try {
                # The others have closed already. Update them and give them back first: this one may be a while.
                foreach ($a in $others) { Update $a }
                foreach ($a in $others) { if ($a.Again) { Again $a @() } }

                if ($self) {
                    $appPid = [int]$env:VEX_UPDATE_PID
                    Say "waiting for process $appPid to exit"
                    $p = Get-Process -Id $appPid -ErrorAction SilentlyContinue
                    if ($p) { $null = $p.WaitForExit(1000 * [int]$env:VEX_UPDATE_WAIT) }
                    if (Get-Process -Id $appPid -ErrorAction SilentlyContinue) {
                        Say "$($self.Id) is still running; it was not updated"
                        Done $self.Id 1001
                        exit 1
                    }
                    Update $self
                    # Start it again whether or not the update took: the old version is better than nothing open.
                    Again $self $appArgs
                }
            } finally {
                $lock.Close()
            }
            """;
}
