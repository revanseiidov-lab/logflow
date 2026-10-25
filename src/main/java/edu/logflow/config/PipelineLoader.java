package edu.logflow.config;

import edu.logflow.Pipeline;
import edu.logflow.PipelineBuilder;
import edu.logflow.Sink;
import edu.logflow.Source;
import edu.logflow.Stage;
import edu.logflow.Summarizing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Yapılandırma dosyasını okuyup {@link Pipeline} nesnesine çevirir:
 * <ol>
 *   <li>YAML'ı ayrıştırır ve yapıyı doğrular ({@link PipelineConfig});</li>
 *   <li>her bileşenin türünü kayıt defterinde arar (bilinmiyorsa hata);</li>
 *   <li>parametreleri doğrulatıp bileşeni kurdurur (eksik/yanlış tip ise hata);</li>
 *   <li>bileşenlerin giriş/çıkış tiplerinin uyuştuğunu denetler;</li>
 *   <li>hepsi geçerliyse hattı birleştirir.</li>
 * </ol>
 * <b>Hiçbir adımda veri okunmaz ya da yazılmaz</b>: tüm yapılandırma hataları, tek satır işlenmeden önce
 * ve açık bir iletiyle bildirilir (fail fast, fail loudly). Hata iletileri dosya adıyla başlar.
 */
public final class PipelineLoader {

    private final SourceRegistry sources;
    private final StageRegistry stages;
    private final SinkRegistry sinks;

    public PipelineLoader(SourceRegistry sources, StageRegistry stages, SinkRegistry sinks) {
        this.sources = sources;
        this.stages = stages;
        this.sinks = sinks;
    }

    /** Yerleşik tüm türleri META-INF/services üzerinden keşfeden yükleyici. */
    public static PipelineLoader withDefaults() {
        return new PipelineLoader(SourceRegistry.discover(), StageRegistry.discover(), SinkRegistry.discover());
    }

    public LoadedPipeline load(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new ConfigException(file + ": yapilandirma dosyasi bulunamadi ya da okunamiyor ("
                    + e.getClass().getSimpleName() + ")");
        }
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return loadFromText(text, file.toString(), dot > 0 ? fileName.substring(0, dot) : fileName);
    }

    /** @param label hata iletilerinde dosya adı yerine gösterilen etiket; @param defaultName ad verilmediyse hat adı */
    public LoadedPipeline loadFromText(String yaml, String label, String defaultName) {
        try {
            return build(PipelineConfig.from(MiniYaml.parse(yaml), defaultName));
        } catch (ConfigException e) {
            throw new ConfigException(label + ": " + e.getMessage());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private LoadedPipeline build(PipelineConfig config) {
        List<String> steps = new ArrayList<>();
        List<Summarizing> summaries = new ArrayList<>();

        PipelineConfig.Component src = config.source();
        SourceFactory sourceFactory = sources.get(src.type(), "source");
        String where = "source ('" + src.type() + "')";
        Source source = sourceFactory.create(new Params(where, sourceFactory.example(), src.params()));
        steps.add(src.type());
        Class<?> current = sourceFactory.outputType();
        String previous = where;

        List<Stage> built = new ArrayList<>();
        for (int i = 0; i < config.stages().size(); i++) {
            PipelineConfig.Component c = config.stages().get(i);
            StageFactory factory = stages.get(c.type(), "stages[" + i + "]");
            where = "stages[" + i + "] ('" + c.type() + "')";
            checkTypes(where, "asama", factory.inputType(), current, previous);
            Stage stage = factory.create(new Params(where, factory.example(), c.params()));
            if (stage instanceof Summarizing s) {
                summaries.add(s);
            }
            built.add(stage);
            steps.add(c.type());
            current = factory.outputType();
            previous = where;
        }

        PipelineConfig.Component snk = config.sink();
        SinkFactory sinkFactory = sinks.get(snk.type(), "sink");
        where = "sink ('" + snk.type() + "')";
        checkTypes(where, "cikis", sinkFactory.inputType(), current, previous);
        Sink sink = sinkFactory.create(new Params(where, sinkFactory.example(), snk.params()));
        steps.add(snk.type());

        // Topoloji kodda değil dosyada olduğundan tip uyumu derleme zamanında değil, yukarıda yüklemede denetlendi.
        PipelineBuilder builder = PipelineBuilder.from(source);
        for (Stage stage : built) {
            builder = builder.via(stage);
        }
        Pipeline pipeline = builder.to(sink);
        return new LoadedPipeline(config.name(), pipeline, List.copyOf(steps), List.copyOf(summaries));
    }

    /** Bir bileşenin beklediği tip, önceki adımın ürettiğiyle uyuşmuyorsa nedenini ve çözümü anlatan hata verir. */
    private void checkTypes(String where, String what, Class<?> expected, Class<?> actual, String previous) {
        if (expected.isAssignableFrom(actual)) {
            return;
        }
        List<String> fixes = new ArrayList<>();
        for (StageFactory f : stages.all()) {
            if (f.inputType().isAssignableFrom(actual) && expected.isAssignableFrom(f.outputType())) {
                fixes.add(f.type());
            }
        }
        String hint = fixes.isEmpty() ? "" : " Araya su asamalardan biri gerekir: " + String.join(", ", fixes) + ".";
        throw new ConfigException(where + ": tip uyusmazligi: bu " + what + " " + name(expected) + " bekliyor, ama "
                + previous + " " + name(actual) + " uretiyor." + hint);
    }

    private static String name(Class<?> type) {
        return type == String.class ? "String (ham satir)" : type.getSimpleName();
    }
}
