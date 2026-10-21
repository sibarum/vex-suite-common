package dev.sibarum.suite.tty.windows;

import dev.sibarum.suite.tty.Pty;
import dev.sibarum.suite.tty.PtyPlatform;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Pseudo-terminals on Windows: {@link ConPty}, and the shells Windows has. */
public final class WindowsPtyPlatform implements PtyPlatform {

    @Override
    public Pty start(String commandLine, int cols, int rows, String cwd, boolean inheritCursor) {
        return ConPty.start(commandLine, cols, rows, cwd, inheritCursor);
    }

    /** Windows PowerShell is always there; the others are offered only when they can be found. */
    @Override
    public List<Shell> shells() {
        List<Shell> found = new ArrayList<>();
        found.add(new Shell("powershell", "Windows PowerShell", "powershell.exe -NoLogo"));
        if (onPath("pwsh.exe")) found.add(new Shell("pwsh", "PowerShell 7", "pwsh.exe -NoLogo"));
        found.add(new Shell("cmd", "Command Prompt", "cmd.exe"));
        return List.copyOf(found);
    }

    /**
     * {@code cmd /d /s /c "line"}: no AutoRun scripts ({@code /d}), and {@code /s} so the outer quotes are stripped
     * and everything between them is the line, whatever quotes it has of its own.
     */
    @Override
    public String runLine(String line) {
        return "cmd.exe /d /s /c \"" + line + "\"";
    }

    /** Double quotes, which cmd and a program's own argument parsing both take. */
    @Override
    public String quote(String word) {
        return word.matches("[\\w.:\\\\/=-]+") ? word : "\"" + word + "\"";
    }

    /** An {@code .exe} (a PE file, which starts {@code MZ}), a {@code .bat} or {@code .cmd}, or a PowerShell script. */
    @Override
    public Optional<String> programLine(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String path = quote(file.toAbsolutePath().toString());
        String name = String.valueOf(file.getFileName()).toLowerCase(Locale.ROOT);
        if (name.endsWith(".ps1")) {
            return Optional.of("powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File " + path);
        }
        if (name.endsWith(".bat") || name.endsWith(".cmd")) {
            return Optional.of(path);
        }
        if (name.endsWith(".exe")) {
            try (InputStream in = Files.newInputStream(file)) {
                return in.read() == 'M' && in.read() == 'Z' ? Optional.of(path) : Optional.empty();
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** {@code PATH} (or {@code Path}, as Windows spells it) split on {@code ;}, each name tried with {@code PATHEXT}. */
    @Override
    public Optional<Path> onPath(String name, Map<String, String> env) {
        String path = env.get("PATH") != null ? env.get("PATH") : env.get("Path");
        if (path == null) return Optional.empty();
        String pathExt = env.getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD");
        List<String> names = new ArrayList<>();
        if (name.contains(".")) names.add(name);
        for (String ext : pathExt.split(";")) {
            if (!ext.isBlank()) names.add(name + ext.toLowerCase(Locale.ROOT));
        }
        for (String dir : path.split(";")) {
            String d = dir.strip();
            if (d.length() >= 2 && d.startsWith("\"") && d.endsWith("\"")) d = d.substring(1, d.length() - 1);
            if (d.isEmpty()) continue;
            for (String n : names) {
                Path p;
                try {
                    p = Path.of(d, n);
                } catch (RuntimeException e) {
                    continue;
                }
                if (Files.isRegularFile(p)) return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /** From kernel32's {@code GetShortPathNameW}: empty when the volume does not make them, or the file is gone. */
    @Override
    public Optional<String> shortName(Path file) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment longPath = a.allocateFrom(file.toAbsolutePath().toString(), StandardCharsets.UTF_16LE);
            int chars = 32_768;
            MemorySegment out = a.allocate(chars * 2L);
            int n = (int) ShortNames.GET_SHORT_PATH_NAME.invokeExact(longPath, out, chars);
            if (n <= 0 || n >= chars) {
                return Optional.empty();
            }
            String shortPath = new String(out.asSlice(0, n * 2L).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_16LE);
            Path name = Path.of(shortPath).getFileName();
            return name == null ? Optional.empty() : Optional.of(name.toString());
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    /** Bound on first use, so a platform that never asks for a short name never loads kernel32 for one. */
    private static final class ShortNames {
        static final MethodHandle GET_SHORT_PATH_NAME = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("kernel32.dll", Arena.global()).find("GetShortPathNameW")
                        .orElseThrow(() -> new UnsatisfiedLinkError("GetShortPathNameW")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank() && new File(dir, exe).isFile()) return true;
        }
        return false;
    }
}
