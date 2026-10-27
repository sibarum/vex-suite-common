package dev.sibarum.suite.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NumstatTest {

    @TempDir
    Path dir;

    private static Numstat parse(String out) {
        return Numstat.parse(out.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsEachPathsCounts() {
        Numstat n = parse("4\t1\tsrc/a b.txt\0" + "0\t7\tgone.txt\0");
        assertEquals(new Numstat.Lines(4, 1, false), n.at("src/a b.txt"));
        assertEquals(new Numstat.Lines(0, 7, false), n.at("gone.txt"));
        assertNull(n.at("clean.txt"));
    }

    @Test
    void aRenameIsKeptUnderItsNewPathAndDoesNotEatTheNextRecord() {
        Numstat n = parse("2\t1\t\0old.txt\0new.txt\0" + "1\t0\tafter.txt\0");
        assertEquals(new Numstat.Lines(2, 1, false), n.at("new.txt"));
        assertNull(n.at("old.txt"));
        assertEquals(new Numstat.Lines(1, 0, false), n.at("after.txt"));
    }

    @Test
    void aBinaryFileIsMarkedNotCounted() {
        assertEquals(Numstat.BINARY, parse("-\t-\tpicture.png\0").at("picture.png"));
    }

    @Test
    void nothingIsNothing() {
        assertEquals(0, parse("").all().size());
    }

    @Test
    void anUntrackedFileCountsItsOwnLines() throws IOException {
        Path f = Files.writeString(dir.resolve("a.txt"), "one\ntwo\nthree");
        assertEquals(new Numstat.Lines(3, 0, false), Numstat.count(f));
        Path g = Files.writeString(dir.resolve("b.txt"), "one\r\ntwo\r\n");
        assertEquals(new Numstat.Lines(2, 0, false), Numstat.count(g));
        Path empty = Files.writeString(dir.resolve("empty.txt"), "");
        assertEquals(new Numstat.Lines(0, 0, false), Numstat.count(empty));
    }

    @Test
    void anUntrackedFileWithANulIsBinary() throws IOException {
        Path f = Files.write(dir.resolve("a.bin"), new byte[] {'a', 0, 'b', '\n'});
        assertEquals(Numstat.BINARY, Numstat.count(f));
    }

    @Test
    void aFileThatCannotBeReadIsBinary() {
        assertEquals(Numstat.BINARY, Numstat.count(dir.resolve("missing.txt")));
    }
}
