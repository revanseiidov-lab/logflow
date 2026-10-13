package edu.logflow;

import java.time.Instant;
import java.util.function.Predicate;

/**
 * Bir kaydı yalnızca koşul sağlanıyorsa iletir; sağlanmıyorsa hiçbir şey yaymaz.
 * Tek sorumluluk: eleme. Ayrıştırma, dönüştürme ya da yazma yapmaz.
 */
public final class PredicateFilterStage implements Stage<LogRecord, LogRecord> {

    private final Predicate<LogRecord> condition;

    public PredicateFilterStage(Predicate<LogRecord> condition) {
        this.condition = condition;
    }

    @Override
    public void process(LogRecord record, Emitter<LogRecord> out) {
        if (condition.test(record)) {
            out.emit(record);
        }
    }

    /** status >= min */
    public static PredicateFilterStage minStatus(int min) {
        return new PredicateFilterStage(r -> r.status() >= min);
    }

    /** path, verilen önekle başlıyor */
    public static PredicateFilterStage pathPrefix(String prefix) {
        return new PredicateFilterStage(r -> r.path().startsWith(prefix));
    }

    /** since <= zaman < until; sınırlardan biri null ise o yön sınırsızdır. */
    public static PredicateFilterStage timeBetween(Instant since, Instant until) {
        return new PredicateFilterStage(r ->
                (since == null || !r.timestamp().isBefore(since))
                        && (until == null || r.timestamp().isBefore(until)));
    }
}
