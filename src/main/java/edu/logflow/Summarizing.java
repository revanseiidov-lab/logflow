package edu.logflow;

/**
 * Çalışma sonunda kullanıcıya gösterilecek bir özet satırı sunan aşama (ör. atlanan satır sayısı).
 * Yapılandırmadan kurulan hatta aşamaları koda sabitlenmediği için, yükleyici bu arayüzü uygulayan
 * aşamaları toplar ve uygulama özetleri çalışmadan sonra yazdırır.
 */
public interface Summarizing {

    String summary();
}
