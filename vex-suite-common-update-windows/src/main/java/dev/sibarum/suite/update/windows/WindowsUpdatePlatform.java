package dev.sibarum.suite.update.windows;

import dev.sibarum.suite.update.UpdatePlatform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Updates on Windows: windows asked to close and brought forward through {@code user32} ({@link Windows}), and a
 * PowerShell script that runs each installer's updater (itself a PowerShell script) once its application has closed.
 */
public final class WindowsUpdatePlatform implements UpdatePlatform {

    @Override
    public int askToClose(long pid) {
        return Windows.close(pid);
    }

    @Override
    public boolean bringForward(long pid) {
        return Windows.show(pid);
    }

    @Override
    public ProcessBuilder handover(Path work, Path plan) throws IOException {
        Path script = work.resolve("restart.ps1");
        Files.writeString(script, SCRIPT, StandardCharsets.US_ASCII);
        return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString())
                .directory(work.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
    }

    /**
     * Updates the applications that have closed and starts them again, then this one once it has exited. Pure ASCII,
     * as the installer's own scripts are, and for Windows PowerShell 5.1. Its inputs come in the environment and a
     * plan file of tab-separated lines, not spliced into the text, so a path with a quote in it is only a path.
     *
     * <p>Plan lines: {@code app <id> <updater> <exe> <start again: 1|0> <this application: 1|0>}, then
     * {@code arg <value>} for each argument this application starts again with. Result lines: {@code <id> <exit>}.
     */
    static final String SCRIPT = """
            # Written by vex-suite-common's Updates: update applications once they have closed, then start them again.
            $ErrorActionPreference = 'Continue'
            $log = $env:VEX_UPDATE_LOG
            function Say([string]$m) { Add-Content -LiteralPath $log -Encoding UTF8 -Value ((Get-Date).ToString('u') + ' ' + $m) }
            function Done([string]$id, [int]$code) { Add-Content -LiteralPath $env:VEX_UPDATE_RESULT -Encoding UTF8 -Value ($id + "`t" + $code) }

            $apps = @()
            $appArgs = @()
            foreach ($line in @(Get-Content -LiteralPath $env:VEX_UPDATE_PLAN -Encoding UTF8)) {
                $f = $line -split "`t"
                if ($f[0] -eq 'app') { $apps += [pscustomobject]@{ Id = $f[1]; Updater = $f[2]; Exe = $f[3]; Again = ($f[4] -eq '1'); Self = ($f[5] -eq '1') } }
                elseif ($f[0] -eq 'arg') { $appArgs += $line.Substring(4) }
            }
            $others = @($apps | Where-Object { -not $_.Self })
            $self = @($apps | Where-Object { $_.Self }) | Select-Object -First 1

            function Again($a, $with) {
                $dir = Split-Path -Parent $a.Exe
                $quoted = @($with | ForEach-Object { '"' + ([string]$_ -replace '(\\\\*)"', '$1$1\\"' -replace '(\\\\+)$', '$1$1') + '"' })
                if ($quoted.Count -gt 0) { Start-Process -FilePath $a.Exe -WorkingDirectory $dir -ArgumentList ($quoted -join ' ') }
                else { Start-Process -FilePath $a.Exe -WorkingDirectory $dir }
                Say "started $($a.Id) again"
            }

            function Update($a) {
                Say "updating $($a.Id): $($a.Updater) -Yes"
                & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $a.Updater -Yes 2>&1 |
                    ForEach-Object { Say ('  ' + [string]$_) }
                $code = $LASTEXITCODE
                Say "$($a.Id): the updater exited with $code"
                Done $a.Id $code
            }

            # One update at a time across the suite: the file is held, shared with nobody, until this script ends.
            $lock = $null
            $until = (Get-Date).AddSeconds([Math]::Min(30, [int]$env:VEX_UPDATE_WAIT))
            while (-not $lock) {
                try { $lock = [IO.File]::Open($env:VEX_UPDATE_LOCK, 'OpenOrCreate', 'ReadWrite', 'None') }
                catch {
                    if ((Get-Date) -gt $until) { break }
                    Start-Sleep -Milliseconds 250
                }
            }
            if (-not $lock) {
                Say 'another update is under way; nothing was updated'
                foreach ($a in $apps) { Done $a.Id 1002 }
                foreach ($a in $others) { if ($a.Again) { Again $a @() } }
                exit 1
            }

            try {
                # The others have closed already. Update them and give them back first: this one may be a while.
                foreach ($a in $others) { Update $a }
                foreach ($a in $others) { if ($a.Again) { Again $a @() } }

                if ($self) {
                    $appPid = [int]$env:VEX_UPDATE_PID
                    Say "waiting for process $appPid to exit"
                    $p = Get-Process -Id $appPid -ErrorAction SilentlyContinue
                    if ($p) { $null = $p.WaitForExit(1000 * [int]$env:VEX_UPDATE_WAIT) }
                    if (Get-Process -Id $appPid -ErrorAction SilentlyContinue) {
                        Say "$($self.Id) is still running; it was not updated"
                        Done $self.Id 1001
                        exit 1
                    }
                    Update $self
                    # Start it again whether or not the update took: the old version is better than nothing open.
                    Again $self $appArgs
                }
            } finally {
                $lock.Close()
            }
            """;
}
