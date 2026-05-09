package com.debugbridge.core.refs;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stores Java object references with stable IDs for cross-boundary access.
 * Uses WeakReferences so objects can still be GC'd.
 */
public class ObjectRefStore {
    private final Map<String, WeakReference<Object>> refs = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);

    /**
     * Store an object and return its reference ID.
     */
    public String store(Object obj) {
        String id = "$ref_" + counter.incrementAndGet();
        refs.put(id, new WeakReference<>(obj));
        return id;
    }

    /**
     * Retrieve an object by reference ID. Returns null if GC'd.
     */
    public Object get(String id) {
        WeakReference<Object> ref = refs.get(id);
        if (ref == null) return null;
        Object obj = ref.get();
        if (obj == null) {
            refs.remove(id);
        }
        return obj;
    }

    /**
     * Clear all references.
     */
    public void clear() {
        refs.clear();
        counter.set(0);
    }

    /**
     * Count of live references.
     */
    public int size() {
        refs.entrySet().removeIf(e -> e.getValue().get() == null);
        return refs.size();
    }
}
