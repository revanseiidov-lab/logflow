package edu.logflow.plugins;

import edu.logflow.LogRecord;
import edu.logflow.Stage;
import edu.logflow.ValidationStage;
import edu.logflow.config.Params;
import edu.logflow.config.StageFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * {@code type: validate} — bildirimli doğrulama kuralları. Hepsi isteğe bağlı:
 * {@code required: [path, method]}, {@code validIp: true}, {@code minStatus/maxStatus}, {@code minBytes/maxBytes},
 * {@code notBefore/notAfter} (ISO zamanı).
 */
public final class ValidateStageFactory implements StageFactory {

    @Override
    public String type() {
        return "validate";
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
        return "{ required: [clientIp, path], validIp: true, minBytes: 0, notBefore: \"2000-01-01T00:00:00Z\" }";
    }

    @Override
    public Stage<?, ?> create(Params p) {
        p.allowOnly("required", "validIp", "minStatus", "maxStatus", "minBytes", "maxBytes", "notBefore", "notAfter");
        var required = p.optStringList("required");
        for (String field : required) {
            if (!ValidationStage.Rules.REQUIRABLE.contains(field)) {
                throw p.fail("'required' icin bilinmeyen alan '" + field + "' (gecerli: "
                        + String.join(", ", ValidationStage.Rules.REQUIRABLE) + ")");
            }
        }
        Integer minBytes = p.optIntOrNull("minBytes");
        Integer maxBytes = p.optIntOrNull("maxBytes");
        Integer minStatus = p.optIntOrNull("minStatus");
        Integer maxStatus = p.optIntOrNull("maxStatus");
        order(p, "minStatus", minStatus, "maxStatus", maxStatus);
        order(p, "minBytes", minBytes, "maxBytes", maxBytes);
        Instant notBefore = instant(p, "notBefore");
        Instant notAfter = instant(p, "notAfter");
        if (notBefore != null && notAfter != null && notBefore.isAfter(notAfter)) {
            throw p.fail("'notBefore' 'notAfter'dan sonra olamaz");
        }
        return new ValidationStage(new ValidationStage.Rules(required, p.optBoolean("validIp", false),
                minStatus, maxStatus, minBytes == null ? null : minBytes.longValue(),
                maxBytes == null ? null : maxBytes.longValue(), notBefore, notAfter));
    }

    private static void order(Params p, String lo, Integer a, String hi, Integer b) {
        if (a != null && b != null && a > b) {
            throw p.fail("'" + lo + "' (" + a + "), '" + hi + "' (" + b + ") degerinden buyuk olamaz");
        }
    }

    private static Instant instant(Params p, String name) {
        if (!p.has(name)) {
            return null;
        }
        String text = p.requireString(name);
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw p.fail("'" + name + "' gecerli bir ISO zamani degil: \"" + text + "\"");
        }
    }
}
