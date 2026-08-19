package com.equipmock.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * plugin-api 契约单测（M1-2）：静态工厂语义正确。
 */
class MockOutcomeTest {

    @Test
    void ofValueCarriesValue() {
        MockOutcome o = MockOutcome.ofValue(5);
        assertEquals(MockOutcome.Type.VALUE, o.getType());
        assertEquals(5, o.getValue());
        assertNull(o.getThrowable());
    }

    @Test
    void ofVoidHasNoPayload() {
        MockOutcome o = MockOutcome.ofVoid();
        assertEquals(MockOutcome.Type.VOID, o.getType());
        assertNull(o.getValue());
        assertNull(o.getThrowable());
    }

    @Test
    void ofThrowCarriesThrowable() {
        IOException ex = new IOException("boom");
        MockOutcome o = MockOutcome.ofThrow(ex);
        assertEquals(MockOutcome.Type.THROW, o.getType());
        assertSame(ex, o.getThrowable());
        assertNull(o.getValue());
    }

    @Test
    void passthroughSkipsConfig() {
        MockOutcome o = MockOutcome.passthrough();
        assertEquals(MockOutcome.Type.PASSTHROUGH, o.getType());
        assertNull(o.getValue());
        assertNull(o.getThrowable());
    }
}
