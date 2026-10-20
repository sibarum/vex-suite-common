package dev.sibarum.suite.tty;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Everything about pseudo-terminals that differs per operating system, provided by that OS's module
 * ({@code vex-suite-common-pty-windows}, {@code -linux}) through {@link ServiceLoader}. This module holds no platform
 * knowledge: the consumer's OS-activated build profile put exactly one of them on the class path, and that one
 * answers.
 */
public interface PtyPlatform {

    /** A shell a terminal can offer: an id to remember the choice by, the name to show, and its command line. */
    record Shell(String id, String label, String commandLine) {
    }

    /** Starts {@code commandLine} on a new pseudo-terminal; see {@link Pty#start(String, int, int, String, boolean)}. */
    Pty start(String commandLine, int cols, int rows, String cwd, boolean inheritCursor);

    /** The shells installed here, the default first. Never empty. */
    List<Shell> shells();

    /** The command line that runs one line of this platform's shell and exits with its status. */
    String runLine(String line);

    /** The one platform on the class path. */
    static PtyPlatform current() {
        return Found.INSTANCE;
    }

    /** Lazily, so a process that never opens a terminal never looks. */
    final class Found {
        private static final PtyPlatform INSTANCE = choose(ServiceLoader.load(PtyPlatform.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList());

        private Found() {
        }

        static PtyPlatform choose(List<PtyPlatform> found) {
            if (found.size() != 1) {
                throw new IllegalStateException((found.isEmpty() ? "no" : found.size() + "")
                        + " pty OS modules on the class path; exactly one of vex-suite-common-pty-windows or "
                        + "vex-suite-common-pty-linux is needed, which vex-suite-common's OS-activated profile adds");
            }
            return found.getFirst();
        }
    }
}
