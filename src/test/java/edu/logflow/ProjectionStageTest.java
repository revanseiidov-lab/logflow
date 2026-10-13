package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectionStageTest {

    private static final LogRecord RECORD = new LogRecord(Instant.parse("2023-10-10T10:55:36Z"),
            "192.168.1.10", "GET", "/search", 200, 2326L, "Mozilla/5.0", Map.of("query", "q=1"), "raw line");

    private static ProjectedRecord project(ProjectionStage stage) {
        CollectingEmitter<ProjectedRecord> out = new CollectingEmitter<>();
        stage.process(RECORD, out);
        assertEquals(1, out.size());
        return out.first();
    }

    @Test
    void keepsOnlySelectedFieldsInSelectedOrder() {
        ProjectedRecord p = project(new ProjectionStage("status", "path", "timestamp"));

        assertEquals(List.of("status", "path", "timestamp"), List.copyOf(p.values().keySet()));
        assertEquals("200", p.get("status"));
        assertEquals("/search", p.get("path"));
        assertEquals("2023-10-10T10:55:36Z", p.get("timestamp"));
    }

    @Test
    void dataMinimisationDroppedFieldsAreGone() {
        ProjectedRecord p = project(new ProjectionStage("status", "path"));

        assertEquals(null, p.get("clientIp"));
        assertEquals(null, p.get("userAgent"));
        assertEquals(false, p.toLine().contains("192.168.1.10"));
        assertEquals(false, p.toLine().contains("Mozilla"));
    }

    @Test
    void toLineIsTabSeparatedNameValuePairs() {
        ProjectedRecord p = project(new ProjectionStage("clientIp", "status", "bytes"));

        assertEquals("clientIp=192.168.1.10\tstatus=200\tbytes=2326", p.toLine());
    }

    @Test
    void queryComesFromAttributesAndMissingQueryIsEmpty() {
        assertEquals("q=1", project(new ProjectionStage("query")).get("query"));

        CollectingEmitter<ProjectedRecord> out = new CollectingEmitter<>();
        LogRecord noQuery = new LogRecord(Instant.EPOCH, "1.1.1.1", "GET", "/", 200, 0L, "", Map.of(), "raw");
        new ProjectionStage("query").process(noQuery, out);
        assertEquals("", out.first().get("query"));
    }

    @Test
    void duplicateFieldsAreCollapsed() {
        ProjectedRecord p = project(new ProjectionStage("status", "status", "path"));

        assertEquals(2, p.values().size());
    }

    @Test
    void defaultFieldListKeepsEverythingExceptRawLine() {
        ProjectedRecord p = project(new ProjectionStage(ProjectionStage.ALL_FIELDS));

        assertEquals(7, p.values().size());
        assertEquals(false, p.toLine().contains("raw line"));
    }

    @Test
    void unknownOrEmptyFieldSelectionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectionStage("nope"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectionStage("raw"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectionStage(List.of()));
    }

    @Test
    void projectedRecordIsImmutable() {
        ProjectedRecord p = project(new ProjectionStage("status"));

        assertThrows(UnsupportedOperationException.class, () -> p.values().put("x", "y"));
    }
}
