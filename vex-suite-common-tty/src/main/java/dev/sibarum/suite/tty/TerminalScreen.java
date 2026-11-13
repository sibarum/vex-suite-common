package dev.sibarum.suite.tty;

import dev.sibarum.suite.tty.Terminal.Line;
import dev.sibarum.suite.tty.Terminal.Palette;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Addressed;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.input.WheelEvent;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.spi.Seams;
import dev.vexelray.gui.core.style.Paint;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.text.GlyphLayout;
import sibarum.atchung.Subscription;
import sibarum.atchung.Topic;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

/**
 * A {@link Terminal} drawn on a node as a cell grid: what any window that shows a terminal shares, whatever feeds it.
 * MainFrame puts a shell behind one; VexelRay IDE puts a build behind one and keeps it after the build has ended.
 *
 * <p>The widget owns its terminal, and talks to whatever runs behind it by message, addressed by the widget
 * (vexelray-framework's {@code docs/threading.md}, T3.8):
 * <ul>
 *   <li><b>In:</b> what the program wrote, a {@link Feed} on {@link #COMMANDS}, which the widget parses into its
 *       terminal and draws; and the commands a key map binds ({@link Page}, {@link CopyOrInterrupt}, {@link Paste},
 *       {@link CopyAll}).</li>
 *   <li><b>Out:</b> what goes to the program on {@link #SENT}, every one in order: a key as the terminal spells it, a
 *       paste, and what the terminal answers a program's question with. The grid's size in cells on {@link #FITTED},
 *       which a pseudoconsole wants to be told; and the title the program gave itself on {@link #TITLED}.</li>
 * </ul>
 * Nothing here starts a program or reads one: that is the application's, which hears {@link #SENT} and writes it to
 * the program, and sends what the program writes back as a {@link Feed}.
 *
 * <p>What only the picture needs stays in the widget, with no message (T3.9): scrolling back through the history (the
 * wheel, {@link Page}), selecting with a drag, and copying. The keys that are typing reach it while it has the focus:
 * printable characters, the keys {@link #keySequence} spells, and Tab, which is claimed so that it is typing rather
 * than moving the focus. Every other chord is the key map's.
 *
 * <p>GUI thread only, as building a widget is: the commands are applied there, and the node's events arrive there.
 */
public final class TerminalScreen {

    /**
     * What goes to the program, every one, in order: what is typed ({@code typed}), a paste (also typed), and what
     * the terminal answers a program's question with on its own (not typed). Write {@code text} to the program as
     * UTF-8.
     */
    public static final Topic<Sent> SENT = Addressed.edge(Sent.class);

    /** The grid's size in cells: the last in a frame. A pseudoconsole is told it, and lays its screen out to it. */
    public static final Topic<Fitted> FITTED = Addressed.state(Fitted.class, Fitted::terminal);

    /** The title the program gave itself (an OSC 0 or 2): the last in a frame. */
    public static final Topic<Titled> TITLED = Addressed.state(Titled.class, Titled::terminal);

    /** What the application asks of a terminal, routed by its address: see {@link Command}. */
    public static final Topic<Command> COMMANDS = Addressed.edge(Command.class);

    /** {@code text} goes to the program behind the terminal at {@code terminal}; {@code typed} if the user sent it. */
    public record Sent(String terminal, String text, boolean typed) implements Addressed {
        @Override
        public Object address() {
            return terminal;
        }
    }

    /** The terminal at {@code terminal} is {@code cols} by {@code rows} cells. */
    public record Fitted(String terminal, int cols, int rows) implements Addressed {
        @Override
        public Object address() {
            return terminal;
        }
    }

    /** The program behind the terminal at {@code terminal} called itself {@code title}. */
    public record Titled(String terminal, String title) implements Addressed {
        @Override
        public Object address() {
            return terminal;
        }
    }

    /**
     * Something the application asks of a terminal. Each kind says what it does to the terminal itself, so the
     * terminal never switches over the kinds (vexelray-gui's style rule).
     */
    public sealed interface Command extends Addressed permits Feed, Page, CopyOrInterrupt, Paste, CopyAll {
        /** The address of the terminal this is for. */
        String terminal();

