package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogRecordTest {

    private static LogRecord record(Map<String, String> attributes) {
        return new LogRecord(Instant.EPOCH, "1.2.3.4", "GET", "/", 200, 1L, "ua", attributes, "raw");
    }

    @Test
    void attributesAreImmutable() {
        LogRecord r = record(new HashMap<>(Map.of("k", "v")));

        assertThrows(UnsupportedOperationException.class, () -> r.attributes().put("x", "y"));
    }

    @Test
    void attributesAreCopiedSoLaterChangesToSourceMapDoNotLeakIn() {
        Map<String, String> source = new HashMap<>(Map.of("k", "v"));
        LogRecord r = record(source);
        source.put("late", "change");

        assertEquals(Map.of("k", "v"), r.attributes());
    }

    @Test
    void nullAttributesBecomeEmptyMap() {
        assertTrue(record(null).attributes().isEmpty());
    }
}
