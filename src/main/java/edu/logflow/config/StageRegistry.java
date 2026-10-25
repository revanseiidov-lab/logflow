package edu.logflow.config;

/** Aşama türlerinin kayıt defteri: {@code type: filter} -> {@link StageFactory}. */
public final class StageRegistry extends Registry<StageFactory> {

    public StageRegistry() {
        super("asama (stage)", StageFactory::type);
    }

    /** META-INF/services/edu.logflow.config.StageFactory dosyalarındaki tüm fabrikaları yükler. */
    public static StageRegistry discover() {
        return discover(StageFactory.class, new StageRegistry());
    }
}
