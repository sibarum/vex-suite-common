package dev.sibarum.suite.tty.linux;

import dev.sibarum.suite.tty.Pty;
import dev.sibarum.suite.tty.PtyPlatform;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pseudo-terminals on Linux: {@link LinuxPty}, and the shells this machine has. */
public final class LinuxPtyPlatform implements PtyPlatform {

    /** Offered in this order after the login shell, when installed. */
    private static final List<String> KNOWN = List.of("bash", "zsh", "fish", "sh");

    @Override
    public Pty start(String commandLine, int cols, int rows, String cwd, boolean inheritCursor) {
        // inheritCursor needs nothing here: a Unix pty never clears the screen of its own accord.
        return LinuxPty.start(commandLine, cols, rows, cwd);
    }

    /**
     * The user's login shell ({@code $SHELL}) first, then the common ones that are installed, each once whichever
     * path it is reached by. Each runs as a login shell ({@code -l}), as a terminal window's shell does, so the
     * profile that sets up the user's {@code PATH} is read.
     */
    @Override
    public List<Shell> shells() {
        List<Shell> found = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        String login = System.getenv("SHELL");
        if (login != null && !login.isBlank()) {
            add(found, seen, Path.of(login));
        }
        for (String name : KNOWN) {
            Path p = onPath(name);
            if (p != null) add(found, seen, p);
        }
        if (found.isEmpty()) {
            found.add(new Shell("sh", "sh", "/bin/sh -l"));
        }
        return List.copyOf(found);
    }

    private static void add(List<Shell> found, Set<Path> seen, Path shell) {
        Path real;
        try {
            real = shell.toRealPath();
        } catch (IOException e) {
            return;   // not installed after all
        }
        if (!seen.add(real)) return;
        String name = shell.getFileName().toString();
        if (found.stream().anyMatch(s -> s.id().equals(name))) return;
        found.add(new Shell(name, name, shell + " -l"));
    }

    /** {@code /bin/sh -c 'line'}, the line quoted so that the shell sees exactly it. */
    @Override
    public String runLine(String line) {
        return "/bin/sh -c '" + line.replace("'", "'\\''") + "'";
    }

    private static Path onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path p = Path.of(dir, exe);
            if (Files.isExecutable(p) && !Files.isDirectory(p)) return p;
        }
        return null;
    }
}
