package edu.logflow.plugins;

import edu.logflow.FileSink;
import edu.logflow.ProjectedRecord;
import edu.logflow.Sink;
import edu.logflow.config.Params;
import edu.logflow.config.SinkFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@code type: file} — projeksiyonlu kayıtları dosyaya yazar. Parametre: path (üst dizinler gerekirse oluşturulur). */
public final class FileSinkFactory implements SinkFactory {

    @Override
    public String type() {
        return "file";
    }

    @Override
    public Class<?> inputType() {
        return ProjectedRecord.class;
    }

    @Override
    public String example() {
        return "{ path: out/errors.txt }";
    }

    @Override
    public Sink<?> create(Params params) {
        params.allowOnly("path");
        Path path = Path.of(params.requireString("path"));
        if (Files.isDirectory(path)) {
            throw params.failWithoutExample("'" + path + "' bir dizin; cikti icin bir dosya yolu verin");
        }
        return new FileSink(path);
    }
}
