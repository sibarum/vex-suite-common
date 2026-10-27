package dev.sibarum.suite.git;

/**
 * A branch, local or remote, as a list of branches shows it. Read by {@link Refs}.
 *
 * @param name     the short name: {@code main}, {@code origin/main}
 * @param remote   whether it is a remote-tracking branch, which is switched to by making a local one
 * @param head     whether it is the branch checked out
 * @param oid      the short hash of the commit it is at
 * @param upstream the branch it tracks, short; null if none, and always null for a remote one
 * @param ahead    commits here and not upstream
 * @param behind   commits upstream and not here
 * @param gone     whether it tracks an upstream that no longer exists
 */
public record Ref(String name, boolean remote, boolean head, String oid, String upstream, int ahead, int behind,
                  boolean gone) {

    /** {@code ↑2 ↓1}, {@code gone}, or empty: how it stands against what it tracks. */
    public String distance() {
        if (gone) {
            return "gone";
        }
        StringBuilder s = new StringBuilder();
        if (ahead > 0) {
            s.append('↑').append(ahead);
        }
        if (behind > 0) {
            if (!s.isEmpty()) {
                s.append(' ');
            }
            s.append('↓').append(behind);
        }
        return s.toString();
    }
}
