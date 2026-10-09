package dev.sibarum.suite.view;

import dev.sibarum.suite.Lru;
import dev.sibarum.suite.pictures.Picture;
import dev.sibarum.suite.pictures.Stamp;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.SampledImage;

import java.util.function.Consumer;

/**
 * Pictures on the GPU: each one uploaded once, the recent ones kept, and the rest given back.
 *
 * <p>Late-bound: a texture belongs to the window's device, which does not exist when the tree is built, and a part
 * taking {@code GuiApp} directly would pull the tree into the window's phase. The application {@linkplain #bind binds}
 * it when its window exists, and until then {@link #show} uploads nothing.
 *
 * <p><b>Release, not close.</b> An evicted texture may still be drawn by a frame in flight, or still be named by the
 * filmstrip for a frame, so it goes to {@code GuiApp.release}, which closes it once no frame samples it. Closing it here
 * would be a use-after-free on the GPU.
 *
 * <p>Everything but {@link #show} runs on the GUI thread, where the cache lives; {@code show} may be called from
 * anywhere and posts there.
 */
public final class Textures {

    /** GPU memory for recent pictures. A 1024 px still is 4 MB, so this keeps a folder's worth of going back. */
    static final long BUDGET = 192L * 1024 * 1024;

    private volatile GuiApp app;
    private Lru<Key, Uploaded> cache;

    /** One file at one size: the thumbnail and the viewer's picture of the same file are two textures. */
    private record Key(Stamp stamp, int width, int height) {
        static Key of(Picture p) {
            return new Key(p.stamp(), p.sheetWidth(), p.sheetHeight());
        }
    }

    /** A texture and what it costs, which the texture itself does not say. */
    private record Uploaded(AtlasTexture texture, long bytes) {
    }

    /** Main thread. Null on the way out, after which nothing is uploaded; the application closes what is left. */
    public void bind(GuiApp app) {
        this.app = app;
        this.cache = app == null ? null : new Lru<>(BUDGET, Uploaded::bytes, u -> app.release(u.texture()));
    }

    /**
     * Hand {@code picture}'s texture to {@code then} on the GUI thread, uploading it if it is not already there.
     * False if there is no window to upload into, in which case {@code then} is never called.
     */
    public boolean show(Picture picture, Consumer<SampledImage> then) {
        GuiApp window = app;
        if (window == null) {
            return false;
        }
        window.post(() -> {
            Lru<Key, Uploaded> textures = cache;
            if (textures == null || app != window) {
                return;
            }
            Key key = Key.of(picture);
            Uploaded u = textures.get(key);
            if (u == null || u.texture().isClosed()) {
                u = new Uploaded(window.texture(picture.sheet(), picture.sheetWidth(), picture.sheetHeight()),
                        picture.bytes());
                textures.put(key, u);
            }
            then.accept(u.texture());
        });
        return true;
    }

    /**
     * Run {@code then} on the GUI thread after every {@link #show} already asked for has handed over its texture, so a
     * change that must land in the same frame as some pictures (a filmstrip starting to slide) does. At once if there
     * is no window, since nothing is coming.
     */
    public void then(Runnable then) {
        GuiApp window = app;
        if (window == null) {
            then.run();
            return;
        }
        window.post(then);
    }
}
