package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.logflow.error.ErrorType;
import edu.logflow.error.StageException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ParserStage testleri: dosya sistemi yok, yalnızca dizgi girer ve CollectingEmitter çıktıyı yakalar. */
class ParserStageTest {

    private static final String VALID =
            "192.168.1.10 - - [10/Oct/2023:13:55:36 +0300] \"GET /index.html HTTP/1.1\" 200 2326 \"-\" \"Mozilla/5.0\"";

    private ParserStage parser;
    private CollectingEmitter<LogRecord> out;
    private List<StageException> rejected;

    @BeforeEach
    void setUp() {
        parser = new ParserStage();
        out = new CollectingEmitter<>();
        rejected = new ArrayList<>();
    }

    /** v5: ayrıştırıcı hatayı fırlatır; sayma yönlendiricinin işidir. Burada fırlatılanları topluyoruz. */
    private void feed(String line, CollectingEmitter<LogRecord> sink) {
        try {
            parser.process(line, sink);
        } catch (StageException e) {
            rejected.add(e);
        }
    }

    @Test
    void errorTypesTellWhyTheLineWasRejected() {
        feed("garbage", out);
        feed("", out);
        feed("1.2.3.4 - - [31/Foo/2023:13:55:36 +0300] \"GET / HTTP/1.1\" 200 10", out);
        feed("1.2.3.4 - - [10/Oct/2023:13:55:36 +0300] \"GET / HTTP/1.1\" abc 10", out);
        feed("1.2.3.4 - - [10/Oct/2023:13:55:36 +0300] \"GET / HTTP/1.1\" 200 xyz", out);
        feed("1.2.3.4 - - [10/Oct/2023:13:55:36 +0300] \"GET / HTTP/1.1\" 999 10", out);
        feed("1.2.3.4 - - [10/Oct/2023:13:55:36 +0300] \"GET / HTTP/1.1\" 200 -5", out);

        assertEquals(0, out.size());
        assertEquals(List.of(ErrorType.PARSE_MALFORMED, ErrorType.PARSE_MALFORMED, ErrorType.PARSE_TIMESTAMP,
                ErrorType.PARSE_NUMBER, ErrorType.PARSE_NUMBER, ErrorType.VALIDATION_RANGE,
                ErrorType.VALIDATION_RANGE), rejected.stream().map(StageException::type).toList());
    }

    @Test
    void rejectionNeverEmitsAnythingBeforeThrowing() {
        feed("garbage", out);
        assertEquals(0, out.size());
    }

    // 1. geçerli satır
    @Test
    void validLineProducesRecordWithAllFields() {
        feed(VALID, out);

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
        assertEquals(0, rejected.size());
    }

    // 2. eksik alan
    @Test
    void missingFieldIsSkippedAndCounted() {
        feed("192.168.1.10 - - [10/Oct/2023:13:55:36 +0300] \"GET /index.html HTTP/1.1\" 200", out);

        assertEquals(0, out.size());
        assertEquals(1, rejected.size());
    }

    // 3. hatalı zaman damgası
    @Test
    void badTimestampIsSkippedAndCounted() {
        feed("192.168.1.10 - - [31/Foo/2023:13:55:36 +0300] \"GET / HTTP/1.1\" 200 10", out);

        assertEquals(0, out.size());
        assertEquals(1, rejected.size());
    }

    // 4. hatalı durum kodu (sayı değil ve aralık dışı)
    @Test
    void badStatusCodeIsSkippedAndCounted() {
        feed("192.168.1.10 - - [10/Oct/2023:13:55:36 +0300] \"GET / HTTP/1.1\" abc 10", out);
        feed("192.168.1.10 - - [10/Oct/2023:13:55:36 +0300] \"GET / HTTP/1.1\" 999 10", out);

        assertEquals(0, out.size());
        assertEquals(2, rejected.size());
    }

    // 5. boş satır
    @Test
    void blankLinesAreSkippedAndCounted() {
        feed("", out);
        feed("   \t ", out);
        feed(null, out);

        assertEquals(0, out.size());
        assertEquals(3, rejected.size());
    }

    // 6. fazladan boşluk
    @Test
    void extraWhitespaceIsTolerated() {
        feed("   192.168.1.10   -  -  [10/Oct/2023:13:55:36 +0300]   \"GET   /index.html   HTTP/1.1\"   200   2326   \"-\"   \"Mozilla/5.0\"   ", out);

        assertEquals(1, out.size());
        assertEquals("/index.html", out.first().path());
        assertEquals(200, out.first().status());
        assertEquals("Mozilla/5.0", out.first().userAgent());
        assertEquals(0, rejected.size());
    }

    // 7. boşluk içeren tırnaklı user agent
    @Test
    void quotedUserAgentWithSpacesIsKeptWhole() {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/118.0";
        feed("10.0.0.1 - - [10/Oct/2023:13:55:36 +0300] \"GET / HTTP/1.1\" 200 5 \"http://example.com/a b\" \"" + ua + "\"", out);

        assertEquals(1, out.size());
        assertEquals(ua, out.first().userAgent());
    }

    // 8. sorgu dizesi içeren satır
    @Test
    void queryStringIsSplitFromPathIntoAttributes() {
        feed("10.0.0.1 - - [10/Oct/2023:13:55:36 +0300] \"GET /search?q=log+analysis&page=2 HTTP/1.1\" 200 9042 \"-\" \"curl/8\"", out);

        assertEquals(1, out.size());
        assertEquals("/search", out.first().path());
        assertEquals("q=log+analysis&page=2", out.first().attributes().get("query"));
    }

    // ek durumlar
    @Test
    void dashBytesMeansZero() {
        feed("10.0.0.1 - - [10/Oct/2023:13:55:36 +0300] \"GET /x HTTP/1.1\" 404 - \"-\" \"curl/8\"", out);

        assertEquals(1, out.size());
        assertEquals(0L, out.first().bytes());
    }

    @Test
    void plainCommonLogFormatWithoutUserAgentGivesEmptyUserAgent() {
        feed("10.0.0.1 - frank [10/Oct/2023:13:55:36 -0700] \"GET /x HTTP/1.0\" 200 2326", out);

        assertEquals(1, out.size());
        assertEquals("", out.first().userAgent());
        assertEquals(Instant.parse("2023-10-10T20:55:36Z"), out.first().timestamp());
    }

    @Test
    void goodAndBadLinesAreCountedSeparately() {
        feed(VALID, out);
        feed("garbage", out);
        feed(VALID, out);
        feed("", out);

        assertEquals(2, out.size());
        assertEquals(2, rejected.size());
    }
}
