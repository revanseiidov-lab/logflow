package edu.logflow.error;

import edu.logflow.Sink;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Dead-letter kayıtlarını JSON Lines olarak yazar (satır başına bir nesne; bağımlılık yok). */
public final class JsonlSink implements Sink<FailedRecord> {

    private final Path path;
    private BufferedWriter writer;

    public JsonlSink(Path path) {
        this.path = path;
    }

    @Override
    public void open() throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    @Override
    public void accept(FailedRecord r) {
        try {
            writer.write(toJson(r));
            writer.newLine();
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

    static String toJson(FailedRecord r) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"timestamp\":").append(quote(r.timestamp().toString()));
        sb.append(",\"stage\":").append(quote(r.stage()));
        sb.append(",\"errorType\":").append(quote(r.errorType().name()));
        sb.append(",\"message\":").append(quote(r.message()));
        sb.append(",\"line\":").append(r.line());
        sb.append(",\"rawInput\":").append(quote(r.rawInput()));
        sb.append(",\"rawTruncated\":").append(r.rawTruncated());
        if (r.rawBase64() != null) {
            sb.append(",\"rawBase64\":").append(quote(r.rawBase64()));
        }
        return sb.append('}').toString();
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f || Character.isSurrogate(c) && !validSurrogate(s, i)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static boolean validSurrogate(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        }
        return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }
}
