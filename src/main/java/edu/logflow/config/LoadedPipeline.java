package edu.logflow.config;

import edu.logflow.Pipeline;
import edu.logflow.Summarizing;
import java.util.List;

/**
 * Yapılandırmadan kurulmuş, çalıştırılmaya hazır hat.
 *
 * @param name      hat adı ({@code pipeline.name})
 * @param pipeline  çalıştırılabilir hat
 * @param steps     bileşen türleri sırayla: kaynak, aşamalar, sink (ör. file, parse, filter, project, file)
 * @param summaries çalışma sonunda yazdırılacak özet kaynakları (ör. ayrıştırıcının atlanan satır sayısı)
 */
public record LoadedPipeline(String name, Pipeline pipeline, List<String> steps, List<Summarizing> summaries) {

    /** {@code error-report: file -> parse -> filter -> project -> file} */
    public String describe() {
        return name + ": " + String.join(" -> ", steps);
    }
}
