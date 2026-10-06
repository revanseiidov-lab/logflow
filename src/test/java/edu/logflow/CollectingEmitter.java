package edu.logflow;

import java.util.ArrayList;
import java.util.List;

/**
 * Test dublörü (test double): bir aşamanın yaydığı her şeyi bir listede toplar.
 * Aşama testleri dosya sistemine dokunmaz: aşama oluşturulur, ona bir dizgi verilir,
 * çıktılar bu Emitter ile yakalanıp doğrulanır. Sonraki haftalarda her aşama testinde yeniden kullanılır.
 */
final class CollectingEmitter<T> implements Emitter<T> {

    private final List<T> items = new ArrayList<>();

    @Override
    public void emit(T item) {
        items.add(item);
    }

    List<T> items() {
        return items;
    }

    int size() {
        return items.size();
    }

    T first() {
        return items.get(0);
    }
}
