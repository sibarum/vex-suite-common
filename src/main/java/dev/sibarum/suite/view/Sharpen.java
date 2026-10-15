package dev.sibarum.suite.view;

import dev.vexelray.canvas.ImageFilter;
import dev.vexelray.codec.image.Original;
import dev.vexelray.codec.image.Picture;
import dev.vexelray.codec.image.gui.ImageView;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The sharper copy of what is on the screen, made once the picture has come to rest: the part of it the stage shows,
 * resampled from the file's own full-size pixels to exactly the size it is drawn at ({@link Original#detail}), and laid
 * over the picture ({@link ImageView#detail}). While the picture moves it is drawn as it always was, from its texture
 * on the GPU; what changes at rest is only how good it looks.
 *
 * <p>One worker, the latest request wins: a request made while one is being made replaces any waiting, and a part
 * finished after a newer one was asked for is not shown. The file's full-size pixels are read once and kept while it
 * is the one shown, so moving about it costs only the resample.
 */
final class Sharpen {

    /** How much of the stage's size is made beyond each side of it, so a short move stays sharp. */
    static final float MARGIN = 0.25f;
    /** The largest side a part is made at: a texture every device samples. Larger is made smaller and enlarged. */
    static final int MAX_SIDE = Picture.SHEET_SIDE;

    /** One part to make: of which file, over which picture, which part, and how large. */
    private record Job(Path path, Picture of, ImageFilter filter, float zoom, float u0, float v0, float u1, float v1,
                       int w, int h) {
    }

    private final Executor offload;
    private final ImageView image;
    private final AtomicReference<Job> pending = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();

    /** The file whose pixels are kept, and them: null when it has none to sharpen from. Worker only. */
    private Path kept;
    private Original original;

    Sharpen(Executor offload, ImageView image) {
        this.offload = offload;
        this.image = image;
    }

    /**
     * Make {@code of}, the picture of {@code path} that is up, again where {@code visible} says the stage shows it
     * ({@link Lens#visible}), from the file's own pixels scaled with {@code filter}. Nothing for a picture that moves,
     * whose frames are shown as they come: a part made sharper from one frame would lie over every other.
     */
    void request(Path path, Picture of, float[] visible, ImageFilter filter) {
        if (path == null || of == null || of.moves() || visible == null || filter == null) {
            return;
        }
        float w = visible[4];
        float h = visible[5];
        float shrink = Math.min(1f, MAX_SIDE / Math.max(w, h));
        int pw = Math.max(1, Math.round(w * shrink));
        int ph = Math.max(1, Math.round(h * shrink));
        pending.set(new Job(path, of, filter, visible[6], visible[0], visible[1], visible[2], visible[3], pw, ph));
        if (running.compareAndSet(false, true)) {
            offload.execute(this::work);
        }
    }

    /** Let go of the file's pixels and of anything waiting: nothing is shown. */
    void forget() {
        pending.set(FORGET);
        if (running.compareAndSet(false, true)) {
            offload.execute(this::work);
        }
    }

    /** The job that makes nothing and keeps nothing. */
    private static final Job FORGET = new Job(null, null, null, 1f, 0f, 0f, 0f, 0f, 0, 0);

    private void work() {
        while (true) {
            Job job = pending.getAndSet(null);
            if (job == null) {
                running.set(false);
                // Asked for again between the empty read and the flag going down: carry on, unless another has.
                if (pending.get() == null || !running.compareAndSet(false, true)) {
                    return;
                }
                continue;
            }
            if (job == FORGET) {
                kept = null;
                original = null;
                continue;
            }
            if (!job.path().equals(kept)) {
                original = null;   // let the last file go before reading the next
                kept = job.path();
                original = Original.of(job.path());
            }
            if (original == null) {
                continue;
            }
            Picture part = original.detail(job.filter(), job.u0(), job.v0(), job.u1(), job.v1(), job.w(), job.h());
            if (part != null && pending.get() == null) {
                image.detail(job.of(), part, job.zoom(), job.u0(), job.v0(), job.u1(), job.v1());
            }
        }
    }
}
