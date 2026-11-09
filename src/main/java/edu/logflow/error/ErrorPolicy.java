package edu.logflow.error;

import java.util.Locale;

/**
 * Bir aşamanın hata politikası. Yapılandırmada {@code on-error:} ile yazılır:
 * <ul>
 *   <li>{@code fail-fast} — çalışmayı durdurur ({@code ABORTED}); veri bütünlüğü her şeyden önemliyse.</li>
 *   <li>{@code skip} — kaydı atar ve <b>sayar</b>; sessiz atlama yoktur.</li>
 *   <li>{@code dead-letter} — kaydı nedeniyle birlikte ikincil hedefe yazar; sonradan incelenir/yeniden oynatılır.</li>
 *   <li>{@code retry(n)} — aşamayı n kez daha dener, tükenirse {@code fallback} politikası uygulanır.
 *       Yalnızca G/Ç aşamalarında (sink) anlamlıdır: deterministik bir ayrıştırma hatası her denemede aynı şekilde başarısız olur.</li>
 * </ul>
 */
public record ErrorPolicy(Kind kind, int retries) {

    public enum Kind { FAIL_FAST, SKIP, DEAD_LETTER, RETRY }

    public static final ErrorPolicy FAIL_FAST = new ErrorPolicy(Kind.FAIL_FAST, 0);
    public static final ErrorPolicy SKIP = new ErrorPolicy(Kind.SKIP, 0);
    public static final ErrorPolicy DEAD_LETTER = new ErrorPolicy(Kind.DEAD_LETTER, 0);

    public static ErrorPolicy retry(int times) {
        if (times < 1) {
            throw new IllegalArgumentException("retry(n) icin n >= 1 olmali: " + times);
        }
        return new ErrorPolicy(Kind.RETRY, times);
    }

    /** Büyük/küçük harf ve {@code - _} ayrımı gözetmez: {@code dead-letter}, {@code DEAD_LETTER}, {@code Retry(3)}. */
    public static ErrorPolicy parse(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (t.startsWith("retry(") && t.endsWith(")")) {
            String n = t.substring(6, t.length() - 1).trim();
            try {
                return retry(Integer.parseInt(n));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("retry(n) icin n bir tam sayi olmali: '" + text + "'");
            }
        }
        return switch (t) {
            case "fail-fast", "failfast" -> FAIL_FAST;
            case "skip" -> SKIP;
            case "dead-letter", "deadletter" -> DEAD_LETTER;
            default -> throw new IllegalArgumentException("bilinmeyen hata politikasi '" + text
                    + "' (gecerli: fail-fast, skip, dead-letter, retry(n))");
        };
    }

    @Override
    public String toString() {
        return switch (kind) {
            case FAIL_FAST -> "fail-fast";
            case SKIP -> "skip";
            case DEAD_LETTER -> "dead-letter";
            case RETRY -> "retry(" + retries + ")";
        };
    }
}