        @Override
        default Object address() {
            return terminal();
        }

        /** Do it, on the GUI thread. */
        void applyTo(TerminalScreen target);
    }

    /** The program wrote {@code text}: {@link #feed}. */
    public record Feed(String terminal, String text) implements Command {
        @Override
        public void applyTo(TerminalScreen target) {
            target.feed(text);
        }
    }

    /** A page back ({@code direction} 1) or forward (-1) through the history: {@link #page}. */
    public record Page(String terminal, int direction) implements Command {
        @Override
        public void applyTo(TerminalScreen target) {
            target.page(direction);
        }
    }

    /** Copy the selection, or with nothing selected send the interrupt a shell's Ctrl+C is: {@link #copyOrInterrupt}. */
    public record CopyOrInterrupt(String terminal) implements Command {
        @Override
        public void applyTo(TerminalScreen target) {
            target.copyOrInterrupt();
        }
    }

    /** Send what the clipboard holds, as a paste: {@link #paste}. */
    public record Paste(String terminal) implements Command {
        @Override
        public void applyTo(TerminalScreen target) {
            target.paste();
        }
    }

    /** Everything the terminal holds onto the clipboard: {@link #copyAll}. */
    public record CopyAll(String terminal) implements Command {
        @Override
        public void applyTo(TerminalScreen target) {
            target.copyAll();
        }
    }

    /** What a cell's background resolves to where the node's own shows through: nothing is filled. */
    private static final int NO_FILL = Terminal.DEFAULT;

    /** The screen's default foreground, as a cell's resolved colour (an inverse cell's background, say). */
    private static final int DEFAULT_FG = -2;

    /** The screen's default background, as a cell's resolved colour (an inverse cell's foreground). */
    private static final int DEFAULT_BG = -3;

    /** The 240 xterm colours above the sixteen: the program's, the same in every look. */
    private static final Paint[] XTERM = new Paint[256];

    static {
        for (int n = 16; n < 256; n++) {
            XTERM[n] = Paint.of(Color.rgb(Palette.xterm(n)));
        }
    }

    /** The grid a terminal has before it is first laid out. */
    private static final int COLS = 80, ROWS = 24;

    /**
     * How long after a resize what the pseudoconsole sends is taken for its repaint of the new size rather than the
     * program writing (see {@link #feed}). Its repaint takes a frame or two; the margin is wide because mistaking a
     * repaint for a write is the error that matters, and mistaking a write for a repaint costs only a row a while.
     */
    private static final long REPAINT_NANOS = 500_000_000L;

    private final Gui gui;
    private final Terminal term;
    private final Metrics glyphs;
    private final Node node;
    private String name;
    private Subscription commands;

    private TerminalColors colors = TerminalColors.CAMPBELL;
    private float px, cw, lineH, ascent;
    /** The node's content area as last laid out. */
    private float boxW, boxH;
    private boolean active = true;
    /** The picture is behind the terminal: drawn when the screen is next on show. */
    private boolean stale = true;

    /** A Windows pseudoconsole is behind the terminal: see {@link #pseudoconsole}. */
    private boolean pseudoconsole;
    /** How many rows the terminal had when the program last wrote; zero until it first does. */
    private int wroteAtRows;
    /** When the terminal was last resized, in nanos. */
    private long resizedAt;
    /** A row is being held back (see {@link #pseudoconsole}), and the next write gives it back. */
    private boolean heldBack;

    private int scrollOffset;
    /** What the wheel has turned that is not yet a whole line, in px; positive is back into the history. */
    private float wheelPx;
    private boolean hasSel;
    private int anchorRow, anchorCol, headRow, headCol;

    /** How big a cell is at a type size: the one face's advance, ascent and line height. */
    interface Metrics {
        float advance(float px);

        float ascent(float px);

        float lineHeight(float px);
    }

