package edu.logflow.error;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bir çalıştırmanın <b>mutabakat</b> (reconciliation) özeti. Her okunan kaydın akıbeti tam olarak bir sayaca yazılır:
 * <pre>read = emitted + filtered + skipped + deadLettered + aborted</pre>
 * Eşitlik bozulursa kayıt <b>sessizce kaybolmuştur</b>: durum {@code MISMATCH}, çıkış kodu 3.
 */
public final class RunSummary {

    public enum Status { OK, ABORTED, FAILED, MISMATCH }

    private long read;
    private long emitted;
    private long filtered;
    private long skipped;
    private long deadLettered;
    private long aborted;
    private Status status = Status.OK;
    private String failure;
    private final Map<ErrorType, Long> byType = new EnumMap<>(ErrorType.class);
    private final Map<String, Long> byStage = new TreeMap<>();

    public void read() { read++; }
    public void emitted() { emitted++; }
    public void filtered() { filtered++; }

    void skipped(String stage, ErrorType type) { skipped++; count(stage, type); }
    void deadLettered(String stage, ErrorType type) { deadLettered++; count(stage, type); }
    void aborted(String stage, ErrorType type) { aborted++; count(stage, type); }

    private void count(String stage, ErrorType type) {
        byType.merge(type, 1L, Long::sum);
        byStage.merge(stage, 1L, Long::sum);
    }

    void markAborted(String reason) { status = Status.ABORTED; failure = reason; }
    void markFailed(String reason) { status = Status.FAILED; failure = reason; }

    /** Mutabakatı denetler; yalnızca normal biten çalışmada anlamlıdır. */
    void reconcile() {
        if (status == Status.OK && !balanced()) {
            status = Status.MISMATCH;
            failure = "kayit kaybi: okunan " + read + " != hesaplanan " + accounted();
        }
    }

    public long accounted() { return emitted + filtered + skipped + deadLettered + aborted; }
    public boolean balanced() { return read == accounted(); }

    public long readCount() { return read; }
    public long emittedCount() { return emitted; }
    public long filteredCount() { return filtered; }
    public long skippedCount() { return skipped; }
    public long deadLetteredCount() { return deadLettered; }
    public long abortedCount() { return aborted; }
    public Status status() { return status; }
    public String failure() { return failure; }
    public Map<ErrorType, Long> byType() { return Map.copyOf(byType); }
    public Map<String, Long> byStage() { return Map.copyOf(byStage); }

    /**
     * 0 başarılı; 1 çalışma tamamlanmadı (fail-fast durdurması ya da G/Ç hatası; v4'ten beri "çalışma hatası");
     * 3 kayıp tespit edildi. (2 yapılandırma/argüman hatasına ayrılmıştır ve çalışma başlamadan döner.)
     */
    public int exitCode() {
        return switch (status) {
            case OK -> 0;
            case ABORTED, FAILED -> 1;
            case MISMATCH -> 3;
        };
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("Calisma ozeti [").append(status).append("]\n");
        sb.append("  okunan          : ").append(read).append('\n');
        sb.append("  yazilan (sink)  : ").append(emitted).append('\n');
        sb.append("  filtrelenen     : ").append(filtered).append('\n');
        sb.append("  atlanan (skip)  : ").append(skipped).append('\n');
        sb.append("  dead-letter     : ").append(deadLettered).append('\n');
        sb.append("  durduran (abort): ").append(aborted).append('\n');
        sb.append("  mutabakat       : ").append(balanced() ? "TAMAM" : "UYUSMUYOR")
          .append(" (okunan ").append(read).append(" / hesaplanan ").append(accounted()).append(")\n");
        if (!byType.isEmpty()) {
            sb.append("  hata turleri    : ");
            byType.forEach((t, n) -> sb.append(t).append('=').append(n).append(' '));
            sb.append('\n');
        }
        if (!byStage.isEmpty()) {
            sb.append("  asamalara gore  : ");
            byStage.forEach((s, n) -> sb.append(s).append('=').append(n).append(' '));
            sb.append('\n');
        }
        if (failure != null) {
            sb.append("  neden           : ").append(failure).append('\n');
        }
        return sb.toString();
    }
}
