package edu.logflow.error;

import edu.logflow.Emitter;
import edu.logflow.Stage;

/**
 * Bir aşamayı hata politikasıyla sarar (dekoratör); {@code Stage} arayüzü değişmez.
 *
 * <ul>
 *   <li>{@link StageException} → politika uygulanır; kayıt hattan çıkar.</li>
 *   <li>Beklenmeyen {@link RuntimeException} → {@code UNEXPECTED} olarak aynı yoldan geçer (çökme yok).</li>
 *   <li>Alt akıştan ({@code out.emit}) gelen hatalar bu aşamaya <b>yazılmaz</b>; olduğu gibi yukarı çıkar.</li>
 * </ul>
 * Kural: aşama, bir kaydı yaymadan <b>önce</b> fırlatmalıdır (yarım çıktı olmaz).
 * {@code filter=true} ise hiçbir şey yaymayan kayıt "filtrelenen" sayılır; aksi hâlde sessiz kayıp
 * mutabakatta yakalanır.
 */
public final class GuardedStage<I, O> implements Stage<I, O> {

    /** Alt akış hatasını kendi aşama hatamızdan ayırmak için taşıyıcı. */
    private static final class Downstream extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Downstream(RuntimeException cause) {
            super(null, cause, false, false);
        }
    }

    private final String label;
    private final Stage<I, O> delegate;
    private final ErrorPolicy policy;
    private final ErrorRouter router;
    private final boolean filter;

    public GuardedStage(String label, Stage<I, O> delegate, ErrorPolicy policy, ErrorRouter router, boolean filter) {
        this.label = label;
        this.delegate = delegate;
        this.policy = policy;
        this.router = router;
        this.filter = filter;
    }

    @Override
    public void process(I input, Emitter<O> out) {
        int[] emitted = {0};
        Emitter<O> counting = item -> {
            emitted[0]++;
            try {
                out.emit(item);
            } catch (RuntimeException e) {
                throw new Downstream(e);
            }
        };
        try {
            delegate.process(input, counting);
        } catch (Downstream d) {
            throw (RuntimeException) d.getCause();
        } catch (AbortRun | PipelineException e) {
            throw e;
        } catch (StageException e) {
            router.handle(label, policy, e);
            return;
        } catch (RuntimeException e) {
            router.handle(label, policy, new StageException(ErrorType.UNEXPECTED,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), e));
            return;
        }
        if (filter && emitted[0] == 0) {
            router.summary().filtered();
        }
    }
}
