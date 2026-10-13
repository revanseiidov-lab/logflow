package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PredicateFilterStageTest {

    private static LogRecord record(String time, String path, int status) {
        return new LogRecord(Instant.parse(time), "1.1.1.1", "GET", path, status, 10L, "ua", Map.of(), "raw");
    }

    private static CollectingEmitter<LogRecord> run(PredicateFilterStage stage, LogRecord... records) {
        CollectingEmitter<LogRecord> out = new CollectingEmitter<>();
        for (LogRecord r : records) {
            stage.process(r, out);
        }
        return out;
    }

    @Test
    void minStatusKeepsOnlyErrorsAtOrAboveThreshold() {
        LogRecord ok = record("2023-10-10T10:00:00Z", "/a", 200);
        LogRecord edge = record("2023-10-10T10:00:00Z", "/b", 400);
        LogRecord err = record("2023-10-10T10:00:00Z", "/c", 503);

        CollectingEmitter<LogRecord> out = run(PredicateFilterStage.minStatus(400), ok, edge, err);

        assertEquals(java.util.List.of(edge, err), out.items());
    }

    @Test
    void pathPrefixMatchesOnlyPathsStartingWithPrefix() {
        LogRecord api = record("2023-10-10T10:00:00Z", "/api/users", 200);
        LogRecord other = record("2023-10-10T10:00:00Z", "/index.html", 200);
        LogRecord notPrefix = record("2023-10-10T10:00:00Z", "/v2/api/x", 200);

        CollectingEmitter<LogRecord> out = run(PredicateFilterStage.pathPrefix("/api"), api, other, notPrefix);

        assertEquals(java.util.List.of(api), out.items());
    }

    @Test
    void timeBetweenIncludesSinceAndExcludesUntil() {
        LogRecord before = record("2023-10-10T09:59:59Z", "/a", 200);
        LogRecord atSince = record("2023-10-10T10:00:00Z", "/b", 200);
        LogRecord inside = record("2023-10-10T10:30:00Z", "/c", 200);
        LogRecord atUntil = record("2023-10-10T11:00:00Z", "/d", 200);

        PredicateFilterStage stage = PredicateFilterStage.timeBetween(
                Instant.parse("2023-10-10T10:00:00Z"), Instant.parse("2023-10-10T11:00:00Z"));
        CollectingEmitter<LogRecord> out = run(stage, before, atSince, inside, atUntil);

        assertEquals(java.util.List.of(atSince, inside), out.items());
    }

    @Test
    void timeBetweenWithNullBoundIsOpenEnded() {
        LogRecord early = record("2000-01-01T00:00:00Z", "/a", 200);
        LogRecord late = record("2030-01-01T00:00:00Z", "/b", 200);

        assertEquals(2, run(PredicateFilterStage.timeBetween(null, null), early, late).size());
        assertEquals(1, run(PredicateFilterStage.timeBetween(Instant.parse("2020-01-01T00:00:00Z"), null),
                early, late).size());
    }

    @Test
    void customPredicateIsHonouredAndRecordIsPassedThroughUnchanged() {
        LogRecord r = record("2023-10-10T10:00:00Z", "/a", 200);

        CollectingEmitter<LogRecord> accepted = run(new PredicateFilterStage(x -> true), r);
        CollectingEmitter<LogRecord> rejected = run(new PredicateFilterStage(x -> false), r);

        assertEquals(1, accepted.size());
        assertEquals(true, accepted.first() == r);
        assertEquals(0, rejected.size());
    }
}
