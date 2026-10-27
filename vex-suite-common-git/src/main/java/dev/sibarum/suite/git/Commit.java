package dev.sibarum.suite.git;

import java.time.Instant;
import java.util.List;

/**
 * One commit, as a history lists it.
 *
 * @param hash    the full hash
 * @param shortHash the hash as short as the repository lets it be and stay unique
 * @param author  the author's name
 * @param when    when it was authored
 * @param refs    the refs pointing at it, as git names them: {@code HEAD}, {@code main}, {@code origin/main},
 *                {@code tag: v1}; empty for most
 * @param subject the first line of its message
 */
public record Commit(String hash, String shortHash, String author, Instant when, List<String> refs, String subject) {

    public Commit {
        refs = List.copyOf(refs);
    }

    /** Whether {@code HEAD} is here. */
    public boolean head() {
        return refs.contains("HEAD");
    }
}
