package dev.sibarum.suite.tty;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Utf8ChunksTest {

    @Test
    void aCharacterSplitAcrossTwoReadsIsPutBackTogether() {
        byte[] bytes = "a€b".getBytes(StandardCharsets.UTF_8);   // € is three bytes
        Utf8Chunks chunks = new Utf8Chunks();
        assertEquals("a", chunks.decode(bytes, 2));
        byte[] rest = java.util.Arrays.copyOfRange(bytes, 2, bytes.length);
        assertEquals("€b", chunks.decode(rest, rest.length));
    }

    @Test
    void aLargerChunkThanAnyBeforeKeepsWhatWasCarried() {
        byte[] euro = "€".getBytes(StandardCharsets.UTF_8);
        Utf8Chunks chunks = new Utf8Chunks();
        assertEquals("", chunks.decode(euro, 1));
        byte[] big = new byte[1000];
        big[0] = euro[1];
        big[1] = euro[2];
        java.util.Arrays.fill(big, 2, big.length, (byte) 'x');
        assertEquals("€" + "x".repeat(998), chunks.decode(big, big.length));
    }

    @Test
    void malformedInputIsAReplacementMark() {
        assertEquals("a�b", new Utf8Chunks().decode(new byte[] {'a', (byte) 0xFF, 'b'}, 3));
    }
}
