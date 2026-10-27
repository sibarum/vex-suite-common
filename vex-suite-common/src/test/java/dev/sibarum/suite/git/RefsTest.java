package dev.sibarum.suite.git;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RefsTest {

    private static List<Ref> parse(String... lines) {
        return Refs.parse((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String line(String... fields) {
        return String.join("\u001f", fields);
    }

    @Test
    void readsLocalAndRemoteBranches() {
        List<Ref> refs = parse(
                line("refs/heads/main", "main", "a41c9e2", "origin/main", "ahead 2", "*"),
                line("refs/heads/git-mode", "git-mode", "5f1e0b9", "origin/git-mode", "ahead 5, behind 3", " "),
                line("refs/heads/sdf-icons", "sdf-icons", "08aa6f1", "", "", " "),
                line("refs/remotes/origin/HEAD", "origin", "e02f5a8", "", "", " "),
                line("refs/remotes/origin/main", "origin/main", "e02f5a8", "", "", " "));
        assertEquals(List.of(
                new Ref("main", false, true, "a41c9e2", "origin/main", 2, 0, false),
                new Ref("git-mode", false, false, "5f1e0b9", "origin/git-mode", 5, 3, false),
                new Ref("sdf-icons", false, false, "08aa6f1", null, 0, 0, false),
                new Ref("origin/main", true, false, "e02f5a8", null, 0, 0, false)), refs);
        assertEquals("↑2", refs.get(0).distance());
        assertEquals("↑5 ↓3", refs.get(1).distance());
        assertEquals("", refs.get(2).distance());
    }

    @Test
    void anUpstreamThatWentAwayIsGone() {
        Ref r = parse(line("refs/heads/old", "old", "1234567", "origin/old", "gone", " ")).get(0);
        assertEquals(true, r.gone());
        assertEquals("gone", r.distance());
    }

    @Test
    void aLineItCannotReadIsSkipped() {
        assertEquals(1, parse("nonsense", line("refs/tags/v1", "v1", "1234567", "", "", " "),
                line("refs/heads/main", "main", "1234567", "", "", "*")).size());
    }
}
