package edu.logflow.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.logflow.Pipeline;
import edu.logflow.PipelineBuilder;
import edu.logflow.Sink;
import edu.logflow.Source;
import edu.logflow.Stage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Çalıştırıcı: durum, çıkış kodu ve mutabakat. */
class ManagedRunTest {

    private final RunSummary summary = new RunSummary();
    private final List<FailedRecord> dead = new ArrayList<>();
    private final ErrorRouter router = new ErrorRouter(summary, dead::add);
    private final List<String> written = new ArrayList<>();

    private ManagedRun managed(Source<String> source, Stage<String, String> stage, ErrorPolicy policy, boolean filter) {
        Pipeline p = PipelineBuilder.from(new TrackingSource<>(source, router, TrackingSource.annotatorFor(String.class)))
                .via(new GuardedStage<>("s", stage, policy, router, filter))
                .to(new GuardedSink<>("sink", (Sink<String>) written::add, ErrorPolicy.FAIL_FAST, ErrorPolicy.FAIL_FAST, router));
        return new ManagedRun(p, router, null);
    }

    private static Source<String> lines(String... items) {
        return out -> {
            for (String s : items) {
                out.emit(s);
            }
        };
    }

    private static Stage<String, String> rejectBad() {
        return (in, out) -> {
            if (in.startsWith("bad")) {
                throw new StageException(ErrorType.PARSE_MALFORMED, "bad");
            }
            out.emit(in);
        };
    }

    @Test
    void cleanRunReconcilesAndExitsZero() {
        RunSummary s = managed(lines("a", "bad1", "b", "bad2"), rejectBad(), ErrorPolicy.SKIP, false).run();

        assertEquals(RunSummary.Status.OK, s.status());
        assertEquals(0, s.exitCode());
        assertEquals(4, s.readCount());
        assertEquals(2, s.emittedCount());
        assertEquals(2, s.skippedCount());
        assertTrue(s.balanced());
        assertEquals(List.of("a", "b"), written);
    }

    @Test
    void failFastEndsAbortedWithExitOneAndKeepsTheReconciliationEquation() {
        RunSummary s = managed(lines("a", "bad", "never-read"), rejectBad(), ErrorPolicy.FAIL_FAST, false).run();

        assertEquals(RunSummary.Status.ABORTED, s.status());
        assertEquals(1, s.exitCode());
        assertEquals(2, s.readCount());                  // üçüncü satır hiç okunmadı
        assertEquals(1, s.abortedCount());
        assertTrue(s.balanced());
        assertTrue(s.failure().contains("durdurdu"));
    }

    @Test
    void silentlyDroppedRecordIsDetectedAsMismatchWithExitThree() {
        Stage<String, String> swallows = (in, out) -> { /* ne yayar ne fırlatır: sessiz kayıp */ };

        RunSummary s = managed(lines("a", "b"), swallows, ErrorPolicy.SKIP, false).run();

        assertEquals(RunSummary.Status.MISMATCH, s.status());
        assertEquals(3, s.exitCode());
        assertFalse(s.balanced());
        assertTrue(s.failure().contains("kayit kaybi"));
    }

    @Test
    void sameSwallowingStageDeclaredAsFilterIsLegitimate() {
        Stage<String, String> dropEvens = (in, out) -> {
            if (!in.equals("b")) {
                out.emit(in);
            }
        };
        RunSummary s = managed(lines("a", "b", "c"), dropEvens, ErrorPolicy.SKIP, true).run();

        assertEquals(RunSummary.Status.OK, s.status());
        assertEquals(1, s.filteredCount());
        assertEquals(2, s.emittedCount());
    }

    @Test
    void sourceIoErrorEndsFailedWithExitOneWithoutLeakingTheException() {
        Source<String> broken = out -> {
            out.emit("a");
            throw new IOException("disk gitti");
        };
        RunSummary s = managed(broken, rejectBad(), ErrorPolicy.SKIP, false).run();

        assertEquals(RunSummary.Status.FAILED, s.status());
        assertEquals(1, s.exitCode());
        assertTrue(s.failure().contains("disk gitti"));
    }

    @Test
    void deadLetterTargetIsOpenedAndClosedAroundTheRun() {
        List<String> events = new ArrayList<>();
        Sink<FailedRecord> target = new Sink<>() {
            @Override public void open() { events.add("open"); }
            @Override public void accept(FailedRecord r) { events.add("record"); }
            @Override public void close() { events.add("close"); }
        };
        ErrorRouter r = new ErrorRouter(new RunSummary(), target);
        Pipeline p = PipelineBuilder.from(new TrackingSource<>(lines("bad"), r, TrackingSource.annotatorFor(String.class)))
                .via(new GuardedStage<>("s", rejectBad(), ErrorPolicy.DEAD_LETTER, r, false))
                .to(item -> { });

        new ManagedRun(p, r, target).run();

        assertEquals(List.of("open", "record", "close"), events);
    }

    @Test
    void deadLetterIsClosedEvenWhenTheRunAborts() {
        List<String> events = new ArrayList<>();
        Sink<FailedRecord> target = new Sink<>() {
            @Override public void accept(FailedRecord r) { }
            @Override public void close() { events.add("close"); }
        };
        ErrorRouter r = new ErrorRouter(new RunSummary(), target);
        Pipeline p = PipelineBuilder.from(new TrackingSource<>(lines("bad"), r, TrackingSource.annotatorFor(String.class)))
                .via(new GuardedStage<>("s", rejectBad(), ErrorPolicy.FAIL_FAST, r, false))
                .to(item -> { });

        assertEquals(RunSummary.Status.ABORTED, new ManagedRun(p, r, target).run().status());
        assertEquals(List.of("close"), events);
    }

    @Test
    void renderShowsCountsReconciliationAndFailureReason() {
        RunSummary s = managed(lines("a", "bad"), rejectBad(), ErrorPolicy.SKIP, false).run();
        String text = s.render();
        assertTrue(text.contains("Calisma ozeti [OK]"));
        assertTrue(text.contains("okunan          : 2"));
        assertTrue(text.contains("mutabakat       : TAMAM (okunan 2 / hesaplanan 2)"));
        assertTrue(text.contains("PARSE_MALFORMED=1"));
    }
}
