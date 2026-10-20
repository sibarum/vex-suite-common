package dev.sibarum.suite.tty.linux;

import dev.sibarum.suite.tty.Pty;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * A child program on a Linux pseudo-terminal.
 *
 * <p>The pty is made here, through libc ({@code posix_openpt}, {@code grantpt}, {@code unlockpt}, {@code ptsname_r}),
 * and the program is <em>not</em> forked from here: forking a running JVM and doing anything but {@code exec} in the
 * child is not safe. {@link ProcessBuilder} starts it instead, with the pty's other end as its stdin, stdout and
 * stderr, under {@code setsid --ctty} (util-linux): a session of its own, with that pty as its controlling terminal.
 * So job control works, Ctrl+C (a 0x03 written here) interrupts what is in front, and a resize reaches the program as
 * {@code SIGWINCH}. Closing ends the session with {@code SIGHUP}, which is what a terminal window closing means.
 *
 * <p>The output ends ({@link #read} returns -1) once no process has the pty open any more: the kernel then answers
 * the master with {@code EIO}.
 */
final class LinuxPty implements Pty {

    private static final int O_RDWR = 0x2;
    private static final int O_NOCTTY = 0x100;
    private static final int O_CLOEXEC = 0x80000;
    private static final long TIOCSWINSZ = 0x5414;
    private static final int SIGHUP = 1;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = SymbolLookup.libraryLookup("libc.so.6", Arena.global());

    private static final MethodHandle POSIX_OPENPT = fn("posix_openpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle GRANTPT = fn("grantpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle UNLOCKPT = fn("unlockpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle PTSNAME_R = fn("ptsname_r",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));
    /** {@code ioctl} is variadic: everything after the request is passed as a variadic argument. */
    private static final MethodHandle IOCTL = LINKER.downcallHandle(LIBC.findOrThrow("ioctl"),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS), Linker.Option.firstVariadicArg(2));
    private static final MethodHandle READ = fn("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle WRITE = fn("write",
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle CLOSE = fn("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle KILL = fn("kill", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));

    private final int master;
    private final Process process;
    private volatile boolean closed;

    private LinuxPty(int master, Process process) {
        this.master = master;
        this.process = process;
    }

    /** Starts {@code commandLine} (a {@code /bin/sh} line) on a new pty of the given size, in {@code cwd} (null: inherit). */
    static LinuxPty start(String commandLine, int cols, int rows, String cwd) {
        int master = call(POSIX_OPENPT, O_RDWR | O_NOCTTY | O_CLOEXEC);
        if (master < 0) {
            throw new IllegalStateException("posix_openpt failed");
        }
        try {
            check(call(GRANTPT, master), "grantpt");
            check(call(UNLOCKPT, master), "unlockpt");
            String slave;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment name = a.allocate(128);
                check((int) PTSNAME_R.invokeExact(master, name, 128L), "ptsname_r");
                slave = name.getString(0);
            }
            setSize(master, cols, rows);

            File tty = new File(slave);
            ProcessBuilder pb = new ProcessBuilder("setsid", "--ctty", "/bin/sh", "-c", "exec " + commandLine)
                    .redirectInput(tty)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(tty))
                    .redirectError(ProcessBuilder.Redirect.appendTo(tty));
            if (cwd != null) {
                pb.directory(new File(cwd));
            }
            pb.environment().put("TERM", "xterm-256color");
            pb.environment().put("COLORTERM", "truecolor");
            return new LinuxPty(master, pb.start());
        } catch (Throwable t) {
            closeFd(master);
            if (t instanceof IOException e) {
                throw new IllegalStateException("could not start " + commandLine + ": " + e.getMessage(), e);
            }
            throw t instanceof RuntimeException r ? r : new IllegalStateException(t);
        }
    }

    @Override
    public int read(byte[] buf) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment mem = a.allocate(buf.length);
            long n = (long) READ.invokeExact(master, mem, (long) buf.length);
            if (n <= 0) {
                return -1;   // EIO: nobody has the pty open any more
            }
            MemorySegment.copy(mem, ValueLayout.JAVA_BYTE, 0, buf, 0, (int) n);
            return (int) n;
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public synchronized void write(byte[] data) {
        if (closed || data.length == 0) return;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment mem = a.allocateFrom(ValueLayout.JAVA_BYTE, data);
            long off = 0;
            while (off < data.length) {
                long n = (long) WRITE.invokeExact(master, mem.asSlice(off), (long) data.length - off);
                if (n <= 0) return;
                off += n;
            }
        } catch (Throwable t) {
            // A program that has gone takes no more input; that is not the caller's failure.
        }
    }

    @Override
    public void resize(int cols, int rows) {
        if (!closed) setSize(master, cols, rows);
    }

    @Override
    public int waitFor() {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * Hang up: {@code SIGHUP} to the program's process group (an interactive shell ignores {@code SIGTERM}, never a
     * hangup), then, off the caller's thread, a kill if it has not gone within a second, and only then the master.
     * Closed last so the reader sees the end of the output rather than a descriptor that has vanished under it.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        int pid = (int) process.pid();
        call(KILL, -pid, SIGHUP);
        Thread t = new Thread(() -> {
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly().waitFor(1, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                closeFd(master);
            }
        }, "pty-close");
        t.setDaemon(true);
        t.start();
    }

    // -- plumbing ---------------------------------------------------------------------------------------------

    /** {@code struct winsize}: rows, columns, and two pixel sizes nobody reads. */
    private static void setSize(int fd, int cols, int rows) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ws = a.allocate(JAVA_SHORT, 4);
            ws.setAtIndex(JAVA_SHORT, 0, (short) rows);
            ws.setAtIndex(JAVA_SHORT, 1, (short) cols);
            int ignored = (int) IOCTL.invokeExact(fd, TIOCSWINSZ, ws);
        } catch (Throwable t) {
            // A size the program never hears about is a cosmetic loss, not a broken terminal.
        }
    }

    private static void closeFd(int fd) {
        call(CLOSE, fd);
    }

    private static MethodHandle fn(String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(LIBC.findOrThrow(name), fd);
    }

    private static void check(int rc, String what) {
        if (rc != 0) throw new IllegalStateException(what + " failed");
    }

    private static int call(MethodHandle h, Object... args) {
        try {
            return (int) h.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
