package dev.sibarum.suite.update;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * One update of the suite, from the press of the button to the hand-over to the script: the other applications to be
 * updated are asked to close, one at a time, and the script is started for those that did. No GUI; {@link #run}
 * blocks for as long as the closing takes, and tells a {@link Progress} what it is waiting for.
 *
 * <p><b>Asking, never forcing.</b> Each application is asked as its close button would ask it, so one with unsaved work
 * asks its user first, and its user may say no. Nothing here ends a process. While one stays open, its user decides,
 * from any thread: {@link #show} brings it to the front, {@link #skip} updates the rest without it, and
 * {@link #cancel} updates nothing and opens again what was closed. Or the user closes it there, and the update goes on.
 *
 * <p><b>One at a time</b>, so there is never more than one "save changes?" waiting, and the one being waited for is
 * always the one the words name. An application with two windows open in two processes is waited for until both
 * have gone.
 */
public final class SuiteUpdate {

    /** How long an application may take to close before its user is asked what to do about it. */
    static final Duration GRACE = Duration.ofSeconds(3);

    /** How often an application that is still open is looked at again, and one with no window asked again. */
    static final Duration POLL = Duration.ofMillis(200);

    /** What {@link #run} is doing, for the words the user reads. Called on {@link #run}'s thread. */
    public interface Progress {

        /** {@code app} has been asked to close. */
        void closing(Install app);

        /**
         * {@code app} has not closed after a while. {@code asked} is whether it had a window to ask: when it did, it
         * is most likely asking about unsaved changes; when not, it is starting, busy, or hung.
         */
        void stillOpen(Install app, boolean asked);
    }

    /** How a run ended. */
    public sealed interface Result {

        /** The script is running: {@code update} is being updated. {@code self} is whether this application is. */
        record Started(Process script, List<Install> update, boolean self) implements Result {
        }

        /** Another update is under way already. Nothing was closed. */
        record Busy() implements Result {
        }

        /** The user cancelled. What had closed was opened again. */
        record Cancelled() implements Result {
        }

        /** Every application to be updated was skipped. What had closed was opened again. */
        record Nothing() implements Result {
        }

        /** The script could not start. What had closed was opened again; nothing was updated. */
        record Failed(IOException why) implements Result {
        }
    }

    private enum Answer { WAIT, SKIP, CANCEL }

    private final Updates updates;
    private final Function<Install, List<ProcessHandle>> processes;
    private final ToIntFunction<ProcessHandle> ask;
    private final Duration grace;

    private volatile Answer answer = Answer.WAIT;
    private volatile ProcessHandle waitingFor;

    public SuiteUpdate(Updates updates) {
        this(updates, Install::processes, Updates::askToClose, GRACE);
    }

    SuiteUpdate(Updates updates, Function<Install, List<ProcessHandle>> processes, ToIntFunction<ProcessHandle> ask,
                Duration grace) {
        this.updates = updates;
        this.processes = processes;
        this.ask = ask;
        this.grace = grace;
    }

    /** Bring the application being waited for to the front. Any thread; nothing when none is. */
    public boolean show() {
        ProcessHandle p = waitingFor;
        return p != null && Updates.bringForward(p);
    }

    /** Update the others without the application being waited for. Any thread. */
    public void skip() {
        answer = Answer.SKIP;
    }

    /** Update nothing, and open again what was closed. Any thread. */
    public void cancel() {
        answer = Answer.CANCEL;
    }

    /**
     * Close the other applications of {@code available} that are open, then hand over to the script, which updates
     * them all and starts again those that were open, this one with {@code args}. When this application is among
     * them, the caller closes it as soon as this returns {@link Result.Started} with {@code self}.
     */
    public Result run(List<Updates.Available> available, List<String> args, Progress progress)
            throws InterruptedException {
        Install self = updates.install();
        List<Install> closed = new ArrayList<>();
        List<Install> update = new ArrayList<>();
        try (Updates.Lock lock = updates.lock().orElse(null)) {
            if (lock == null) {
                return new Result.Busy();
            }
            for (Updates.Available a : available) {
                Install app = a.install();
                if (app.id().equals(self.id())) {
                    update.add(app);
                    continue;
                }
                List<ProcessHandle> open = processes.apply(app);
                if (open.isEmpty()) {
                    update.add(app);
                    continue;
                }
                progress.closing(app);
                switch (close(app, open, progress)) {
                    case WAIT -> {
                        closed.add(app);
                        update.add(app);
                    }
                    case SKIP -> answer = Answer.WAIT;
                    case CANCEL -> {
                        reopen(closed);
                        return new Result.Cancelled();
                    }
                }
            }
            if (update.isEmpty()) {
                reopen(closed);
                return new Result.Nothing();
            }
            // Let go just before the script takes it: it waits for the file, and this process may outlive it.
            lock.close();
            try {
                Process script = updates.handover(update, closed, args);
                return new Result.Started(script, List.copyOf(update),
                        update.stream().anyMatch(i -> i.id().equals(self.id())));
            } catch (IOException e) {
                reopen(closed);
                return new Result.Failed(e);
            }
        } finally {
            waitingFor = null;
        }
    }

    /**
     * Ask each of {@code open} to close, and wait until they have all gone ({@code WAIT}), or the user says to skip
     * this application or to cancel.
     */
    private Answer close(Install app, List<ProcessHandle> open, Progress progress) throws InterruptedException {
        List<ProcessHandle> left = new ArrayList<>(open);
        for (ProcessHandle p : left) {
            waitingFor = p;
            boolean asked = ask.applyAsInt(p) > 0;
            long told = System.nanoTime() + grace.toNanos();
            boolean said = false;
            while (p.isAlive()) {
                Answer a = answer;
                if (a != Answer.WAIT) {
                    return a;
                }
                if (!said && System.nanoTime() > told) {
                    progress.stillOpen(app, asked);
                    said = true;
                }
                Thread.sleep(POLL.toMillis());
                if (!asked && p.isAlive() && ask.applyAsInt(p) > 0) {
                    // It was still starting, say, and its window has come up since: it has been asked now.
                    asked = true;
                    progress.closing(app);
                    told = System.nanoTime() + grace.toNanos();
                    said = false;
                }
            }
        }
        return Answer.WAIT;
    }

    private static void reopen(List<Install> closed) {
        for (Install app : closed) {
            try {
                Updates.reopen(app);
            } catch (IOException e) {
                // It is on the Start menu, as it always was; nothing better to do about it here.
            }
        }
    }
}
