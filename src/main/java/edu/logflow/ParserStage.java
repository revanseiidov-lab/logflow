package edu.logflow;

import edu.logflow.error.ErrorType;
import edu.logflow.error.StageException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ortak Günlük Biçimi (CLF) satırını {@link LogRecord} nesnesine çeviren aşama.
 *
 * <p>Biçim: {@code ip kimlik kullanici [zaman] "YONTEM yol PROTOKOL" durum bayt}. Sonuna isteğe bağlı
 * olarak "referer" ve "user agent" alanları (birleşik biçim, combined) gelebilir; yoksa userAgent boş kalır.
 *
 * <p><b>Hatalı satırlar (v5):</b> aşama artık satırı sessizce yutmaz; nedenini taşıyan bir
 * {@link StageException} fırlatır. Kaydın akıbetini (atla, dead-letter, dur) aşamanın hata politikası belirler;
 * aşama bunu bilmez. Sayaç tutmaz: sayım yönlendiricide (tek doğruluk kaynağı).
 */
public final class ParserStage implements Stage<String, LogRecord> {

    private static final Pattern CLF = Pattern.compile(
            "^(\\S+)\\s+\\S+\\s+\\S+\\s+\\[([^\\]]+)\\]\\s+"      // ip, kimlik, kullanici, [zaman]
            + "\"(\\S+)\\s+(\\S+)(?:\\s+[^\"]*)?\"\\s+"           // "YONTEM hedef [protokol]"
            + "(\\S+)\\s+(\\S+)"                                    // durum, bayt
            + "(?:\\s+\"[^\"]*\"\\s+\"([^\"]*)\")?$");              // [ "referer" "user-agent" ]

    private static final DateTimeFormatter CLF_TIME =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    @Override
    public void process(String line, Emitter<LogRecord> out) {
        out.emit(parse(line));
    }

    /** Ayrıştırır; olmazsa nedenini taşıyan {@link StageException} fırlatır. */
    static LogRecord parse(String line) {
        if (line == null || line.isBlank()) {
            throw new StageException(ErrorType.PARSE_MALFORMED, "bos satir");
        }
        String trimmed = line.strip();
        Matcher m = CLF.matcher(trimmed);
        if (!m.matches()) {
            throw new StageException(ErrorType.PARSE_MALFORMED, "satir gunluk bicimine (CLF) uymuyor");
        }
        Instant timestamp;
        try {
            timestamp = OffsetDateTime.parse(m.group(2), CLF_TIME).toInstant();
        } catch (DateTimeParseException e) {
            throw new StageException(ErrorType.PARSE_TIMESTAMP, "zaman damgasi okunamadi: [" + m.group(2) + "]", e);
        }
        int status;
        try {
            status = Integer.parseInt(m.group(5));
        } catch (NumberFormatException e) {
            throw new StageException(ErrorType.PARSE_NUMBER, "durum kodu sayi degil: '" + m.group(5) + "'", e);
        }
        if (status < 100 || status > 599) {
            throw new StageException(ErrorType.VALIDATION_RANGE, "durum kodu 100-599 disinda: " + status);
        }
        String bytesField = m.group(6);
        long bytes;
        try {
            bytes = bytesField.equals("-") ? 0L : Long.parseLong(bytesField);
        } catch (NumberFormatException e) {
            throw new StageException(ErrorType.PARSE_NUMBER, "bayt alani sayi degil: '" + bytesField + "'", e);
        }
        if (bytes < 0) {
            throw new StageException(ErrorType.VALIDATION_RANGE, "bayt alani negatif: " + bytes);
        }

        String target = m.group(4);
        Map<String, String> attributes = new HashMap<>();
        String path = target;
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            attributes.put("query", target.substring(q + 1));
        }
        String userAgent = m.group(7) == null ? "" : m.group(7);

        return new LogRecord(timestamp, m.group(1), m.group(3), path, status, bytes, userAgent, attributes, line);
    }
}
