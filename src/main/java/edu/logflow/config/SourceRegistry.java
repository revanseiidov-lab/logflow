package edu.logflow.config;

/** Kaynak türlerinin kayıt defteri. */
public final class SourceRegistry extends Registry<SourceFactory> {

    public SourceRegistry() {
        super("kaynak (source)", SourceFactory::type);
    }

    public static SourceRegistry discover() {
        return discover(SourceFactory.class, new SourceRegistry());
    }
}
