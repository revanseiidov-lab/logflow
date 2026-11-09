package edu.logflow.config;

import edu.logflow.Pipeline;
import edu.logflow.PipelineBuilder;
import edu.logflow.Sink;
import edu.logflow.Source;
import edu.logflow.Stage;
import edu.logflow.error.ErrorPolicy;
import edu.logflow.error.ErrorRouter;
import edu.logflow.error.GuardedSink;
import edu.logflow.error.GuardedStage;
import edu.logflow.error.JsonlSink;
import edu.logflow.error.ManagedRun;
import edu.logflow.error.RunSummary;
import edu.logflow.error.TrackingSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        List<String> policies = new ArrayList<>();
        boolean[] needsDeadLetter = {false};

        RunSummary summary = new RunSummary();
        JsonlSink deadLetter = config.deadLetterPath() == null ? null : new JsonlSink(Path.of(config.deadLetterPath()));
        ErrorRouter router = new ErrorRouter(summary, deadLetter);

        PipelineConfig.Component src = config.source();
        SourceFactory sourceFactory = sources.get(src.type(), "source");
        String where = "source ('" + src.type() + "')";
        if (src.onError() != null || src.fallback() != null) {
            throw new ConfigException(where + ": kaynakta 'on-error' kullanilamaz: okuma hatalari kayit duzeyinde, "
                    + "'decode' asamasinda ele alinir; kaynagin kendisi (dosya yok, G/S hatasi) her zaman durdurur");
        }
        Source source = sourceFactory.create(new Params(where, sourceFactory.example(), src.params()));
        steps.add(src.type());
        Class<?> current = sourceFactory.outputType();
        String previous = where;
        source = new TrackingSource(source, router, TrackingSource.annotatorFor(current));
        policies.add(src.type() + ": hata politikasi yok (kaynak hatasi calismayi durdurur)");

        List<Stage> built = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < config.stages().size(); i++) {
            PipelineConfig.Component c = config.stages().get(i);
            StageFactory factory = stages.get(c.type(), "stages[" + i + "]");
            where = "stages[" + i + "] ('" + c.type() + "')";
            checkTypes(where, "asama", factory.inputType(), current, previous);
            Stage stage = factory.create(new Params(where, factory.example(), c.params()));
            ErrorPolicy policy = stagePolicy(where, c);
            needsDeadLetter[0] |= policy.kind() == ErrorPolicy.Kind.DEAD_LETTER;
            int n = seen.merge(c.type(), 1, Integer::sum);
            String label = n == 1 ? c.type() : c.type() + "#" + n;
            built.add(new GuardedStage(label, stage, policy, router, factory.filtering()));
            policies.add(label + ": " + policy);
            steps.add(c.type());
            current = factory.outputType();
            previous = where;
        }

        PipelineConfig.Component snk = config.sink();
        SinkFactory sinkFactory = sinks.get(snk.type(), "sink");
        where = "sink ('" + snk.type() + "')";
        checkTypes(where, "cikis", sinkFactory.inputType(), current, previous);
        Sink sink = sinkFactory.create(new Params(where, sinkFactory.example(), snk.params()));
        ErrorPolicy[] sinkPolicies = sinkPolicies(where, snk);
        needsDeadLetter[0] |= sinkPolicies[0].kind() == ErrorPolicy.Kind.DEAD_LETTER
                || sinkPolicies[1].kind() == ErrorPolicy.Kind.DEAD_LETTER;
        sink = new GuardedSink("sink", sink, sinkPolicies[0], sinkPolicies[1], router);
        steps.add(snk.type());
        policies.add("sink: " + sinkPolicies[0]
                + (sinkPolicies[0].kind() == ErrorPolicy.Kind.RETRY ? ", sonra " + sinkPolicies[1] : ""));

        if (needsDeadLetter[0] && deadLetter == null) {
            throw new ConfigException("'dead-letter' politikasi kullaniliyor ama hedefi tanimli degil. Ekleyin:\n"
                    + "  errors:\n    dead-letter: { path: out/rejected.jsonl }");
        }
        policies.add(deadLetter == null ? "dead-letter: yok" : "dead-letter: " + config.deadLetterPath());

        // Topoloji kodda değil dosyada olduğundan tip uyumu derleme zamanında değil, yukarıda yüklemede denetlendi.
        PipelineBuilder builder = PipelineBuilder.from(source);
        for (Stage stage : built) {
            builder = builder.via(stage);
        }
        Pipeline pipeline = builder.to(sink);
        return new LoadedPipeline(config.name(), new ManagedRun(pipeline, router, deadLetter),
                List.copyOf(steps), List.copyOf(policies));
    }

    /** Aşama politikası. Varsayılan {@code skip}: v4 davranışı (atla) ama artık <b>sayılır ve raporlanır</b>. */
    private static ErrorPolicy stagePolicy(String where, PipelineConfig.Component c) {
        if (c.fallback() != null) {
            throw new ConfigException(where + ": 'fallback' yalnizca 'retry(n)' ile birlikte, sink'te kullanilabilir");
        }
        ErrorPolicy policy = c.onError() == null ? ErrorPolicy.SKIP : parsePolicy(where, "on-error", c.onError());
        if (policy.kind() == ErrorPolicy.Kind.RETRY) {
            throw new ConfigException(where + ": 'retry' asamalarda anlamsiz: ayristirma/dogrulama hatasi deterministiktir, "
                    + "her denemede ayni sonucu verir. retry yalnizca sink icindir; burada skip, dead-letter ya da fail-fast kullanin");
        }
        return policy;
    }

    /** @return [asıl politika, fallback]. Sink varsayılanı {@code fail-fast}: çıktı sessizce kaybolmamalı. */
    private static ErrorPolicy[] sinkPolicies(String where, PipelineConfig.Component c) {
        ErrorPolicy policy = c.onError() == null ? ErrorPolicy.FAIL_FAST : parsePolicy(where, "on-error", c.onError());
        ErrorPolicy fallback = ErrorPolicy.FAIL_FAST;
        if (c.fallback() != null) {
            if (policy.kind() != ErrorPolicy.Kind.RETRY) {
                throw new ConfigException(where + ": 'fallback' yalnizca on-error: retry(n) ile anlamli");
            }
            fallback = parsePolicy(where, "fallback", c.fallback());
            if (fallback.kind() == ErrorPolicy.Kind.RETRY) {
                throw new ConfigException(where + ": 'fallback' retry olamaz (sonsuz ic ice deneme): "
                        + "skip, dead-letter ya da fail-fast kullanin");
            }
        }
        return new ErrorPolicy[] {policy, fallback};
    }

    private static ErrorPolicy parsePolicy(String where, String key, String text) {
        try {
            return ErrorPolicy.parse(text);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(where + ": " + key + ": " + e.getMessage());
        }
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
