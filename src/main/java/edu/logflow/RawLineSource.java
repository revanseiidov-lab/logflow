package edu.logflow;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Dosyayı <b>bayt</b> düzeyinde satırlara böler ({@code \n}, sondaki {@code \r} atılır). Kodlamayı çözmez
 * ve satırı belleğe sınırsız almaz: {@code maxLineBytes}'ı aşan satır "oversize" işaretlenir, yalnızca ön eki tutulur
 * (bellek koruması). Her satır, boş olsa bile, bir {@link RawLine}'dır ve numarası korunur.
 *
 * <p>Neden {@code FileLineSource} değil? O, dosyayı katı UTF-8 ile satır satır okur ve ilk geçersiz bayt dizisinde
 * tüm çalışmayı {@code MalformedInputException} ile durdurur; kusurlu tek bir satır yüzünden bütün dosya kaybolur.
 */
public final class RawLineSource implements Source<RawLine> {

    public static final int DEFAULT_MAX_LINE_BYTES = 64 * 1024;

    private final Path file;
    private final int maxLineBytes;

    public RawLineSource(Path file) {
        this(file, DEFAULT_MAX_LINE_BYTES);
    }

    public RawLineSource(Path file, int maxLineBytes) {
        if (maxLineBytes < 1) {
            throw new IllegalArgumentException("maxLineBytes >= 1 olmali: " + maxLineBytes);
        }
        this.file = file;
        this.maxLineBytes = maxLineBytes;
    }

    @Override
    public void emitAll(Emitter<RawLine> out) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 64 * 1024)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
            long number = 0;
            long length = 0;
            boolean pending = false;
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    out.emit(line(++number, buf, length));
                    buf.reset();
                    length = 0;
                    pending = false;
                    continue;
                }
                pending = true;
                length++;
                if (length <= maxLineBytes) {
                    buf.write(b);
                }
            }
            if (pending) {                       // son satır \n ile bitmiyorsa
                out.emit(line(++number, buf, length));
            }
        }
    }

    private RawLine line(long number, ByteArrayOutputStream buf, long length) {
        byte[] bytes = buf.toByteArray();
        long len = length;
        if (len <= maxLineBytes && bytes.length > 0 && bytes[bytes.length - 1] == '\r') {
            bytes = Arrays.copyOf(bytes, bytes.length - 1);
            len--;
        }
        boolean oversize = len > maxLineBytes;
        if (oversize && bytes.length > RawLine.PREFIX) {
            bytes = Arrays.copyOf(bytes, RawLine.PREFIX);
        }
        return new RawLine(number, bytes, oversize, len);
    }
}
