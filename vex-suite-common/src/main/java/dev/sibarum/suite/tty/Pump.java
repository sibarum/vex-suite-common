package dev.sibarum.suite.tty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * A program's output, read into a {@link Terminal} on a thread of its own until it ends: UTF-8, with a character
 * split across two reads put back together rather than turned into two replacement marks.
 */
public final class Pump {

    /** Where the bytes come from: a read into {@code buf} that blocks, and returns the count or -1 at the end. */
    @FunctionalInterface
    public interface Source {
        int read(byte[] buf) throws IOException;
    }

    private Pump() {
    }

    /** A pseudo-terminal's output. */
    public static Source of(Pty pty) {
        return pty::read;
    }

    /** A pipe's. */
    public static Source of(InputStream in) {
        return in::read;
    }

    /**
     * Start reading {@code from} into {@code term} on a daemon thread called {@code name}: {@code wrote} runs after
     * each read has been fed, and {@code ended} once, when the source has ended or failed.
     */
    public static Thread start(Source from, Terminal term, Runnable wrote, Runnable ended, String name) {
        Thread t = new Thread(() -> {
            try {
                CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
                byte[] chunk = new byte[16384];
                ByteBuffer in = ByteBuffer.allocate(chunk.length + 8);
                CharBuffer out = CharBuffer.allocate(chunk.length + 8);
                int n;
                while ((n = from.read(chunk)) > 0) {
                    in.put(chunk, 0, n).flip();
                    dec.decode(in, out, false);
                    in.compact();
                    out.flip();
                    term.feed(out);
                    out.clear();
                    wrote.run();
                }
            } catch (IOException e) {
                // A pipe that breaks is a program that has gone: the end, as far as the terminal is concerned.
            } finally {
                ended.run();
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }
}
