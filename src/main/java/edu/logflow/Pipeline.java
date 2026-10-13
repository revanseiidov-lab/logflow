package edu.logflow;

import java.io.IOException;

/**
 * Kurulmuş, çalıştırılmaya hazır bir işlem hattı. Kurmak için {@link PipelineBuilder} kullanılır:
 * <pre>
 *   Pipeline p = PipelineBuilder.from(source).via(stageA).via(stageB).to(sink);
 *   p.run();
 * </pre>
 */
public final class Pipeline {

    @FunctionalInterface
    interface Runner {
        void run() throws IOException;
    }

    private final Runner runner;

    Pipeline(Runner runner) {
        this.runner = runner;
    }

    public void run() throws IOException {
        runner.run();
    }
}
