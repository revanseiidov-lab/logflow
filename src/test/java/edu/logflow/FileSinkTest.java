package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSinkTest {

    private static ProjectedRecord rec(String status, String path) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("path", path);
        return new ProjectedRecord(m);
    }

    @Test
    void writesOneLinePerRecordAfterClose(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("out.txt");
        FileSink sink = new FileSink(file);

        sink.open();
        sink.accept(rec("404", "/a"));
        sink.accept(rec("500", "/b"));
        sink.close();

        assertEquals(List.of("status=404\tpath=/a", "status=500\tpath=/b"), Files.readAllLines(file));
        assertEquals(2, sink.written());
    }

    /** Yaşam döngüsünün süs olmadığının kanıtı: close() çağrılmazsa veri dosyaya ulaşmaz. */
    @Test
    void withoutCloseBufferedDataNeverReachesTheFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("out.txt");
        FileSink sink = new FileSink(file);

        sink.open();
        sink.accept(rec("404", "/a"));

        assertEquals(0L, Files.size(file));      // kayıt yazıldı sanıldı, ama arabellekte takılı

        sink.close();
        assertEquals(1, Files.readAllLines(file).size());
    }

    @Test
    void acceptBeforeOpenFailsClearly(@TempDir Path dir) {
        FileSink sink = new FileSink(dir.resolve("out.txt"));

        assertThrows(IllegalStateException.class, () -> sink.accept(rec("200", "/a")));
    }

    @Test
    void closeWithoutOpenAndDoubleCloseAreHarmless(@TempDir Path dir) throws IOException {
        FileSink sink = new FileSink(dir.resolve("out.txt"));

        sink.close();
        sink.open();
        sink.close();
        sink.close();
    }

    @Test
    void openTruncatesAnExistingFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("out.txt");
        Files.writeString(file, "old content\n");
        FileSink sink = new FileSink(file);

        sink.open();
        sink.close();

        assertEquals(0L, Files.size(file));
    }

    @Test
    void openFailsWhenDirectoryDoesNotExist(@TempDir Path dir) {
        FileSink sink = new FileSink(dir.resolve("missing-dir").resolve("out.txt"));

        assertThrows(IOException.class, sink::open);
    }
}