    /**
     * @param face   the atlas face {@code glyphs} is, which the node's text is set in
     * @param fontPx the type size, in the picture's pixels
     */
    public TerminalScreen(Gui gui, GlyphLayout glyphs, int face, float fontPx) {
        this(gui, new Metrics() {
            @Override
            public float advance(float px) {
                return glyphs.advance('M', px);
            }

            @Override
            public float ascent(float px) {
                return glyphs.ascent(px);
            }

            @Override
            public float lineHeight(float px) {
                return glyphs.lineHeight(px);
            }
        }, face, fontPx);
    }

    /** With the cell measured by {@code metrics}: a test's, which has no atlas. */
    TerminalScreen(Gui gui, Metrics metrics, int face, float fontPx) {
        this.gui = gui;
        this.glyphs = metrics;
        this.term = new Terminal(COLS, ROWS, new Terminal.Host() {
            @Override
            public void reply(String s) {
                send(s, false);
            }

            @Override
            public void title(String title) {
                gui.bus().publish(TITLED, new Titled(address(), title));
            }
        });
        measure(fontPx);
        this.node = gui.box().role("terminal").width(Length.FILL).height(Length.grow(1)).font(face)
                .background(colors.background());
        this.name = "terminal-" + node.id();
        this.commands = gui.mailbox(COMMANDS, address(), c -> c.applyTo(this));
        wire();
    }

