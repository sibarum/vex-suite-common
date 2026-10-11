package dev.sibarum.suite.git;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The state of a working tree, as {@link Git#status} saw it, and the two questions a file list asks of it: what is
 * this file, and what is changed inside this folder.
 *
 * <p>Only what differs from the last commit is recorded. A path with no {@link Change} is clean, or outside what was
 * asked about, and {@link #at} answers null for both.
 *
 * <p>A class and not a record because it keeps an index by path beside the list, built once: the lookup is asked
 * once per visible row, every time the list draws.
 */
public final class Status {

    private final Path root;
    private final Branch branch;
    private final List<Change> changes;
    private final Map<String, Change> byPath = new HashMap<>();

    /** What one path is, compared with the last commit. Ordered by how much it wants to be seen, most first. */
    public enum State {
        /** Unmerged: a merge or rebase stopped here and is waiting. */
        CONFLICTED,
        /** Changed and staged, then changed again: what is staged is not what is on disk. */
        STAGED_AND_MODIFIED,
        /** Changed, not staged. */
        MODIFIED,
        /** Changed, renamed or deleted, and staged as it is. */
        STAGED,
        /** New, and staged. */
        ADDED,
        /** New, and git has not been told about it. */
        UNTRACKED,
        /** Matched by a {@code .gitignore}. */
        IGNORED
    }

    /**
     * @param path  relative to the root, {@code /}-separated; ends in {@code /} for a whole directory
     * @param from  for a rename, the path it was renamed from; otherwise null
     * @param state what it is
     */
    public record Change(String path, String from, State state) {
    }

    /**
     * @param head     the branch checked out, or null when {@code HEAD} is detached
     * @param oid      the commit {@code HEAD} is at, or null before the first commit
     * @param upstream the branch it tracks, or null
     * @param ahead    commits here and not upstream
     * @param behind   commits upstream and not here
     */
    public record Branch(String head, String oid, String upstream, int ahead, int behind) {

        /** {@code main ↑2 ↓1}: the branch, or the short commit when detached, then the distance when there is one. */
        public String label() {
            String name = head != null ? head : oid != null ? oid.substring(0, Math.min(7, oid.length())) : "no commits";
            StringBuilder s = new StringBuilder(name);
            if (ahead > 0) {
                s.append(" ↑").append(ahead);
            }
            if (behind > 0) {
                s.append(" ↓").append(behind);
            }
            return s.toString();
        }
    }

    /**
     * What is inside a folder, counted. {@code changed} is every tracked change, staged or not; an untracked directory
     * counts once, as git reports it.
     */
    public record Inside(int conflicted, int changed, int untracked) {

        public static final Inside NOTHING = new Inside(0, 0, 0);

        public boolean isEmpty() {
            return conflicted == 0 && changed == 0 && untracked == 0;
        }
    }

    /**
     * @param root    the repository's top directory
     * @param branch  where {@code HEAD} is, and how far it is from its upstream
     * @param changes every path that is not clean, relative to {@code root}, {@code /}-separated; a directory that
     *                is untracked or ignored as a whole is one entry ending in {@code /}
     */
    public Status(Path root, Branch branch, List<Change> changes) {
        this.root = root;
        this.branch = branch;
        this.changes = List.copyOf(changes);
        for (Change c : this.changes) {
            byPath.put(fold(c.path()), c);
        }
    }

    /**
     * Whether a file's name may be spelled in either case, which is the platform's own answer: on Windows
     * {@code C:\Repo\SUB\a.txt} is git's {@code sub/a.txt}, and a folder typed into the breadcrumb in the wrong case
     * must still find it.
     */
    private static final boolean CASE_BLIND = Path.of("A").equals(Path.of("a"));

    private static String fold(String path) {
        return CASE_BLIND ? path.toLowerCase(java.util.Locale.ROOT) : path;
    }

    public Path root() {
        return root;
    }

    public Branch branch() {
        return branch;
    }

    public List<Change> changes() {
        return changes;
    }

    /**
     * What {@code file} is: its own change, or that of an untracked or ignored directory it is inside. Null when it is
     * clean, outside the repository, or outside what was asked about.
     */
    public State at(Path file) {
        String rel = relative(file);
        if (rel == null || rel.isEmpty()) {
            return null;
        }
        Map<String, Change> m = byPath;
        Change own = m.get(rel);
        if (own == null) {
            own = m.get(rel + "/");
        }
        if (own != null) {
            return own.state();
        }
        // Inside a directory that is untracked or ignored as a whole: git reported the directory, not this.
        for (int slash = rel.indexOf('/'); slash >= 0; slash = rel.indexOf('/', slash + 1)) {
            Change dir = m.get(rel.substring(0, slash + 1));
            if (dir != null) {
                return dir.state();
            }
        }
        return null;
    }

    /** What is changed beneath {@code folder}, at any depth. Ignored paths are not counted: nobody asks after them. */
    public Inside inside(Path folder) {
        String rel = relative(folder);
        if (rel == null) {
            return Inside.NOTHING;
        }
        String prefix = rel.isEmpty() ? "" : rel + "/";
        int conflicted = 0;
        int changed = 0;
        int untracked = 0;
        for (Change c : changes) {
            String path = fold(c.path());
            if (!path.startsWith(prefix) || path.length() == prefix.length()) {
                continue;
            }
            switch (c.state()) {
                case CONFLICTED -> conflicted++;
                case UNTRACKED -> untracked++;
                case IGNORED -> {
                }
                default -> changed++;
            }
        }
        return new Inside(conflicted, changed, untracked);
    }

    /**
     * {@code p} relative to the root, {@code /}-separated and case-folded where the platform ignores case; empty for
     * the root itself, null if it is outside.
     */
    String relative(Path p) {
        try {
            Path r = root.relativize(p.toAbsolutePath().normalize());
            if (r.isAbsolute() || (r.getNameCount() > 0 && r.getName(0).toString().equals(".."))) {
                return null;
            }
            return fold(r.toString().replace('\\', '/'));
        } catch (IllegalArgumentException e) {
            // a different drive, or a different kind of path
            return null;
        }
    }
}
