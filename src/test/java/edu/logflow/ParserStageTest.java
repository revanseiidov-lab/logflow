package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ParserStage testleri: dosya sistemi yok, yalnızca dizgi girer ve CollectingEmitter çıktıyı yakalar. */
class ParserStageTest {

    private static final String VALID =
            "192.168.1.10 - - [10/Oct/2023:13:55:36 +0300] \"GET /index.html HTTP/1.1\" 200 2326 \"-\" \"Mozilla/5.0\"";

    private ParserStage parser;
    private CollectingEmitter<LogRecord> out;

    @BeforeEach
    void setUp() {
        parser = new ParserStage();
        out = new CollectingEmitter<>();
    }

    // 1. geçerli satır
    @Test
    void validLineProducesRecordWithAllFields() {
        parser.process(VALID, out);

        assertEquals(1, out.size());
        LogRecord r = out.first();
        assertEquals(Instant.parse("2023-10-10T10:55:36Z"), r.timestamp());
        assertEquals("192.168.1.10", r.clientIp());
        assertEquals("GET", r.method());
        assertEquals("/index.html", r.path());
        assertEquals(200, r.status());
        assertEquals(2326L, r.bytes());
        assertEquals("Mozilla/5.0", r.userAgent());
        assertTrue(r.attributes().isEmpty());
        assertEquals(VALID, r.raw());
        assertEquals(0, parser.malformedCount());
    }

    // 2. eksik alan
    @Test
    void missingFieldIsSkippedAndCounted() {
        parser.process("192.168.1.10 - - [10/Oct/2023:13:55:36 +0300] \"GET /index.html HTTP/1.1\" 200", out);

        assertEquals(0, out.size());
        assertEquals(1, parser.malformedCount());
    }

    // 3. hatalı zaman damgası
    @Test
    void badTimestampIsSkippedAndCounted() {
        parser.process("192.168.1.10 - - [31/Foo/2023:13:55:36 +0300] \"GET / HTTP/1.1\" 200 10", out);

        assertEquals(0, out.size());
        assertEquals(1, parser.malformedCount());
    }

    // 4. hatalı durum kodu (sayı değil ve aralık dışı)
    @Test
    void badStatusCodeIsSkippedAndCounted() {
        parser.process("192.168.1.10 - - [10/Oct/2023:13:55:36 +0300] \"GET / HTTP/1.1\" abc 10", out);
        parser.process("192.168.1.10 - - [10/Oct/2023:13:55:36 +0300] \"GET / HTTP/1.1\" 999 10", out);

        assertEquals(0, out.size());
        assertEquals(2, parser.malformedCount());
    }

    // 5. boş satır
    @Test
    void blankLinesAreSkippedAndCounted() {
        parser.process("", out);
        parser.process("   \t ", out);
        parser.process(null, out);

        assertEquals(0, out.size());
        assertEquals(3, parser.malformedCount());
    }

    // 6. fazladan boşluk
    @Test
    void extraWhitespaceIsTolerated() {
        parser.process("   192.168.1.10   -  -  [10/Oct/2023:13:55:36 +0300]   \"GET   /index.html   HTTP/1.1\"   200   2326   \"-\"   \"Mozilla/5.0\"   ", out);

        assertEquals(1, out.size());
        assertEquals("/index.html", out.first().path());
        assertEquals(200, out.first().status());
        assertEquals("Mozilla/5.0", out.first().userAgent());
        assertEquals(0, parser.malformedCount());
    }

    // 7. boşluk içeren tırnaklı user agent
    @Test
    void quotedUserAgentWithSpacesIsKeptWhole() {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/118.0";
        parser.process("10.0.0.1 - - [10/Oct/2023:13:55:36 +0300] \"GET / HTTP/1.1\" 200 5 \"http://example.com/a b\" \"" + ua + "\"", out);

        assertEquals(1, out.size());
        assertEquals(ua, out.first().userAgent());
    }

    // 8. sorgu dizesi içeren satır
    @Test
    void queryStringIsSplitFromPathIntoAttributes() {
        parser.process("10.0.0.1 - - [10/Oct/2023:13:55:36 +0300] \"GET /search?q=log+analysis&page=2 HTTP/1.1\" 200 9042 \"-\" \"curl/8\"", out);

        assertEquals(1, out.size());
        assertEquals("/search", out.first().path());
        assertEquals("q=log+analysis&page=2", out.first().attributes().get("query"));
    }

    // ek durumlar
    @Test
    void dashBytesMeansZero() {
        parser.process("10.0.0.1 - - [10/Oct/2023:13:55:36 +0300] \"GET /x HTTP/1.1\" 404 - \"-\" \"curl/8\"", out);

        assertEquals(1, out.size());
        assertEquals(0L, out.first().bytes());
    }

    @Test
    void plainCommonLogFormatWithoutUserAgentGivesEmptyUserAgent() {
        parser.process("10.0.0.1 - frank [10/Oct/2023:13:55:36 -0700] \"GET /x HTTP/1.0\" 200 2326", out);

        assertEquals(1, out.size());
        assertEquals("", out.first().userAgent());
        assertEquals(Instant.parse("2023-10-10T20:55:36Z"), out.first().timestamp());
    }

    @Test
    void goodAndBadLinesAreCountedSeparately() {
        parser.process(VALID, out);
        parser.process("garbage", out);
        parser.process(VALID, out);
        parser.process("", out);

        assertEquals(2, out.size());
        assertEquals(2, parser.malformedCount());
    }
}
