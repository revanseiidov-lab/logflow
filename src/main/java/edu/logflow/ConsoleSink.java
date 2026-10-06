package edu.logflow;

import java.io.PrintStream;

/** v1: gelen dizgiyi (string) olduğu gibi konsola yazar. */
public final class ConsoleSink implements Sink<String> {

    private final PrintStream out;

    public ConsoleSink(PrintStream out) {
        this.out = out;
    }

    @Override
    public void accept(String line) {
        out.println(line);
    }
}
