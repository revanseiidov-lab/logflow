package edu.logflow;

import java.io.IOException;
import java.io.PrintStream;

/**
 * Uygulamanın mantığı (Main'den ayrıldı ki test edilebilsin). Çıkış kodu: 0 başarılı,
 * 1 çalışma hatası (ör. dosya yok), 2 hatalı argüman.
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

        ParserStage parser = new ParserStage();
        try {
            buildPipeline(options, parser, out).run();
        } catch (IllegalArgumentException e) {            // ör. bilinmeyen --fields değeri
            err.println("Hata: " + e.getMessage());
            return 2;
        } catch (IOException | RuntimeException e) {
            err.println("Hata: " + e);
            return 1;
        }
        err.println("Hatali (atlanan) satir sayisi: " + parser.malformedCount());
        return 0;
    }

    /** Seçeneklere göre aşamaları bağlar: oku -> ayrıştır -> [filtreler] -> projeksiyon -> yaz. */
    static Pipeline buildPipeline(CliOptions o, ParserStage parser, PrintStream out) {
        PipelineBuilder<LogRecord> builder =
                PipelineBuilder.from(new FileLineSource(o.input)).via(parser);

        if (o.minStatus != null) {
            builder = builder.via(PredicateFilterStage.minStatus(o.minStatus));
        }
        if (o.pathPrefix != null) {
            builder = builder.via(PredicateFilterStage.pathPrefix(o.pathPrefix));
        }
        if (o.since != null || o.until != null) {
            builder = builder.via(PredicateFilterStage.timeBetween(o.since, o.until));
        }

        Sink<ProjectedRecord> sink = o.output != null
                ? new FileSink(o.output)
                : record -> out.println(record.toLine());
        return builder.via(new ProjectionStage(o.fields)).to(sink);
    }
}
