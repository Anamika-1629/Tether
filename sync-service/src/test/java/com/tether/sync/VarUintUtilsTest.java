package com.tether.sync;

import com.tether.sync.crdt.VarUintUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VarUintUtilsTest {

    @Test
    void testVarUintRoundTrip() {
        int[] testValues = { 0, 1, 42, 127, 128, 255, 300, 16384, 2097151 };

        for (int val : testValues) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            VarUintUtils.writeVarUint(out, val);

            ByteBuffer buf = ByteBuffer.wrap(out.toByteArray());
            int decoded = VarUintUtils.readVarUint(buf);

            assertEquals(val, decoded, "Mismatch for value: " + val);
        }
    }

    @Test
    void testVarUint8ArrayRoundTrip() {
        byte[] original = new byte[] { 1, 2, 3, 4, 5, 0, 127, -1 };

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VarUintUtils.writeVarUint8Array(out, original);

        ByteBuffer buf = ByteBuffer.wrap(out.toByteArray());
        byte[] decoded = VarUintUtils.readVarUint8Array(buf);

        assertArrayEquals(original, decoded);
    }

    @Test
    void testVarStringRoundTrip() {
        String testStr = "Tether CRDT Incident Notes 🚀";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        VarUintUtils.writeVarString(out, testStr);

        ByteBuffer buf = ByteBuffer.wrap(out.toByteArray());
        String decoded = VarUintUtils.readVarString(buf);

        assertEquals(testStr, decoded);
    }
}
