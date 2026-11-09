package edu.logflow.config;

import edu.logflow.error.RunSummary;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.logflow.plugins.ConsoleSinkFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Yükleyici testleri: geçerli yapılandırma ve her türlü yapılandırma zamanı hatası. */
class PipelineLoaderTest {

    private static final String LOG = String.join("\n",
            "10.0.0.1 - - [10/Oct/2023:13:00:00 +0300] \"GET /api/users HTTP/1.1\" 200 10 \"-\" \"curl/8\"",
            "10.0.0.2 - - [10/Oct/2023:13:00:01 +0300] \"POST /api/login HTTP/1.1\" 401 20 \"-\" \"curl/8\"",
            "10.0.0.3 - - [10/Oct/2023:13:00:02 +0300] \"GET /index.html HTTP/1.1\" 404 0 \"-\" \"Mozilla\"",
            "not a log line",
            "10.0.0.4 - - [10/Oct/2023:13:00:03 +0300] \"GET /api/items?x=1 HTTP/1.1\" 500 40 \"-\" \"curl/8\"",
            "");

    private final PipelineLoader loader = PipelineLoader.withDefaults();

    /** Geçerli bir YAML şablonu: source/sink yolları ve aşamalar parametre olarak verilir. */
    private static String yaml(Path in, Path out, String stages) {
        return "pipeline:\n"
                + "  name: test\n"
                + "  source: { type: file, params: { path: \"" + in + "\" } }\n"
                + "  stages:\n" + stages
                + "  sink: { type: file, params: { path: \"" + out + "\" } }\n";
    }

    private static final String ERROR_STAGES = String.join("\n",
            "    - type: parse",
            "    - type: filter",
            "      params: { field: status, op: \">=\", value: 400 }",
            "    - type: project",
            "      params: { fields: [clientIp, status, path] }",
            "");

    private Path log(Path dir) throws IOException {
        Path in = dir.resolve("in.log");
        Files.writeString(in, LOG);
        return in;
    }

    private ConfigException loadFails(String yaml) {
        return assertThrows(ConfigException.class, () -> loader.loadFromText(yaml, "test.yaml", "test"));
    }

    // 1. geçerli yapılandırma
    @Test
    void validConfigurationBuildsAPipelineThatProducesTheExpectedFile(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("out").resolve("errors.txt");

        LoadedPipeline loaded = loader.loadFromText(yaml(log(dir), out, ERROR_STAGES), "test.yaml", "x");
        RunSummary summary = loaded.run();

        assertEquals("test", loaded.name());
        assertEquals(List.of("file", "parse", "filter", "project", "file"), loaded.steps());
        assertEquals("test: file -> parse -> filter -> project -> file", loaded.describe());
        assertEquals(List.of(
                "clientIp=10.0.0.2\tstatus=401\tpath=/api/login",
                "clientIp=10.0.0.3\tstatus=404\tpath=/index.html",
                "clientIp=10.0.0.4\tstatus=500\tpath=/api/items"),
                Files.readAllLines(out));
        assertEquals(1, summary.skippedCount());
        assertEquals(RunSummary.Status.OK, summary.status());
    }

    @Test
    void nameDefaultsToTheFileNameWhenOmitted(@TempDir Path dir) throws IOException {
        Path config = dir.resolve("my-report.yaml");
        Files.writeString(config, "pipeline:\n"
                + "  source: { type: file, params: { path: \"" + log(dir) + "\" } }\n"
                + "  stages:\n    - type: parse\n    - type: project\n      params: { fields: [status] }\n"
                + "  sink: { type: console }\n");

        assertEquals("my-report", loader.load(config).name());
    }

    // 2. bilinmeyen aşama türü
    @Test
    void unknownStageTypeIsRejectedWithSuggestion(@TempDir Path dir) throws IOException {
        String stages = "    - type: parse\n    - type: fliter\n      params: { field: status, op: \">=\", value: 1 }\n";

        ConfigException e = loadFails(yaml(log(dir), dir.resolve("o.txt"), stages));

        assertTrue(e.getMessage().startsWith("test.yaml: stages[1]: bilinmeyen asama"));
        assertTrue(e.getMessage().contains("'fliter'"));
        assertTrue(e.getMessage().contains("'filter'"));
    }

