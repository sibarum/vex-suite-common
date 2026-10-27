package dev.sibarum.suite.git;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One file's diff, read from git's unified format: its hunks, and each line in them with its old and new line numbers.
 *
 * <p>The text it was read from is kept ({@link #text}). It is one string, so it can be handed to another part of an
 * application whole and read again there by {@link #parse}, which is pure and public for that reason.
 *
 * <pre>
 * diff --git a/x b/x           the header, up to the first hunk: skipped, except to see "Binary files … differ"
 * &#64;&#64; -12,3 +12,4 &#64;&#64; class X {   a hunk: where it starts on each side, then the line git saw above it
 *  context                      in both
 * -removed                      only in the old
 * +added                        only in the new
 * \ No newline at end of file   about the line before it
 * </pre>
 *
 * A file with CRLF line ends has a CR at the end of each line's text. It is taken off: a line is shown, not written.
 */
public final class Diff {

    /** Which two versions of a file a diff is between. */
    public enum Side {
        /** The working tree against the index: what is not staged. */
        UNSTAGED,
        /** The index against {@code HEAD}: what is staged. */
        STAGED,
        /** An untracked file against nothing: all of it, as added. */
        UNTRACKED
    }

    /** What a line of a hunk is. */
    public enum Kind {
        CONTEXT, ADDED, REMOVED
    }

    /**
     * @param old  its number in the old version; 0 for an added line
     * @param neu  its number in the new version; 0 for a removed line
     * @param kind what it is
     * @param text the line, without its {@code +}, {@code -} or space, and without its line end
     */
    public record Line(int old, int neu, Kind kind, String text) {
    }

    /**
     * @param header   the hunk's own line, {@code @@ -12,3 +12,4 @@ class X {}
     * @param oldStart where it starts in the old version
     * @param newStart where it starts in the new version
     * @param lines    its lines, in order
     */
    public record Hunk(String header, int oldStart, int newStart, List<Line> lines) {

        public Hunk {
            lines = List.copyOf(lines);
        }
    }

    private final String text;
    private final List<Hunk> hunks;
    private final boolean binary;

    private Diff(String text, List<Hunk> hunks, boolean binary) {
        this.text = text;
        this.hunks = List.copyOf(hunks);
        this.binary = binary;
    }

    /** The diff as git wrote it. */
    public String text() {
        return text;
    }

    /** Its hunks in order; none for a binary file or no difference. */
    public List<Hunk> hunks() {
        return hunks;
    }

    /** Whether git said only that the file differs, because it is not text. */
    public boolean binary() {
        return binary;
    }

    /** Lines added, over every hunk. */
    public int added() {
        return (int) hunks.stream().flatMap(h -> h.lines().stream()).filter(l -> l.kind() == Kind.ADDED).count();
    }

    /** Lines removed, over every hunk. */
    public int removed() {
        return (int) hunks.stream().flatMap(h -> h.lines().stream()).filter(l -> l.kind() == Kind.REMOVED).count();
    }

    /** Read a diff of one file. Text that is not a diff reads as a diff of nothing; a line it does not know is skipped. */
    public static Diff parse(String text) {
        List<Hunk> hunks = new ArrayList<>();
        boolean binary = false;
        String header = null;
        int oldStart = 0;
        int newStart = 0;
        int old = 0;
        int neu = 0;
        List<Line> lines = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            String l = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (l.startsWith("@@ ")) {
                if (header != null) {
                    hunks.add(new Hunk(header, oldStart, newStart, lines));
                    lines = new ArrayList<>();
                }
                header = l;
                int[] at = starts(l);
                oldStart = at[0];
                newStart = at[1];
                old = oldStart;
                neu = newStart;
                continue;
            }
            if (header == null) {
                if (l.startsWith("Binary files ") && l.endsWith(" differ")) {
                    binary = true;
                }
                continue;
            }
            if (l.isEmpty()) {
                // the end of the text; a context line that is empty still has its space
                continue;
            }
            switch (l.charAt(0)) {
                case ' ' -> lines.add(new Line(old++, neu++, Kind.CONTEXT, l.substring(1)));
                case '-' -> lines.add(new Line(old++, 0, Kind.REMOVED, l.substring(1)));
                case '+' -> lines.add(new Line(0, neu++, Kind.ADDED, l.substring(1)));
                default -> {
                    // "\ No newline at end of file". A diff of several files is not read: its next header's
                    // "--- a/…" would be taken for a removed line.
                }
            }
        }
        if (header != null) {
            hunks.add(new Hunk(header, oldStart, newStart, lines));
        }
        return new Diff(text, hunks, binary);
    }

    /** A diff of nothing: the file is the same on both sides, or could not be asked about. */
    public static Diff none() {
        return new Diff("", List.of(), false);
    }

    /**
     * An untracked file's diff: the whole of it, as added, in the form git would write it, so its {@link #text} reads
     * back the same. Binary as {@link Numstat#count} decides it; only the first {@value Numstat#COUNT_LIMIT} bytes
     * are shown of a larger file. An unreadable file is a diff of nothing.
     */
    static Diff untracked(Path file) {
        if (Numstat.count(file).binary()) {
            return parse("Binary files differ\n");
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes((int) Numstat.COUNT_LIMIT);
        } catch (IOException e) {
            return none();
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.isEmpty()) {
            return none();
        }
        boolean endsInNewline = content.endsWith("\n");
        String[] lines = (endsInNewline ? content.substring(0, content.length() - 1) : content).split("\n", -1);
        StringBuilder s = new StringBuilder("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String l : lines) {
            s.append('+').append(l).append('\n');
        }
        if (!endsInNewline) {
            s.append("\\ No newline at end of file\n");
        }
        return parse(s.toString());
    }

    /** {@code @@ -a[,b] +c[,d] @@}: a and c. A side that is empty starts at 0, and its first line is then 1. */
    private static int[] starts(String header) {
        int[] at = {1, 1};
        String[] parts = header.split(" ");
        for (int i = 1; i < parts.length && i < 3; i++) {
            String p = parts[i];
            if (p.length() < 2) {
                continue;
            }
            int comma = p.indexOf(',');
            try {
                int n = Integer.parseInt(comma < 0 ? p.substring(1) : p.substring(1, comma));
                at[i - 1] = Math.max(1, n);
            } catch (NumberFormatException e) {
                // left at 1
            }
        }
        return at;
    }
}
