package edu.logflow.plugins;

import edu.logflow.RawLine;
import edu.logflow.RawLineSource;
import edu.logflow.Source;
import edu.logflow.config.Params;
import edu.logflow.config.SourceFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code type: raw-file} — dosyayı <b>bayt</b> düzeyinde satırlara böler (kodlamayı çözmez). {@code decode} aşamasıyla
 * birlikte kullanılır; kusurlu bir satır tüm dosyayı değil yalnızca kendi kaydını kaybettirir.
 * Parametreler: path, maxLineBytes (varsayılan 65536).
 */
public final class RawFileSourceFactory implements SourceFactory {

    @Override
    public String type() {
        return "raw-file";
    }

    @Override
    public Class<?> outputType() {
        return RawLine.class;
    }

    @Override
    public String example() {
        return "{ path: data/access.log, maxLineBytes: 65536 }";
    }

    @Override
    public Source<?> create(Params params) {
        params.allowOnly("path", "maxLineBytes");
        Path path = Path.of(params.requireString("path"));
        int max = params.optInt("maxLineBytes", RawLineSource.DEFAULT_MAX_LINE_BYTES);
        if (max < 1) {
            throw params.fail("'maxLineBytes' en az 1 olmali, bulundu: " + max);
        }
        if (Files.isDirectory(path)) {
            throw params.failWithoutExample("'" + path + "' bir dizin; bir dosya yolu verin");
        }
        if (!Files.isReadable(path)) {
            throw params.failWithoutExample("kaynak dosya bulunamadi ya da okunamiyor: " + path
                    + " (calisma dizini: " + Path.of("").toAbsolutePath() + ")");
        }
        return new RawLineSource(path, max);
    }
}
