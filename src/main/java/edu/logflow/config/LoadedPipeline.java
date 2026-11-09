package edu.logflow.config;

import edu.logflow.error.ManagedRun;
import edu.logflow.error.RunSummary;

import java.util.List;

/**
 * Yapılandırmadan kurulmuş, çalıştırılmaya hazır hat (hata yönetimiyle sarılmış). Tek kullanımlıktır:
 * sayaçlar çalıştırmaya aittir.
 *
 * @param name     hat adı ({@code pipeline.name})
 * @param managed  çalıştırıcı: hattı çalıştırır, hataları yönlendirir, mutabakatı denetler
 * @param steps    bileşen türleri sırayla: kaynak, aşamalar, sink (ör. raw-file, decode, parse, file)
 * @param policies bileşen başına hata politikası açıklamaları ({@code --dry-run} bunları gösterir)
 */
public record LoadedPipeline(String name, ManagedRun managed, List<String> steps, List<String> policies) {

    /** Hattı çalıştırır; istisna sızdırmaz, sonucu {@link RunSummary} olarak verir. */
    public RunSummary run() {
        return managed.run();
    }

    /** {@code error-report: raw-file -> decode -> parse -> filter -> project -> file} */
    public String describe() {
        return name + ": " + String.join(" -> ", steps);
    }
}
