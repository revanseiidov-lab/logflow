package edu.logflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Projeksiyondan çıkan, yalnızca seçilen alanları taşıyan kayıt (alan adı -> metin değeri).
 * Sıra korunur; değişmezdir.
 */
public record ProjectedRecord(Map<String, String> values) {

    public ProjectedRecord {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public String get(String field) {
        return values.get(field);
    }

    /** Tek satır: {@code alan=değer} çiftleri sekme (tab) ile ayrılır. */
    public String toLine() {
        return values.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\t"));
    }
}
