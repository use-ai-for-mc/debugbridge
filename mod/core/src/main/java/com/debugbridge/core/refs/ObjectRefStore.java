package com.debugbridge.core.refs;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stores Java object references with stable IDs for cross-boundary access.
 * Uses WeakReferences so objects can still be GC'd.
 */
public class ObjectRefStore {
    private static final int DEFAULT_MAX_REFS = 8192;

    private final Map<String, WeakReference<Object>> refs = new LinkedHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private final int maxRefs;

    public ObjectRefStore() {
        this(DEFAULT_MAX_REFS);
    }

    public ObjectRefStore(int maxRefs) {
        if (maxRefs <= 0) {
            throw new IllegalArgumentException("maxRefs must be positive");
        }
        this.maxRefs = maxRefs;
    }

    /**
     * Store an object and return its reference ID.
     */
    public synchronized String store(Object obj) {
        String id = "$ref_" + counter.incrementAndGet();
        refs.put(id, new WeakReference<>(obj));
        pruneToLimit();
        return id;
    }

    private void pruneToLimit() {
        refs.entrySet().removeIf(e -> e.getValue().get() == null);
        Iterator<String> ids = refs.keySet().iterator();
        while (refs.size() > maxRefs) {
            if (!ids.hasNext()) {
                return;
            }
            ids.next();
            ids.remove();
        }
    }

    /**
     * Retrieve an object by reference ID. Returns null if GC'd.
     */
    public synchronized Object get(String id) {
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
    public synchronized void clear() {
        refs.clear();
        counter.set(0);
    }

    /**
     * Count of live references.
     */
    public synchronized int size() {
        refs.entrySet().removeIf(e -> e.getValue().get() == null);
        return refs.size();
    }
}
