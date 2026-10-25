package edu.logflow;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;

/**
 * Komut satırı argümanlarının ayrıştırılmış hali. Hangi filtrelerin uygulanacağını belirler.
 * Geçersiz girdide {@link IllegalArgumentException} fırlatır (mesajı kullanıcıya gösterilir).
 */
public final class CliOptions {

    public static final String USAGE = String.join("\n",
            "Kullanim: logflow --config <yapilandirma.yaml> [--dry-run]",
            "  --config <dosya>      hatti YAML dosyasindan kurar (baska secenekle birlikte kullanilmaz)",
            "  --dry-run             yapilandirmayi yalnizca dogrular, veri islemez",
            "",
            "Veya dogrudan: logflow <girdi-dosyasi> [secenekler]",
            "  --output <dosya>      sonuc dosyasi (verilmezse ekrana yazar)",
            "  --min-status <N>      yalnizca status >= N olan kayitlar",
            "  --path-prefix <onek>  yalnizca path <onek> ile baslayan kayitlar",
            "  --since <zaman>       zaman >= bu an (ISO, ornek 2023-10-10T11:00:00Z)",
            "  --until <zaman>       zaman <  bu an",
            "  --fields <a,b,c>      yalnizca bu alanlar (varsayilan: hepsi)",
            "                        gecerli alanlar: timestamp,clientIp,method,path,status,bytes,userAgent,query");

    public final Path config;          // null: dogrudan secenek kipi
    public final boolean dryRun;
    public final Path input;
    public final Path output;          // null: ekrana yaz
    public final Integer minStatus;    // null: filtre yok
    public final String pathPrefix;    // null: filtre yok
    public final Instant since;        // null: sinir yok
    public final Instant until;        // null: sinir yok
    public final List<String> fields;

    private CliOptions(Path config, boolean dryRun, Path input, Path output, Integer minStatus,
                       String pathPrefix, Instant since, Instant until, List<String> fields) {
        this.config = config;
        this.dryRun = dryRun;
        this.input = input;
        this.output = output;
        this.minStatus = minStatus;
        this.pathPrefix = pathPrefix;
        this.since = since;
        this.until = until;
        this.fields = fields;
    }

    public static CliOptions parse(String[] args) {
        Path config = null;
        boolean dryRun = false;
        boolean directOptionUsed = false;
        Path input = null;
        Path output = null;
        Integer minStatus = null;
        String pathPrefix = null;
        Instant since = null;
        Instant until = null;
        List<String> fields = ProjectionStage.ALL_FIELDS;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                if (input != null) {
                    throw new IllegalArgumentException("Birden fazla girdi dosyasi verildi: " + arg);
                }
                input = Path.of(arg);
                directOptionUsed = true;
                continue;
            }
            if (arg.equals("--dry-run")) {
                dryRun = true;
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(arg + " icin bir deger gerekli");
            }
            String value = args[++i];
            if (!arg.equals("--config")) {
                directOptionUsed = true;
            }
            switch (arg) {
                case "--config" -> config = Path.of(value);
                case "--output" -> output = Path.of(value);
                case "--min-status" -> minStatus = parseInt(arg, value);
                case "--path-prefix" -> pathPrefix = value;
                case "--since" -> since = parseInstant(arg, value);
                case "--until" -> until = parseInstant(arg, value);
                case "--fields" -> fields = Arrays.asList(value.split(","));
                default -> throw new IllegalArgumentException("Bilinmeyen secenek: " + arg);
            }
        }
        if (config != null) {
            if (directOptionUsed) {
                throw new IllegalArgumentException(
                        "--config baska seceneklerle ya da girdi dosyasiyla birlikte kullanilamaz (yalnizca --dry-run ile)");
            }
        } else {
            if (dryRun) {
                throw new IllegalArgumentException("--dry-run yalnizca --config ile kullanilabilir");
            }
            if (input == null) {
                throw new IllegalArgumentException("Girdi dosyasi ya da --config verilmedi");
            }
        }
        return new CliOptions(config, dryRun, input, output, minStatus, pathPrefix, since, until, fields);
    }

    private static int parseInt(String option, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " bir tam sayi olmali: " + value);
        }
    }

    private static Instant parseInstant(String option, String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(option + " ISO bicimli bir an olmali (2023-10-10T11:00:00Z): " + value);
        }
    }
}
