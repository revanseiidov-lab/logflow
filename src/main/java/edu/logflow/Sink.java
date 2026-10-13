package edu.logflow;

import java.io.IOException;

/**
 * İşlem hattının son durağı: gelen öğeyi tüketir (yazdırır, depolar, ...).
 *
 * <p><b>Yaşam döngüsü (lifecycle):</b> {@link #open()} ilk öğeden önce, {@link #close()} son öğeden
 * sonra çağrılır. Bunları işlem hattı çağırır ({@code PipelineBuilder.to(...)}); hata olsa bile
 * {@code close()} çalışır. Kaynak tutan sink'ler (dosya, ağ) kaynağı {@code open()}'da açıp
 * {@code close()}'da kapatmalıdır. Varsayılan uygulamalar boştur, bu yüzden kaynak tutmayan
 * sink'ler (ConsoleSink, lambda) etkilenmez.
 */
@FunctionalInterface
public interface Sink<T> {

    default void open() throws IOException {
    }

    void accept(T item);

    default void close() throws IOException {
    }
}
