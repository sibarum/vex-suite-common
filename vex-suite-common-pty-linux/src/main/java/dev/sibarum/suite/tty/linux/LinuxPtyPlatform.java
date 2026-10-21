package dev.sibarum.suite.tty.linux;

import dev.sibarum.suite.tty.Pty;
import dev.sibarum.suite.tty.PtyPlatform;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** Single quotes, inside which nothing is special but the quote itself. */
    @Override
    public String quote(String word) {
        return word.matches("[\\w.:/=+,@%-]+") ? word : "'" + word.replace("'", "'\\''") + "'";
    }

    /**
     * An executable regular file that is an ELF binary or starts {@code #!}. The executable bit alone does not decide
     * it: a VM's shared folder shows every file as executable.
     */
    @Override
    public Optional<String> programLine(Path file) {
        if (!Files.isRegularFile(file) || !Files.isExecutable(file)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(4);
            boolean elf = head.length == 4 && head[0] == 0x7F && head[1] == 'E' && head[2] == 'L' && head[3] == 'F';
            boolean script = head.length >= 2 && head[0] == '#' && head[1] == '!';
            return elf || script ? Optional.of(quote(file.toAbsolutePath().toString())) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Path> onPath(String name, Map<String, String> env) {
        String path = env.get("PATH");
        if (path == null) return Optional.empty();
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path p = Path.of(dir, name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return Optional.of(p);
        }
        return Optional.empty();
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
