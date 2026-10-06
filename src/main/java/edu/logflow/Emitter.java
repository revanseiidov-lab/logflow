package edu.logflow;

/**
 * Bir aşamanın çıktı kanalı. Aşama sonucu doğrudan döndürmez; Emitter'a "yayar".
 * Böylece bir girdi 0, 1 ya da çok sayıda çıktı üretebilir (ör. hatalı satır = 0 çıktı).
 */
@FunctionalInterface
public interface Emitter<T> {
    void emit(T item);
}
