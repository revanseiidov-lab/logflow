package edu.logflow;

import edu.logflow.error.ErrorType;
import edu.logflow.error.StageException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * {@link RawLine} → {@code String}. <b>Katı</b> UTF-8: geçersiz bayt dizisi sessizce {@code U+FFFD}'ye
 * çevrilip veri bozulmaz, kaydın hatası olarak bildirilir ({@code READ_ENCODING}). Boyut sınırını aşan satır
 * {@code READ_OVERSIZE} olur. Başarılı satırlar yayılır; yarım çıktı yoktur.
 */
public final class DecodeStage implements Stage<RawLine, String> {

    private final int maxLineBytes;

    public DecodeStage(int maxLineBytes) {
        this.maxLineBytes = maxLineBytes;
    }

    @Override
    public void process(RawLine line, Emitter<String> out) {
        if (line.oversize()) {
            throw new StageException(ErrorType.READ_OVERSIZE,
                    "satir cok uzun: " + line.length() + " bayt (sinir " + maxLineBytes + ")");
        }
        out.emit(decode(line.bytes()));
    }

    static String decode(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        CharBuffer chars = CharBuffer.allocate(bytes.length);
        CoderResult result = decoder.decode(in, chars, true);
        if (result.isError()) {
            throw new StageException(ErrorType.READ_ENCODING,
                    "gecersiz UTF-8 dizisi (satir icinde bayt konumu " + in.position() + ")");
        }
        result = decoder.flush(chars);
        if (result.isError()) {
            throw new StageException(ErrorType.READ_ENCODING, "gecersiz UTF-8 dizisi (satir sonu)");
        }
        chars.flip();
        return chars.toString();
    }
}
