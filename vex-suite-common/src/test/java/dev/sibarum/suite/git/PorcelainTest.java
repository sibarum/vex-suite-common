package dev.sibarum.suite.git;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PorcelainTest {

    private static final Path ROOT = Path.of("C:/repo").toAbsolutePath();

    /** Records as git writes them with {@code -z}: each ends in a NUL, and a rename carries its old path as a second. */
    private static Status parse(String... records) {
        StringBuilder s = new StringBuilder();
        for (String r : records) {
            s.append(r).append('\0');
        }
        return Porcelain.parse(ROOT, s.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static final String HASHES = "100644 100644 100644 6178079 6178079 ";

    @Test
    void readsTheBranchAndItsDistanceFromUpstream() {
        Status s = parse("# branch.oid 79d8835d50cfd814a67ed82b1c3b7de32be10f8a", "# branch.head main",
                "# branch.upstream origin/main", "# branch.ab +2 -1");
        assertEquals("main", s.branch().head());
        assertEquals("origin/main", s.branch().upstream());
        assertEquals(2, s.branch().ahead());
        assertEquals(1, s.branch().behind());
        assertEquals("main \u21912 \u21931", s.branch().label());
    }

    @Test
    void aDetachedHeadIsNamedByItsShortCommit() {
        Status s = parse("# branch.oid 79d8835d50cfd814a67ed82b1c3b7de32be10f8a", "# branch.head (detached)");
        assertNull(s.branch().head());
        assertEquals("79d8835", s.branch().label());
    }

    @Test
    void theTwoLettersDecideTheState() {
        Status s = parse(
                "1 .M N... " + HASHES + "src/modified.txt",
                "1 M. N... " + HASHES + "src/staged.txt",
                "1 MM N... " + HASHES + "src/both.txt",
                "1 A. N... " + HASHES + "src/added.txt",
                "u UU N... 100644 100644 100644 100644 h1 h2 h3 src/conflict.txt",
                "? src/new.txt",
                "! target/");
        assertEquals(Status.State.MODIFIED, s.at(ROOT.resolve("src/modified.txt")));
        assertEquals(Status.State.STAGED, s.at(ROOT.resolve("src/staged.txt")));
        assertEquals(Status.State.STAGED_AND_MODIFIED, s.at(ROOT.resolve("src/both.txt")));
        assertEquals(Status.State.ADDED, s.at(ROOT.resolve("src/added.txt")));
        assertEquals(Status.State.CONFLICTED, s.at(ROOT.resolve("src/conflict.txt")));
        assertEquals(Status.State.UNTRACKED, s.at(ROOT.resolve("src/new.txt")));
        assertNull(s.at(ROOT.resolve("src/clean.txt")));
    }

    @Test
    void aRenameKeepsItsOldPathAndDoesNotEatTheNextRecord() {
        Status s = parse("2 R. N... " + HASHES + "R100 has space/b d.txt", "has space/b c.txt", "? after.txt");
        assertEquals(2, s.changes().size());
        assertEquals("has space/b d.txt", s.changes().get(0).path());
        assertEquals("has space/b c.txt", s.changes().get(0).from());
        assertEquals(Status.State.STAGED, s.at(ROOT.resolve("has space/b d.txt")));
        assertEquals(Status.State.UNTRACKED, s.at(ROOT.resolve("after.txt")));
    }

    @Test
    void aWholeDirectoryAnswersForItselfAndForEverythingInIt() {
        Status s = parse("! target/", "? src/untracked/");
        assertEquals(Status.State.IGNORED, s.at(ROOT.resolve("target")));
        assertEquals(Status.State.IGNORED, s.at(ROOT.resolve("target/classes/A.class")));
        assertEquals(Status.State.UNTRACKED, s.at(ROOT.resolve("src/untracked")));
        assertEquals(Status.State.UNTRACKED, s.at(ROOT.resolve("src/untracked/deep/x.txt")));
        assertNull(s.at(ROOT.resolve("src")));
    }

    @Test
    void aFolderCountsWhatIsChangedBeneathItButNotWhatIsIgnored() {
        Status s = parse(
                "1 .M N... " + HASHES + "src/a.txt",
                "1 A. N... " + HASHES + "src/deep/b.txt",
                "u UU N... 100644 100644 100644 100644 h1 h2 h3 src/c.txt",
                "? src/new/",
                "! src/out/",
                "1 .M N... " + HASHES + "srcfoo/d.txt");
        Status.Inside in = s.inside(ROOT.resolve("src"));
        assertEquals(1, in.conflicted());
        assertEquals(2, in.changed());
        assertEquals(1, in.untracked());
        assertTrue(s.inside(ROOT.resolve("docs")).isEmpty());
        assertEquals(new Status.Inside(1, 3, 1), s.inside(ROOT));
    }

    @Test
    void aPathOutsideTheRepositoryIsNothing() {
        Status s = parse("? a.txt");
        assertNull(s.at(Path.of("C:/elsewhere/a.txt").toAbsolutePath()));
        assertTrue(s.inside(Path.of("C:/elsewhere").toAbsolutePath()).isEmpty());
    }

    @Test
    void aRecordItDoesNotKnowIsSkipped() {
        Status s = parse("# branch.head main", "z something new", "? a.txt");
        assertEquals(1, s.changes().size());
    }
}
