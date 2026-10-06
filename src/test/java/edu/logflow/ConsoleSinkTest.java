package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsoleSinkTest {

    @Test
    void formatsRecordAsSingleReadableLine() {
        LogRecord r = new LogRecord(Instant.parse("2023-10-10T10:55:36Z"), "192.168.1.10", "GET",
                "/index.html", 200, 2326L, "Mozilla/5.0", Map.of(), "raw");

        assertEquals("2023-10-10T10:55:36Z  192.168.1.10     GET    /index.html  200  2326B  \"Mozilla/5.0\"",
                ConsoleSink.format(r));
    }

    @Test
    void queryStringIsShownAfterPath() {
        LogRecord r = new LogRecord(Instant.EPOCH, "1.1.1.1", "GET", "/s", 200, 0L, "", Map.of("query", "q=1"), "raw");

        assertEquals(true, ConsoleSink.format(r).contains("/s?q=1"));
    }

    @Test
    void acceptWritesOneLinePerRecord() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ConsoleSink sink = new ConsoleSink(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        LogRecord r = new LogRecord(Instant.EPOCH, "1.1.1.1", "GET", "/", 200, 0L, "", Map.of(), "raw");

        sink.accept(r);
        sink.accept(r);

        String[] lines = buffer.toString(StandardCharsets.UTF_8).split(System.lineSeparator());
        assertEquals(2, lines.length);
    }
}
