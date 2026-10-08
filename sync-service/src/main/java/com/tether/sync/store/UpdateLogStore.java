package com.tether.sync.store;

import java.util.List;

/**
 * Durable, shared log of the Yjs updates for each room (key = "tenantId:incidentId").
 *
 * Updates are append-only except for compaction, which replaces the oldest part of the log with
 * one full-state snapshot. Yjs updates are idempotent, so replaying duplicates is harmless.
 */
public interface UpdateLogStore {

    /** Appends one update and returns the log length after the append (0 if it could not be stored). */
    long append(String roomKey, byte[] update);

    /** The whole log, oldest first. Empty if the room is unknown or the store is unavailable. */
    List<byte[]> load(String roomKey);

    /** Takes the cross-instance compaction lock for this room. False if someone else holds it. */
    boolean tryBeginCompaction(String roomKey);

    /** Releases the compaction lock. Safe to call when not held. */
    void endCompaction(String roomKey);

    /**
     * Atomically removes the first {@code dropCount} entries and puts {@code snapshot} at the front.
     * Entries appended in the meantime are kept.
     *
     * @return the new log length, or -1 if nothing was changed (log shorter than dropCount, or store down)
     */
    long replacePrefix(String roomKey, int dropCount, byte[] snapshot);
}
