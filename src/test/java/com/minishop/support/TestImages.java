package com.minishop.support;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Bytes of images for upload tests. Only the magic bytes are real (the app checks nothing deeper), the rest is
 * padding up to the requested size.
 */
public final class TestImages {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    private TestImages() {
    }

    /** A PNG signature followed by padding, {@code bytes} long in total (at least the signature). */
    public static byte[] png(int bytes) {
        return padded(PNG_MAGIC, bytes);
    }

    /** A JPEG signature followed by padding, {@code bytes} long in total (at least the signature). */
    public static byte[] jpeg(int bytes) {
        return padded(JPEG_MAGIC, bytes);
    }

    /** {@code RIFF <size> WEBP} followed by padding, {@code bytes} long in total (at least 12). */
    public static byte[] webp(int bytes) {
        byte[] header = new byte[12];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, header, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, header, 8, 4);
        return padded(header, bytes);
    }

    public static byte[] gif() {
        return padded("GIF89a".getBytes(StandardCharsets.US_ASCII), 64);
    }

    public static byte[] svg() {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
    }

    /** HTML content that a client would upload as {@code evil.jpg} with content type {@code image/jpeg}. */
    public static byte[] htmlNamedJpg() {
        return "<html><body><script>alert(1)</script></body></html>".getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] padded(byte[] magic, int bytes) {
        int size = Math.max(bytes, magic.length);
        byte[] result = Arrays.copyOf(magic, size);
        Arrays.fill(result, magic.length, size, (byte) 0x41);
        return result;
    }
}
