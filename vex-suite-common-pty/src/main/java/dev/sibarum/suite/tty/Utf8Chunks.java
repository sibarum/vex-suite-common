package dev.sibarum.suite.tty;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * A program's output as text, read in chunks: UTF-8, with a character split across two reads put back together
 * rather than turned into two replacement marks, and anything malformed a replacement mark. One per stream, used by
 * the one thread that reads it.
 */
public final class Utf8Chunks {

    /** The most bytes a character split across two reads leaves over: a four-byte sequence less its last. */
    private static final int CARRY = 3;

    private final CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    private ByteBuffer in = ByteBuffer.allocate(0);
    private CharBuffer out = CharBuffer.allocate(0);

    /** The text in the first {@code n} bytes of {@code chunk}, less a character it ends partway through. */
    public String decode(byte[] chunk, int n) {
        if (in.capacity() < n + CARRY) {
            in = ByteBuffer.allocate(n + CARRY).put(in.flip());
            out = CharBuffer.allocate(n + CARRY);
        }
        in.put(chunk, 0, n).flip();
        dec.decode(in, out, false);
        in.compact();
        String text = out.flip().toString();
        out.clear();
        return text;
    }
}
