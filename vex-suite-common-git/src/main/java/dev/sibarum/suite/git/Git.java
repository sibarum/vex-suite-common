package dev.sibarum.suite.git;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
        return run(dir, args, null, TIMEOUT);
    }

    /**
     * Run {@code git args…} in {@code dir} with {@code stdin} written to its standard input, and killed after
     * {@code timeout}. For a commit's message on {@code -F -}, which then never passes through the platform's quoting
     * of arguments, and for what may take longer than {@link #TIMEOUT}: a commit's hooks, a push. A null
     * {@code stdin} is an empty one.
     */
    public Result run(Path dir, List<String> args, String stdin, Duration timeout) throws IOException {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable);
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
        pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        long start = System.nanoTime();
        Process p = pb.start();
        // Written from a thread of its own, as the pipes are drained: git may fill stdout before it reads its input.
        Thread input = Thread.ofVirtual().start(() -> {
            try (OutputStream in = p.getOutputStream()) {
                if (stdin != null) {
                    in.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                // git stopped reading, or never started to; what it did is in its exit and its stderr
            }
        });
        // Both pipes are drained at once: a process whose stderr fills its pipe stops writing stdout, and waits.
        StringBuilder err = new StringBuilder();
        Thread errors = Thread.ofVirtual().start(() -> err.append(drain(p.getErrorStream())));
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
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
            boolean done = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
            }
            exit = done ? p.exitValue() : -1;
            errors.join();
            input.join();
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

    /**
     * The repository's own directory, as an absolute path: {@code .git} for a plain checkout, the worktree's own under
     * {@code .git/worktrees} for a linked one. Where {@code index} and {@code HEAD} are, so where a change made by git
     * itself can be seen without running it. Empty outside a repository, or if git failed.
     */
    public Optional<Path> gitDir(Path folder) {
        try {
            Result r = run(folder, List.of("rev-parse", "--absolute-git-dir"));
            return r.ok() ? Optional.of(Path.of(r.text().strip())) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Lines added and removed per tracked path: what is not staged, or with {@code staged} what is. Untracked files
     * are not in it; {@link Numstat#count} counts one. Nothing, rather than an error, if git failed.
     *
     * @param root the repository's top directory, {@link Status#root}
     */
    public Numstat numstat(Path root, boolean staged) {
        List<String> args = new ArrayList<>(List.of("diff", "--numstat", "-z", "--no-color", "--no-ext-diff",
                "--no-textconv"));
        if (staged) {
            args.add("--cached");
        }
        try {
            Result r = run(root, args);
            return r.ok() ? Numstat.parse(r.out()) : Numstat.none();
        } catch (IOException | RuntimeException e) {
            return Numstat.none();
        }
    }

    /**
     * One file's diff: not staged, staged, or the whole of an untracked file. {@link Diff#none()} if there is no
     * difference or git failed. Only git's own diff is asked for, whatever the user's config says: no colour, no
     * external diff tool, no text conversion, so what comes back is what {@link Diff#parse} reads.
     *
     * @param root the repository's top directory, {@link Status#root}
     * @param path the file, as {@link Status.Change#path} has it
     */
    public Diff diff(Path root, String path, Diff.Side side) {
        if (side == Diff.Side.UNTRACKED) {
            // Git has nothing to compare an untracked file with, so it is read here: all of it, as added.
            return Diff.untracked(root.resolve(path));
        }
        List<String> args = new ArrayList<>(List.of("diff", "--no-color", "--no-ext-diff", "--no-textconv"));
        if (side == Diff.Side.STAGED) {
            args.add("--cached");
        }
        args.addAll(List.of("--", path));
        try {
            Result r = run(root, args);
            return r.ok() ? Diff.parse(r.text()) : Diff.none();
        } catch (IOException | RuntimeException e) {
            return Diff.none();
        }
    }

    /**
     * The newest {@code count} commits reachable from {@code ref}, newest first; from {@code HEAD} if {@code ref} is
     * null. Empty before the first commit, for a ref that does not exist, or if git failed.
     *
     * @param root the repository's top directory, {@link Status#root}
     */
    public List<Commit> log(Path root, String ref, int count) {
        try {
            Result r = run(root, List.of("-c", "log.showSignature=false", "log", "-z", "--no-color",
                    "--format=" + Log.FORMAT, "-n", Integer.toString(count), ref == null ? "HEAD" : ref, "--"));
            return r.ok() ? Log.parse(r.out()) : List.of();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Every local branch and every remote-tracking one, local first, each in name order. Empty before the first
     * commit, or if git failed.
     *
     * @param root the repository's top directory, {@link Status#root}
     */
    public List<Ref> branches(Path root) {
        try {
            Result r = run(root, List.of("for-each-ref", "--format=" + Refs.FORMAT, "refs/heads", "refs/remotes"));
            return r.ok() ? Refs.parse(r.out()) : List.of();
        } catch (IOException | RuntimeException e) {
            return List.of();
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
