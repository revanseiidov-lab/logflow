package edu.logflow;

import edu.logflow.error.ErrorType;
import edu.logflow.error.StageException;

import java.time.Instant;
import java.util.List;

/**
 * Bildirimli (declarative) doğrulama: biçimce geçerli ama <b>anlamca</b> geçersiz kayıtları reddeder.
 * Ayrıştırıcı "satır CLF mi?" sorusunu, bu aşama "değerler makul mü?" sorusunu yanıtlar. Kurallar yapılandırmadan gelir
 * ({@link Rules}); kayıt hiç değiştirilmez, ya aynen geçer ya da nedenli bir {@link StageException} ile reddedilir.
 */
public final class ValidationStage implements Stage<LogRecord, LogRecord> {

    /** Doğrulama kuralları; {@code null} = kural yok. */
    public record Rules(List<String> required, boolean validIp, Integer minStatus, Integer maxStatus,
                        Long minBytes, Long maxBytes, Instant notBefore, Instant notAfter) {

        public static final List<String> REQUIRABLE = List.of("clientIp", "method", "path", "userAgent");

        public static Rules none() {
            return new Rules(List.of(), false, null, null, null, null, null, null);
        }
    }

    private final Rules rules;

    public ValidationStage(Rules rules) {
        this.rules = rules;
    }

    @Override
    public void process(LogRecord r, Emitter<LogRecord> out) {
        for (String field : rules.required()) {
            String v = switch (field) {
                case "clientIp" -> r.clientIp();
                case "method" -> r.method();
                case "path" -> r.path();
                default -> r.userAgent();
            };
            if (v == null || v.isBlank() || v.equals("-")) {
                throw new StageException(ErrorType.VALIDATION_REQUIRED, "zorunlu alan bos: " + field);
            }
        }
        if (rules.validIp() && !isIp(r.clientIp())) {
            throw new StageException(ErrorType.VALIDATION_IP, "gecersiz IP adresi: '" + r.clientIp() + "'");
        }
        if (rules.minStatus() != null && r.status() < rules.minStatus()
                || rules.maxStatus() != null && r.status() > rules.maxStatus()) {
            throw new StageException(ErrorType.VALIDATION_RANGE, "durum kodu izin verilen aralikta degil: " + r.status());
        }
        if (rules.minBytes() != null && r.bytes() < rules.minBytes()
                || rules.maxBytes() != null && r.bytes() > rules.maxBytes()) {
            throw new StageException(ErrorType.VALIDATION_RANGE, "bayt izin verilen aralikta degil: " + r.bytes());
        }
        if (rules.notBefore() != null && r.timestamp().isBefore(rules.notBefore())
                || rules.notAfter() != null && r.timestamp().isAfter(rules.notAfter())) {
            throw new StageException(ErrorType.VALIDATION_TIMESTAMP,
                    "zaman damgasi makul aralikta degil: " + r.timestamp());
        }
        out.emit(r);
    }

    /** DNS çözümlemesi yapmadan (ağ erişimi yok) IPv4/IPv6 sözdizimini denetler. */
    static boolean isIp(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        return s.indexOf(':') >= 0 ? isIpv6(s) : isIpv4(s);
    }

    private static boolean isIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) {
                return false;
            }
            for (int i = 0; i < p.length(); i++) {
                if (p.charAt(i) < '0' || p.charAt(i) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv6(String s) {
        int dbl = s.indexOf("::");
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) {
            return false;                                   // en çok bir "::"
        }
        String[] groups = s.split(":", -1);
        int count = 0;
        for (int i = 0; i < groups.length; i++) {
            String g = groups[i];
            if (g.isEmpty()) {
                continue;
            }
            if (i == groups.length - 1 && g.indexOf('.') >= 0) {   // gömülü IPv4
                if (!isIpv4(g)) {
                    return false;
                }
                count += 2;
                continue;
            }
            if (g.length() > 4) {
                return false;
            }
            for (int j = 0; j < g.length(); j++) {
                if (Character.digit(g.charAt(j), 16) < 0) {
                    return false;
                }
            }
            count++;
        }
        return dbl >= 0 ? count < 8 : count == 8 && !s.startsWith(":") && !s.endsWith(":");
    }
}
