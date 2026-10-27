package dev.sibarum.suite.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffTest {

    private static final String MODIFIED = """
            diff --git a/Ui.java b/Ui.java
            index 1111111..2222222 100644
            --- a/Ui.java
            +++ b/Ui.java
            @@ -27,3 +27,4 @@ final class Ui {
                 /** The navigator's width. */
            -    private static final Length NAVIGATOR = Length.rem(16);
            +    private static final Length NAVIGATOR = Length.rem(18);
            +
             \s
            @@ -80,2 +81,1 @@ final class Ui {
            -    gone();
                 kept();
            """;

    @Test
    void readsHunksAndNumbersEachSide() {
        Diff d = Diff.parse(MODIFIED);
        assertEquals(2, d.hunks().size());
        Diff.Hunk first = d.hunks().get(0);
        assertEquals("@@ -27,3 +27,4 @@ final class Ui {", first.header());
        assertEquals(27, first.oldStart());
        assertEquals(27, first.newStart());
        assertEquals(List.of(
                new Diff.Line(27, 27, Diff.Kind.CONTEXT, "    /** The navigator's width. */"),
                new Diff.Line(28, 0, Diff.Kind.REMOVED, "    private static final Length NAVIGATOR = Length.rem(16);"),
                new Diff.Line(0, 28, Diff.Kind.ADDED, "    private static final Length NAVIGATOR = Length.rem(18);"),
                new Diff.Line(0, 29, Diff.Kind.ADDED, ""),
                new Diff.Line(29, 30, Diff.Kind.CONTEXT, " ")), first.lines());
        Diff.Hunk second = d.hunks().get(1);
        assertEquals(new Diff.Line(80, 0, Diff.Kind.REMOVED, "    gone();"), second.lines().get(0));
        assertEquals(new Diff.Line(81, 81, Diff.Kind.CONTEXT, "    kept();"), second.lines().get(1));
        assertEquals(2, d.added());
        assertEquals(2, d.removed());
        assertFalse(d.binary());
    }

    @Test
    void anAddedFileStartsItsOldSideAtNothing() {
        Diff d = Diff.parse("""
                diff --git a/new.txt b/new.txt
                new file mode 100644
                --- /dev/null
                +++ b/new.txt
                @@ -0,0 +1,2 @@
                +one
                +two
                \\ No newline at end of file
                """);
        assertEquals(List.of(new Diff.Line(0, 1, Diff.Kind.ADDED, "one"), new Diff.Line(0, 2, Diff.Kind.ADDED, "two")),
                d.hunks().get(0).lines());
    }

    @Test
    void aDeletedFileEndsItsNewSideAtNothing() {
        Diff d = Diff.parse("@@ -1 +0,0 @@\n-only\n");
        assertEquals(List.of(new Diff.Line(1, 0, Diff.Kind.REMOVED, "only")), d.hunks().get(0).lines());
    }

    @Test
    void crlfLinesLoseTheirCarriageReturn() {
        Diff d = Diff.parse("@@ -1,1 +1,1 @@\r\n-a\r\n+b\r\n");
        assertEquals("a", d.hunks().get(0).lines().get(0).text());
        assertEquals("b", d.hunks().get(0).lines().get(1).text());
    }

    @Test
    void aBinaryFileHasNoHunks() {
        Diff d = Diff.parse("diff --git a/p.png b/p.png\nindex 1..2 100644\nBinary files a/p.png and b/p.png differ\n");
        assertTrue(d.binary());
        assertTrue(d.hunks().isEmpty());
    }

    @Test
    void theTextIsKeptSoItCanBeReadAgain() {
        Diff d = Diff.parse(MODIFIED);
        assertEquals(MODIFIED, d.text());
        assertEquals(d.hunks(), Diff.parse(d.text()).hunks());
    }

    @Test
    void anUntrackedFileIsAllAddedAndReadsBackTheSame(@TempDir Path dir) throws IOException {
        Path f = Files.writeString(dir.resolve("new.txt"), "one\r\n\r\nthree");
        Diff d = Diff.untracked(f);
        assertEquals(List.of(new Diff.Line(0, 1, Diff.Kind.ADDED, "one"), new Diff.Line(0, 2, Diff.Kind.ADDED, ""),
                new Diff.Line(0, 3, Diff.Kind.ADDED, "three")), d.hunks().get(0).lines());
        assertEquals("@@ -0,0 +1,3 @@", d.hunks().get(0).header());
        assertEquals(d.hunks(), Diff.parse(d.text()).hunks());
        assertTrue(Diff.untracked(Files.writeString(dir.resolve("empty.txt"), "")).hunks().isEmpty());
        assertTrue(Diff.untracked(Files.write(dir.resolve("a.bin"), new byte[] {1, 0, 2})).binary());
    }

    @Test
    void nothingIsADiffOfNothing() {
        assertTrue(Diff.parse("").hunks().isEmpty());
        assertTrue(Diff.none().hunks().isEmpty());
    }
}
