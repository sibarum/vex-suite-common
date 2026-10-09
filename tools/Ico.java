import sibarum.imagelib.Frames;
import sibarum.imagelib.Imagelib;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * An application icon for Windows from its SVG: the suite's icons are drawn on the design canvas, and this renders one
 * at every size Windows asks for and packs them into one {@code .ico}, each size a PNG (what Windows has read since
 * Vista, and what {@code rc.exe} links).
 *
 * <p>Not part of the library's build: run it by hand when an icon changes, with imagelib on the class path, from this
 * repository's root.
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -cp ~/.m2/repository/sibarum/imagelib/imagelib-wrapper/0.1.0-SNAPSHOT/imagelib-wrapper-0.1.0-SNAPSHOT.jar \
 *      tools/Ico.java ../Pix/src/main/rc/pix.svg ../Pix/src/main/rc/pix.ico
 * </pre>
 *
 * <p>Rasterised by imagelib's own SVG renderer, at each size from the vector rather than shrunk from the largest, so
 * the 16 px icon is drawn at 16 px.
 */
public final class Ico {

    /** What Explorer, the taskbar, Start, and a shortcut at each display scale ask for. */
    static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 96, 128, 256};

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: Ico <in.svg> <out.ico>");
            System.exit(2);
        }
        byte[] svg = Files.readAllBytes(Path.of(args[0]));
        List<byte[]> pngs = new ArrayList<>();
        for (int side : SIZES) {
            pngs.add(png(Imagelib.rasterize(svg, side, side)));
        }
        Files.write(Path.of(args[1]), ico(pngs));
        System.out.println(args[1] + ": " + SIZES.length + " sizes, " + Files.size(Path.of(args[1])) + " bytes");
    }

    /** RGBA8, straight alpha, top row first, as imagelib gives it, to a PNG. */
    static byte[] png(Frames f) throws IOException {
        int w = f.width();
        int h = f.height();
        byte[] rgba = f.rgba();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                int argb = (rgba[i + 3] & 0xFF) << 24 | (rgba[i] & 0xFF) << 16 | (rgba[i + 1] & 0xFF) << 8
                        | rgba[i + 2] & 0xFF;
                img.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** ICONDIR, one ICONDIRENTRY per image, then the images; sizes in the order {@link #SIZES} gives them. */
    static byte[] ico(List<byte[]> pngs) {
        int header = 6 + 16 * pngs.size();
        int total = header + pngs.stream().mapToInt(p -> p.length).sum();
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) pngs.size());
        int offset = header;
        for (int i = 0; i < pngs.size(); i++) {
            int side = SIZES[i];
            b.put((byte) (side >= 256 ? 0 : side)).put((byte) (side >= 256 ? 0 : side));
            b.put((byte) 0).put((byte) 0);
            b.putShort((short) 1).putShort((short) 32);
            b.putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        for (byte[] p : pngs) {
            b.put(p);
        }
        return b.array();
    }

    private Ico() {
    }
}
