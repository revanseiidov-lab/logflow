package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineTest {

    private static final String LINE =
            "10.0.0.1 - - [10/Oct/2023:13:55:36 +0300] \"GET /a HTTP/1.1\" 200 5 \"-\" \"curl/8\"";

    @Test
    void parserStageInsertedInTheMiddleConnectsSourceAndSink() throws IOException {
        ParserStage parser = new ParserStage();
        List<LogRecord> received = new ArrayList<>();
        Source<String> source = out -> {
            out.emit(LINE);
            out.emit("not a log line");
            out.emit(LINE);
        };

        Pipeline.from(source).then(parser).to(received::add).run();

        assertEquals(2, received.size());
        assertEquals(1, parser.malformedCount());
    }

    @Test
    void stagesRunInTheOrderTheyWereAdded() throws IOException {
        List<String> received = new ArrayList<>();
        Source<String> source = out -> out.emit("a");
        Stage<String, String> addX = (in, out) -> out.emit(in + "x");
        Stage<String, String> addY = (in, out) -> out.emit(in + "y");

        Pipeline.from(source).then(addX).then(addY).to(received::add).run();

        assertEquals(List.of("axy"), received);
    }

    @Test
    void lineSourceReadsFileLineByLine(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("t.log");
        Files.writeString(file, "one\ntwo\nthree\n");
        List<String> received = new ArrayList<>();

        Pipeline.from(new LineSource(file)).to(received::add).run();

        assertEquals(List.of("one", "two", "three"), received);
    }
}
