package edu.logflow.error;

/**
 * Hata türleri sözlüğü: dead-letter kaydında ve özetlerde görünen kararlı adlar.
 * Ad, hatanın <b>nerede</b> ve <b>neden</b> oluştuğunu söyler (READ_*, PARSE_*, VALIDATION_*, SINK_*).
 */
public enum ErrorType {
    /** Satır geçerli UTF-8 değil (sessizce bozulmak yerine reddedilir). */
    READ_ENCODING,
    /** Satır, izin verilen en büyük boyuttan uzun. */
    READ_OVERSIZE,
    /** Satır günlük biçimine uymuyor (kesik satır, eksik alan, boş satır...). */
    PARSE_MALFORMED,
    /** Zaman damgası okunamıyor (ör. 31/Foo/2023). */
    PARSE_TIMESTAMP,
    /** Sayı alanı (durum kodu, bayt) sayı değil. */
    PARSE_NUMBER,
    /** Zorunlu alan boş ya da "-". */
    VALIDATION_REQUIRED,
    /** İstemci adresi geçerli bir IPv4/IPv6 değil. */
    VALIDATION_IP,
    /** Sayısal alan izin verilen aralığın dışında (ör. negatif bayt). */
    VALIDATION_RANGE,
    /** Zaman damgası makul aralığın dışında (ör. 1970, 2099). */
    VALIDATION_TIMESTAMP,
    /** Ana çıktıya yazılamadı. */
    SINK_IO,
    /** Aşamada beklenmeyen bir istisna (hata/bug). Kayıt düzeyinde ele alınır, çökme olmaz. */
    UNEXPECTED
}
