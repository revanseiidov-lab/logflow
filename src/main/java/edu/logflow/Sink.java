package edu.logflow;

/** İşlem hattının son durağı: gelen öğeyi tüketir (yazdırır, depolar, ...). */
@FunctionalInterface
public interface Sink<T> {
    void accept(T item);
}
