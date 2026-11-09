package edu.logflow;

import edu.logflow.config.ConfigException;
import edu.logflow.config.LoadedPipeline;
import edu.logflow.config.PipelineLoader;
import edu.logflow.error.ErrorPolicy;
import edu.logflow.error.ErrorRouter;
import edu.logflow.error.GuardedSink;
import edu.logflow.error.GuardedStage;
import edu.logflow.error.ManagedRun;
import edu.logflow.error.RunSummary;
import edu.logflow.error.TrackingSource;
import java.io.PrintStream;

/**
 * Uygulamanın mantığı (Main'den ayrıldı ki test edilebilsin). Çıkış kodu: 0 başarılı; 1 çalışma tamamlanmadı
 * (fail-fast durdurması ya da G/Ç hatası); 2 hatalı argüman ya da yapılandırma hatası; 3 kayıt kaybı (mutabakat tutmuyor).
 * Çalışma özeti her zaman {@code stderr}'e yazılır; {@code stdout} veriye ayrılmıştır.
 */
public final class App {

    private App() {
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("Hata: " + e.getMessage());
            err.println(CliOptions.USAGE);
            return 2;
        }

        if (options.config != null) {
            return runConfig(options, out, err);
        }

        // Eski (komut satırı) kip: hatalı satır atlanır ama artık sayılır ve özette raporlanır (skip politikası).
        ManagedRun managed;
        try {
            managed = buildManaged(options, out);
        } catch (IllegalArgumentException e) {            // ör. bilinmeyen --fields değeri
            err.println("Hata: " + e.getMessage());
            return 2;
        }
        RunSummary summary = managed.run();
        err.print(summary.render());
        return summary.exitCode();
    }

    /**
     * Yapılandırma kipi: hat YAML dosyasından kurulur. Yapılandırma hataları, tek satır işlenmeden önce
     * yükleme sırasında bildirilir (çıkış kodu 2).
     */
    private static int runConfig(CliOptions options, PrintStream out, PrintStream err) {
        LoadedPipeline loaded;
        try {
            loaded = PipelineLoader.withDefaults().load(options.config);
        } catch (ConfigException e) {
            err.println("Yapilandirma hatasi: " + e.getMessage());
            return 2;
        }
        if (options.dryRun) {
            out.println("Yapilandirma gecerli: " + loaded.describe());
            loaded.policies().forEach(p -> out.println("  hata politikasi - " + p));
            return 0;
        }
        RunSummary summary = loaded.run();
        err.print(summary.render());
        return summary.exitCode();
    }

    /** Seçeneklere göre aşamaları bağlar: oku -> ayrıştır -> [filtreler] -> projeksiyon -> yaz; hepsi hata yönetimiyle sarılı. */
    static ManagedRun buildManaged(CliOptions o, PrintStream out) {
        RunSummary summary = new RunSummary();
        ErrorRouter router = new ErrorRouter(summary, null);

        Source<String> source = new TrackingSource<>(new FileLineSource(o.input), router,
                TrackingSource.annotatorFor(String.class));
        PipelineBuilder<LogRecord> builder = PipelineBuilder.from(source)
                .via(new GuardedStage<>("parse", new ParserStage(), ErrorPolicy.SKIP, router, false));

        if (o.minStatus != null) {
            builder = builder.via(filter(PredicateFilterStage.minStatus(o.minStatus), router));
        }
        if (o.pathPrefix != null) {
            builder = builder.via(filter(PredicateFilterStage.pathPrefix(o.pathPrefix), router));
        }
        if (o.since != null || o.until != null) {
            builder = builder.via(filter(PredicateFilterStage.timeBetween(o.since, o.until), router));
        }

        Sink<ProjectedRecord> sink = o.output != null
                ? new FileSink(o.output)
                : record -> out.println(record.toLine());
        Pipeline pipeline = builder
                .via(new GuardedStage<>("project", new ProjectionStage(o.fields), ErrorPolicy.SKIP, router, false))
                .to(new GuardedSink<>("sink", sink, ErrorPolicy.FAIL_FAST, ErrorPolicy.FAIL_FAST, router));
        return new ManagedRun(pipeline, router, null);
    }

    private static GuardedStage<LogRecord, LogRecord> filter(PredicateFilterStage stage, ErrorRouter router) {
        return new GuardedStage<>("filter", stage, ErrorPolicy.SKIP, router, true);
    }
}
