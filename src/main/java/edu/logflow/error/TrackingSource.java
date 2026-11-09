package edu.logflow.error;

import edu.logflow.Emitter;
import edu.logflow.Source;

import java.io.IOException;

/**
 * Kaynağı sarar: okunan her öğeyi sayar ve "şu anki kayıt" bağlamını yönlendiriciye bırakır.
 * Mutabakatın sol tarafı ({@code read}) buradan gelir.
 */
public final class TrackingSource<T> implements Source<T> {

    /** Öğeden dead-letter bağlamı üretir (satır no, özgün metin). */
    @FunctionalInterface
    public interface Annotator<T> {
        ErrorRouter.Context contextOf(T item, long ordinal);
    }

    /** Kaynağın çıktı tipine uygun bağlam üreticisi: RawLine → baytlı, String → metin, diğerleri → bağlamsız. */
    @SuppressWarnings("unchecked")
    public static <T> Annotator<T> annotatorFor(Class<?> type) {
        if (type == edu.logflow.RawLine.class) {
            return (item, n) -> RawLineContext.of((edu.logflow.RawLine) item);
        }
        if (type == String.class) {
            return (item, n) -> ErrorRouter.Context.text(n, (String) item);
        }
        return (item, n) -> new ErrorRouter.Context(n, () -> String.valueOf(item), false, null);
    }

    private final Source<T> delegate;
    private final ErrorRouter router;
    private final Annotator<T> annotator;

    public TrackingSource(Source<T> delegate, ErrorRouter router, Annotator<T> annotator) {
        this.delegate = delegate;
        this.router = router;
        this.annotator = annotator;
    }

    @Override
    public void emitAll(Emitter<T> out) throws IOException {
        long[] n = {0};
        delegate.emitAll(item -> {
            n[0]++;
            router.summary().read();
            router.setCurrent(annotator.contextOf(item, n[0]));
            out.emit(item);
        });
    }
}
