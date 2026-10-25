package edu.logflow.config;

import edu.logflow.Source;

/** Bir kaynak türünün (ör. "file") adı, çıkış tipi ve kurulumu. Bkz. {@link StageFactory}. */
public interface SourceFactory {

    String type();

    Class<?> outputType();

    default String example() {
        return null;
    }

    Source<?> create(Params params);
}
