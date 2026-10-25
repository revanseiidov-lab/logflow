package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Uçtan uca: <b>aynı</b> {@code App.run} (aynı çalıştırılabilir kod), üç farklı yapılandırma dosyasıyla
 * üç farklı çıktı üretir. Gönderilen configs/*.yaml dosyaları kullanılır; yalnızca çıktı yolları geçici
 * dizine yönlendirilir. (Testler proje kökünden çalıştırılır: Maven ve IDE varsayılanı.)
 */
class ConfigIntegrationTest {

    private static final class Result {
        int exit;
        String out;
        String err;
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Result r = new Result();
        r.exit = App.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        r.out = out.toString(StandardCharsets.UTF_8);
        r.err = err.toString(StandardCharsets.UTF_8);
        return r;
    }

    /** Gönderilen yapılandırmayı okur, çıktı dizinini geçici dizine çevirip kaydeder; yolunu döndürür. */
    private static Path redirected(String name, Path tmp) throws IOException {
        String text = Files.readString(Path.of("configs", name)).replace("path: out/", "path: " + tmp + "/");
        Path copy = tmp.resolve(name);
        Files.writeString(copy, text);
        return copy;
    }

    @Test
    void errorReportConfigProducesTheErrorReport(@TempDir Path tmp) throws IOException {
        Result r = run("--config", redirected("error-report.yaml", tmp).toString());

        assertEquals(0, r.exit);
        assertEquals(List.of(
                "timestamp=2023-10-10T10:56:01Z\tclientIp=10.0.0.5\tstatus=401\tpath=/api/login",
                "timestamp=2023-10-10T10:57:30Z\tclientIp=172.16.4.2\tstatus=404\tpath=/missing/page",
                "timestamp=2023-10-10T11:00:00Z\tclientIp=203.0.113.7\tstatus=500\tpath=/api/items/42"),
                Files.readAllLines(tmp.resolve("errors.txt")));
        assertTrue(r.err.contains("Hatali (atlanan) satir sayisi: 4"));
    }

    @Test
    void sameExecutableCodeWithThreeConfigsGivesThreeDifferentOutputs(@TempDir Path tmp) throws IOException {
        assertEquals(0, run("--config", redirected("error-report.yaml", tmp).toString()).exit);
        assertEquals(0, run("--config", redirected("api-traffic.yaml", tmp).toString()).exit);
        assertEquals(0, run("--config", redirected("bandwidth-by-client.yaml", tmp).toString()).exit);

        List<String> errors = Files.readAllLines(tmp.resolve("errors.txt"));
        List<String> api = Files.readAllLines(tmp.resolve("api-traffic.txt"));
        List<String> bandwidth = Files.readAllLines(tmp.resolve("bandwidth-by-client.txt"));

        assertEquals(3, errors.size());
        assertEquals(3, api.size());
        assertEquals(11, bandwidth.size());            // 14 gecerli kayit - 3 tanesi 0 bayt
        assertTrue(!errors.equals(api));
        assertTrue(!errors.equals(bandwidth));
        assertTrue(!api.equals(bandwidth));
        // her çıktının alan kümesi kendi yapılandırmasından geliyor
        assertTrue(errors.get(0).startsWith("timestamp=") && errors.get(0).contains("clientIp="));
        assertTrue(api.get(0).contains("method=POST") && !api.get(0).contains("clientIp="));
        assertEquals("clientIp=192.168.1.10\tbytes=2326", bandwidth.get(0));
        assertTrue(api.stream().allMatch(l -> l.contains("path=/api")));
    }

    @Test
    void dryRunValidatesWithoutProcessingAnyData(@TempDir Path tmp) throws IOException {
        Path config = redirected("error-report.yaml", tmp);

        Result r = run("--config", config.toString(), "--dry-run");

        assertEquals(0, r.exit);
        assertEquals("Yapilandirma gecerli: error-report: file -> parse -> filter -> project -> file",
                r.out.strip());
        assertEquals(false, Files.exists(tmp.resolve("errors.txt")));
    }

    @Test
    void configurationErrorsExitWithCode2AndNameTheProblem(@TempDir Path tmp) throws IOException {
        Path bad = tmp.resolve("bad.yaml");
        Files.writeString(bad, "pipeline:\n  source: { type: file, params: { path: data/access-small.log } }\n"
                + "  stages:\n    - type: fliter\n  sink: { type: console }\n");

        Result r = run("--config", bad.toString());

        assertEquals(2, r.exit);
        assertTrue(r.err.startsWith("Yapilandirma hatasi: "));
        assertTrue(r.err.contains("stages[0]: bilinmeyen asama (stage) turu 'fliter'"));
        assertEquals("", r.out);
    }

    @Test
    void configCannotBeCombinedWithDirectOptions() {
        Result r = run("--config", "configs/error-report.yaml", "--min-status", "400");

        assertEquals(2, r.exit);
        assertTrue(r.err.contains("--config baska seceneklerle"));
    }

    @Test
    void shippedInvalidExamplesAllFailFastWithExitCode2() throws IOException {
        try (var files = Files.list(Path.of("configs", "invalid"))) {
            List<Path> examples = files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
            assertTrue(examples.size() >= 5);
            for (Path example : examples) {
                Result r = run("--config", example.toString());
                assertEquals(2, r.exit);
                assertTrue(r.err.startsWith("Yapilandirma hatasi: " + example));
            }
        }
    }
}
