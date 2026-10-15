package dev.sibarum.suite.tty;

import dev.sibarum.suite.tty.Terminal.Line;
import dev.sibarum.suite.tty.Terminal.Palette;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.text.GlyphLayout;
import sibarum.atchung.Subscription;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * A {@link Terminal} drawn on a node as a cell grid: what any window that shows a terminal shares, whatever feeds it.
 * MainFrame puts a shell behind one; Vex puts a build behind one and keeps it after the build has ended.
 *
 * <p>This is the picture and the parts of the pointer that act on the picture alone: the cell grid fitted to the
 * node, scrolling back through the history (the wheel, {@link #page}), and selecting with a drag and copying it.
 * What a key or a paste sends, and to whom, is the owner's: {@link #keySequence} and {@link #pasteText} say what
 * the bytes are, and the owner writes them wherever the terminal's input goes, if it has one.
 *
 * <h2>Threads</h2>
 *
 * <p>Whatever feeds the terminal (its methods are synchronized) calls {@link #invalidate}; the owner's per-frame
 * work calls {@link #tick}, which turns a stale picture into a new one. The frame loop parks when nothing is due,
 * so <b>every way the picture goes stale also wakes it</b>: the {@code wake} given to the constructor.
 */
public final class TerminalScreen implements AutoCloseable {

    private static final Color SELECTION = Color.argb(0xA0264F78);

    private final Gui gui;
    private final Terminal term;
    private final GlyphLayout glyphs;
    private final Runnable wake;
    private final Node node;
    private final Subscription scrolls;
    private final AtomicBoolean dirty = new AtomicBoolean(true);

    private volatile int defaultBg = Palette.DEFAULT_BG;
    private volatile int defaultFg = Palette.DEFAULT_FG;
    private volatile BiConsumer<Integer, Integer> onFit = (c, r) -> { };
    private volatile float px, cw, lineH, ascent;
    /** The node's content area as last laid out. */
    private volatile float boxW, boxH;
    private volatile boolean active;

    private volatile int scrollOffset;
    private volatile boolean hasSel;
    private volatile int anchorRow, anchorCol, headRow, headCol;

    /**
     * @param face   the atlas face {@code glyphs} is, which the node's text is set in
     * @param fontPx the type size, in the picture's pixels
     * @param wake   wakes the frame loop: the picture is stale. Any thread.
     */
    public TerminalScreen(Gui gui, GlyphLayout glyphs, int face, Terminal term, float fontPx, Runnable wake) {
        this.gui = gui;
        this.glyphs = glyphs;
        this.term = term;
        this.wake = wake;
        measure(fontPx);
        this.node = gui.box().width(Length.FILL).height(Length.grow(1)).font(face)
                .background(Color.rgb(defaultBg));
        wire();
        scrolls = gui.bus().subscribe(InputTopics.INPUT, (InputEvent e) -> {
            if (active && e instanceof InputEvent.Scrolled s && s.yOffset() != 0) {
                scrollBy(s.yOffset() > 0 ? 3 : -3);
            }
        });
    }

    /**
     * The colours the terminal's default background and foreground stand for, as {@code 0xRRGGBB}: what shows where
     * a program asked for no colour of its own. The node's background is the first.
     */
    public TerminalScreen colors(int background, int foreground) {
        defaultBg = background;
        defaultFg = foreground;
        node.background(Color.rgb(background));
        invalidate();
        return this;
    }

    /** Told the grid's new size in cells whenever the node's size changes it: a pseudoconsole wants to know. */
    public TerminalScreen onFit(BiConsumer<Integer, Integer> fit) {
        this.onFit = fit;
        return this;
    }

    public Node node() {
        return node;
    }

    public Terminal terminal() {
        return term;
    }

    public int cols() {
        return term.cols();
    }

    public int rows() {
        return term.rows();
    }

    /** Change the type size: the cell grid is remeasured, and refitted to the node. */
    public void fontPx(float size) {
        if (size == px) {
            return;
        }
        measure(size);
        refit();
        invalidate();
    }

    /** Only a screen on show draws; one behind another keeps its terminal and draws when it comes back. */
    public void active(boolean on) {
        active = on;
        if (on) {
            invalidate();
        }
    }

    /** On the frame loop's thread, once a frame. */
    public void tick() {
        if (active && dirty.getAndSet(false)) {
            render();
        }
    }

    /** The picture is stale: say so, and wake the loop to redraw it. Any thread. */
    public void invalidate() {
        dirty.set(true);
        wake.run();
    }

    @Override
    public void close() {
        scrolls.close();
    }

    // -- scrolling and selection ------------------------------------------------------------------------------

    /** Scroll back {@code lines} into the history, or forward for a negative count. */
    public void scrollBy(int lines) {
        scrollOffset += lines;
        invalidate();
    }

    /** A page back (+1) or forward (-1): a screenful less a line, so a line of context stays in view. */
    public void page(int direction) {
        scrollBy(direction * (term.rows() - 1));
    }

    /** Back to the live screen, with nothing selected: where typing is seen. */
    public void snapToBottom() {
        if (scrollOffset != 0 || hasSel) {
            scrollOffset = 0;
            hasSel = false;
            invalidate();
        }
    }

    public boolean hasSelection() {
        return hasSel;
    }

    /** Put the selection on the clipboard and let it go. Nothing when nothing is selected. */
    public void copySelection() {
        int[] sel = selection();
        if (sel == null) {
            return;
        }
        gui.clipboard().set(term.text(sel[0], sel[1], sel[2], sel[3]));
        hasSel = false;
        invalidate();
    }

    /** Everything the terminal holds, the history and the screen, with the blank lines at the end left off. */
    public String allText() {
        String all;
        synchronized (term) {
            all = term.text(0, 0, term.scrollbackSize() + term.rows() - 1, term.cols() - 1);
        }
        int end = all.length();
        while (end > 0 && (all.charAt(end - 1) == '\n' || all.charAt(end - 1) == ' ')) {
            end--;
        }
        return all.substring(0, end);
    }

    /** {@link #allText} onto the clipboard. */
    public void copyAll() {
        gui.clipboard().set(allText());
    }

    // -- what keys send ---------------------------------------------------------------------------------------

    /**
     * What {@code k} sends a program in the terminal, or null for nothing. A typed character arrives separately, as
     * a char, and is sent as itself.
     */
    public String keySequence(Key k, boolean ctrl, boolean alt, boolean shift) {
        int mod = 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0);
        String out = switch (k) {
            case ENTER -> "\r";
            case BACKSPACE -> ctrl ? "\b" : "\u007f";
            case ESCAPE -> "\u001b";
            case UP -> cursorKey('A', mod);
            case DOWN -> cursorKey('B', mod);
            case RIGHT -> cursorKey('C', mod);
            case LEFT -> cursorKey('D', mod);
            case HOME -> cursorKey('H', mod);
            case END -> cursorKey('F', mod);
            case INSERT -> tilde(2, mod);
            case DELETE -> tilde(3, mod);
            case PAGE_UP -> tilde(5, mod);
            case PAGE_DOWN -> tilde(6, mod);
            case F1 -> "\u001bOP";
            case F2 -> "\u001bOQ";
            case F3 -> "\u001bOR";
            case F4 -> "\u001bOS";
            case F5 -> tilde(15, mod);
            case F6 -> tilde(17, mod);
            case F7 -> tilde(18, mod);
            case F8 -> tilde(19, mod);
            case F9 -> tilde(20, mod);
            case F10 -> tilde(21, mod);
            case F11 -> tilde(23, mod);
            case F12 -> tilde(24, mod);
            default -> null;
        };
        String name = k.name();
        if (out == null && name.length() == 1 && name.charAt(0) >= 'A' && name.charAt(0) <= 'Z') {
            if (ctrl && !alt) {
                out = String.valueOf((char) (name.charAt(0) - 'A' + 1));
            } else if (alt && !ctrl) {
                out = "\u001b";   // the letter itself follows as a typed character
            }
        }
        return out;
    }

    /** A cursor key: {@code ESC [ f}, or {@code ESC O f} while the program has asked for application keys. */
    public String cursorKey(char f, int mod) {
        if (mod > 1) {
            return "\u001b[1;" + mod + f;
        }
        return term.appCursorKeys() ? "\u001bO" + f : "\u001b[" + f;
    }

    private static String tilde(int n, int mod) {
        return mod > 1 ? "\u001b[" + n + ";" + mod + "~" : "\u001b[" + n + "~";
    }

    /**
     * What pasting {@code text} sends: its line ends as Enter, and bracketed when the program has asked to be told
     * a paste from typing.
     */
    public String pasteText(String text) {
        String t = text.replace("\r\n", "\r").replace('\n', '\r');
        return term.bracketedPaste() ? "\u001b[200~" + t + "\u001b[201~" : t;
    }

    // -- picture ----------------------------------------------------------------------------------------------

    private void measure(float size) {
        px = size;
        cw = glyphs.advance('M', size);
        ascent = glyphs.ascent(size);
        lineH = glyphs.lineHeight(size);
    }

    /** Make the terminal as many cells as the content area holds. */
    private void refit() {
        // A screen that is not on show is laid out at no size at all. Resizing to that would shrink the terminal to
        // nothing, and a program does not repaint what it lost when it grows back.
        if (boxW < cw * 2 || boxH < lineH) {
            return;
        }
        int cols = Math.max(2, (int) (boxW / cw));
        int rows = Math.max(1, (int) (boxH / lineH));
        if (cols != term.cols() || rows != term.rows()) {
            term.resize(cols, rows);
            onFit.accept(cols, rows);
            invalidate();
        }
    }

    private void render() {
        Sketch s = new Sketch();
        int dBg = defaultBg, dFg = defaultFg;
        synchronized (term) {
            int sb = term.scrollbackSize(), rows = term.rows(), cols = term.cols();
            int offset = Math.max(0, Math.min(scrollOffset, sb));
            scrollOffset = offset;
            boolean cursor = offset == 0 && term.cursorVisible();
            int[] sel = selection();
            for (int r = 0; r < rows; r++) {
                int abs = sb + r - offset;
                Line l = term.lineAt(abs);
                int w = Math.min(cols, l.width());
                float y = r * lineH;
                boolean cursorRow = cursor && r == term.cursorY();
                int selFrom = -1, selTo = -2;
                if (sel != null && abs >= sel[0] && abs <= sel[2]) {
                    selFrom = abs == sel[0] ? sel[1] : 0;
                    selTo = abs == sel[2] ? sel[3] : cols - 1;
                }
                // backgrounds, merged into runs
                for (int c = 0; c < w; ) {
                    int bg = cellBg(l, c, cursorRow && c == term.cursorX(), dFg);
                    int e = c + 1;
                    while (e < w && bg == cellBg(l, e, cursorRow && e == term.cursorX(), dFg)) {
                        e++;
                    }
                    if (bg != Terminal.DEFAULT) {
                        s.fill(c * cw, y, (e - c) * cw, lineH, Color.rgb(bg));
                    }
                    c = e;
                }
                if (selTo >= selFrom) {
                    s.fill(selFrom * cw, y, (Math.min(selTo, cols - 1) - selFrom + 1) * cw, lineH, SELECTION);
                }
                // glyphs, merged into runs of one colour; anything outside Latin-1 is placed on its own cell
                StringBuilder run = new StringBuilder();
                int runStart = 0, runFg = 0, runAt = 0;
                for (int c = 0; c <= w; c++) {
                    int ch = c < w ? l.ch[c] : 0;
                    int fg = c < w ? cellFg(l, c, cursorRow && c == term.cursorX(), dBg, dFg) : 0;
                    int at = c < w ? l.at[c] & (Terminal.BOLD | Terminal.UNDERLINE | Terminal.STRIKE) : 0;
                    boolean latin = ch > 0 && ch < 0x100;
                    if (!run.isEmpty() && (!latin || fg != runFg || at != runAt)) {
                        text(s, run.toString(), runStart, y, runFg, runAt);
                        run.setLength(0);
                    }
                    if (latin) {
                        if (run.isEmpty()) {
                            runStart = c;
                            runFg = fg;
                            runAt = at;
                        }
                        run.append((char) ch);
                    } else if (CellArt.handles(ch)) {
                        CellArt.draw(s, ch, c * cw, y, cw, lineH, fg);
                    } else if (ch > 0) {
                        text(s, new String(Character.toChars(ch)), c, y, fg, at);
                    }
                }
            }
        }
        node.picture(s.picture());
    }

    private void text(Sketch s, String str, int col, float y, int rgb, int at) {
        Color color = Color.rgb(rgb);
        float x = col * cw;
        s.text(str, x, y + ascent, px, color);
        if ((at & Terminal.BOLD) != 0) {
            s.text(str, x + 0.7f, y + ascent, px, color);   // one mono face: embolden by doubling
        }
        if ((at & Terminal.UNDERLINE) != 0) {
            s.fill(x, y + ascent + 2, str.length() * cw, 1, color);
        }
        if ((at & Terminal.STRIKE) != 0) {
            s.fill(x, y + lineH / 2, str.length() * cw, 1, color);
        }
    }

    /** Resolved background as 0xRRGGBB, or DEFAULT when the node's own background shows through. */
    private static int cellBg(Line l, int c, boolean cursor, int defaultFg) {
        boolean inv = ((l.at[c] & Terminal.INVERSE) != 0) ^ cursor;
        if (!inv) {
            return l.bg[c];
        }
        return l.fg[c] == Terminal.DEFAULT ? defaultFg : l.fg[c];
    }

    private static int cellFg(Line l, int c, boolean cursor, int defaultBg, int defaultFg) {
        boolean inv = ((l.at[c] & Terminal.INVERSE) != 0) ^ cursor;
        int fg = inv ? (l.bg[c] == Terminal.DEFAULT ? defaultBg : l.bg[c])
                : (l.fg[c] == Terminal.DEFAULT ? defaultFg : l.fg[c]);
        if ((l.at[c] & Terminal.DIM) != 0) {
            fg = blend(fg, defaultBg);
        }
        return fg;
    }

    private static int blend(int a, int b) {
        int r = (((a >> 16) & 255) * 6 + ((b >> 16) & 255) * 4) / 10;
        int g = (((a >> 8) & 255) * 6 + ((b >> 8) & 255) * 4) / 10;
        int bl = ((a & 255) * 6 + (b & 255) * 4) / 10;
        return (r << 16) | (g << 8) | bl;
    }

    // -- pointer ----------------------------------------------------------------------------------------------

    private void wire() {
        gui.onResizeUi(node, l -> {
            if (!l.present()) {
                return;
            }
            boxW = l.content().w();
            boxH = l.content().h();
            refit();
        });
        gui.onDragUi(node, d -> {
            int sb = term.scrollbackSize();
            int col = Math.max(0, Math.min(term.cols() - 1, (int) ((d.x() - d.nodeX()) / cw)));
            int row = Math.max(0, Math.min(term.rows() - 1, (int) ((d.y() - d.nodeY()) / lineH)));
            int abs = sb + row - scrollOffset;
            if (d.phase() == DragEvent.Phase.START) {
                anchorRow = headRow = abs;
                anchorCol = headCol = col;
                hasSel = false;
            } else {
                headRow = abs;
                headCol = col;
                hasSel = anchorRow != headRow || anchorCol != headCol;
            }
            invalidate();
        });
    }

    /** {firstRow, firstCol, lastRow, lastCol} in absolute coordinates, or null. */
    private int[] selection() {
        if (!hasSel) {
            return null;
        }
        boolean forward = anchorRow < headRow || (anchorRow == headRow && anchorCol <= headCol);
        return forward ? new int[] {anchorRow, anchorCol, headRow, headCol}
                : new int[] {headRow, headCol, anchorRow, anchorCol};
    }
}
