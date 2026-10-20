package dev.sibarum.suite.tty.windows;

import dev.sibarum.suite.tty.Pty;
import dev.sibarum.suite.tty.PtyPlatform;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank() && new File(dir, exe).isFile()) return true;
        }
        return false;
    }
}
