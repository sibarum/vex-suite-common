package dev.sibarum.suite.git;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * How many lines each changed path adds and removes: the {@code +4 −1} beside a row. Read from
 * {@code git diff --numstat -z}, by {@link #parse}, which is pure, so it is tested without git.
 *
 * <p>With {@code -z} a record is {@code added TAB removed TAB path NUL}. A rename leaves the path empty and follows
 * with two more fields, {@code from NUL to NUL}; it is kept under the path it has now, as {@link Status} keeps it. A
 * binary file counts {@code -} for both, and is marked binary instead.
 *
 * <p>Git counts only what it tracks. An untracked file is counted by {@link #count}, which reads it.
 */
public final class Numstat {

    /** A binary file's {@link Lines}, and an unreadable one's. */
    public static final Lines BINARY = new Lines(0, 0, true);

    /** How far an untracked file is read to count it. A larger one is counted as far as this. */
    static final long COUNT_LIMIT = 8L << 20;

    /** How much of a file git looks at for a NUL to call it binary, and so how much is looked at here. */
    private static final int SNIFF = 8000;

    private final Map<String, Lines> byPath;
    private final String text;

    /**
     * @param added   lines added
     * @param removed lines removed
     * @param binary  whether git would not count it as text; both counts are 0
     */
    public record Lines(int added, int removed, boolean binary) {
    }

    private Numstat(Map<String, Lines> byPath, String text) {
        this.byPath = Map.copyOf(byPath);
        this.text = text;
    }

    /**
     * What git wrote, which {@link #parse} reads back to this: a list of counts cannot cross between the parts of an
     * application, and its text can. Records made by {@link #record} may be added to it, for untracked files.
     */
    public String text() {
        return text;
    }

    /** Read counts from their {@link #text}, or from several such texts joined. */
    public static Numstat parse(String text) {
        return parse(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * One path's counts as git would write them, NUL and all: for an untracked file counted by {@link #count}, so that
     * its counts can travel with git's own.
     */
    public static String record(String path, Lines lines) {
        return lines.binary() ? "-\t-\t" + path + "\0" : lines.added() + "\t" + lines.removed() + "\t" + path + "\0";
    }

    /** The counts for a path as git writes it, relative to the root and {@code /}-separated; null if it has none. */
    public Lines at(String path) {
        return byPath.get(path);
    }

    /** Every path counted, keyed as {@link #at} takes them. */
    public Map<String, Lines> all() {
        return byPath;
    }

    /** Nothing counted: outside a repository, or nothing changed. */
    public static Numstat none() {
        return new Numstat(Map.of(), "");
    }

    static Numstat parse(byte[] out) {
        String text = new String(out, StandardCharsets.UTF_8);
        String[] fields = text.split("\0");
        Map<String, Lines> lines = new HashMap<>();
        for (int i = 0; i < fields.length; i++) {
            String[] r = fields[i].split("\t", 3);
            if (r.length < 3) {
                continue;
            }
            String path = r[2];
            if (path.isEmpty()) {
                // a rename: the old path, then the new one, as fields of their own
                if (i + 2 >= fields.length) {
                    break;
                }
                i++;
                path = fields[++i];
            }
            boolean binary = r[0].equals("-") || r[1].equals("-");
            lines.put(path, binary ? BINARY : new Lines(count(r[0]), count(r[1]), false));
        }
        return new Numstat(lines, text);
    }

    /**
     * The lines of a whole file, as added: what an untracked file would add if it were staged. A file with a NUL in
     * its first 8000 bytes is binary, as git decides it. Only the first {@value #COUNT_LIMIT} bytes are read. An
     * unreadable file is {@link #BINARY}: nothing can be shown of it either way.
     */
    public static Lines count(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            long read = 0;
            int lines = 0;
            boolean last = true;
            int n;
            while (read < COUNT_LIMIT && (n = in.read(buf)) > 0) {
                for (int j = 0; j < n; j++) {
                    if (buf[j] == 0 && read + j < SNIFF) {
                        return BINARY;
                    }
                    if (buf[j] == '\n') {
                        lines++;
                    }
                }
                last = buf[n - 1] == '\n';
                read += n;
            }
            // a last line with no newline at its end is still a line
            if (!last) {
                lines++;
            }
            return new Lines(lines, 0, false);
        } catch (IOException e) {
            return BINARY;
        }
    }

    private static int count(String n) {
        try {
            return Integer.parseInt(n);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
