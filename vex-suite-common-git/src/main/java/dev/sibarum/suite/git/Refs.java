package dev.sibarum.suite.git;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code git for-each-ref} over {@code refs/heads} and {@code refs/remotes}, in the one format {@link Git#branches}
 * asks for. Pure: bytes in, {@link Ref}s out, so it is tested without git.
 *
 * <p>One line per ref, fields separated by the unit separator, {@code 0x1F}: the full ref name, the short name, the
 * short hash, the upstream's short name, how it stands against the upstream ({@code ahead 2, behind 1}, {@code gone},
 * or nothing), and {@code *} if it is checked out. A remote's {@code HEAD}, {@code origin/HEAD}, names another branch
 * rather than being one, and is left out. A line this does not recognise is skipped.
 */
final class Refs {

    /** The {@code --format} that {@link #parse} reads. */
    static final String FORMAT =
            "%(refname)%1f%(refname:short)%1f%(objectname:short)%1f%(upstream:short)%1f%(upstream:track,nobracket)%1f%(HEAD)";

    private static final String SEP = "\u001f";

    private Refs() {
    }

    static List<Ref> parse(byte[] out) {
        List<Ref> refs = new ArrayList<>();
        for (String line : new String(out, StandardCharsets.UTF_8).split("\n")) {
            String[] f = line.split(SEP, -1);
            if (f.length < 6) {
                continue;
            }
            boolean remote = f[0].startsWith("refs/remotes/");
            if (!remote && !f[0].startsWith("refs/heads/")) {
                continue;
            }
            if (remote && f[0].endsWith("/HEAD")) {
                continue;
            }
            int ahead = 0;
            int behind = 0;
            boolean gone = false;
            for (String t : f[4].split(", ")) {
                String s = t.strip();
                if (s.equals("gone")) {
                    gone = true;
                } else if (s.startsWith("ahead ")) {
                    ahead = count(s.substring(6));
                } else if (s.startsWith("behind ")) {
                    behind = count(s.substring(7));
                }
            }
            refs.add(new Ref(f[1], remote, f[5].strip().equals("*"), f[2],
                    remote || f[3].isEmpty() ? null : f[3], ahead, behind, gone));
        }
        return refs;
    }

    private static int count(String n) {
        try {
            return Integer.parseInt(n.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
