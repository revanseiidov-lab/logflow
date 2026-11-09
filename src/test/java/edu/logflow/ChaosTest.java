package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Kaos testi: kasıtlı bozulmuş gerçek dosya ({@code data/access-dirty.log}: 11 satır; 3 geçerli, kesik, negatif bayt,
 * 1970, 2099, geçersiz UTF-8, 100 KiB'lık satır, yorum satırı). Çalışma çökmemeli, kayıp olmamalı,
 * her geçersiz kayıt politikaya göre ele alınmalı. Testler proje kökünden çalışır (Maven/IDE varsayılanı).
 */
class ChaosTest {

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

    private static Path redirected(String name, Path tmp) throws IOException {
        String text = Files.readString(Path.of("configs", name)).replace("path: out/", "path: " + tmp + "/");
        Path copy = tmp.resolve(name);
        Files.writeString(copy, text);
        return copy;
    }

    @Test
    void deadLetterRunSurvivesCorruptionAndReconciles(@TempDir Path tmp) throws IOException {
        Result r = run("--config", redirected("dirty-dead-letter.yaml", tmp).toString());

        assertEquals(0, r.exit, r.err);
        assertTrue(r.err.contains("Calisma ozeti [OK]"), r.err);
        assertTrue(r.err.contains("okunan          : 11"), r.err);
        assertTrue(r.err.contains("yazilan (sink)  : 3"), r.err);
        assertTrue(r.err.contains("dead-letter     : 8"), r.err);
        assertTrue(r.err.contains("mutabakat       : TAMAM"), r.err);

        // ana çıktı: yalnızca 3 geçerli kayıt
        assertEquals(List.of(
                "timestamp=2026-10-15T10:15:32Z\tclientIp=192.0.2.10\tstatus=200\tpath=/index.html",
                "timestamp=2026-10-15T10:16:05Z\tclientIp=198.51.100.22\tstatus=401\tpath=/api/login",
                "timestamp=2026-10-15T10:17:41Z\tclientIp=203.0.113.7\tstatus=304\tpath=/images/logo.png"),
                Files.readAllLines(tmp.resolve("dirty-valid.txt")));

        // dead-letter: 8 satır, her biri nedeni ve satır numarasıyla
        List<String> dead = Files.readAllLines(tmp.resolve("dirty-rejected.jsonl"));
        assertEquals(8, dead.size());
        String all = String.join("\n", dead);
        for (String type : new String[] {"READ_ENCODING", "READ_OVERSIZE", "PARSE_MALFORMED", "VALIDATION_RANGE",
                "VALIDATION_TIMESTAMP"}) {
            assertTrue(all.contains("\"errorType\":\"" + type + "\""), type);
        }
        assertEquals(2, dead.stream().filter(l -> l.contains("VALIDATION_TIMESTAMP")).count());   // 1970 ve 2099
        assertTrue(all.contains("\"line\":10"));                 // geçersiz UTF-8
        assertTrue(all.contains("\"line\":11"));                 // 100 KiB'lık satır
    }

    @Test
    void invalidBytesArePreservedAsBase64EvidenceNotSilentlyReplaced(@TempDir Path tmp) throws IOException {
        run("--config", redirected("dirty-dead-letter.yaml", tmp).toString());

        String line = Files.readAllLines(tmp.resolve("dirty-rejected.jsonl")).stream()
                .filter(l -> l.contains("READ_ENCODING")).findFirst().orElseThrow();
        String b64 = line.replaceAll(".*\"rawBase64\":\"([^\"]+)\".*", "$1");
        byte[] original = Base64.getDecoder().decode(b64);
        // 0xFF 0xFE 0xFA kasıtlı bozuk baytlar özgün hâliyle duruyor
        String asLatin1 = new String(original, StandardCharsets.ISO_8859_1);
        assertTrue(asLatin1.contains("ÿþú"));
    }

    @Test
    void oversizeLineEvidenceIsBoundedSoDeadLetterDoesNotExplode(@TempDir Path tmp) throws IOException {
        run("--config", redirected("dirty-dead-letter.yaml", tmp).toString());

        String line = Files.readAllLines(tmp.resolve("dirty-rejected.jsonl")).stream()
                .filter(l -> l.contains("READ_OVERSIZE")).findFirst().orElseThrow();
        assertTrue(line.contains("\"rawTruncated\":true"));
        assertTrue(line.length() < 4000, "dead-letter satiri sinirli olmali, uzunluk: " + line.length());
        assertTrue(line.contains("102400 bayt"));
    }

    @Test
    void skipRunCountsEverythingAndWritesNoDeadLetterFile(@TempDir Path tmp) throws IOException {
        Result r = run("--config", redirected("dirty-skip.yaml", tmp).toString());

        assertEquals(0, r.exit, r.err);
        assertTrue(r.err.contains("yazilan (sink)  : 3"), r.err);
        assertTrue(r.err.contains("atlanan (skip)  : 8"), r.err);
        assertTrue(r.err.contains("dead-letter     : 0"), r.err);
        assertTrue(r.err.contains("mutabakat       : TAMAM"), r.err);
        assertFalse(Files.exists(tmp.resolve("dirty-rejected.jsonl")));
    }

    @Test
    void failFastRunStopsAtTheFirstBadRecordWithExitCodeOne(@TempDir Path tmp) throws IOException {
        Result r = run("--config", redirected("dirty-fail-fast.yaml", tmp).toString());

        assertEquals(1, r.exit);
        assertTrue(r.err.contains("[ABORTED]"), r.err);
        assertTrue(r.err.contains("durduran (abort): 1"), r.err);
        assertTrue(r.err.contains("(satir 1)"), r.err);
        assertTrue(r.err.contains("mutabakat       : TAMAM"), r.err);   // durdurma da hesapta
    }

    @Test
    void emptyFileIsHandledCleanlyWithZeroRecords(@TempDir Path tmp) throws IOException {
        String text = Files.readString(redirected("dirty-dead-letter.yaml", tmp))
                .replace("data/access-dirty.log", "data/access-empty.log");
        Path cfg = tmp.resolve("empty.yaml");
        Files.writeString(cfg, text);

        Result r = run("--config", cfg.toString());

        assertEquals(0, r.exit, r.err);
        assertTrue(r.err.contains("okunan          : 0"), r.err);
        assertEquals(0, Files.size(tmp.resolve("dirty-rejected.jsonl")));
        assertEquals(0, Files.size(tmp.resolve("dirty-valid.txt")));
    }

    @Test
    void legacyFileSourceStopsTheWholeRunOnInvalidUtf8WhileRawFileDoesNot(@TempDir Path tmp) throws IOException {
        // Karşılaştırma: eski 'file' kaynağı katı UTF-8 okur; ilk geçersiz baytta tüm çalışma FAILED olur.
        Result legacy = run("data/access-dirty.log", "--output", tmp.resolve("legacy.txt").toString());

        assertEquals(1, legacy.exit);
        assertTrue(legacy.err.contains("[FAILED]"), legacy.err);
        assertTrue(legacy.err.contains("MalformedInputException"), legacy.err);
        assertTrue(legacy.err.contains("mutabakat"), legacy.err);
    }

    @Test
    void shippedV5InvalidExamplesFailBeforeProcessingAnything() {
        for (String name : new String[] {"retry-on-stage", "dead-letter-without-target", "unknown-policy",
                "fallback-without-retry"}) {
            Result r = run("--config", "configs/invalid/" + name + ".yaml");
            assertEquals(2, r.exit, name);
            assertTrue(r.err.startsWith("Yapilandirma hatasi: configs/invalid/" + name), r.err);
            assertEquals("", r.out);
        }
    }
}
