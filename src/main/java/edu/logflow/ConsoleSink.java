package edu.logflow;

import java.io.PrintStream;

/** v2: bir {@link LogRecord} nesnesini okunabilir, tek satırlık biçimde konsola yazar. */
public final class ConsoleSink implements Sink<LogRecord> {

    private final PrintStream out;

    public ConsoleSink(PrintStream out) {
        this.out = out;
    }

    @Override
    public void accept(LogRecord r) {
        out.println(format(r));
    }

    /** Örnek: {@code 2023-10-10T10:55:36Z  192.168.1.10  GET /index.html  200  2326B  "Mozilla/5.0"} */
    static String format(LogRecord r) {
        String query = r.attributes().get("query");
        String target = query == null ? r.path() : r.path() + "?" + query;
        return String.format("%s  %-15s  %-6s %s  %d  %dB  \"%s\"",
                r.timestamp(), r.clientIp(), r.method(), target,
                r.status(), r.bytes(), r.userAgent());
    }
}
