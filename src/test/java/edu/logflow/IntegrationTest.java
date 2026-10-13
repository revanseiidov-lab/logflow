package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uçtan uca: küçük bir test verisi (fixture) -> 3 aşamalı hat -> çıktı dosyasının içeriği. */
class IntegrationTest {

    /** 7 satır: 4 geçerli /api hatası, 1 başarılı /api, 1 /api dışı hata, 1 bozuk satır. */
    private static final String FIXTURE = String.join("\n",
            "10.0.0.1 - - [10/Oct/2023:13:00:00 +0300] \"GET /api/users HTTP/1.1\" 200 10 \"-\" \"curl/8\"",
            "10.0.0.2 - - [10/Oct/2023:13:00:01 +0300] \"POST /api/login HTTP/1.1\" 401 20 \"-\" \"curl/8\"",
            "10.0.0.3 - - [10/Oct/2023:13:00:02 +0300] \"GET /index.html HTTP/1.1\" 404 30 \"-\" \"Mozilla\"",
            "this line is not a log entry",
            "10.0.0.4 - - [10/Oct/2023:13:00:03 +0300] \"GET /api/items?x=1 HTTP/1.1\" 500 40 \"-\" \"curl/8\"",
            "10.0.0.5 - - [10/Oct/2023:13:00:04 +0300] \"GET /api/health HTTP/1.1\" 503 50 \"-\" \"kube\"",
            "10.0.0.6 - - [10/Oct/2023:13:00:05 +0300] \"GET /api/ok HTTP/1.1\" 204 0 \"-\" \"curl/8\"",
            "");

    @Test
    void threeStagePipelineWritesExpectedFile(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("in.log");
        Path out = dir.resolve("out.txt");
        Files.writeString(in, FIXTURE);
        ParserStage parser = new ParserStage();

        Pipeline p = PipelineBuilder.from(new FileLineSource(in))
                .via(parser)
                .via(PredicateFilterStage.minStatus(400))
                .via(PredicateFilterStage.pathPrefix("/api"))
                .via(new ProjectionStage("clientIp", "status", "path"))
                .to(new FileSink(out));
        p.run();

        assertEquals(List.of(
                "clientIp=10.0.0.2\tstatus=401\tpath=/api/login",
                "clientIp=10.0.0.4\tstatus=500\tpath=/api/items",
                "clientIp=10.0.0.5\tstatus=503\tpath=/api/health"),
                Files.readAllLines(out));
        assertEquals(1, parser.malformedCount());
    }

    @Test
    void commandLineRunProducesTheSameFile(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("in.log");
        Path out = dir.resolve("out.txt");
        Files.writeString(in, FIXTURE);
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = App.run(new String[] {in.toString(), "--min-status", "400", "--path-prefix", "/api",
                "--fields", "clientIp,status,path", "--output", out.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, exit);
        assertEquals(3, Files.readAllLines(out).size());
        assertEquals("clientIp=10.0.0.2\tstatus=401\tpath=/api/login", Files.readAllLines(out).get(0));
        assertEquals(true, err.toString(StandardCharsets.UTF_8).contains("atlanan) satir sayisi: 1"));
    }

    @Test
    void withoutOutputOptionResultGoesToStandardOutput(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("in.log");
        Files.writeString(in, FIXTURE);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exit = App.run(new String[] {in.toString(), "--min-status", "500", "--fields", "status"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exit);
        assertEquals("status=500" + System.lineSeparator() + "status=503" + System.lineSeparator(),
                stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void noFilterOptionsKeepsEveryValidRecord(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("in.log");
        Path out = dir.resolve("out.txt");
        Files.writeString(in, FIXTURE);

        int exit = App.run(new String[] {in.toString(), "--output", out.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exit);
        assertEquals(6, Files.readAllLines(out).size());
    }

    @Test
    void timeRangeFilterThroughTheCommandLine(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("in.log");
        Path out = dir.resolve("out.txt");
        Files.writeString(in, FIXTURE);

        // 13:00:02+03:00 = 10:00:02Z ; [10:00:02Z, 10:00:04Z) -> 404, 500
        int exit = App.run(new String[] {in.toString(), "--since", "2023-10-10T10:00:02Z",
                "--until", "2023-10-10T10:00:04Z", "--fields", "status", "--output", out.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exit);
        assertEquals(List.of("status=404", "status=500"), Files.readAllLines(out));
    }

    @Test
    void errorsReturnNonZeroExitCodes(@TempDir Path dir) {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream());

        assertEquals(2, App.run(new String[] {}, sink, sink));
        assertEquals(2, App.run(new String[] {dir.resolve("x.log").toString(), "--fields", "nope"}, sink, sink));
        assertEquals(1, App.run(new String[] {dir.resolve("missing.log").toString()}, sink, sink));
    }
}
