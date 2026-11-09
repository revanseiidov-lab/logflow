package edu.logflow.error;

/**
 * <b>Tek bir kaydın</b>, <b>tek bir aşamada</b> işlenememesi. Kurtarılabilir: işlem hattı çalışmaya devam eder;
 * kaydın akıbetini aşamanın hata politikası belirler (bkz. {@link ErrorPolicy}).
 *
 * <p>Aşamalar yalnızca bunu <em>fırlatır</em>; hatanın nereye gideceğine (atla, dead-letter, dur) aşama değil,
 * yönlendirici ({@link ErrorRouter}) ve yapılandırma karar verir. Ayrım kuralı: "Bu kaydı atlayıp devam etmek
 * anlamlı mı?" Evet ise {@code StageException}, hayır ise {@link PipelineException}.
 *
 * <p>Denetlenmeyen (unchecked) istisnadır; bu yüzden {@code Stage} arayüzü v1'den beri değişmedi.
 */
public final class StageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorType type;

    public StageException(ErrorType type, String message) {
        super(message);
        this.type = type;
    }

    public StageException(ErrorType type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    public ErrorType type() {
        return type;
    }
}
