package edu.logflow.plugins;

import edu.logflow.LogRecord;
import edu.logflow.PredicateFilterStage;
import edu.logflow.Stage;
import edu.logflow.config.Params;
import edu.logflow.config.StageFactory;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * {@code type: filter} — {@code field}, {@code op}, {@code value} üçlüsünden bir koşul kurar.
 * Alanın türü hangi işleçlerin ve hangi değer tipinin geçerli olduğunu belirler:
 * sayısal (status, bytes), metin (clientIp, method, path, userAgent), zaman (timestamp).
 */
public final class FilterStageFactory implements StageFactory {

    private enum Kind {
        NUMBER("sayisal", List.of("==", "!=", ">", ">=", "<", "<=")),
        TEXT("metin", List.of("==", "!=", "startsWith", "endsWith", "contains")),
        TIME("zaman", List.of("==", ">", ">=", "<", "<="));

        final String label;
        final List<String> ops;

        Kind(String label, List<String> ops) {
            this.label = label;
            this.ops = ops;
        }
    }

    private static final Map<String, Kind> FIELDS = new LinkedHashMap<>();
    private static final Map<String, Function<LogRecord, Long>> NUMBERS = Map.of(
            "status", r -> (long) r.status(),
            "bytes", LogRecord::bytes);
    private static final Map<String, Function<LogRecord, String>> TEXTS = Map.of(
            "clientIp", LogRecord::clientIp,
            "method", LogRecord::method,
            "path", LogRecord::path,
            "userAgent", LogRecord::userAgent);

    static {
        FIELDS.put("status", Kind.NUMBER);
        FIELDS.put("bytes", Kind.NUMBER);
        FIELDS.put("clientIp", Kind.TEXT);
        FIELDS.put("method", Kind.TEXT);
        FIELDS.put("path", Kind.TEXT);
        FIELDS.put("userAgent", Kind.TEXT);
        FIELDS.put("timestamp", Kind.TIME);
    }

    @Override
    public String type() {
        return "filter";
    }

    @Override
    public Class<?> inputType() {
        return LogRecord.class;
    }

    @Override
    public Class<?> outputType() {
        return LogRecord.class;
    }

    @Override
    public String example() {
        return "{ field: status, op: \">=\", value: 400 }";
    }

    @Override
    public Stage<?, ?> create(Params p) {
        p.allowOnly("field", "op", "value");
        String field = p.requireString("field");
        Kind kind = FIELDS.get(field);
        if (kind == null) {
            throw p.fail("bilinmeyen alan '" + field + "' (gecerli: " + String.join(", ", FIELDS.keySet()) + ")");
        }
        String op = p.requireString("op");
        if (!kind.ops.contains(op)) {
            throw p.fail("'" + op + "' islemi '" + field + "' alani (" + kind.label + ") icin gecersiz (gecerli: "
                    + String.join(", ", kind.ops) + ")");
        }
        Object value = p.require("value");
        return new PredicateFilterStage(switch (kind) {
            case NUMBER -> number(p, field, op, value);
            case TEXT -> text(p, field, op, value);
            case TIME -> time(p, op, value);
        });
    }

    private static Predicate<LogRecord> number(Params p, String field, String op, Object value) {
        if (!(value instanceof Integer || value instanceof Long)) {
            throw p.typeError("value", "tam sayi ('" + field + "' sayisal bir alan)", value);
        }
        long expected = ((Number) value).longValue();
        Function<LogRecord, Long> get = NUMBERS.get(field);
        return r -> {
            long actual = get.apply(r);
            return switch (op) {
                case "==" -> actual == expected;
                case "!=" -> actual != expected;
                case ">" -> actual > expected;
                case ">=" -> actual >= expected;
                case "<" -> actual < expected;
                default -> actual <= expected;
            };
        };
    }

    private static Predicate<LogRecord> text(Params p, String field, String op, Object value) {
        if (!(value instanceof String expected)) {
            throw p.typeError("value", "metin ('" + field + "' bir metin alani; sayilari tirnak icine alin)", value);
        }
        Function<LogRecord, String> get = TEXTS.get(field);
        return r -> {
            String actual = get.apply(r);
            return switch (op) {
                case "==" -> actual.equals(expected);
                case "!=" -> !actual.equals(expected);
                case "startsWith" -> actual.startsWith(expected);
                case "endsWith" -> actual.endsWith(expected);
                default -> actual.contains(expected);
            };
        };
    }

    private static Predicate<LogRecord> time(Params p, String op, Object value) {
        if (!(value instanceof String text)) {
            throw p.typeError("value", "ISO bicimli zaman metni (ornek: \"2023-10-10T11:00:00Z\")", value);
        }
        Instant expected;
        try {
            expected = Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw p.fail("'value' gecerli bir ISO zamani degil: \"" + text + "\" (ornek: \"2023-10-10T11:00:00Z\")");
        }
        return r -> {
            int c = r.timestamp().compareTo(expected);
            return switch (op) {
                case "==" -> c == 0;
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                case "<" -> c < 0;
                default -> c <= 0;
            };
        };
    }
}
