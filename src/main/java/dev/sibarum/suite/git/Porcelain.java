package dev.sibarum.suite.git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code git status --porcelain=v2 -z --branch}. Pure: bytes in, a {@link Status} out, so it is tested without
 * git.
 *
 * <p>With {@code -z} every record ends in a NUL and paths are written as they are, unquoted, spaces and all; a
 * rename's record is followed by a second NUL-terminated field, the path it was renamed from. Paths are relative to
 * the repository root and {@code /}-separated, whatever directory git was run in. An untracked or ignored directory is
 * one record ending in {@code /}, not one per file inside it.
 *
 * <pre>
 * # branch.oid &lt;commit&gt; | (initial)
 * # branch.head &lt;name&gt; | (detached)
 * # branch.upstream &lt;name&gt;
 * # branch.ab +&lt;ahead&gt; -&lt;behind&gt;
 * 1 &lt;XY&gt; &lt;sub&gt; &lt;mH&gt; &lt;mI&gt; &lt;mW&gt; &lt;hH&gt; &lt;hI&gt; &lt;path&gt;
 * 2 &lt;XY&gt; &lt;sub&gt; &lt;mH&gt; &lt;mI&gt; &lt;mW&gt; &lt;hH&gt; &lt;hI&gt; &lt;Xscore&gt; &lt;path&gt;NUL&lt;from&gt;
 * u &lt;XY&gt; &lt;sub&gt; &lt;m1&gt; &lt;m2&gt; &lt;m3&gt; &lt;mW&gt; &lt;h1&gt; &lt;h2&gt; &lt;h3&gt; &lt;path&gt;
 * ? &lt;path&gt;
 * ! &lt;path&gt;
 * </pre>
 *
 * A record this does not recognise is skipped, so a later git adding a kind of line does not stop the rest being read.
 */
final class Porcelain {

    private Porcelain() {
    }

    static Status parse(Path root, byte[] out) {
        String[] fields = new String(out, StandardCharsets.UTF_8).split("\0");
        String oid = null;
        String head = null;
        String upstream = null;
        int ahead = 0;
        int behind = 0;
        List<Status.Change> changes = new ArrayList<>();
        for (int i = 0; i < fields.length; i++) {
            String f = fields[i];
            if (f.isEmpty()) {
                continue;
            }
            switch (f.charAt(0)) {
                case '#' -> {
                    String[] h = f.split(" ", 3);
                    if (h.length < 3) {
                        continue;
                    }
                    switch (h[1]) {
                        case "branch.oid" -> oid = h[2].equals("(initial)") ? null : h[2];
                        case "branch.head" -> head = h[2].equals("(detached)") ? null : h[2];
                        case "branch.upstream" -> upstream = h[2];
                        case "branch.ab" -> {
                            String[] ab = h[2].split(" ");
                            if (ab.length == 2) {
                                ahead = count(ab[0]);
                                behind = count(ab[1]);
                            }
                        }
                        default -> {
                        }
                    }
                }
                case '1' -> {
                    String[] r = f.split(" ", 9);
                    if (r.length == 9) {
                        changes.add(tracked(r[1], r[8], null));
                    }
                }
                case '2' -> {
                    String[] r = f.split(" ", 10);
                    String from = i + 1 < fields.length ? fields[++i] : null;
                    if (r.length == 10) {
                        changes.add(tracked(r[1], r[9], from));
                    }
                }
                case 'u' -> {
                    String[] r = f.split(" ", 11);
                    if (r.length == 11) {
                        changes.add(new Status.Change(r[10], null, Status.State.CONFLICTED));
                    }
                }
                case '?' -> changes.add(new Status.Change(f.substring(2), null, Status.State.UNTRACKED));
                case '!' -> changes.add(new Status.Change(f.substring(2), null, Status.State.IGNORED));
                default -> {
                }
            }
        }
        return new Status(root, new Status.Branch(head, oid, upstream, ahead, behind), changes);
    }

    /** A tracked file's state from its two status letters: X for the index, Y for the working tree, '.' for none. */
    static Status.Change tracked(String xy, String path, String from) {
        char x = xy.length() > 0 ? xy.charAt(0) : '.';
        char y = xy.length() > 1 ? xy.charAt(1) : '.';
        Status.State state;
        if (x != '.' && y != '.') {
            state = Status.State.STAGED_AND_MODIFIED;
        } else if (x == 'A') {
            state = Status.State.ADDED;
        } else if (x != '.') {
            state = Status.State.STAGED;
        } else {
            state = Status.State.MODIFIED;
        }
        return new Status.Change(path, from, state);
    }

    private static int count(String signed) {
        try {
            return Math.abs(Integer.parseInt(signed));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
