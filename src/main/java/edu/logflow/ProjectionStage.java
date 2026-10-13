package edu.logflow;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Her kaydı seçilen alanların bir alt kümesine indirger (veri minimizasyonu): seçilmeyen alan
 * (ör. istemci IP'si, user agent) bu aşamadan sonra hiçbir aşamada ve çıktıda yer almaz.
 * Hem çıktı boyutunu küçültür hem de kişisel veriyi daha erken noktada eler.
 */
public final class ProjectionStage implements Stage<LogRecord, ProjectedRecord> {

    /** Varsayılan alan kümesi. Ham satır (raw) bilerek dışarıda: her şeyi içerir. */
    public static final List<String> ALL_FIELDS =
            List.of("timestamp", "clientIp", "method", "path", "status", "bytes", "userAgent");

    private final Set<String> fields;

    public ProjectionStage(String... fields) {
        this(List.of(fields));
    }

    public ProjectionStage(List<String> fields) {
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("En az bir alan secilmeli");
        }
        for (String f : fields) {
            if (!ALL_FIELDS.contains(f) && !f.equals("query")) {
                throw new IllegalArgumentException("Bilinmeyen alan: " + f + " (gecerli: " + ALL_FIELDS + ", query)");
            }
        }
        this.fields = new LinkedHashSet<>(fields);
    }

    @Override
    public void process(LogRecord r, Emitter<ProjectedRecord> out) {
        Map<String, String> picked = new LinkedHashMap<>();
        for (String f : fields) {
            picked.put(f, valueOf(r, f));
        }
        out.emit(new ProjectedRecord(picked));
    }

    private static String valueOf(LogRecord r, String field) {
        return switch (field) {
            case "timestamp" -> r.timestamp().toString();
            case "clientIp" -> r.clientIp();
            case "method" -> r.method();
            case "path" -> r.path();
            case "status" -> String.valueOf(r.status());
            case "bytes" -> String.valueOf(r.bytes());
            case "userAgent" -> r.userAgent();
            default -> r.attributes().getOrDefault(field, "");   // "query"
        };
    }
}
