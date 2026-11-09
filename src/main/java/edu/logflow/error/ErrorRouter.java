package edu.logflow.error;

import java.time.Instant;
import java.util.Base64;
import java.util.function.Supplier;

import edu.logflow.Sink;

/**
 * Hata yönlendiricisi: hatanın akıbetine <b>aşama değil, yapılandırma</b> karar verir. Aşamalar yalnızca
 * {@link StageException} fırlatır; bu sınıf politikayı uygular, sayaçları günceller ve dead-letter'a yazar.
 *
 * <p>Hat eşzamanlı ve tek iş parçacıklıdır; bu yüzden "şu an işlenen kaydın" bağlamı ({@link Context})
 * kaynak tarafından yönlendiriciye bırakılır ve aşamalar satır numarasından habersiz kalır.
 */
public final class ErrorRouter {

    /**
     * Şu an işlenen girdi satırı (dead-letter kaydı için). Özgün metin <b>tembel</b> üretilir: yalnızca bir hata
     * yazılacaksa hesaplanır, sağlıklı satırlar için ek maliyet yoktur.
     *
     * @param bytes özgün baytlar (yoksa null); geçersiz UTF-8 için kanıt olarak Base64'e çevrilir
     */
    public record Context(long line, Supplier<String> raw, boolean truncated, byte[] bytes) {
        public static final Context NONE = new Context(0, () -> "", false, null);

        public static Context text(long line, String raw) {
            return new Context(line, () -> raw, false, null);
        }
    }

    public static final int RAW_LIMIT = 1024;
    /** Kanıt olarak saklanan en çok bayt (Base64 öncesi). */
    public static final int BASE64_LIMIT = 4096;

    private final RunSummary summary;
    private final Sink<FailedRecord> deadLetter;
    private final Supplier<Instant> clock;
    private Context current = Context.NONE;

    public ErrorRouter(RunSummary summary, Sink<FailedRecord> deadLetter) {
        this(summary, deadLetter, Instant::now);
    }

    public ErrorRouter(RunSummary summary, Sink<FailedRecord> deadLetter, Supplier<Instant> clock) {
        this.summary = summary;
        this.deadLetter = deadLetter;
        this.clock = clock;
    }

    public RunSummary summary() {
        return summary;
    }

    public void setCurrent(Context context) {
        this.current = context;
    }

    public Context current() {
        return current;
    }

    public boolean hasDeadLetter() {
        return deadLetter != null;
    }

    /** Politikayı uygular. FAIL_FAST ise {@link AbortRun} fırlatır. */
    public void handle(String stage, ErrorPolicy policy, StageException e) {
        switch (policy.kind()) {
            case SKIP -> summary.skipped(stage, e.type());
            case DEAD_LETTER -> {
                if (deadLetter == null) {
                    throw new PipelineException("dead-letter hedefi tanimli degil (asama '" + stage + "')");
                }
                write(stage, e);
                summary.deadLettered(stage, e.type());
            }
            case FAIL_FAST, RETRY -> {
                summary.aborted(stage, e.type());
                throw new AbortRun("asama '" + stage + "' durdurdu: " + e.type() + " - " + e.getMessage()
                        + (current.line() > 0 ? " (satir " + current.line() + ")" : ""), e);
            }
        }
    }

    private void write(String stage, StageException e) {
        String raw = current.raw().get();
        boolean truncated = current.truncated();
        if (raw.length() > RAW_LIMIT) {
            raw = raw.substring(0, RAW_LIMIT);
            truncated = true;
        }
        String base64 = null;
        if (current.bytes() != null && (e.type() == ErrorType.READ_ENCODING || e.type() == ErrorType.READ_OVERSIZE)) {
            byte[] b = current.bytes();
            if (b.length > BASE64_LIMIT) {
                b = java.util.Arrays.copyOf(b, BASE64_LIMIT);
                truncated = true;
            }
            base64 = base64(b);
        }
        FailedRecord r = new FailedRecord(clock.get(), stage, e.type(), String.valueOf(e.getMessage()),
                current.line(), raw, truncated, base64);
        try {
            deadLetter.accept(r);
        } catch (RuntimeException io) {
            // Kanit sessizce kaybolmamali: dead-letter yazilamiyorsa calisma durur.
            throw new PipelineException("dead-letter yazilamadi: " + io.getMessage(), io);
        }
    }

    public static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
