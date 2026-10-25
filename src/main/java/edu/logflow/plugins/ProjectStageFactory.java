package edu.logflow.plugins;

import edu.logflow.LogRecord;
import edu.logflow.ProjectedRecord;
import edu.logflow.ProjectionStage;
import edu.logflow.Stage;
import edu.logflow.config.Params;
import edu.logflow.config.StageFactory;

/** {@code type: project} — kaydı seçilen alanlara indirger. Parametre: fields (liste). */
public final class ProjectStageFactory implements StageFactory {

    @Override
    public String type() {
        return "project";
    }

    @Override
    public Class<?> inputType() {
        return LogRecord.class;
    }

    @Override
    public Class<?> outputType() {
        return ProjectedRecord.class;
    }

    @Override
    public String example() {
        return "{ fields: [timestamp, clientIp, status, path] }";
    }

    @Override
    public Stage<?, ?> create(Params params) {
        params.allowOnly("fields");
        try {
            return new ProjectionStage(params.requireStringList("fields"));
        } catch (IllegalArgumentException e) {
            throw params.fail(e.getMessage());
        }
    }
}
