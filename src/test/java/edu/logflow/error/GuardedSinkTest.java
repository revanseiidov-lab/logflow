package edu.logflow.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.logflow.Sink;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** retry(n) yalnızca sink'te: geçici G/Ç hatası yeniden denenir, tükenirse fallback politikası uygulanır. */
class GuardedSinkTest {

    private final RunSummary summary = new RunSummary();
    private final List<FailedRecord> dead = new ArrayList<>();
    private final ErrorRouter router = new ErrorRouter(summary, dead::add);

    /** İlk {@code failures} çağrıda IO hatası verir, sonra başarılı olur. */
    private static final class Flaky implements Sink<String> {
        final int failures;
        int calls;
        final List<String> written = new ArrayList<>();

        Flaky(int failures) {
            this.failures = failures;
        }

        @Override
        public void accept(String item) {
            if (++calls <= failures) {
                throw new UncheckedIOException(new IOException("gecici hata"));
            }
            written.add(item);
        }
    }

    @Test
    void healthyWriteIsCountedAsEmitted() {
        Flaky sink = new Flaky(0);
        new GuardedSink<>("sink", sink, ErrorPolicy.FAIL_FAST, ErrorPolicy.FAIL_FAST, router).accept("a");
        assertEquals(1, summary.emittedCount());
    }

    @Test
    void retrySucceedsAfterTransientFailures() {
        Flaky sink = new Flaky(2);
        new GuardedSink<>("sink", sink, ErrorPolicy.retry(3), ErrorPolicy.FAIL_FAST, router).accept("a");

        assertEquals(3, sink.calls);
        assertEquals(List.of("a"), sink.written);
        assertEquals(1, summary.emittedCount());
        assertEquals(0, summary.deadLetteredCount());
    }

    @Test
    void exhaustedRetriesFallBackToDeadLetterWithSinkIoType() {
        Flaky sink = new Flaky(100);
        new GuardedSink<>("sink", sink, ErrorPolicy.retry(2), ErrorPolicy.DEAD_LETTER, router).accept("a");

        assertEquals(3, sink.calls);                       // ilk deneme + 2 yeniden deneme
        assertEquals(0, summary.emittedCount());
        assertEquals(1, summary.deadLetteredCount());
        assertEquals(ErrorType.SINK_IO, dead.get(0).errorType());
        assertEquals("sink", dead.get(0).stage());
    }

    @Test
    void exhaustedRetriesWithFailFastFallbackAbortTheRun() {
        Flaky sink = new Flaky(100);
        GuardedSink<String> g = new GuardedSink<>("sink", sink, ErrorPolicy.retry(1), ErrorPolicy.FAIL_FAST, router);
        assertThrows(AbortRun.class, () -> g.accept("a"));
        assertEquals(2, sink.calls);
    }

    @Test
    void withoutRetryASinkFailureIsHandledByThePolicyImmediately() {
        Flaky sink = new Flaky(100);
        new GuardedSink<>("sink", sink, ErrorPolicy.SKIP, ErrorPolicy.FAIL_FAST, router).accept("a");
        assertEquals(1, sink.calls);
        assertEquals(1, summary.skippedCount());
    }
}