    // 3. eksik parametre
    @Test
    void missingRequiredParameterNamesTheParameterAndShowsAnExample(@TempDir Path dir) throws IOException {
        String stages = "    - type: parse\n    - type: filter\n      params: { op: \">=\", value: 400 }\n";

        ConfigException e = loadFails(yaml(log(dir), dir.resolve("o.txt"), stages));

        assertTrue(e.getMessage().contains("stages[1] ('filter')"));
        assertTrue(e.getMessage().contains("zorunlu parametre eksik: 'field'"));
        assertTrue(e.getMessage().contains("Ornek: params: { field: status"));
    }

    @Test
    void missingParamsSectionEntirelyIsAlsoReported(@TempDir Path dir) throws IOException {
        String stages = "    - type: parse\n    - type: project\n";

        ConfigException e = loadFails(yaml(log(dir), dir.resolve("o.txt"), stages));

        assertTrue(e.getMessage().contains("stages[1] ('project')"));
        assertTrue(e.getMessage().contains("zorunlu parametre eksik: 'fields'"));
    }

    // 4. yanlış türde parametre
    @Test
    void wrongParameterTypeSaysWhatWasExpectedAndWhatWasFound(@TempDir Path dir) throws IOException {
        String stages = "    - type: parse\n    - type: filter\n      params: { field: status, op: \">=\", value: \"dortyuz\" }\n";

        ConfigException e = loadFails(yaml(log(dir), dir.resolve("o.txt"), stages));

        assertTrue(e.getMessage().contains("'value' parametresi tam sayi"));
        assertTrue(e.getMessage().contains("bulundu: metin \"dortyuz\""));
    }

    @Test
    void fieldsMustBeAListNotASingleString(@TempDir Path dir) throws IOException {
        String stages = "    - type: parse\n    - type: project\n      params: { fields: status }\n";

        ConfigException e = loadFails(yaml(log(dir), dir.resolve("o.txt"), stages));

        assertTrue(e.getMessage().contains("'fields' parametresi liste"));
        assertTrue(e.getMessage().contains("bulundu: metin \"status\""));
    }

    @Test
    void numericFilterValueMustNotBeText_and_textFilterValueMustNotBeNumber(@TempDir Path dir) throws IOException {
        Path in = log(dir);
        Path out = dir.resolve("o.txt");

        ConfigException num = loadFails(yaml(in, out,
                "    - type: parse\n    - type: filter\n      params: { field: bytes, op: \">\", value: lots }\n"));
        ConfigException text = loadFails(yaml(in, out,
                "    - type: parse\n    - type: filter\n      params: { field: path, op: startsWith, value: 404 }\n"));

        assertTrue(num.getMessage().contains("tam sayi"));
        assertTrue(text.getMessage().contains("metin"));
        assertTrue(text.getMessage().contains("tam sayi 404"));
    }

    // 5. boş işlem hattı
    @Test
    void emptyStageListIsRejected(@TempDir Path dir) throws IOException {
        ConfigException e = loadFails("pipeline:\n"
                + "  source: { type: file, params: { path: \"" + log(dir) + "\" } }\n"
                + "  stages: []\n"
                + "  sink: { type: console }\n");

        assertTrue(e.getMessage().contains("pipeline.stages bos"));
    }

    @Test
    void missingStagesSectionAndEmptyFileAreRejected(@TempDir Path dir) throws IOException {
        ConfigException noStages = loadFails("pipeline:\n"
                + "  source: { type: file, params: { path: \"" + log(dir) + "\" } }\n"
                + "  sink: { type: console }\n");
        ConfigException empty = loadFails("# sadece yorum\n");

        assertTrue(noStages.getMessage().contains("pipeline.stages eksik"));
        assertTrue(empty.getMessage().contains("yapilandirma bos"));
    }

    // ---- diğer yapılandırma zamanı hataları ------------------------------------------------

