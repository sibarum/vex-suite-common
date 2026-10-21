package dev.sibarum.suite.update.windows;

import dev.supirvast.ffi.Ffi;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Another application's windows, asked to close as its own close button would ask them, and brought to the front.
 *
 * <p><b>Why {@code WM_CLOSE}, posted.</b> It is the message the close button and Alt+F4 send. A VexelRay window does
 * not pass it on to be destroyed: it hands it to the application's close gate, which may ask about unsaved changes
 * first and may refuse. So this asks, and never forces: whether the application closes is still the application's
 * and its user's to decide. Posted, not sent, so a busy application cannot hold this thread.
 *
 * <p>Only a process's own top-level windows that are visible and have no owner are asked: those are its main windows.
 * A menu or a tooltip is owned, and closes with its owner. Every call here is a {@code user32} downcall whose shape
 * {@code vexelray-os-windows} already registers for native-image, and the handles are bound on first use, so nothing
 * native is touched elsewhere than on Windows.
 */
final class Windows {

    private static final int WM_CLOSE = 0x0010;
    private static final int GW_HWNDNEXT = 2;
    private static final int GW_OWNER = 4;
    private static final int SW_RESTORE = 9;
    /** However the windows are restacked while they are walked, the walk ends. */
    private static final int MOST = 10_000;

    private Windows() {
    }

    /** Bound when first needed, so loading this class binds nothing. */
    private static final class User32 {
        private static final SymbolLookup LIB = Ffi.library("user32");
        static final MethodHandle GetTopWindow = Ffi.downcall(LIB, "GetTopWindow",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle GetWindow = Ffi.downcall(LIB, "GetWindow",
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        static final MethodHandle GetWindowThreadProcessId = Ffi.downcall(LIB, "GetWindowThreadProcessId",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle IsWindowVisible = Ffi.downcall(LIB, "IsWindowVisible",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle IsIconic = Ffi.downcall(LIB, "IsIconic",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle ShowWindow = Ffi.downcall(LIB, "ShowWindow",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        static final MethodHandle SetForegroundWindow = Ffi.downcall(LIB, "SetForegroundWindow",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle PostMessageW = Ffi.downcall(LIB, "PostMessageW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
    }

    /**
     * Ask every main window of process {@code pid} to close. Returns how many were asked: 0 when it has none to
     * ask, because it is still starting, has hung before showing one, or has no window at all.
     */
    static int close(long pid) {
        int asked = 0;
        for (MemorySegment hwnd : windows(pid)) {
            try {
                if ((int) User32.PostMessageW.invokeExact(hwnd, WM_CLOSE, 0L, 0L) != 0) {
                    asked++;
                }
            } catch (Throwable t) {
                // The window went away between finding it and asking it: one fewer to ask.
            }
        }
        return asked;
    }

    /**
     * Bring process {@code pid}'s first main window to the front, restored if it was minimised, so its user sees
     * what it is asking. Windows lets a process do this only while it is in the foreground itself, which the
     * application is when its user has just pressed a button in it. Returns whether there was a window to show.
     */
    static boolean show(long pid) {
        List<MemorySegment> windows = windows(pid);
        if (windows.isEmpty()) {
            return false;
        }
        MemorySegment hwnd = windows.getFirst();
        try {
            if ((int) User32.IsIconic.invokeExact(hwnd) != 0) {
                int ignored = (int) User32.ShowWindow.invokeExact(hwnd, SW_RESTORE);
            }
            int ignored = (int) User32.SetForegroundWindow.invokeExact(hwnd);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The visible, unowned top-level windows of process {@code pid}, front first. */
    static List<MemorySegment> windows(long pid) {
        List<MemorySegment> out = new ArrayList<>();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment owner = arena.allocate(JAVA_INT);
            MemorySegment hwnd = (MemorySegment) User32.GetTopWindow.invokeExact(MemorySegment.NULL);
            for (int i = 0; i < MOST && !hwnd.equals(MemorySegment.NULL); i++) {
                int ignored = (int) User32.GetWindowThreadProcessId.invokeExact(hwnd, owner);
                if ((owner.get(JAVA_INT, 0) & 0xFFFF_FFFFL) == pid
                        && (int) User32.IsWindowVisible.invokeExact(hwnd) != 0
                        && ((MemorySegment) User32.GetWindow.invokeExact(hwnd, GW_OWNER)).equals(MemorySegment.NULL)) {
                    out.add(hwnd);
                }
                hwnd = (MemorySegment) User32.GetWindow.invokeExact(hwnd, GW_HWNDNEXT);
            }
        } catch (Throwable t) {
            // No user32 to ask (not Windows), or the walk failed part way: what was found is what there is.
        }
        return out;
    }
}
