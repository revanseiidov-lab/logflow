package edu.logflow.error;

import edu.logflow.RawLine;

import java.nio.charset.StandardCharsets;

/** {@link RawLine} için dead-letter bağlamı: geçersiz baytlar kayıplı gösterilir, özgün baytlar korunur. */
final class RawLineContext {

    private RawLineContext() {
    }

    static ErrorRouter.Context of(RawLine line) {
        return new ErrorRouter.Context(line.number(),
                () -> new String(line.bytes(), StandardCharsets.UTF_8), line.oversize(), line.bytes());
    }
}
