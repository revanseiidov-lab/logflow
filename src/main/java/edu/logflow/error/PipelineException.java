package edu.logflow.error;

/**
 * İşlem hattı <b>devam edemez</b>: okunamayan girdi, kapanamayan çıktı, yazılamayan dead-letter hedefi.
 * Kurtarılamaz: çalışma durur, özet {@code FAILED} yazılır, çıkış kodu 2.
 *
 * <p>Dead-letter hedefinin yazılamaması bilerek bu sınıfa girer: kanıt sessizce kaybolacağı için
 * "kaydı atla" seçeneği yoktur.
 */
public final class PipelineException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PipelineException(String message) {
        super(message);
    }

    public PipelineException(String message, Throwable cause) {
        super(message, cause);
    }
}
