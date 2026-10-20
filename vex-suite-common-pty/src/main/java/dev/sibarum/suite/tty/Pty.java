package dev.sibarum.suite.tty;

import java.nio.charset.StandardCharsets;

/**
 * A child program attached to a pseudo-terminal: it believes it has a real terminal, and we get a stream of VT
 * sequences out and give a stream of VT/UTF-8 in.
 *
 * <p>Platform-neutral: {@link #start} asks the one {@link PtyPlatform} on the class path (ConPTY on Windows, a
 * {@code posix_openpt} pty on Linux) to make one. The command line is that platform's, as {@link PtyPlatform#shells}
 * and {@link PtyPlatform#runLine} give them.
 */
public interface Pty extends AutoCloseable {

    /** Starts {@code commandLine} on a new pseudo-terminal of the given size, in {@code cwd} (null: inherit). */
    static Pty start(String commandLine, int cols, int rows, String cwd) {
        return start(commandLine, cols, rows, cwd, false);
    }

    /**
     * As {@link #start(String, int, int, String)}; with {@code inheritCursor}, the program starts where the
     * terminal's cursor already is rather than on a cleared screen, so what the terminal showed before it stays
     * above its output. On Windows the console then asks where the cursor is (a {@code CSI 6n}) before it writes
     * anything and waits for the answer, so the terminal must be fed this pty's output and reply to it; a Unix pty
     * never clears the screen of its own accord, so there it changes nothing.
     */
    static Pty start(String commandLine, int cols, int rows, String cwd, boolean inheritCursor) {
        return PtyPlatform.current().start(commandLine, cols, rows, cwd, inheritCursor);
    }

    /** Blocks for output. Returns the byte count, or -1 once the program and its terminal are gone. */
    int read(byte[] buf);

    /** Sends {@code data} to the program as if typed. Does nothing once closed. */
    void write(byte[] data);

    /** Sends {@code text}, as UTF-8. */
    default void write(String text) {
        write(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Tells the program its terminal is now {@code cols} by {@code rows}. */
    void resize(int cols, int rows);

    /** Blocks until the program exits and returns its exit code. */
    int waitFor();

    /** Ends the program, if it is still running, and releases the terminal. Idempotent. */
    @Override
    void close();
}
