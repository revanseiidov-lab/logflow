package edu.logflow;

import java.io.IOException;

/**
 * Akıcı (fluent) ve tip güvenli işlem hattı kurucusu:
 * <pre>
 *   Pipeline p = PipelineBuilder.from(new FileLineSource(in))
 *           .via(new ParserStage())
 *           .via(new PredicateFilterStage(r -> r.status() >= 400))
 *           .via(new ProjectionStage("timestamp", "clientIp", "status", "path"))
 *           .to(new FileSink(out));
 * </pre>
 * Her {@code via(...)} yeni bir kurucu döndürür; tip parametresi aşamanın çıktı tipine döner.
 * Bu yüzden aşamalar herhangi bir sırada ve sayıda bağlanabilir, ama yalnızca tipleri uyuşuyorsa
 * (örneğin ayrıştırmadan önce LogRecord filtresi bağlamak derleme hatasıdır).
 *
 * <p>4. haftada bu kurucu yapılandırma (configuration) dosyasından beslenecek.
 */
public final class PipelineBuilder<S> {

    /** Kaynaktan gelen öğeleri aşağı akıştaki Emitter'a akıtan iç yardımcı. */
    @FunctionalInterface
    interface Feed<S> {
        void feed(Emitter<S> downstream) throws IOException;
    }

    private final Feed<S> feed;

    private PipelineBuilder(Feed<S> feed) {
        this.feed = feed;
    }

    public static <T> PipelineBuilder<T> from(Source<T> source) {
        return new PipelineBuilder<>(source::emitAll);
    }

    public <R> PipelineBuilder<R> via(Stage<S, R> stage) {
        return new PipelineBuilder<>(downstream -> feed.feed(item -> stage.process(item, downstream)));
    }

    /** Hattı sink ile tamamlar. Sink'in open()/close() kancaları çalıştırma sırasında çağrılır. */
    public Pipeline to(Sink<S> sink) {
        return new Pipeline(() -> {
            sink.open();
            try {
                feed.feed(sink::accept);
            } catch (Throwable failure) {
                try {
                    sink.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
            sink.close();
        });
    }
}
