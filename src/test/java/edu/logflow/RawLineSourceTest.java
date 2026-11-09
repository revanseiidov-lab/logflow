package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RawLineSourceTest {

    private static List<RawLine> read(Path file, int max) throws IOException {
        List<RawLine> lines = new ArrayList<>();
        new RawLineSource(file, max).emitAll(lines::add);
        return lines;
    }

    private static Path write(Path dir, byte[] content) throws IOException {
        Path f = dir.resolve("f.log");
        Files.write(f, content);
        return f;
    }

    @Test
    void splitsOnNewlineAndNumbersFromOne(@TempDir Path dir) throws IOException {
        List<RawLine> lines = read(write(dir, "a\nbb\nccc\n".getBytes(StandardCharsets.UTF_8)), 100);

        assertEquals(3, lines.size());
        assertEquals(1, lines.get(0).number());
        assertEquals("bb", new String(lines.get(1).bytes(), StandardCharsets.UTF_8));
        assertEquals(3, lines.get(2).number());
    }

    @Test
    void lastLineWithoutTerminatorIsStillEmitted(@TempDir Path dir) throws IOException {
        List<RawLine> lines = read(write(dir, "a\nb".getBytes(StandardCharsets.UTF_8)), 100);
        assertEquals(2, lines.size());
        assertEquals("b", new String(lines.get(1).bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void crLfAndEmptyLinesAreHandledAndEmptyLinesKeepTheirNumber(@TempDir Path dir) throws IOException {
        List<RawLine> lines = read(write(dir, "a\r\n\r\nb\n".getBytes(StandardCharsets.UTF_8)), 100);

        assertEquals(3, lines.size());
        assertEquals("a", new String(lines.get(0).bytes(), StandardCharsets.UTF_8));
        assertEquals(0, lines.get(1).bytes().length);
        assertEquals(2, lines.get(1).number());
        assertEquals(3, lines.get(2).number());
    }

    @Test
    void emptyFileProducesNoLinesAndNoError(@TempDir Path dir) throws IOException {
        assertEquals(0, read(write(dir, new byte[0]), 100).size());
    }

    @Test
    void invalidBytesAreKeptUntouchedBecauseDecodingIsNotThisClassesJob(@TempDir Path dir) throws IOException {
        byte[] raw = {'a', (byte) 0xFF, (byte) 0xFE, 'b', '\n'};
        RawLine line = read(write(dir, raw), 100).get(0);
        assertTrue(java.util.Arrays.equals(new byte[] {'a', (byte) 0xFF, (byte) 0xFE, 'b'}, line.bytes()));
        assertFalse(line.oversize());
    }

    @Test
    void oversizeLineIsFlaggedKeepsRealLengthAndOnlyAPrefixInMemory(@TempDir Path dir) throws IOException {
        byte[] big = new byte[5000];
        java.util.Arrays.fill(big, (byte) 'x');
        byte[] content = new byte[big.length + 4];
        System.arraycopy(big, 0, content, 0, big.length);
        content[big.length] = '\n';
        content[big.length + 1] = 'o';
        content[big.length + 2] = 'k';
        content[big.length + 3] = '\n';

        List<RawLine> lines = read(write(dir, content), 2000);

        assertEquals(2, lines.size());
        assertTrue(lines.get(0).oversize());
        assertEquals(5000, lines.get(0).length());
        assertEquals(RawLine.PREFIX, lines.get(0).bytes().length);
        assertFalse(lines.get(1).oversize());            // sonraki satir etkilenmez
        assertEquals(2, lines.get(1).number());
    }

    @Test
    void lineExactlyAtTheLimitIsNotOversize(@TempDir Path dir) throws IOException {
        RawLine line = read(write(dir, "12345\n".getBytes(StandardCharsets.UTF_8)), 5).get(0);
        assertFalse(line.oversize());
        assertEquals(5, line.length());
    }

    @Test
    void missingFileIsAnIoErrorNotSilentlyEmpty(@TempDir Path dir) {
        assertThrows(NoSuchFileException.class, () -> read(dir.resolve("nope.log"), 100));
    }

    @Test
    void rejectsNonPositiveLimit(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> new RawLineSource(dir.resolve("x"), 0));
    }
}
