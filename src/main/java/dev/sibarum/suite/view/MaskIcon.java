package dev.sibarum.suite.view;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.ImageFilter;
import dev.vexelray.codec.image.MaskField;
import dev.vexelray.codec.image.gui.Textures;
import dev.vexelray.gui.core.ImageField;
import dev.vexelray.gui.core.Node;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * One of the suite's utility icons, from {@code vexelray-icons/utility/small}: a mask, one solid shape, copied into this
 * module's resources ({@code icons/<name>-mask.svg}) as that folder's README asks. Where {@link Icons} are strokes drawn
 * here, these are the icon set's own art.
 *
 * <p>Drawn as a signed distance field ({@link MaskField}, vexelray-canvas's {@code DistanceField} of the mask): made
 * once from the mask, uploaded once, and then any size and any colour, with a glow or a soft shadow from the shader,
 * for nothing more. A state that changes an icon's colour or effect changes four numbers, not a texture.
 */
enum MaskIcon {

    /** A gear: the settings. */
    SETTINGS("settings"),
    /** A picture gone to blocks: how a picture is scaled while it moves. */
    QUALITY_LOW("quality-low"),
    /** A clean picture with a spark: how it is scaled at rest. */
    QUALITY_HIGH("quality-high"),
    FILTER_NEAREST("filter-nearest"),
    FILTER_SHARP_BILINEAR("filter-sharp-bilinear"),
    FILTER_BILINEAR("filter-bilinear"),
    FILTER_GAUSSIAN("filter-gaussian"),
    FILTER_NORMAL("filter-normal"),
    FILTER_B_SPLINE("filter-bspline"),
    FILTER_MITCHELL("filter-mitchell"),
    FILTER_LANCZOS("filter-lanczos");

    /** The field's texels across the icon's own square: four to a unit of its 16-unit grid. */
    static final int INNER = 64;
    /** Texels of margin round it, and the farthest a glow or a shadow can reach: a quarter of the icon. */
    static final int SPREAD = 16;

    private final String file;
    private volatile MaskField field;

    MaskIcon(String file) {
        this.file = file;
    }

    /** The icon for how {@code filter} scales, or null if the set has none for it. */
    static MaskIcon of(ImageFilter filter) {
        try {
            return valueOf("FILTER_" + filter.name());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Show this icon across {@code glyph} in {@code tint}, with a glow reaching {@code glow} px and a shadow
     * {@code shadow} px soft (0 for none; see {@link ImageField#effects(float, float)}). False if it cannot be drawn: no
     * window to upload into yet, or the mask does not rasterise; the control is still a control.
     */
    boolean draw(Node glyph, Textures textures, Color tint, float glow, float shadow) {
        MaskField f = field();
        return f != null && textures.showData(f.picture(),
                t -> glyph.field(new ImageField(t, f.side(), f.spread(), tint).effects(glow, shadow)));
    }

    /** The field, made the first time it is asked for. */
    private MaskField field() {
        MaskField f = field;
        if (f == null) {
            synchronized (this) {
                f = field;
                if (f == null) {
                    f = MaskField.of("icon-field:" + file, read(), INNER, SPREAD);
                    field = f;
                }
            }
        }
        return f;
    }

    private byte[] read() {
        String name = file + "-mask.svg";
        try (InputStream in = MaskIcon.class.getResourceAsStream("icons/" + name)) {
            if (in == null) {
                throw new IllegalStateException("no icon " + name + " on the class path");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read icon " + name + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
