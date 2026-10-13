package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** PipelineBuilder: tip güvenli birleştirme ve Sink yaşam döngüsü (open -> accept* -> close). */
class PipelineBuilderTest {

    /** Olayları sırayla kaydeden sink. */
    private static final class RecordingSink implements Sink<String> {
        final List<String> events = new ArrayList<>();

        @Override
        public void open() {
            events.add("open");
        }

        @Override
        public void accept(String item) {
            events.add("accept:" + item);
        }

        @Override
        public void close() {
            events.add("close");
        }
    }

    @Test
    void sinkIsOpenedBeforeFirstItemAndClosedAfterLast() throws IOException {
        RecordingSink sink = new RecordingSink();
        Source<String> source = out -> {
            out.emit("a");
            out.emit("b");
        };

        PipelineBuilder.from(source).to(sink).run();

        assertEquals(List.of("open", "accept:a", "accept:b", "close"), sink.events);
    }

    @Test
    void sinkIsStillClosedWhenAStageFails() {
        RecordingSink sink = new RecordingSink();
        Source<String> source = out -> out.emit("a");
        Stage<String, String> boom = (in, out) -> {
            throw new IllegalStateException("boom");
        };

        assertThrows(IllegalStateException.class,
                () -> PipelineBuilder.from(source).via(boom).to(sink).run());

        assertEquals(List.of("open", "close"), sink.events);
    }

    @Test
    void sinkIsStillClosedWhenSourceFails() {
        RecordingSink sink = new RecordingSink();
        Source<String> source = out -> {
            throw new IOException("disk gone");
        };

        assertThrows(IOException.class, () -> PipelineBuilder.from(source).to(sink).run());

        assertEquals(List.of("open", "close"), sink.events);
    }

    @Test
    void closeFailureDoesNotMaskTheOriginalFailure() {
        Sink<String> badSink = new Sink<>() {
            @Override
            public void accept(String item) {
            }

            @Override
            public void close() throws IOException {
                throw new IOException("close failed");
            }
        };
        Source<String> source = out -> {
            throw new IOException("read failed");
        };

        IOException thrown = assertThrows(IOException.class,
                () -> PipelineBuilder.from(source).to(badSink).run());

        assertEquals("read failed", thrown.getMessage());
        assertEquals(1, thrown.getSuppressed().length);
    }

    @Test
    void sameStageTypeCanBeAddedAnyNumberOfTimes() throws IOException {
        List<Integer> received = new ArrayList<>();
        Source<Integer> source = out -> {
            for (int i = 1; i <= 10; i++) {
                out.emit(i);
            }
        };
        Stage<Integer, Integer> even = (in, out) -> {
            if (in % 2 == 0) {
                out.emit(in);
            }
        };
        Stage<Integer, Integer> big = (in, out) -> {
            if (in > 4) {
                out.emit(in);
            }
        };

        PipelineBuilder.from(source).via(even).via(big).via(even).to(received::add).run();

        assertEquals(List.of(6, 8, 10), received);
    }

    @Test
    void emptyPipelineWithoutStagesWorks() throws IOException {
        RecordingSink sink = new RecordingSink();

        PipelineBuilder.<String>from(out -> { }).to(sink).run();

        assertTrue(sink.events.equals(List.of("open", "close")));
    }
}
