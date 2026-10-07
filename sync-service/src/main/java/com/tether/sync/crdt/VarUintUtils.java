package com.tether.sync.crdt;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Variable-length unsigned integer (LEB128) encoding and decoding utilities
 * compatible with lib0 / Yjs binary serialization.
 */
public final class VarUintUtils {

    private VarUintUtils() {}

    /**
     * Reads a variable-length unsigned integer from the ByteBuffer.
     */
    public static int readVarUint(ByteBuffer buffer) {
        int result = 0;
        int shift = 0;
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        return result;
    }

    /**
     * Writes a variable-length unsigned integer into a ByteArrayOutputStream.
     */
    public static void writeVarUint(ByteArrayOutputStream out, int value) {
        int val = value;
        while ((val & ~0x7F) != 0) {
            out.write((byte) ((val & 0x7F) | 0x80));
            val >>>= 7;
        }
        out.write((byte) (val & 0x7F));
    }

    /**
     * Reads length-prefixed bytes from the ByteBuffer.
     */
    public static byte[] readVarUint8Array(ByteBuffer buffer) {
        int length = readVarUint(buffer);
        if (length < 0 || length > buffer.remaining()) {
            throw new IllegalArgumentException("Invalid VarUint8Array length: " + length);
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }

    /**
     * Writes length-prefixed bytes into a ByteArrayOutputStream.
     */
    public static void writeVarUint8Array(ByteArrayOutputStream out, byte[] bytes) {
        if (bytes == null) {
            writeVarUint(out, 0);
            return;
        }
        writeVarUint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Reads a variable-length UTF-8 string from the ByteBuffer.
     */
    public static String readVarString(ByteBuffer buffer) {
        byte[] bytes = readVarUint8Array(buffer);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Writes a variable-length UTF-8 string into a ByteArrayOutputStream.
     */
    public static void writeVarString(ByteArrayOutputStream out, String str) {
        if (str == null) {
            writeVarUint(out, 0);
            return;
        }
        writeVarUint8Array(out, str.getBytes(StandardCharsets.UTF_8));
    }
}
