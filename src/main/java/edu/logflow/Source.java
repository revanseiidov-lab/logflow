package edu.logflow;

import java.io.IOException;

/** İşlem hattının başlangıcı: öğeleri tek tek Emitter'a yayar. */
@FunctionalInterface
public interface Source<T> {
    void emitAll(Emitter<T> out) throws IOException;
}
