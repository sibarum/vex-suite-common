package dev.sibarum.suite.git;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The {@code git} executable, run as a process with pipes.
 *
 * <p><b>It is git, not a reimplementation of it.</b> The user's config, hooks, {@code fsmonitor}, sparse checkouts
 * and LFS all behave exactly as they would at a prompt, and the machine-readable formats asked for here
 * ({@code --porcelain=v2 -z}) are documented as stable. Git not being installed is not an error: every query answers
 * "nothing known", and the application carries on without it, as it does without Pix.
 *
 * <p><b>Every method blocks.</b> Callers run these off the frame loop, as they do a folder listing.
 *
 * <p><b>Read-only queries never take a lock.</b> {@code git status} normally refreshes the index as a side effect,
 * which takes {@code index.lock}; run in the background while the user runs git themselves, that is how a
 * {@code git commit} at the prompt comes to fail with "another git process seems to be running".
 * {@code GIT_OPTIONAL_LOCKS=0} turns the refresh off, which is what it exists for. Nothing here ever prompts either.
 */
public final class Git {

    /** How long one call may take before it is killed. Status on a very large repository is the slow one. */
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String executable;

    /** The {@code git} found on the {@code PATH}, as a prompt would find it. */
    public static Git onPath() {
        return new Git("git");
    }

    /** A particular executable; for a test, or a git that is not on the {@code PATH}. */
    public Git(String executable) {
        this.executable = executable;
    }

    /**
     * One run of git: what was asked, where, and what came back. Kept whole, so that something showing the user what
     * git did (a console) has the exact command, its output and its exit code.
     *
     * @param command the full command line, the executable first
     * @param dir     the working directory it ran in
     * @param exit    the exit code; -1 if it was killed for taking too long
     * @param out     standard output, as bytes: {@code -z} output is NUL-separated and not text
     * @param err     standard error
     * @param took    how long it ran
     */
    public record Result(List<String> command, Path dir, int exit, byte[] out, String err, Duration took) {

        public boolean ok() {
            return exit == 0;
        }

        /** Standard output as UTF-8, which is what git writes paths in. */
        public String text() {
            return new String(out, StandardCharsets.UTF_8);
        }
    }

    /**
     * Run {@code git args…} in {@code dir}. Throws only if git could not be started at all: not installed, or
     * {@code dir} is not a directory. A git that ran and failed is a {@link Result} with a non-zero exit.
     */
    public Result run(Path dir, List<String> args) throws IOException {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable);
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
        pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        long start = System.nanoTime();
        Process p = pb.start();
        p.getOutputStream().close();
        // Both pipes are drained at once: a process whose stderr fills its pipe stops writing stdout, and waits.
        StringBuilder err = new StringBuilder();
        Thread errors = Thread.ofVirtual().start(() -> err.append(drain(p.getErrorStream())));
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                if (!p.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                // the run finished first and stopped the watch
            }
        });
        byte[] out;
        try (InputStream in = p.getInputStream()) {
            out = in.readAllBytes();
        }
        int exit;
        try {
            boolean done = p.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
            }
            exit = done ? p.exitValue() : -1;
            errors.join();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            exit = -1;
        } finally {
            watchdog.interrupt();
        }
        return new Result(List.copyOf(command), dir, exit, out, err.toString(),
                Duration.ofNanos(System.nanoTime() - start));
    }

    /**
     * The state of the working tree under {@code folder}, or empty if it is not inside a repository, git is not
     * installed, or git failed. Only {@code folder} and what is beneath it is asked about, so a listing deep in a
     * large repository costs what its own subtree costs.
     */
    public Optional<Status> status(Path folder) {
        try {
            Result top = run(folder, List.of("rev-parse", "--show-toplevel"));
            if (!top.ok()) {
                return Optional.empty();
            }
            Path root = Path.of(top.text().strip());
            Result st = run(folder, List.of("status", "--porcelain=v2", "-z", "--branch",
                    "--untracked-files=normal", "--ignored=matching", "--", "."));
            if (!st.ok()) {
                return Optional.empty();
            }
            return Optional.of(Porcelain.parse(root, st.out()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String drain(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
