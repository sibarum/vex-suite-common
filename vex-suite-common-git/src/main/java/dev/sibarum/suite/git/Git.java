package dev.sibarum.suite.git;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * {@code GIT_OPTIONAL_LOCKS=0} turns the refresh off, which is what it exists for.
 * Git never prompts at a terminal either; the one thing that may ask anything is a push, through the credential
 * manager's own window ({@link Push}).
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
        return run(dir, args, stdin, timeout, Map.of());
    }

    /** As {@link #run(Path, List, String, Duration)}, with {@code env} added to git's environment. */
    private Result run(Path dir, List<String> args, String stdin, Duration timeout, Map<String, String> env)
            throws IOException {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable);
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile());
        pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
        // Git's own prompt only: a credential helper's window, Git Credential Manager's, is still allowed.
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().putAll(env);
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

    // ------------------------------------------------------------------ writes

    /**
     * Stage {@code paths}, relative to {@code dir}, as they are on the disk: changed, new, or gone ({@code add -A}, so a
     * deletion is staged too). {@code "."} is everything under {@code dir}. The whole {@link Result}, for whatever
     * shows the user what git did; a git that could not be started is one with exit -1 and the reason as its error.
     */
    public Result stage(Path dir, List<String> paths) {
        List<String> args = new ArrayList<>(List.of("add", "-A", "--"));
        args.addAll(paths);
        return write(dir, args);
    }

    /**
     * Take {@code paths}, relative to {@code dir}, out of what is staged, leaving the working tree as it is:
     * {@code restore --staged}, or before the first commit, when there is nothing to restore from,
     * {@code rm --cached}, which is the same thing then.
     */
    public Result unstage(Path dir, List<String> paths) {
        boolean born;
        try {
            born = run(dir, List.of("rev-parse", "--verify", "--quiet", "HEAD")).ok();
        } catch (IOException | RuntimeException e) {
            born = true;   // the write below fails the same way, and says why
        }
        List<String> args = new ArrayList<>(born ? List.of("restore", "--staged", "--")
                : List.of("rm", "-r", "-q", "--cached", "--"));
        args.addAll(paths);
        return write(dir, args);
    }

    /**
     * Commit what is staged, with {@code message} as it was typed: given on standard input ({@code -F -}), so a
     * message of several lines never passes through the platform's quoting of arguments. Git's own cleanup applies,
     * as at a prompt with {@code -F}: trailing spaces and blank lines at either end go, and nothing else. The
     * repository's hooks run, so it may take up to {@link #COMMIT_TIMEOUT}; one that fails commits nothing, and what
     * it printed is the {@link Result}'s.
     */
    public Result commit(Path dir, String message) {
        return write(dir, List.of("commit", "-F", "-"), message, COMMIT_TIMEOUT);
    }

    /** How long a commit may take: its hooks may run a linter or the tests. */
    public static final Duration COMMIT_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Push the branch checked out to where it pushes ({@code git push}, as at a prompt: its upstream, or what
     * {@code push.default} says). An installed SSH key is preferred, and Git Credential Manager is the fallback
     * ({@link Push}): the {@link Result} is the last way tried. It may take up to {@link #PUSH_TIMEOUT}, which is long
     * enough for a person to sign in.
     */
    public Result push(Path dir) {
        return push(dir, Push.identity(Path.of(System.getProperty("user.home"), ".ssh")));
    }

    /** {@link #push(Path)}, told whether a key is installed. */
    Result push(Path dir, boolean identity) {
        Result last = null;
        for (Push.Way way : Push.ways(pushUrl(dir), identity)) {
            List<String> args = new ArrayList<>(way.config());
            args.add("push");
            last = write(dir, args, null, PUSH_TIMEOUT, way.ssh() ? batchSsh(dir) : Map.of());
            if (last.ok() || !way.ssh() || !Push.unreachable(last)) {
                return last;
            }
        }
        return last;
    }

    /** How long a push may take: the network, the remote's hooks, and a person answering the credential manager. */
    public static final Duration PUSH_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Where a push of the branch checked out goes, with any {@code insteadOf} applied; null if it is detached, has no
     * remote to push to, or git failed.
     */
    String pushUrl(Path dir) {
        try {
            Result head = run(dir, List.of("symbolic-ref", "-q", "HEAD"));
            if (!head.ok()) {
                return null;
            }
            Result remote = run(dir, List.of("for-each-ref", "--format=%(push:remotename)", head.text().strip()));
            String name = remote.ok() ? remote.text().strip() : "";
            if (name.isEmpty()) {
                return null;
            }
            Result url = run(dir, List.of("remote", "get-url", "--push", name));
            return url.ok() ? url.text().strip() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Over SSH nothing may prompt, since nobody sees the prompt: {@code BatchMode}, unless the person chose their own
     * ssh ({@code GIT_SSH_COMMAND}, {@code GIT_SSH}, {@code core.sshCommand}), which is theirs to keep.
     */
    private Map<String, String> batchSsh(Path dir) {
        if (System.getenv("GIT_SSH_COMMAND") != null || System.getenv("GIT_SSH") != null) {
            return Map.of();
        }
        try {
            if (run(dir, List.of("config", "core.sshCommand")).ok()) {
                return Map.of();
            }
        } catch (IOException | RuntimeException e) {
            // no config is no choice made
        }
        return Map.of("GIT_SSH_COMMAND", "ssh -o BatchMode=yes -o ConnectTimeout=15");
    }

    /**
     * Check out {@code branch} ({@code git switch}), which refuses, and says which files, rather than overwrite a
     * change not committed. A remote's branch with no local one of its name gets one that tracks it, git's own guess.
     */
    public Result switchTo(Path dir, String branch) {
        return write(dir, List.of("switch", branch));
    }

    private Result write(Path dir, List<String> args) {
        return write(dir, args, null, TIMEOUT);
    }

    private Result write(Path dir, List<String> args, String stdin, Duration timeout) {
        return write(dir, args, stdin, timeout, Map.of());
    }

    /** A write, as a {@link Result} either way: one that could not start is exit -1 with the reason as its error. */
    private Result write(Path dir, List<String> args, String stdin, Duration timeout, Map<String, String> env) {
        try {
            return run(dir, args, stdin, timeout, env);
        } catch (IOException | RuntimeException e) {
            List<String> command = new ArrayList<>(args.size() + 1);
            command.add(executable);
            command.addAll(args);
            return new Result(List.copyOf(command), dir, -1, new byte[0], String.valueOf(e.getMessage()),
                    Duration.ZERO);
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
