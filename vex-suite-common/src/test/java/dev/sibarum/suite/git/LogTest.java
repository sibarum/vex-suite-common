package dev.sibarum.suite.git;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogTest {

    private static final char US = '\u001f';

    private static String record(String hash, String author, long when, String refs, String subject) {
        return hash + US + hash.substring(0, 7) + US + author + US + when + US + refs + US + subject;
    }

    @Test
    void readsEachCommitNewestFirst() {
        String out = record("a41c9e2aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "James", 1793000000L, "HEAD -> main",
                "Show Recent projects above the Open-mode tree") + "\0\n"
                + record("e02f5a8bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "James", 1792900000L, "origin/main, tag: v0.3",
                "Stat tiles: java files, classes") + "\0";
        List<Commit> log = Log.parse(out.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, log.size());
        Commit head = log.get(0);
        assertEquals("a41c9e2", head.shortHash());
        assertEquals("James", head.author());
        assertEquals(Instant.ofEpochSecond(1793000000L), head.when());
        assertEquals(List.of("HEAD", "main"), head.refs());
        assertTrue(head.head());
        assertEquals("Show Recent projects above the Open-mode tree", head.subject());
        Commit older = log.get(1);
        assertEquals(List.of("origin/main", "tag: v0.3"), older.refs());
        assertFalse(older.head());
    }

    @Test
    void aCommitWithNoRefsHasNone() {
        String out = record("19c7b44ccccccccccccccccccccccccccccccccc", "A. N. Other", 1L, "", "Run menu") + "\0";
        assertEquals(List.of(), Log.parse(out.getBytes(StandardCharsets.UTF_8)).get(0).refs());
    }

    @Test
    void aDetachedHeadIsJustHead() {
        assertEquals(List.of("HEAD"), Log.refs("HEAD"));
    }

    @Test
    void aRecordItCannotReadIsSkipped() {
        String out = "not a commit\0" + record("19c7b44ccccccccccccccccccccccccccccccccc", "x", 1L, "", "ok") + "\0";
        assertEquals(1, Log.parse(out.getBytes(StandardCharsets.UTF_8)).size());
    }
}
