package edu.logflow.config;

import edu.logflow.Sink;

/** Bir sink türünün (ör. "file", "console") adı, giriş tipi ve kurulumu. Bkz. {@link StageFactory}. */
public interface SinkFactory {

    String type();

    Class<?> inputType();

    default String example() {
        return null;
    }

    Sink<?> create(Params params);
}
