package edu.logflow;

import java.io.IOException;
import java.nio.file.Path;

/** Giriş noktası: logflow &lt;günlük-dosyası&gt; */
public final class Main {

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Kullanim: logflow <gunluk-dosyasi>");
            System.exit(2);
        }
        Pipeline.from(new LineSource(Path.of(args[0])))
                .to(new ConsoleSink(System.out))
                .run();
    }
}
