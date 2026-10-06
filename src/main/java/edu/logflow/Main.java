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
        ParserStage parser = new ParserStage();
        Pipeline.from(new LineSource(Path.of(args[0])))
                .then(parser)
                .to(new ConsoleSink(System.out))
                .run();
        System.out.println("Hatali (atlanan) satir sayisi: " + parser.malformedCount());
    }
}
