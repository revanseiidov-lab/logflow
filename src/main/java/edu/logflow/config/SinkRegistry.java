package edu.logflow.config;

/** Sink türlerinin kayıt defteri. */
public final class SinkRegistry extends Registry<SinkFactory> {

    public SinkRegistry() {
        super("cikis (sink)", SinkFactory::type);
    }

    public static SinkRegistry discover() {
        return discover(SinkFactory.class, new SinkRegistry());
    }
}
