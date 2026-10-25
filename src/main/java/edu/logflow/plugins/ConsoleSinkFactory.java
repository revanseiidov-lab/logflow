package edu.logflow.plugins;

import edu.logflow.ProjectedRecord;
import edu.logflow.Sink;
import edu.logflow.config.Params;
import edu.logflow.config.SinkFactory;
import java.io.PrintStream;

/** {@code type: console} — projeksiyonlu kayıtları ekrana yazar (dosya sink'iyle aynı satır biçimi). Parametre yok. */
public final class ConsoleSinkFactory implements SinkFactory {

    private final PrintStream target;

    /** ServiceLoader için: çıktı, kurulum anındaki {@code System.out}. */
    public ConsoleSinkFactory() {
        this(null);
    }

    public ConsoleSinkFactory(PrintStream target) {
        this.target = target;
    }

    @Override
    public String type() {
        return "console";
    }

    @Override
    public Class<?> inputType() {
        return ProjectedRecord.class;
    }

    @Override
    public Sink<?> create(Params params) {
        params.allowOnly();
        PrintStream out = target != null ? target : System.out;
        Sink<ProjectedRecord> sink = record -> out.println(record.toLine());
        return sink;
    }
}
