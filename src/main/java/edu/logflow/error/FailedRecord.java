package edu.logflow.error;

import java.time.Instant;

/**
 * Dead-letter kanalına giden kayıt: neyin, nerede, neden ve ne zaman başarısız olduğu.
 *
 * @param timestamp    hatanın oluştuğu an
 * @param stage        başarısız olan aşamanın etiketi (ör. {@code parse})
 * @param errorType    hata türü
 * @param message      insan okuyabilir neden
 * @param line         girdi dosyasındaki satır numarası (1'den başlar)
 * @param rawInput     özgün satır (en çok 1024 karakter; geçersiz baytlar için kayıplı gösterim)
 * @param rawTruncated {@code rawInput} kesildi mi (ör. 100 KiB'lık satır)
 * @param rawBase64    geçersiz UTF-8 için özgün baytların Base64'ü (kanıt bozulmasın); yoksa {@code null}
 */
public record FailedRecord(Instant timestamp, String stage, ErrorType errorType, String message,
                           long line, String rawInput, boolean rawTruncated, String rawBase64) {
}