    /**
     * Call this terminal {@code name} in its tree ("tab-3"): its {@link #address()} is the tree's name and this,
     * which its messages carry and its commands are sent to.
     */
    public TerminalScreen name(String name) {
        java.util.Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a widget's name is not empty");
        }
        if (!name.equals(this.name)) {
            Subscription next = gui.mailbox(COMMANDS, gui.address(name), c -> c.applyTo(this));
            commands.close();
            commands = next;
            this.name = name;
        }
        return this;
    }

    /** Where this terminal is on the bus: {@code "<tree>.<name>"}. */
    public String address() {
        return gui.address(name);
    }

    /**
     * Draw the terminal in {@code colors}: its sixteen ANSI colours, its default background and foreground (the node's
     * background is the first) and its selection's wash. {@link TerminalColors#CAMPBELL} until told otherwise.
     *
     * <p>They are paints, resolved every frame in the look the tree is drawn in, and the cells keep the colour a program
     * named rather than what it stood for. So a set that answers per look ({@link TerminalColors#following}) is handed
     * once, and a change of look recolours everything already on screen, fading with the tree.
     */
    public TerminalScreen colors(TerminalColors colors) {
        this.colors = java.util.Objects.requireNonNull(colors, "colors");
        node.background(colors.background());
        redraw();
        return this;
    }

    /** The colours the terminal is drawn in: see {@link #colors(TerminalColors)}. */
    public TerminalColors colors() {
        return colors;
    }

    /**
     * A Windows pseudoconsole is behind the terminal.
     *
     * <p>Conhost, behind the pseudoconsole, keeps its own screen and has a flaw this works around. Shrink it past
     * the cursor and grow it back to the height it had when the program last wrote, and it draws the grown screen
     * one way and puts its cursor back where it was before the shrink: the program's next write lands rows below
     * the line the terminal shows, past a band of blank ones. Any other height, or the same height after the program
     * has written at another, keeps the two in step. So a fit that would land on that height stops a row short, and
     * the program's next write (a keystroke's echo will do) gives the row back.
     */
    public TerminalScreen pseudoconsole() {
        pseudoconsole = true;
        return this;
    }

    public Node node() {
        return node;
    }

    /** The terminal this draws: what the program's output has made of it so far. */
    public Terminal terminal() {
        return term;
    }

    public int cols() {
        return term.cols();
    }

    public int rows() {
        return term.rows();
    }

    /**
     * The program wrote {@code text}: parse it into the terminal and draw it. What a pseudoconsole sends just after
     * a resize is its repaint, not the program, and does not count as the program writing.
     */
    public void feed(CharSequence text) {
        term.feed(text);
        if (System.nanoTime() - resizedAt > REPAINT_NANOS) {
            wroteAtRows = term.rows();
            if (heldBack) {
                refit();
            }
        }
        redraw();
    }

    /** Change the type size: the cell grid is remeasured, and refitted to the node. */
    public void fontPx(float size) {
        if (size == px) {
            return;
        }
        measure(size);
        refit();
        redraw();
    }

    /**
     * Only a screen on show draws; one behind another keeps its terminal, parses what it is fed, and draws when it
     * comes back. On show until told otherwise.
     */
    public void active(boolean on) {
        active = on;
        if (on && stale) {
            render();
        }
    }

    /** Let go of the commands' mailbox. The node is the caller's to remove. */
    public void close() {
        commands.close();
    }

    // -- scrolling and selection ------------------------------------------------------------------------------

    /** Scroll back {@code lines} into the history, or forward for a negative count. */
    public void scrollBy(int lines) {
        scrollOffset += lines;
        redraw();
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
            redraw();
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
        redraw();
    }

    /** Copy the selection and let it go; with nothing selected, the interrupt a shell's Ctrl+C sends, 0x03. */
    public void copyOrInterrupt() {
        if (hasSel) {
            copySelection();
        } else {
            send("\u0003", true);
        }
    }

    /** Send what the clipboard holds, as {@link #pasteText} spells it. Nothing when it holds no text. */
    public void paste() {
        String text = gui.clipboard().get();
        if (text != null && !text.isEmpty()) {
            send(pasteText(text), true);
        }
    }

    /** Everything the terminal holds, the history and the screen, with the blank lines at the end left off. */
    public String allText() {
        String all = term.text(0, 0, term.scrollbackSize() + term.rows() - 1, term.cols() - 1);
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

    /** Send {@code text} to the program: back to the live screen first, so what it echoes is seen. */
    private void send(String text, boolean typed) {
        if (typed) {
            snapToBottom();
        }
        gui.bus().publish(SENT, new Sent(address(), text, typed));
    }

    // -- picture ----------------------------------------------------------------------------------------------

    private void measure(float size) {
        px = size;
        cw = glyphs.advance(size);
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
        // The pseudoconsole's flaw (see pseudoconsole()): not back to the height the program last wrote at.
        heldBack = pseudoconsole && rows > 1 && rows == wroteAtRows && term.rows() != rows;
        if (heldBack) {
            rows--;
        }
        if (cols != term.cols() || rows != term.rows()) {
            resizedAt = System.nanoTime();
            term.resize(cols, rows);
            gui.bus().publish(FITTED, new Fitted(address(), cols, rows));
            redraw();
        }
    }

    /** The picture is behind the terminal: draw it now if the screen is on show, else when it comes back. */
    private void redraw() {
        if (active) {
            render();
        } else {
            stale = true;
        }
    }

    private void render() {
        stale = false;
        Sketch s = new Sketch();
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
                int bg = cellBg(l, c, cursorRow && c == term.cursorX());
                int e = c + 1;
                while (e < w && bg == cellBg(l, e, cursorRow && e == term.cursorX())) {
                    e++;
                }
                if (bg != NO_FILL) {
                    s.fill(c * cw, y, (e - c) * cw, lineH, paint(bg));
                }
                c = e;
            }
            if (selTo >= selFrom) {
                s.fill(selFrom * cw, y, (Math.min(selTo, cols - 1) - selFrom + 1) * cw, lineH, colors.selection());
            }
            // glyphs, merged into runs of one colour; anything outside Latin-1 is placed on its own cell
            StringBuilder run = new StringBuilder();
            int runStart = 0, runFg = 0, runAt = 0;
            for (int c = 0; c <= w; c++) {
                int ch = c < w ? l.ch[c] : 0;
                int fg = c < w ? cellFg(l, c, cursorRow && c == term.cursorX()) : 0;
                int at = c < w ? l.at[c] & (Terminal.BOLD | Terminal.DIM | Terminal.UNDERLINE | Terminal.STRIKE) : 0;
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
                    CellArt.draw(s, ch, c * cw, y, cw, lineH, ink(fg, at));
                } else if (ch > 0) {
                    text(s, new String(Character.toChars(ch)), c, y, fg, at);
                }
            }
        }
        node.picture(s.picture());
    }

    private void text(Sketch s, String str, int col, float y, int fg, int at) {
        Paint color = ink(fg, at);
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

    /**
     * The colour a cell's background is filled in, as a cell holds one ({@link Terminal#DEFAULT}'s forms) or
     * {@link #DEFAULT_FG}; {@link #NO_FILL} where the node's own background shows through.
     */
    private static int cellBg(Line l, int c, boolean cursor) {
        boolean inv = ((l.at[c] & Terminal.INVERSE) != 0) ^ cursor;
        if (!inv) {
            return l.bg[c];
        }
        return l.fg[c] == Terminal.DEFAULT ? DEFAULT_FG : l.fg[c];
    }

    /** The colour a cell's glyph is drawn in, as a cell holds one, or {@link #DEFAULT_FG} or {@link #DEFAULT_BG}. */
    private static int cellFg(Line l, int c, boolean cursor) {
        boolean inv = ((l.at[c] & Terminal.INVERSE) != 0) ^ cursor;
        if (inv) {
            return l.bg[c] == Terminal.DEFAULT ? DEFAULT_BG : l.bg[c];
        }
        return l.fg[c] == Terminal.DEFAULT ? DEFAULT_FG : l.fg[c];
    }

    /** {@code fg} as a glyph is drawn in it: dimmed toward the default background under {@link Terminal#DIM}. */
    private Paint ink(int fg, int at) {
        Paint p = paint(fg);
        if ((at & Terminal.DIM) == 0) {
            return p;
        }
        TerminalColors.Dimmed dim = new TerminalColors.Dimmed(p, colors.background());
        // Both fixed, the mix is too: drawn as a colour, with nothing to resolve each frame.
        return p.fixed() != null && colors.background().fixed() != null ? Paint.of(dim.in(null)) : dim;
    }

    /** A resolved cell colour as the paint it is drawn in, in this screen's {@link TerminalColors}. */
    private Paint paint(int color) {
        if (color == DEFAULT_FG) {
            return colors.foreground();
        }
        if (color == DEFAULT_BG) {
            return colors.background();
        }
        if ((color & Terminal.RGB) != 0) {
            return Paint.of(Color.rgb(color & 0xFFFFFF));
        }
        return color < 16 ? colors.ansi(color) : XTERM[color];
    }

    // -- input ------------------------------------------------------------------------------------------------

    private void wire() {
        Seams seams = Seams.of(gui);
        seams.onResize(node, this::laidOut);
        seams.onDrag(node, this::drag);
        seams.onWheel(node, this::wheel);
        seams.onChar(node, cp -> {
            if (cp >= 0x20 && cp != 0x7f) {
                send(new String(Character.toChars(cp)), true);
            }
        });
        seams.onKey(node, this::key);
        // Tab is typing, not a command: claimed only to outrank the focus traversal.
        seams.claim(node, Shortcut.of(Key.TAB), ClaimScope.FOCUSED, () -> send("\t", true));
        seams.claim(node, Shortcut.of(Key.TAB, Modifier.SHIFT), ClaimScope.FOCUSED, () -> send("\u001b[Z", true));
        seams.onContextClick(node, c -> {
            if (hasSel) {
                copySelection();
            } else {
                paste();
            }
        });
    }

    private void laidOut(NodeLayout l) {
        if (!l.present()) {
            return;
        }
        boxW = l.content().w();
        boxH = l.content().h();
        refit();
    }

    /** Any key no claim took: to the program, as the terminal spells it. Enter over a selection copies it instead. */
    private void key(KeyEvent e) {
        if (e.key() == Key.ENTER && hasSel) {
            copySelection();
            return;
        }
        String out = keySequence(e.key(), e.has(Modifier.CONTROL), e.has(Modifier.ALT), e.has(Modifier.SHIFT));
        if (out != null) {
            send(out, true);
        }
    }

    /** The wheel scrolls the history: toward the end of the content is toward the live screen. */
    private void wheel(WheelEvent e) {
        wheelPx -= e.dy();
        int lines = (int) (wheelPx / lineH);
        if (lines != 0) {
            wheelPx -= lines * lineH;
            scrollBy(lines);
        }
    }

    private void drag(DragEvent d) {
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
        redraw();
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
