package edu.logflow;

import java.time.Instant;
import java.util.Map;

/**
 * Alan modeli (domain model): ayrıştırılmış tek bir günlük satırı. Değişmezdir (immutable).
 *
 * <p><b>attributes neden var?</b> Kayıt biçimi genişletmeye açık olmalıdır. Sonraki aşamalar
 * (5.-7. haftalar: zenginleştirme, etiketleme, sınıflandırma) kayda bugünkü ayrıştırıcının hiçbir şey
 * bilmediği veriler ekleyecektir (ör. coğrafi konum, oturum kimliği, "bot" etiketi). Her yeni veri için
 * bu record'a yeni bir alan eklemek, onu kullanan her aşamayı ve testi etkilerdi. Bunun yerine ortak
 * alanlar sabit ve tipli kalır; açık uçlu veriler {@code attributes} içinde taşınır. Ayrıştırıcı bu
 * eşlemeyi yalnızca ihtiyaç duyduğu kadar doldurur (şimdilik yalnızca sorgu dizesi).
 *
 * <p>Eşleme kurucuda kopyalanıp değiştirilemez hale getirilir; böylece record gerçekten değişmez kalır.
 *
 * @param timestamp  isteğin zamanı (UTC anı)
 * @param clientIp   istemci adresi
 * @param method     HTTP yöntemi (GET, POST, ...)
 * @param path       istek yolu, sorgu dizesi hariç
 * @param status     HTTP durum kodu (100-599)
 * @param bytes      yanıt gövdesi boyutu; CLF'deki "-" değeri 0 olarak okunur
 * @param userAgent  istemci tanımı; satırda yoksa boş dizgi
 * @param attributes genişleme noktası (değiştirilemez)
 * @param raw        orijinal satır (hata ayıklama ve izlenebilirlik için)
 */
public record LogRecord(
        Instant timestamp, String clientIp, String method,
        String path, int status, long bytes, String userAgent,
        Map<String, String> attributes,
        String raw) {

    public LogRecord {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
