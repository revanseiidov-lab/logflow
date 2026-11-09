package edu.logflow.error;

import edu.logflow.Pipeline;
import edu.logflow.Sink;

import java.io.IOException;

/**
 * Hattı çalıştırır ve sonucu {@link RunSummary}'e çevirir: dead-letter hedefini açar/kapatır, denetimli durdurmayı
 * ({@link AbortRun}) ve kurtarılamaz hatayı ayırır, sonunda mutabakatı denetler. Dışarıya istisna sızdırmaz.
 */
public final class ManagedRun {

    private final Pipeline pipeline;
    private final ErrorRouter router;
    private final Sink<FailedRecord> deadLetter;

    public ManagedRun(Pipeline pipeline, ErrorRouter router, Sink<FailedRecord> deadLetter) {
        this.pipeline = pipeline;
        this.router = router;
        this.deadLetter = deadLetter;
    }

    public RunSummary run() {
        RunSummary summary = router.summary();
        boolean opened = false;
        try {
            if (deadLetter != null) {
                deadLetter.open();
                opened = true;
            }
            pipeline.run();
            summary.reconcile();
        } catch (AbortRun e) {
            summary.markAborted(e.getMessage());
        } catch (PipelineException e) {
            summary.markFailed(e.getMessage());
        } catch (IOException | RuntimeException e) {
            summary.markFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (opened) {
                try {
                    deadLetter.close();
                } catch (IOException e) {
                    summary.markFailed("dead-letter kapatilamadi: " + e.getMessage());
                }
            }
        }
        return summary;
    }
}