    @Test
    void typoInTopLevelOrComponentKeysIsCaught(@TempDir Path dir) throws IOException {
        Path in = log(dir);
        ConfigException top = loadFails("pipeline:\n  stage: []\n");
        ConfigException comp = loadFails("pipeline:\n"
                + "  source: { type: file, param: { path: \"" + in + "\" } }\n"
                + "  stages:\n    - type: parse\n  sink: { type: console }\n");

        assertTrue(top.getMessage().contains("bilinmeyen anahtar 'stage'"));
        assertTrue(comp.getMessage().contains("pipeline.source: bilinmeyen anahtar 'param'"));
    }

    @Test
    void unknownParameterIsRejectedInsteadOfIgnored(@TempDir Path dir) throws IOException {
        String stages = "    - type: parse\n    - type: filter\n      params: { field: status, op: \">=\", value: 400, vaule: 1 }\n";

        ConfigException e = loadFails(yaml(log(dir), dir.resolve("o.txt"), stages));

        assertTrue(e.getMessage().contains("bilinmeyen parametre: 'vaule'"));
        assertTrue(e.getMessage().contains("gecerli: field, op, value"));
    }

    @Test
    void stageOutputMustMatchWhatTheNextComponentExpects(@TempDir Path dir) throws IOException {
        Path in = log(dir);

        ConfigException noProject = loadFails(yaml(in, dir.resolve("o.txt"),
                "    - type: parse\n    - type: filter\n      params: { field: status, op: \">=\", value: 400 }\n"));
        ConfigException noParse = loadFails(yaml(in, dir.resolve("o.txt"),
                "    - type: filter\n      params: { field: status, op: \">=\", value: 400 }\n"));

        assertTrue(noProject.getMessage().contains("sink ('file'): tip uyusmazligi"));
        assertTrue(noProject.getMessage().contains("ProjectedRecord bekliyor"));
        assertTrue(noProject.getMessage().contains("Araya su asamalardan biri gerekir: project"));
        assertTrue(noParse.getMessage().contains("stages[0] ('filter'): tip uyusmazligi"));
        assertTrue(noParse.getMessage().contains("Araya su asamalardan biri gerekir: parse"));
    }

    @Test
    void invalidFilterFieldOperatorAndTimeValueAreExplained(@TempDir Path dir) throws IOException {
        Path in = log(dir);
        Path out = dir.resolve("o.txt");

        ConfigException field = loadFails(yaml(in, out,
                "    - type: parse\n    - type: filter\n      params: { field: stauts, op: \">=\", value: 1 }\n"));
        ConfigException op = loadFails(yaml(in, out,
                "    - type: parse\n    - type: filter\n      params: { field: path, op: \">=\", value: x }\n"));
        ConfigException time = loadFails(yaml(in, out,
                "    - type: parse\n    - type: filter\n      params: { field: timestamp, op: \">=\", value: \"dun\" }\n"));

        assertTrue(field.getMessage().contains("bilinmeyen alan 'stauts'"));
        assertTrue(op.getMessage().contains("'>=' islemi 'path' alani (metin) icin gecersiz"));
        assertTrue(op.getMessage().contains("startsWith"));
        assertTrue(time.getMessage().contains("gecerli bir ISO zamani degil"));
    }

    @Test
    void projectWithUnknownFieldAndUnsupportedParseFormatAreRejected(@TempDir Path dir) throws IOException {
        Path in = log(dir);
        Path out = dir.resolve("o.txt");

        ConfigException field = loadFails(yaml(in, out,
                "    - type: parse\n    - type: project\n      params: { fields: [status, nope] }\n"));
        ConfigException format = loadFails(yaml(in, out, "    - type: parse\n      params: { format: json }\n"));

        assertTrue(field.getMessage().contains("Bilinmeyen alan: nope"));
        assertTrue(format.getMessage().contains("desteklenmeyen format 'json'"));
    }

    @Test
    void missingSourceFileIsReportedBeforeAnyProcessing(@TempDir Path dir) {
        Path out = dir.resolve("o.txt");

        ConfigException e = loadFails(yaml(dir.resolve("yok.log"), out, ERROR_STAGES));

        assertTrue(e.getMessage().contains("source ('file'): kaynak dosya bulunamadi"));
    }

