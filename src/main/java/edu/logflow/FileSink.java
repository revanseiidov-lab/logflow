package edu.logflow;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Projeksiyonlu kayıtları bir dosyaya yazar, kayıt başına bir satır.
 *
 * <p>Yaşam döngüsü: dosya {@link #open()}'da açılır, {@link #close()}'da boşaltılıp (flush) kapatılır.
 * {@code close()} çağrılmazsa arabellekteki (buffered) veri dosyaya hiç ulaşmayabilir; hata
 * vermeden kaybolur. Bu yüzden işlem hattı close()'u hata olsa bile çağırır.
 */
public final class FileSink implements Sink<ProjectedRecord> {

    private final Path file;
    private BufferedWriter writer;
    private long written;

    public FileSink(Path file) {
        this.file = file;
    }

    @Override
    public void open() throws IOException {
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
    }

    @Override
    public void accept(ProjectedRecord record) {
        if (writer == null) {
            throw new IllegalStateException("FileSink acik degil: once open() cagrilmali");
        }
        try {
            writer.write(record.toLine());
            writer.newLine();
            written++;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    /** Şu ana kadar yazılan kayıt sayısı. */
    public long written() {
        return written;
    }
}
