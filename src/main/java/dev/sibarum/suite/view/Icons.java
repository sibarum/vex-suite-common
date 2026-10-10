package dev.sibarum.suite.view;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.draw.Sketch;

/**
 * The viewer's icons, drawn as strokes. The framework ships no UI icon set and its fonts have no star, rotate or
 * cross, so each icon is a few {@link Sketch} lines on a 24-unit grid, scaled to the box it is drawn in, the way an
 * outline icon font would draw them. Redrawn whenever its box changes size, so a zoom keeps the strokes sharp.
 */
public enum Icons {

    CLOSE {
        @Override
        void draw(Pen p) {
            p.line(6, 6, 18, 18);
            p.line(18, 6, 6, 18);
        }
    },
    PREVIOUS {
        @Override
        void draw(Pen p) {
            p.poly(14.5, 5, 8, 12, 14.5, 19);
        }
    },
    NEXT {
        @Override
        void draw(Pen p) {
            p.poly(9.5, 5, 16, 12, 9.5, 19);
        }
    },
    /** A folder: open another picture, or another folder of them. */
    FOLDER {
        @Override
        void draw(Pen p) {
            p.poly(3.5, 18.5, 3.5, 5.5, 9.5, 5.5, 11.5, 8, 20.5, 8, 20.5, 18.5, 3.5, 18.5);
        }
    },
    /** A square, half of it filled: what shows behind the picture. */
    BACKGROUND {
        @Override
        void draw(Pen p) {
            p.box(3.5, 3.5, 17, 17, 3);
            p.solid(6, 6, 6, 12, 1);
        }
    },
    /** A push pin: the controls stay where they are. */
    PIN {
        @Override
        void draw(Pen p) {
            p.poly(9, 3.5, 15, 3.5, 14, 8.5, 17.5, 12, 6.5, 12, 10, 8.5, 9, 3.5);
            p.line(12, 12, 12, 20.5);
        }
    };

    /** The grid every icon is drawn on, and its stroke. */
    static final double GRID = 24;
    private static final double STROKE = 1.9;

    abstract void draw(Pen p);

    /** This icon in a {@code side} px square, in {@code ink}. */
    public Sketch sketch(float side, Color ink) {
        Sketch s = new Sketch().tag("icon." + name().toLowerCase(java.util.Locale.ROOT));
        draw(new Pen(s, side / GRID, ink));
        return s;
    }

    /** Grid units in, pixels out. */
    record Pen(Sketch s, double k, Color ink) {

        void line(double x0, double y0, double x1, double y1) {
            s.line(x0 * k, y0 * k, x1 * k, y1 * k, STROKE * k, ink);
        }

        /** An open path through {@code xy} pairs. */
        void poly(double... xy) {
            double[] xs = new double[xy.length / 2];
            double[] ys = new double[xy.length / 2];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = xy[2 * i] * k;
                ys[i] = xy[2 * i + 1] * k;
            }
            s.polyline(xs, ys, STROKE * k, ink);
        }

        void box(double x, double y, double w, double h, double r) {
            s.outline(x * k, y * k, w * k, h * k, r * k, STROKE * k, ink);
        }

        void solid(double x, double y, double w, double h, double r) {
            s.fill(x * k, y * k, w * k, h * k, r * k, Color.withAlpha(ink, ink.a() * 0.55f));
        }
    }
}
