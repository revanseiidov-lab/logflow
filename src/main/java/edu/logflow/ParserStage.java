package edu.logflow;

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
 * <p><b>Hatalı satırlar (bu hafta):</b> yalnızca atlanır ve sayılır. Hiçbir şey yayılmaz, hata
 * fırlatılmaz, hata nedeni kaydedilmez. Hataların doğru ele alınması (neden, ayrı kanal, yeniden
 * deneme) 5. haftada gelecektir; mimari katman katman inşa edilir. Boş satır da bu hafta hatalı sayılır.
 */
public final class ParserStage implements Stage<String, LogRecord> {

    private static final Pattern CLF = Pattern.compile(
            "^(\\S+)\\s+\\S+\\s+\\S+\\s+\\[([^\\]]+)\\]\\s+"      // ip, kimlik, kullanici, [zaman]
            + "\"(\\S+)\\s+(\\S+)(?:\\s+[^\"]*)?\"\\s+"           // "YONTEM hedef [protokol]"
            + "(\\S+)\\s+(\\S+)"                                    // durum, bayt
            + "(?:\\s+\"[^\"]*\"\\s+\"([^\"]*)\")?$");              // [ "referer" "user-agent" ]

    private static final DateTimeFormatter CLF_TIME =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    private long malformedCount;

    @Override
    public void process(String line, Emitter<LogRecord> out) {
        LogRecord record = parse(line);
        if (record == null) {
            malformedCount++;
            return;
        }
        out.emit(record);
    }

    /** Atlanan (ayrıştırılamayan) satır sayısı. */
    public long malformedCount() {
        return malformedCount;
    }

    private static LogRecord parse(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        Matcher m = CLF.matcher(trimmed);
        if (!m.matches()) {
            return null;
        }
        try {
            Instant timestamp = OffsetDateTime.parse(m.group(2), CLF_TIME).toInstant();
            int status = Integer.parseInt(m.group(5));
            if (status < 100 || status > 599) {
                return null;
            }
            String bytesField = m.group(6);
            long bytes = bytesField.equals("-") ? 0L : Long.parseLong(bytesField);
            if (bytes < 0) {
                return null;
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

            return new LogRecord(timestamp, m.group(1), m.group(3), path,
                    status, bytes, userAgent, attributes, line);
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }
}
