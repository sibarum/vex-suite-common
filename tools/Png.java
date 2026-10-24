import sibarum.imagelib.Imagelib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One SVG as a PNG at each side named: for art shown larger than an icon, where {@link Ico}'s 256 px stops short — the
 * VexelRay logo in an About window is 96 px at 100 % and 384 at 400 %. Each size is rasterised from the vector, as
 * {@link Ico}'s are, so none is another reduced. Square: the SVG is drawn into a {@code side x side} box.
 *
 * <p>Not part of the library's build: run it by hand when the art changes, from this repository's root, with imagelib
 * on the class path. {@code <out>} is a file name with {@code {side}} in it, which each side replaces:
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -cp ~/.m2/repository/sibarum/imagelib/imagelib-wrapper/0.1.0-SNAPSHOT/imagelib-wrapper-0.1.0-SNAPSHOT.jar \
 *      tools/Png.java logo.svg logo-{side}.png 96 192 288 384
 * </pre>
 */
public final class Png {

    public static void main(String[] args) throws IOException {
        if (args.length < 3 || !args[1].contains("{side}")) {
            System.err.println("usage: Png <in.svg> <out-{side}.png> <side> [<side> ...]");
            System.exit(2);
        }
        byte[] svg = Files.readAllBytes(Path.of(args[0]));
        for (int i = 2; i < args.length; i++) {
            int side = Integer.parseInt(args[i]);
            Path out = Path.of(args[1].replace("{side}", Integer.toString(side)));
            Files.write(out, Ico.png(Imagelib.rasterize(svg, side, side)));
            System.out.println(out + ": " + side + " px, " + Files.size(out) + " bytes");
        }
    }

    private Png() {
    }
}
