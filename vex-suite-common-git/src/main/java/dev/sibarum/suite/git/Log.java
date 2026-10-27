package dev.sibarum.suite.git;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code git log -z} in the one format {@link Git#log} asks for. Pure: bytes in, commits out, so it is tested
 * without git.
 *
 * <p>Each commit is one NUL-terminated record of six fields separated by the unit separator, {@code 0x1F}, which no
 * name or subject has in it: full hash, short hash, author, author time in seconds, the refs at it ({@code %D}), and
 * the subject. A record this does not recognise is skipped.
 */
final class Log {

    /** The {@code --format} that {@link #parse} reads. */
    static final String FORMAT = "%H%x1f%h%x1f%an%x1f%at%x1f%D%x1f%s";

    private static final String SEP = "\u001f";

    private Log() {
    }

    static List<Commit> parse(byte[] out) {
        List<Commit> commits = new ArrayList<>();
        for (String record : new String(out, StandardCharsets.UTF_8).split("\0")) {
            // after the first, git puts a newline between records as well as the NUL
            String r = record.startsWith("\n") ? record.substring(1) : record;
            String[] f = r.split(SEP, 6);
            if (f.length < 6 || f[0].isEmpty()) {
                continue;
            }
            Instant when;
            try {
                when = Instant.ofEpochSecond(Long.parseLong(f[3].strip()));
            } catch (NumberFormatException e) {
                continue;
            }
            commits.add(new Commit(f[0], f[1], f[2], when, refs(f[4]), f[5]));
        }
        return commits;
    }

    /**
     * {@code %D}: {@code HEAD -> main, origin/main, tag: v1}. The arrow is {@code HEAD} and the branch it points to,
     * which are two refs at the commit; a tag keeps its {@code tag: } so it is not taken for a branch.
     */
    static List<String> refs(String decoration) {
        List<String> refs = new ArrayList<>();
        if (decoration.isBlank()) {
            return refs;
        }
        for (String d : decoration.split(", ")) {
            String s = d.strip();
            int arrow = s.indexOf(" -> ");
            if (arrow >= 0) {
                refs.add(s.substring(0, arrow));
                refs.add(s.substring(arrow + 4));
            } else if (!s.isEmpty()) {
                refs.add(s);
            }
        }
        return refs;
    }
}
