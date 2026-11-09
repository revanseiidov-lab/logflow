package edu.logflow.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.logflow.Stage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Yönlendirici ve dekoratörler: aşama yalnızca fırlatır, akıbeti politika belirler. */
class ErrorRoutingTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final RunSummary summary = new RunSummary();
    private final List<FailedRecord> dead = new ArrayList<>();
    private final ErrorRouter router = new ErrorRouter(summary, dead::add, () -> NOW);

    private static Stage<String, String> failingOn(String bad, ErrorType type) {
        return (in, out) -> {
            if (in.equals(bad)) {
                throw new StageException(type, "kotu: " + in);
            }
            out.emit(in);
        };
    }

    private List<String> run(Stage<String, String> stage, String... inputs) {
        List<String> received = new ArrayList<>();
        for (String s : inputs) {
            router.setCurrent(ErrorRouter.Context.text(1, s));
            stage.process(s, received::add);
        }
        return received;
    }

    @Test
    void skipDropsTheRecordAndCountsItByTypeAndStage() {
        var g = new GuardedStage<>("parse", failingOn("x", ErrorType.PARSE_MALFORMED), ErrorPolicy.SKIP, router, false);

        assertEquals(List.of("a", "b"), run(g, "a", "x", "b"));

        assertEquals(1, summary.skippedCount());
        assertEquals(Long.valueOf(1), summary.byType().get(ErrorType.PARSE_MALFORMED));
        assertEquals(Long.valueOf(1), summary.byStage().get("parse"));
        assertTrue(dead.isEmpty());
    }

    @Test
    void deadLetterWritesTheFailureWithReasonStageLineAndRawInput() {
        var g = new GuardedStage<>("parse", failingOn("x", ErrorType.PARSE_NUMBER), ErrorPolicy.DEAD_LETTER, router, false);
        router.setCurrent(ErrorRouter.Context.text(42, "x"));

        g.process("x", s -> { });

        assertEquals(1, summary.deadLetteredCount());
        assertEquals(1, dead.size());
        FailedRecord f = dead.get(0);
        assertEquals(NOW, f.timestamp());
        assertEquals("parse", f.stage());
        assertEquals(ErrorType.PARSE_NUMBER, f.errorType());
        assertEquals("kotu: x", f.message());
        assertEquals(42, f.line());
        assertEquals("x", f.rawInput());
        assertFalse(f.rawTruncated());
    }

    @Test
    void failFastStopsTheRunAndCountsTheAbortedRecord() {
        var g = new GuardedStage<>("parse", failingOn("x", ErrorType.PARSE_MALFORMED), ErrorPolicy.FAIL_FAST, router, false);

        AbortRun e = assertThrows(AbortRun.class, () -> run(g, "a", "x", "b"));

        assertTrue(e.getMessage().contains("parse"));
        assertEquals(1, summary.abortedCount());
    }

    @Test
    void unexpectedExceptionsBecomeRecordLevelErrorsInsteadOfCrashingTheRun() {
        Stage<String, String> buggy = (in, out) -> {
            if (in.equals("x")) {
                throw new IllegalStateException("bug");
            }
            out.emit(in);
        };
        var g = new GuardedStage<>("buggy", buggy, ErrorPolicy.DEAD_LETTER, router, false);

        assertEquals(List.of("a"), run(g, "a", "x"));

        assertEquals(ErrorType.UNEXPECTED, dead.get(0).errorType());
        assertTrue(dead.get(0).message().contains("IllegalStateException: bug"));
    }

    @Test
    void failuresDownstreamAreNotAttributedToTheUpstreamStage() {
        // aşama 2'nin hatası, aşama 1'in emit çağrısından yukarı çıkar; aşama 1 onu kendi hatası saymamalı
        var second = new GuardedStage<>("second", failingOn("x", ErrorType.VALIDATION_RANGE), ErrorPolicy.SKIP, router, false);
        List<String> out = new ArrayList<>();
        Stage<String, String> first = (in, emit) -> emit.emit(in);
        var g1 = new GuardedStage<>("first", first, ErrorPolicy.DEAD_LETTER, router, false);

        router.setCurrent(ErrorRouter.Context.text(1, "x"));
        g1.process("x", item -> second.process(item, out::add));

        assertEquals(1, summary.skippedCount());
        assertEquals(0, summary.deadLetteredCount());
        assertEquals(Long.valueOf(1), summary.byStage().get("second"));
        assertEquals(null, summary.byStage().get("first"));
    }

    @Test
    void abortRaisedDownstreamPassesThroughUpstreamGuardsUntouched() {
        var second = new GuardedStage<>("second", failingOn("x", ErrorType.PARSE_MALFORMED), ErrorPolicy.FAIL_FAST, router, false);
        Stage<String, String> first = (in, emit) -> emit.emit(in);
        var g1 = new GuardedStage<>("first", first, ErrorPolicy.SKIP, router, false);

        assertThrows(AbortRun.class, () -> g1.process("x", item -> second.process(item, s -> { })));
        assertEquals(1, summary.abortedCount());
        assertEquals(0, summary.skippedCount());
    }

    @Test
    void filterStageThatEmitsNothingIsCountedAsFilteredButOthersAreNot() {
        Stage<String, String> dropAll = (in, out) -> { };
        var filter = new GuardedStage<>("filter", dropAll, ErrorPolicy.SKIP, router, true);
        var notFilter = new GuardedStage<>("transform", dropAll, ErrorPolicy.SKIP, router, false);

        run(filter, "a", "b");
        run(notFilter, "c");

        assertEquals(2, summary.filteredCount());      // "transform" sessizce yuttu: sayilmadi
        assertFalse(summary.balanced() && summary.readCount() == 3);
    }

    @Test
    void deadLetterPolicyWithoutATargetIsAFatalPipelineError() {
        ErrorRouter noTarget = new ErrorRouter(new RunSummary(), null);
        var g = new GuardedStage<>("parse", failingOn("x", ErrorType.PARSE_MALFORMED), ErrorPolicy.DEAD_LETTER, noTarget, false);
        assertThrows(PipelineException.class, () -> g.process("x", s -> { }));
    }

    @Test
    void failureToWriteTheDeadLetterIsFatalNotSilent() {
        ErrorRouter broken = new ErrorRouter(new RunSummary(), r -> { throw new UncheckedIOException(new IOException("disk")); });
        var g = new GuardedStage<>("parse", failingOn("x", ErrorType.PARSE_MALFORMED), ErrorPolicy.DEAD_LETTER, broken, false);
        PipelineException e = assertThrows(PipelineException.class, () -> g.process("x", s -> { }));
        assertTrue(e.getMessage().contains("dead-letter yazilamadi"));
    }

    @Test
    void rawInputIsTruncatedAtOneKiB() {
        String huge = "A".repeat(5000);
        router.setCurrent(ErrorRouter.Context.text(3, huge));
        router.handle("parse", ErrorPolicy.DEAD_LETTER, new StageException(ErrorType.PARSE_MALFORMED, "m"));
        assertEquals(1024, dead.get(0).rawInput().length());
        assertTrue(dead.get(0).rawTruncated());
    }

    @Test
    void base64EvidenceIsKeptOnlyForReadErrors() {
        byte[] bytes = {(byte) 0xFF, (byte) 0xFE, 'a'};
        router.setCurrent(new ErrorRouter.Context(5, () -> "??a", false, bytes));
        router.handle("decode", ErrorPolicy.DEAD_LETTER, new StageException(ErrorType.READ_ENCODING, "m"));
        router.handle("parse", ErrorPolicy.DEAD_LETTER, new StageException(ErrorType.PARSE_MALFORMED, "m"));

        assertEquals("//5h", dead.get(0).rawBase64());
        assertEquals(null, dead.get(1).rawBase64());
    }
}
