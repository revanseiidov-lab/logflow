package edu.logflow.plugins;

import edu.logflow.FileLineSource;
import edu.logflow.Source;
import edu.logflow.config.Params;
import edu.logflow.config.SourceFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@code type: file} — bir metin dosyasını satır satır okur. Parametre: path. Dosya yükleme sırasında denetlenir. */
public final class FileSourceFactory implements SourceFactory {

    @Override
    public String type() {
        return "file";
    }

    @Override
    public Class<?> outputType() {
        return String.class;
    }

    @Override
    public String example() {
        return "{ path: data/access.log }";
    }

    @Override
    public Source<?> create(Params params) {
        params.allowOnly("path");
        Path path = Path.of(params.requireString("path"));
        if (Files.isDirectory(path)) {
            throw params.failWithoutExample("'" + path + "' bir dizin; bir dosya yolu verin");
        }
        if (!Files.isReadable(path)) {
            throw params.failWithoutExample("kaynak dosya bulunamadi ya da okunamiyor: " + path
                    + " (calisma dizini: " + Path.of("").toAbsolutePath() + ")");
        }
        return new FileLineSource(path);
    }
}
