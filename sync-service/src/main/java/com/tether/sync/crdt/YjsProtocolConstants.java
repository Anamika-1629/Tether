package com.tether.sync.crdt;

/**
 * Protocol constants matching the standard y-websocket specification.
 *
 * References:
 * - Nicolaescu et al. (2016) Yjs: Near Real-Time Collaborative Editing
 * - Shapiro et al. (2011) Conflict-free Replicated Data Types (CRDTs)
 */
public final class YjsProtocolConstants {

    private YjsProtocolConstants() {}

    // Top-level y-websocket message types
    public static final int MESSAGE_SYNC = 0;
    public static final int MESSAGE_AWARENESS = 1;
    public static final int MESSAGE_AUTH = 2;
    public static final int MESSAGE_QUERY_AWARENESS = 3;

    // y-protocols sync sub-types
    public static final int SYNC_STEP1 = 0;
    public static final int SYNC_STEP2 = 1;
    public static final int SYNC_UPDATE = 2;

    // A Yjs update containing 0 structs and empty delete set
    public static final byte[] EMPTY_UPDATE = new byte[] { 0, 0 };

    // A state vector that knows nothing, prompting the peer to reply with its entire document
    public static final byte[] EMPTY_STATE_VECTOR = new byte[] { 0 };

    // WebSocket custom close codes
    public static final int CLOSE_UNAUTHORIZED = 4401;
    public static final int CLOSE_FORBIDDEN = 4403;
}
