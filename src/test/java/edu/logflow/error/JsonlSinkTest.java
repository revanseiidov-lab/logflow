package edu.logflow.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlSinkTest {

    private static FailedRecord record(String raw) {
        return new FailedRecord(Instant.parse("2026-01-01T00:00:00Z"), "parse", ErrorType.PARSE_MALFORMED,
                "neden", 7, raw, false, null);
    }

    @Test
    void writesOneJsonObjectPerLineAndCreatesParentDirectories(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a/b/dead.jsonl");
        JsonlSink sink = new JsonlSink(file);
        sink.open();
        sink.accept(record("one"));
        sink.accept(record("two"));
        sink.close();

        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertEquals("{\"timestamp\":\"2026-01-01T00:00:00Z\",\"stage\":\"parse\",\"errorType\":\"PARSE_MALFORMED\","
                + "\"message\":\"neden\",\"line\":7,\"rawInput\":\"one\",\"rawTruncated\":false}", lines.get(0));
    }

    @Test
    void emptyRunStillLeavesAnEmptyFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("dead.jsonl");
        JsonlSink sink = new JsonlSink(file);
        sink.open();
        sink.close();
        assertTrue(Files.exists(file));
        assertEquals(0, Files.size(file));
    }

    @Test
    void escapesQuotesBackslashesControlCharactersAndLoneSurrogates() {
        assertEquals("\"a\\\"b\\\\c\\nd\\te\\u0001\"", JsonlSink.quote("a\"b\\c\nd\te\u0001"));
        assertEquals("\"\\ud800\"", JsonlSink.quote("\ud800"));
        assertEquals("\"\uD83D\uDE00\"", JsonlSink.quote("\uD83D\uDE00"));       // geçerli çift korunur
    }

    @Test
    void includesBase64OnlyWhenPresent() {
        FailedRecord r = new FailedRecord(Instant.EPOCH, "decode", ErrorType.READ_ENCODING, "m", 1, "x", true, "/+8=");
        String json = JsonlSink.toJson(r);
        assertTrue(json.contains("\"rawTruncated\":true"));
        assertTrue(json.endsWith(",\"rawBase64\":\"/+8=\"}"));
    }
}
