package edu.logflow;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bir metin dosyasını satır satır okuyan kaynak. Dosya sistemine dokunan tek yer burasıdır. */
public final class LineSource implements Source<String> {

    private final Path file;

    public LineSource(Path file) {
        this.file = file;
    }

    @Override
    public void emitAll(Emitter<String> out) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.emit(line);
            }
        }
    }
}
