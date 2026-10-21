package dev.sibarum.suite.update;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * What updating the suite's applications needs from an operating system, provided by an OS module
 * ({@code vex-suite-common-update-windows}) through {@link ServiceLoader}. Where there is none — the installer only
 * installs on Windows today — there are no updates: {@code Updates.of} answers empty, because nothing provides them,
 * not because anything asked which OS this is.
 */
public interface UpdatePlatform {

    /**
     * Ask process {@code pid}'s windows to close, as their close button would. Returns how many were asked; 0 when it
     * has no window to ask. Whether it closes is up to it.
     */
    int askToClose(long pid);

    /** Bring process {@code pid}'s window to the front. Whether it had one. */
    boolean bringForward(long pid);

    /**
     * The process, not yet started, that carries out the update {@code plan} (a file of tab-separated lines: {@code app
     * <id> <updater> <executable> <again> <self>}, and {@code arg <argument>}) once the applications in it have
     * closed, then starts the ones marked again. It may write what it needs into {@code work}. The caller adds the
     * {@code VEX_UPDATE_*} environment the plan refers to and starts it.
     */
    ProcessBuilder handover(Path work, Path plan) throws IOException;

    /** The one platform on the class path that updates, or empty. */
    static Optional<UpdatePlatform> current() {
        return ServiceLoader.load(UpdatePlatform.class).findFirst();
    }
}
