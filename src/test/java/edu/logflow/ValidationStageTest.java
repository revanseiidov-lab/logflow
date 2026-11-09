package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.logflow.error.ErrorType;
import edu.logflow.error.StageException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidationStageTest {

    private static LogRecord record(String ip, String path, int status, long bytes, String time) {
        return new LogRecord(Instant.parse(time), ip, "GET", path, status, bytes, "ua", Map.of(), "raw");
    }

    private static final LogRecord OK = record("192.0.2.1", "/a", 200, 10, "2026-10-15T10:00:00Z");

    private static ValidationStage.Rules rules(List<String> required, boolean ip, Integer minS, Integer maxS,
                                               Long minB, Long maxB, String after, String before) {
        return new ValidationStage.Rules(required, ip, minS, maxS, minB, maxB,
                after == null ? null : Instant.parse(after), before == null ? null : Instant.parse(before));
    }

    private ErrorType reject(ValidationStage.Rules r, LogRecord rec) {
        CollectingEmitter<LogRecord> out = new CollectingEmitter<>();
        StageException e = assertThrows(StageException.class, () -> new ValidationStage(r).process(rec, out));
        assertEquals(0, out.size());
        return e.type();
    }

    @Test
    void noRulesPassesEverythingUnchanged() {
        CollectingEmitter<LogRecord> out = new CollectingEmitter<>();
        new ValidationStage(ValidationStage.Rules.none()).process(OK, out);
        assertEquals(OK, out.first());
    }

    @Test
    void requiredFieldRejectsBlankAndDash() {
        ValidationStage.Rules r = rules(List.of("path"), false, null, null, null, null, null, null);
        assertEquals(ErrorType.VALIDATION_REQUIRED, reject(r, record("1.1.1.1", "-", 200, 1, "2026-01-01T00:00:00Z")));
        assertEquals(ErrorType.VALIDATION_REQUIRED, reject(r, record("1.1.1.1", " ", 200, 1, "2026-01-01T00:00:00Z")));
    }

    @Test
    void ipRuleAcceptsV4AndV6AndRejectsGarbage() {
        for (String ok : new String[] {"0.0.0.0", "255.255.255.255", "10.1.2.3", "::1", "2001:db8::ff00:42:8329",
                "fe80::", "1:2:3:4:5:6:7:8", "::ffff:192.0.2.1"}) {
            assertTrue(ValidationStage.isIp(ok), ok);
        }
        for (String bad : new String[] {"", "256.1.1.1", "1.2.3", "1.2.3.4.5", "a.b.c.d", "1..2.3", "01234.1.1.1",
                "1:2:3:4:5:6:7", "1:::2", "12345::1", "g::1", ":1:2:3:4:5:6:7", "unknown", "-"}) {
            assertFalse(ValidationStage.isIp(bad), bad);
        }
        ValidationStage.Rules r = rules(List.of(), true, null, null, null, null, null, null);
        assertEquals(ErrorType.VALIDATION_IP, reject(r, record("999.1.1.1", "/a", 200, 1, "2026-01-01T00:00:00Z")));
    }

    @Test
    void numericRangesAreInclusive() {
        ValidationStage.Rules r = rules(List.of(), false, 200, 299, 0L, 100L, null, null);
        CollectingEmitter<LogRecord> out = new CollectingEmitter<>();
        ValidationStage s = new ValidationStage(r);
        s.process(record("1.1.1.1", "/", 200, 0, "2026-01-01T00:00:00Z"), out);
        s.process(record("1.1.1.1", "/", 299, 100, "2026-01-01T00:00:00Z"), out);
        assertEquals(2, out.size());

        assertEquals(ErrorType.VALIDATION_RANGE, reject(r, record("1.1.1.1", "/", 300, 1, "2026-01-01T00:00:00Z")));
        assertEquals(ErrorType.VALIDATION_RANGE, reject(r, record("1.1.1.1", "/", 200, 101, "2026-01-01T00:00:00Z")));
    }

    @Test
    void timestampSanityRangeRejectsTheYear1970AndTheYear2099() {
        ValidationStage.Rules r = rules(List.of(), false, null, null, null, null,
                "2000-01-01T00:00:00Z", "2030-01-01T00:00:00Z");
        assertEquals(ErrorType.VALIDATION_TIMESTAMP, reject(r, record("1.1.1.1", "/", 200, 1, "1970-01-01T00:00:00Z")));
        assertEquals(ErrorType.VALIDATION_TIMESTAMP, reject(r, record("1.1.1.1", "/", 200, 1, "2099-12-31T23:59:59Z")));
        CollectingEmitter<LogRecord> out = new CollectingEmitter<>();
        new ValidationStage(r).process(OK, out);
        assertEquals(1, out.size());
    }
}
