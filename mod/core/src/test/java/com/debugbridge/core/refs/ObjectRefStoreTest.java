package com.debugbridge.core.refs;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ObjectRefStoreTest {

    @Test
    void storePrunesOldestRefsWhenLimitIsReached() {
        ObjectRefStore refs = new ObjectRefStore(2);
        Object firstObject = new Object();
        Object secondObject = new Object();
        Object thirdObject = new Object();

        String first = refs.store(firstObject);
        String second = refs.store(secondObject);
        String third = refs.store(thirdObject);

        assertNull(refs.get(first));
        assertNotNull(refs.get(second));
        assertNotNull(refs.get(third));
        assertEquals(2, refs.size());
    }

    @Test
    void clearDropsRefsAndResetsIds() {
        ObjectRefStore refs = new ObjectRefStore(2);
        refs.store(new Object());

        refs.clear();
        String next = refs.store(new Object());

        assertEquals("$ref_1", next);
        assertEquals(1, refs.size());
    }
}
