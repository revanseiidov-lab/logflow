package edu.logflow.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bir bileşenin (kaynak, aşama, sink) {@code params} bölümü; tip denetimli erişim sağlar.
 * Her hata, bileşeni ve (varsa) örnek kullanımı içeren açık bir {@link ConfigException} olur:
 * <pre>
 *   stages[1] ('filter'): zorunlu parametre eksik: 'field'. Ornek: params: { field: status, ... }
 * </pre>
 */
public final class Params {

    private final String context;
    private final String example;
    private final Map<String, Object> values;

    public Params(String context, String example, Map<String, Object> values) {
        this.context = context;
        this.example = example;
        this.values = values == null ? Map.of() : new LinkedHashMap<>(values);
    }

    public boolean has(String name) {
        return values.get(name) != null;
    }

    /** Ham değer; yoksa ya da null ise "zorunlu parametre eksik" hatası. */
    public Object require(String name) {
        Object v = values.get(name);
        if (v == null) {
            throw fail("zorunlu parametre eksik: '" + name + "'", true);
        }
        return v;
    }

    public String requireString(String name) {
        Object v = require(name);
        if (!(v instanceof String s)) {
            throw typeError(name, "metin (string)", v);
        }
        if (s.isBlank()) {
            throw fail("'" + name + "' parametresi bos olamaz", true);
        }
        return s;
    }

    public String optString(String name, String defaultValue) {
        return has(name) ? requireString(name) : defaultValue;
    }

    public int requireInt(String name) {
        Object v = require(name);
        if (v instanceof Integer i) {
            return i;
        }
        throw typeError(name, "tam sayi", v);
    }

    /** İsteğe bağlı tam sayı; yoksa varsayılan. */
    public int optInt(String name, int defaultValue) {
        return has(name) ? requireInt(name) : defaultValue;
    }

    /** İsteğe bağlı mantıksal değer ({@code true}/{@code false}); yoksa varsayılan. */
    public boolean optBoolean(String name, boolean defaultValue) {
        if (!has(name)) {
            return defaultValue;
        }
        Object v = require(name);
        if (v instanceof Boolean b) {
            return b;
        }
        throw typeError(name, "mantiksal deger (true/false)", v);
    }

    /** İsteğe bağlı metin listesi; yoksa boş liste. */
    public List<String> optStringList(String name) {
        return has(name) ? requireStringList(name) : List.of();
    }

    /** İsteğe bağlı tam sayı; yoksa null. */
    public Integer optIntOrNull(String name) {
        return has(name) ? requireInt(name) : null;
    }

    /** Boş olmayan metin listesi: {@code [a, b]}. */
    public List<String> requireStringList(String name) {
        Object v = require(name);
        if (!(v instanceof List<?> list)) {
            throw typeError(name, "liste (ornek: [a, b])", v);
        }
        if (list.isEmpty()) {
            throw fail("'" + name + "' listesi bos olamaz", true);
        }
        for (Object item : list) {
            if (!(item instanceof String)) {
                throw fail("'" + name + "' listesinin ogeleri metin olmali, bulundu: " + describe(item), true);
            }
        }
        return list.stream().map(String.class::cast).toList();
    }

    /** İzin verilen dışında bir parametre varsa hata verir (yazım hatalarını yakalar). */
    public void allowOnly(String... allowed) {
        Set<String> ok = new HashSet<>(Arrays.asList(allowed));
        for (String key : values.keySet()) {
            if (!ok.contains(key)) {
                throw fail("bilinmeyen parametre: '" + key + "' (gecerli: "
                        + (allowed.length == 0 ? "yok" : String.join(", ", allowed)) + ")", false);
            }
        }
    }

    public ConfigException typeError(String name, String expected, Object actual) {
        return fail("'" + name + "' parametresi " + expected + " olmali, bulundu: " + describe(actual), true);
    }

    /** Hata iletisi + örnek kullanım (parametre biçimiyle ilgili hatalar için). */
    public ConfigException fail(String message) {
        return fail(message, true);
    }

    /** Hata iletisi, örnek olmadan (ortam hataları için: dosya yok gibi). */
    public ConfigException failWithoutExample(String message) {
        return fail(message, false);
    }

    private ConfigException fail(String message, boolean withExample) {
        String hint = withExample && example != null ? ". Ornek: params: " + example : "";
        return new ConfigException(context + ": " + message + hint);
    }

    /** Değerin türünü insan diliyle anlatır: {@code metin "abc"}, {@code tam sayi 5}, ... */
    public static String describe(Object v) {
        if (v == null) {
            return "bos (null)";
        }
        if (v instanceof String s) {
            return "metin \"" + s + "\"";
        }
        if (v instanceof Integer || v instanceof Long) {
            return "tam sayi " + v;
        }
        if (v instanceof Double) {
            return "ondalik sayi " + v;
        }
        if (v instanceof Boolean) {
            return "mantiksal deger " + v;
        }
        if (v instanceof List) {
            return "liste " + v;
        }
        return "nesne " + v;
    }
}
