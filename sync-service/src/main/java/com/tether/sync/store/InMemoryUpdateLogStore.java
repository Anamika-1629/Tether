package com.tether.sync.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Process-local store for tests and for running without Redis (tether.sync.store=memory).
 * History is lost on restart and is not shared between instances.
 */
@Component
@ConditionalOnProperty(name = "tether.sync.store", havingValue = "memory")
public class InMemoryUpdateLogStore implements UpdateLogStore {

    private final Map<String, List<byte[]>> logs = new HashMap<>();
    private final Set<String> locks = new HashSet<>();

    @Override
    public synchronized long append(String roomKey, byte[] update) {
        List<byte[]> log = logs.computeIfAbsent(roomKey, k -> new ArrayList<>());
        log.add(update);
        return log.size();
    }

    @Override
    public synchronized List<byte[]> load(String roomKey) {
        List<byte[]> log = logs.get(roomKey);
        return log == null ? List.of() : new ArrayList<>(log);
    }

    @Override
    public synchronized boolean tryBeginCompaction(String roomKey) {
        return locks.add(roomKey);
    }

    @Override
    public synchronized void endCompaction(String roomKey) {
        locks.remove(roomKey);
    }

    @Override
    public synchronized long replacePrefix(String roomKey, int dropCount, byte[] snapshot) {
        List<byte[]> log = logs.get(roomKey);
        if (log == null || dropCount < 1 || log.size() < dropCount) return -1;
        List<byte[]> next = new ArrayList<>(log.size() - dropCount + 1);
        next.add(snapshot);
        next.addAll(log.subList(dropCount, log.size()));
        logs.put(roomKey, next);
        return next.size();
    }
}
