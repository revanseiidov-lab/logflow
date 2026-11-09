package edu.logflow.error;

/**
 * FAIL_FAST politikası çalışmayı durdurdu. Hat boyunca yukarı doğru yükselir, {@link ManagedRun} yakalar ve
 * özeti {@code ABORTED} yapar. Bir hata değil, <b>denetimli durdurmadır</b> (çıkış kodu 1).
 */
public final class AbortRun extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AbortRun(String message, Throwable cause) {
        super(message, cause);
    }
}
