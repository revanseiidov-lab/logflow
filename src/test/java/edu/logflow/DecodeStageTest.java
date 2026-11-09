package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.logflow.error.ErrorType;
import edu.logflow.error.StageException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DecodeStageTest {

    private final DecodeStage stage = new DecodeStage(100);

    private String decode(byte[] bytes) {
        CollectingEmitter<String> out = new CollectingEmitter<>();
        stage.process(new RawLine(1, bytes, false, bytes.length), out);
        return out.first();
    }

    @Test
    void decodesAsciiAndMultibyteUtf8() {
        assertEquals("abc", decode("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals("şğü €😀", decode("şğü €😀".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void invalidUtf8IsRejectedNeverReplacedSilently() {
        byte[] bad = {'a', (byte) 0xFF, (byte) 0xFE, 'b'};
        CollectingEmitter<String> out = new CollectingEmitter<>();

        StageException e = assertThrows(StageException.class, () -> stage.process(new RawLine(1, bad, false, 4), out));

        assertEquals(ErrorType.READ_ENCODING, e.type());
        assertTrue(e.getMessage().contains("bayt konumu 1"));
        assertEquals(0, out.size());
    }

    @Test
    void truncatedMultibyteSequenceAtTheEndIsRejected() {
        byte[] cut = {'a', (byte) 0xC5};                    // 'ş'nin ilk baytı, ikincisi yok
        assertThrows(StageException.class, () -> decode(cut));
    }

    @Test
    void oversizeLineIsRejectedWithItsRealLength() {
        CollectingEmitter<String> out = new CollectingEmitter<>();
        StageException e = assertThrows(StageException.class,
                () -> stage.process(new RawLine(9, new byte[10], true, 5000), out));
        assertEquals(ErrorType.READ_OVERSIZE, e.type());
        assertTrue(e.getMessage().contains("5000"));
        assertTrue(e.getMessage().contains("100"));
    }

    @Test
    void emptyLineDecodesToEmptyStringAndLeavesRejectionToTheParser() {
        assertEquals("", decode(new byte[0]));
    }
}
