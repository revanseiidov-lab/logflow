package edu.logflow;

import java.io.IOException;

/**
 * Tip güvenli işlem hattı kurucusu:
 * <pre>
 *   Pipeline.from(source).then(stageA).then(stageB).to(sink).run();
 * </pre>
 * Araya yeni bir aşama eklemek yalnızca bir {@code .then(...)} satırı eklemektir;
 * komşu aşamalar değişmez, çünkü birbirlerini değil yalnızca tipleri bilirler.
 */
public final class Pipeline {

    private final Driver<?> driver;

    private Pipeline(Driver<?> driver) {
        this.driver = driver;
    }

    public void run() throws IOException {
        driver.drive();
    }

    public static <S> Builder<S> from(Source<S> source) {
        return new Builder<>(source::emitAll);
    }

    /** Kaynaktan gelen öğeleri verilen Emitter'a akıtan iç yardımcı. */
    @FunctionalInterface
    interface Feed<S> {
        void feed(Emitter<S> downstream) throws IOException;
    }

    @FunctionalInterface
    interface Driver<S> {
        void drive() throws IOException;
    }

    public static final class Builder<S> {
        private final Feed<S> feed;

        private Builder(Feed<S> feed) {
            this.feed = feed;
        }

        public <R> Builder<R> then(Stage<S, R> stage) {
            return new Builder<>(downstream -> feed.feed(item -> stage.process(item, downstream)));
        }

        public Pipeline to(Sink<S> sink) {
            return new Pipeline(() -> feed.feed(sink::accept));
        }
    }
}