    @Test
    void malformedYamlReportsLineNumberWithFileLabel() {
        ConfigException e = loadFails("pipeline:\n  source: { type: file\n");

        assertTrue(e.getMessage().startsWith("test.yaml: satir 2:"));
    }

    @Test
    void missingConfigFileIsAConfigError(@TempDir Path dir) {
        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(dir.resolve("yok.yaml")));

        assertTrue(e.getMessage().contains("yok.yaml: yapilandirma dosyasi bulunamadi"));
    }

    /** Fail fast: yapılandırma hatalıysa çıktı dosyası bile oluşturulmaz, hiçbir veri işlenmez. */
    @Test
    void invalidConfigurationTouchesNoFiles(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("out").resolve("o.txt");

        loadFails(yaml(log(dir), out, "    - type: parse\n    - type: filter\n"));

        assertEquals(false, Files.exists(out));
        assertEquals(false, Files.exists(out.getParent()));
    }

    @Test
    void consoleSinkWritesTheSameLinesToTheGivenStream(@TempDir Path dir) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        SinkRegistry sinks = new SinkRegistry();
        sinks.register(new ConsoleSinkFactory(new PrintStream(buffer, true, StandardCharsets.UTF_8)));
        PipelineLoader custom = new PipelineLoader(SourceRegistry.discover(), StageRegistry.discover(), sinks);
        String text = "pipeline:\n"
                + "  source: { type: file, params: { path: \"" + log(dir) + "\" } }\n"
                + "  stages:\n    - type: parse\n    - type: project\n      params: { fields: [status] }\n"
                + "  sink: { type: console }\n";

        custom.loadFromText(text, "t.yaml", "t").run();

        assertEquals("status=200\nstatus=401\nstatus=404\nstatus=500\n",
                buffer.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void filterOperatorsWorkForNumbersTextAndTime(@TempDir Path dir) throws IOException {
        Path in = log(dir);
        assertEquals(List.of("status=401", "status=404"), run(dir, in,
                "    - type: filter\n      params: { field: status, op: \">=\", value: 401 }\n"
                        + "    - type: filter\n      params: { field: status, op: \"<\", value: 500 }\n"));
        // metin işleçleri ve ardışık dört filtre: yalnızca POST /api/login (401) hepsini geçer
        assertEquals(List.of("status=401"), run(dir, in,
                "    - type: filter\n      params: { field: path, op: startsWith, value: /api/ }\n"
                        + "    - type: filter\n      params: { field: method, op: \"!=\", value: GET }\n"
                        + "    - type: filter\n      params: { field: path, op: contains, value: \"log\" }\n"
                        + "    - type: filter\n      params: { field: path, op: endsWith, value: \"n\" }\n"));
        // zaman: 13:00:01+03:00 = 10:00:01Z ; [>=10:00:01Z, <10:00:03Z) -> 401, 404
        assertEquals(List.of("status=401", "status=404"), run(dir, in,
                "    - type: filter\n      params: { field: timestamp, op: \">=\", value: \"2023-10-10T10:00:01Z\" }\n"
                        + "    - type: filter\n      params: { field: timestamp, op: \"<\", value: \"2023-10-10T10:00:03Z\" }\n"));
        assertEquals(List.of("status=500"), run(dir, in,
                "    - type: filter\n      params: { field: clientIp, op: \"==\", value: \"10.0.0.4\" }\n"));
    }

    /** parse + verilen filtreler + status projeksiyonu çalıştırıp çıktı satırlarını döndürür. */
    private List<String> run(Path dir, Path in, String filters) throws IOException {
        Path out = dir.resolve("run-" + System.nanoTime() + ".txt");
        String stages = "    - type: parse\n" + filters
                + "    - type: project\n      params: { fields: [status] }\n";
        loader.loadFromText(yaml(in, out, stages), "t.yaml", "t").run();
        return Files.readAllLines(out);
    }
}
