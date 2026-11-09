package edu.logflow;

/**
 * Dosyadan okunmuş, <b>henüz çözülmemiş</b> bir satır: baytlar ve satır numarası.
 * Kodlama kararı bir sonraki aşamaya ({@code decode}) bırakılır; böylece geçersiz bir satır
 * tüm çalışmayı çökertmek yerine yalnızca kendi kaydını başarısız kılar.
 *
 * @param number   satır numarası (1'den başlar; boş satırlar da sayılır)
 * @param bytes    satırın baytları (sondaki \n ve \r hariç). {@code oversize} ise yalnızca ilk {@link #PREFIX} bayt
 * @param oversize satır izin verilen en büyük boyutu aştı mı
 * @param length   satırın gerçek bayt uzunluğu (kesilmiş olsa bile)
 */
public record RawLine(long number, byte[] bytes, boolean oversize, long length) {

    /** Boyut sınırı aşıldığında kanıt olarak saklanan ön ek (bayt). */
    public static final int PREFIX = 1024;
}
