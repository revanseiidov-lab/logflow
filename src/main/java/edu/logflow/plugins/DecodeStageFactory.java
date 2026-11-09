package edu.logflow.plugins;

import edu.logflow.DecodeStage;
import edu.logflow.RawLine;
import edu.logflow.RawLineSource;
import edu.logflow.Stage;
import edu.logflow.config.Params;
import edu.logflow.config.StageFactory;

/** {@code type: decode} — {@code RawLine} → {@code String}, katı UTF-8. Parametre: maxLineBytes (raw-file ile aynı olmalı). */
public final class DecodeStageFactory implements StageFactory {

    @Override
    public String type() {
        return "decode";
    }

    @Override
    public Class<?> inputType() {
        return RawLine.class;
    }

    @Override
    public Class<?> outputType() {
        return String.class;
    }

    @Override
    public String example() {
        return "{ maxLineBytes: 65536 }";
    }

    @Override
    public Stage<?, ?> create(Params params) {
        params.allowOnly("maxLineBytes");
        return new DecodeStage(params.optInt("maxLineBytes", RawLineSource.DEFAULT_MAX_LINE_BYTES));
    }
}
