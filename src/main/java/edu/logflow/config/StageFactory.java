package edu.logflow.config;

import edu.logflow.Stage;

/**
 * Bir aşama türünün (ör. "filter") adını, giriş/çıkış tiplerini ve nasıl kurulacağını bildirir.
 * Yeni bir aşama türü eklemek = bir {@code StageFactory} yazmak ve
 * {@code META-INF/services/edu.logflow.config.StageFactory} dosyasına bir satır eklemek;
 * başka hiçbir mevcut dosya değişmez.
 */
public interface StageFactory {

    /** Yapılandırmadaki {@code type:} değeri. */
    String type();

    /** Aşamanın aldığı öğe tipi (yükleme sırasında bir önceki adımın çıktısıyla karşılaştırılır). */
    Class<?> inputType();

    /** Aşamanın yaydığı öğe tipi. */
    Class<?> outputType();

    /** Hata mesajlarında gösterilen örnek {@code params} değeri; yoksa null. */
    default String example() {
        return null;
    }

    /**
     * Bu aşama kayıt <b>eler</b> mi (filtre)? Evet ise hiçbir şey yaymayan kayıt "filtrelenen" sayılır; hayır ise
     * hiçbir şey yaymak sessiz kayıptır ve çalışma sonunda mutabakat bunu yakalar.
     */
    default boolean filtering() {
        return false;
    }

    /** Parametreleri doğrular ve aşamayı kurar. Geçersizse {@link ConfigException} fırlatır. */
    Stage<?, ?> create(Params params);
}
