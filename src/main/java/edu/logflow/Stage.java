package edu.logflow;

/**
 * İşlem hattının bir aşaması: I tipinde girdi alır, O tipinde çıktıları Emitter'a yayar.
 * Aşama kendi komşularını tanımaz; yalnızca tiplerle bağlıdır.
 */
@FunctionalInterface
public interface Stage<I, O> {
    void process(I input, Emitter<O> out);
}
