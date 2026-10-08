package com.tether.sync.service;

import com.tether.sync.crdt.YjsProtocolConstants;
import com.tether.sync.model.Room;
import com.tether.sync.store.UpdateLogStore;
import com.tether.sync.websocket.SyncWebSocketHandler;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Keeps each room's update log bounded.
 *
 * The server cannot merge Yjs updates itself (no JVM Yjs), so it asks a connected client to do it:
 * a Sync Step 1 with an empty state vector makes the client reply with Step 2 holding its WHOLE
 * document as one update. That snapshot replaces the oldest part of the log.
 *
 * Safety rules:
 *  - Only the first (size - keepTail) entries are replaced. The newest {@code keepTail} entries stay, which
 *    covers updates another instance has stored but not yet relayed to this client.
 *  - The snapshot is taken only from a session with no outstanding Step 1 request, so the Step 2 that
 *    answers our request cannot be confused with an older reply computed before our request.
 *  - One compaction per room at a time (local guard + Redis lock), and a stuck attempt times out.
 *  - Yjs updates are idempotent, so keeping an entry the snapshot already contains is harmless.
 */
@Service
public class LogCompactor {

    private static final Logger log = LoggerFactory.getLogger(LogCompactor.class);
    static final String ATTR_PENDING = "pendingFullState";
    static final String ATTR_TICKET = "compactionTicket";
    static final long TIMEOUT_MS = 30_000;

    record Ticket(int dropCount) {}

    private final UpdateLogStore store;
    private final int threshold;
    private final int keepTail;

    public LogCompactor(
            UpdateLogStore store,
            @Value("${tether.sync.compact-threshold:200}") int threshold,
            @Value("${tether.sync.compact-keep-tail:25}") int keepTail) {
        this.store = store;
        this.threshold = threshold;
        this.keepTail = Math.max(1, keepTail);
    }

    /** Call right after the server sends this session a Step 1 (empty state vector) for any reason. */
    public void markFullStateRequested(WebSocketSession session) {
        pending(session).incrementAndGet();
    }

    /** Call after storing an update. Starts a compaction when the log has grown past the threshold. */
    public void maybeRequestSnapshot(WebSocketSession session, Room room, long logSize) {
        if (threshold <= 0 || logSize < threshold) return;
        int drop = (int) (logSize - keepTail);
        if (drop < 2 || !session.isOpen()) return;
        Map<String, Object> attrs = session.getAttributes();
        if (pending(session).get() != 0 || attrs.containsKey(ATTR_TICKET)) return;
        if (!room.tryBeginCompaction(System.currentTimeMillis(), TIMEOUT_MS)) return;
        if (!store.tryBeginCompaction(room.getRoomKey())) {
            room.endCompaction();
            return;
        }
        attrs.put(ATTR_TICKET, new Ticket(drop));
        try {
            byte[] step1 = SyncWebSocketHandler.encodeSync(YjsProtocolConstants.SYNC_STEP1, YjsProtocolConstants.EMPTY_STATE_VECTOR);
            session.sendMessage(new BinaryMessage(step1));
            markFullStateRequested(session);
            log.debug("Requested snapshot for room {} (log {} entries, will replace {})", room.getRoomKey(), logSize, drop);
        } catch (IOException e) {
            abort(session, room);
        }
    }

    /**
     * Call for every Step 2 received from a session. Returns true if it was the awaited snapshot and
     * has been stored (the caller must then NOT append or broadcast it as an ordinary update).
     */
    public boolean consumeStep2(WebSocketSession session, Room room, byte[] update) {
        int left = pending(session).updateAndGet(n -> Math.max(0, n - 1));
        Ticket ticket = (Ticket) session.getAttributes().get(ATTR_TICKET);
        if (ticket == null || left != 0) return false;
        session.getAttributes().remove(ATTR_TICKET);
        try {
            long len = store.replacePrefix(room.getRoomKey(), ticket.dropCount(), update);
            if (len >= 0) log.info("Compacted room {}: replaced {} entries with 1 snapshot ({} bytes), log now {} entries",
                    room.getRoomKey(), ticket.dropCount(), update.length, len);
            else log.warn("Compaction skipped for room {} (log shorter than expected or store unavailable)", room.getRoomKey());
        } finally {
            store.endCompaction(room.getRoomKey());
            room.endCompaction();
        }
        return true;
    }

    /** Call when a session closes so an unanswered request does not keep the room locked until timeout. */
    public void onSessionClosed(WebSocketSession session, Room room) {
        if (session.getAttributes().containsKey(ATTR_TICKET)) abort(session, room);
    }

    private void abort(WebSocketSession session, Room room) {
        session.getAttributes().remove(ATTR_TICKET);
        store.endCompaction(room.getRoomKey());
        room.endCompaction();
    }

    private static AtomicInteger pending(WebSocketSession session) {
        return (AtomicInteger) session.getAttributes().computeIfAbsent(ATTR_PENDING, k -> new AtomicInteger());
    }
}
