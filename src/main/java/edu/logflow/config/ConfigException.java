package edu.logflow.config;

/**
 * Yapılandırma zamanı hatası: dosya bozuk, aşama türü bilinmiyor, parametre eksik ya da yanlış tipte.
 * Hiçbir veri işlenmeden ÖNCE, yükleme sırasında fırlatılır (fail fast, fail loudly).
 *
 * <p>Çalışma zamanı veri hatalarından (bozuk günlük satırı gibi) farklıdır: onlar 5. haftanın
 * konusudur ve bu istisna ile ifade edilmez.
 */
public final class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }
}
