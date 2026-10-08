package com.tether.sync;

import static org.junit.jupiter.api.Assertions.*;

import com.tether.sync.store.InMemoryUpdateLogStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryUpdateLogStoreTest {

    private InMemoryUpdateLogStore store;

    @BeforeEach
    void setUp() { store = new InMemoryUpdateLogStore(); }

    private static byte[] u(int i) { return new byte[] {(byte) i}; }

    @Test
    void appendReturnsGrowingLengthAndLoadKeepsOrder() {
        assertEquals(1, store.append("r", u(1)));
        assertEquals(2, store.append("r", u(2)));
        List<byte[]> log = store.load("r");
        assertEquals(2, log.size());
        assertArrayEquals(u(1), log.get(0));
        assertArrayEquals(u(2), log.get(1));
    }

    @Test
    void unknownRoomLoadsEmpty() {
        assertTrue(store.load("nope").isEmpty());
    }

    @Test
    void roomsAreIsolated() {
        store.append("a", u(1));
        store.append("b", u(2));
        assertEquals(1, store.load("a").size());
        assertArrayEquals(u(2), store.load("b").get(0));
    }

    @Test
    void replacePrefixPutsSnapshotFirstAndKeepsTheRest() {
        for (int i = 0; i < 6; i++) store.append("r", u(i));
        byte[] snapshot = {99, 99};

        assertEquals(4, store.replacePrefix("r", 3, snapshot));

        List<byte[]> log = store.load("r");
        assertArrayEquals(snapshot, log.get(0));
        assertArrayEquals(u(3), log.get(1));
        assertArrayEquals(u(5), log.get(3));
    }

    @Test
    void replacePrefixKeepsEntriesAppendedAfterTheRequest() {
        for (int i = 0; i < 4; i++) store.append("r", u(i));
        store.append("r", u(4)); // arrives while the snapshot is in flight
        assertEquals(3, store.replacePrefix("r", 3, new byte[] {99}));
        assertArrayEquals(u(4), store.load("r").get(2));
    }

    @Test
    void replacePrefixRefusesToDropMoreThanExists() {
        store.append("r", u(1));
        assertEquals(-1, store.replacePrefix("r", 5, new byte[] {99}));
        assertEquals(-1, store.replacePrefix("r", 0, new byte[] {99}));
        assertEquals(-1, store.replacePrefix("missing", 1, new byte[] {99}));
        assertEquals(1, store.load("r").size());
    }

    @Test
    void compactionLockIsExclusiveUntilReleased() {
        assertTrue(store.tryBeginCompaction("r"));
        assertFalse(store.tryBeginCompaction("r"));
        assertTrue(store.tryBeginCompaction("other"));
        store.endCompaction("r");
        assertTrue(store.tryBeginCompaction("r"));
    }
}
