package edu.logflow.plugins;

import edu.logflow.LogRecord;
import edu.logflow.ParserStage;
import edu.logflow.Stage;
import edu.logflow.config.Params;
import edu.logflow.config.StageFactory;

/** {@code type: parse} — günlük satırını (String) LogRecord'a çevirir. Parametre: format (yalnızca clf). */
public final class ParseStageFactory implements StageFactory {

    @Override
    public String type() {
        return "parse";
    }

    @Override
    public Class<?> inputType() {
        return String.class;
    }

    @Override
    public Class<?> outputType() {
        return LogRecord.class;
    }

    @Override
    public String example() {
        return "{ format: clf }";
    }

    @Override
    public Stage<?, ?> create(Params params) {
        params.allowOnly("format");
        String format = params.optString("format", "clf");
        if (!format.equals("clf")) {
            throw params.fail("desteklenmeyen format '" + format + "' (desteklenen: clf)");
        }
        return new ParserStage();
    }
}
