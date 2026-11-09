package edu.logflow.error;

import edu.logflow.Sink;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Ana çıktıyı hata politikasıyla sarar. {@code retry(n)} yalnızca burada anlamlıdır: yazma hatası geçici
 * olabilir (disk dolu, ağ), ayrıştırma hatası ise her denemede aynı sonucu verir. Denemeler tükenirse
 * {@code fallback} politikası uygulanır. Başarılı her yazma "yazılan" sayacını artırır.
 */
public final class GuardedSink<T> implements Sink<T> {

    private final String label;
    private final Sink<T> delegate;
    private final ErrorPolicy policy;
    private final ErrorPolicy fallback;
    private final ErrorRouter router;

    public GuardedSink(String label, Sink<T> delegate, ErrorPolicy policy, ErrorPolicy fallback, ErrorRouter router) {
        this.label = label;
        this.delegate = delegate;
        this.policy = policy;
        this.fallback = fallback;
        this.router = router;
    }

    @Override
    public void open() throws IOException {
        delegate.open();
    }

    @Override
    public void accept(T item) {
        int attempts = policy.kind() == ErrorPolicy.Kind.RETRY ? policy.retries() + 1 : 1;
        StageException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                delegate.accept(item);
                router.summary().emitted();
                return;
            } catch (StageException e) {
                last = e;
            } catch (UncheckedIOException e) {
                last = new StageException(ErrorType.SINK_IO, String.valueOf(e.getMessage()), e);
            } catch (AbortRun | PipelineException e) {
                throw e;
            } catch (RuntimeException e) {
                last = new StageException(ErrorType.UNEXPECTED, e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }
        }
        ErrorPolicy effective = policy.kind() == ErrorPolicy.Kind.RETRY ? fallback : policy;
        router.handle(label, effective, last);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
