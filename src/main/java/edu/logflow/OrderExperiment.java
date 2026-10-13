package edu.logflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * 3. hafta deneyi: aşama sırası sonucu değiştirir mi, maliyeti değiştirir mi?
 * Çalıştırma: {@code java -cp target/classes edu.logflow.OrderExperiment [kayit-sayisi]}
 *
 * <p>Veri bellekte üretilir (ayrıştırma maliyeti ölçümü gürültüye boğmasın diye kayıtlar hazır
 * LogRecord'dur) ve tek bir kaynaktan geçirilir. Sonuçlar Markdown tablosu olarak yazdırılır.
 */
public final class OrderExperiment {

    private static final int WARMUP = 3;
    private static final int RUNS = 21;

    private static long statusChecks;
    private static long pathChecks;
    private static long projections;

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        List<LogRecord> data = generate(n);
        long errors = data.stream().filter(r -> r.status() >= 400).count();
        long api = data.stream().filter(r -> r.path().startsWith("/api")).count();
        System.out.printf("Veri: %,d kayit; status>=400: %,d (%.1f%%); path /api ile baslayan: %,d (%.1f%%)%n%n",
                n, errors, 100.0 * errors / n, api, 100.0 * api / n);

        System.out.println("### Deney 1: iki filtrenin sirasi (ikisi de LogRecord -> LogRecord)");
        List<String> a = filterOrder(data, true);
        List<String> b = filterOrder(data, false);
        compare("A: status -> path", () -> filterOrder(data, true), () -> statusChecks + pathChecks,
                "B: path -> status", () -> filterOrder(data, false), () -> statusChecks + pathChecks);
        System.out.println("Sonuclar ayni mi? " + a.equals(b) + " (" + a.size() + " kayit)\n");

        System.out.println("### Deney 2: projeksiyon, filtreden once mi sonra mi? (filtre: status>=400)");
        List<String> c = projectionPlacement(data, true, "clientIp", "status", "path");
        List<String> d = projectionPlacement(data, false, "clientIp", "status", "path");
        compare("C: filtre -> projeksiyon", () -> projectionPlacement(data, true, "clientIp", "status", "path"),
                () -> projections,
                "D: projeksiyon -> filtre", () -> projectionPlacement(data, false, "clientIp", "status", "path"),
                () -> projections);
        System.out.println("Sonuclar ayni mi? " + c.equals(d) + " (" + c.size() + " kayit)\n");

        List<String> e = projectionPlacement(data, false, "clientIp", "path");
        System.out.println("E: projeksiyon (status'u atan) -> status filtresi: " + e.size()
                + " kayit (beklenen " + c.size() + ") -- hata firlatilmadi, sonuc sessizce yanlis.");
    }

    /** Deney 1: iki LogRecord filtresi, iki sıra. Çıktı satırlarını döndürür; sayaçları doldurur. */
    static List<String> filterOrder(List<LogRecord> data, boolean statusFirst) throws Exception {
        statusChecks = 0;
        pathChecks = 0;
        PredicateFilterStage status = new PredicateFilterStage(r -> {
            statusChecks++;
            return r.status() >= 400;
        });
        PredicateFilterStage path = new PredicateFilterStage(r -> {
            pathChecks++;
            return r.path().startsWith("/api");
        });
        List<String> out = new ArrayList<>();
        PipelineBuilder<LogRecord> builder = PipelineBuilder.from(source(data));
        builder = statusFirst ? builder.via(status).via(path) : builder.via(path).via(status);
        builder.via(new ProjectionStage("clientIp", "status", "path"))
                .to(p -> out.add(p.toLine())).run();
        return out;
    }

    /**
     * Deney 2: projeksiyon filtreden önce ya da sonra. Projeksiyondan sonraki filtre
     * ProjectedRecord üzerinde çalışır, bu yüzden "status" alanını projeksiyonda
     * bırakmışsa metinden okur, bırakmamışsa göremez.
     */
    static List<String> projectionPlacement(List<LogRecord> data, boolean filterFirst, String... fields)
            throws Exception {
        projections = 0;
        ProjectionStage projection = new ProjectionStage(fields);
        Stage<LogRecord, ProjectedRecord> counted = (r, o) -> {
            projections++;
            projection.process(r, o);
        };
        Stage<ProjectedRecord, ProjectedRecord> lateFilter = (p, o) -> {
            String s = p.get("status");
            if (s != null && Integer.parseInt(s) >= 400) {
                o.emit(p);
            }
        };
        List<String> out = new ArrayList<>();
        Sink<ProjectedRecord> sink = p -> out.add(p.toLine());
        if (filterFirst) {
            PipelineBuilder.from(source(data)).via(PredicateFilterStage.minStatus(400)).via(counted).to(sink).run();
        } else {
            PipelineBuilder.from(source(data)).via(counted).via(lateFilter).to(sink).run();
        }
        return out;
    }

    private static Source<LogRecord> source(List<LogRecord> data) {
        return out -> {
            for (LogRecord r : data) {
                out.emit(r);
            }
        };
    }

    @FunctionalInterface
    interface Run {
        List<String> run() throws Exception;
    }

    /**
     * İki varyantı sırayla (A, B, A, B, ...) ölçer: JIT ısınması ve ölçüm sırası, varyantlardan
     * birini kayırmasın. Medyan süre ve çağrı sayacı yazdırılır.
     */
    private static void compare(String nameA, Run runA, Supplier<Long> counterA,
                                String nameB, Run runB, Supplier<Long> counterB) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            runA.run();
            runB.run();
        }
        long[] a = new long[RUNS];
        long[] b = new long[RUNS];
        long countA = 0;
        long countB = 0;
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            runA.run();
            a[i] = System.nanoTime() - t0;
            countA = counterA.get();
            t0 = System.nanoTime();
            runB.run();
            b[i] = System.nanoTime() - t0;
            countB = counterB.get();
        }
        print(nameA, countA, a);
        print(nameB, countB, b);
    }

    private static void print(String name, long count, long[] nanos) {
        Arrays.sort(nanos);
        System.out.printf("- %-28s cagri sayisi: %,12d   medyan sure: %7.1f ms   (min %.1f, max %.1f)%n",
                name, count, nanos[nanos.length / 2] / 1e6, nanos[0] / 1e6, nanos[nanos.length - 1] / 1e6);
    }

    /** Sabit tohumlu üretim: status>=400 yaklaşık %5, path /api yaklaşık %30, birbirinden bağımsız. */
    static List<LogRecord> generate(int n) {
        Random rnd = new Random(42);
        Instant base = Instant.parse("2023-10-10T00:00:00Z");
        int[] okCodes = {200, 200, 200, 204, 301, 304};
        int[] errCodes = {400, 401, 403, 404, 500, 503};
        List<LogRecord> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int status = rnd.nextInt(100) < 5 ? errCodes[rnd.nextInt(errCodes.length)] : okCodes[rnd.nextInt(okCodes.length)];
            String path = (rnd.nextInt(100) < 30 ? "/api/items/" : "/static/page/") + rnd.nextInt(1000);
            String ip = "10.0." + rnd.nextInt(256) + "." + rnd.nextInt(256);
            list.add(new LogRecord(base.plusSeconds(i), ip, "GET", path, status, rnd.nextInt(5000),
                    "Mozilla/5.0 (X11; Linux x86_64) Firefox/118.0", java.util.Map.of(), "raw"));
        }
        return list;
    }

    private OrderExperiment() {
    }
}
