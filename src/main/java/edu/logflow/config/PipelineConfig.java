package edu.logflow.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Yapılandırma dosyasının <b>yapısal</b> modeli: pipeline.name / source / stages / sink.
 * Burada yalnızca yapı doğrulanır (anahtarlar, tipler); "filter" türünün hangi parametreleri aldığı
 * ilgili fabrikanın işidir. Bu ayrım, yeni aşama türünün şemayı değiştirmeden eklenebilmesini sağlar.
 */
public record PipelineConfig(String name, Component source, List<Component> stages, Component sink,
                             String deadLetterPath) {

    /**
     * type + params çifti; v5'te isteğe bağlı hata politikası ({@code on-error}, {@code fallback}) metin olarak taşınır.
     * Politikanın geçerliliği ve hangi bileşende anlamlı olduğu yükleyicide denetlenir.
     */
    public record Component(String type, Map<String, Object> params, String onError, String fallback) {
        public Component(String type, Map<String, Object> params) {
            this(type, params, null, null);
        }
    }

    private static final Set<String> PIPELINE_KEYS = Set.of("name", "source", "stages", "sink", "errors");
    private static final Set<String> COMPONENT_KEYS = Set.of("type", "params", "on-error", "fallback");

    /** @param root MiniYaml çıktısı; @param defaultName ad verilmediyse kullanılacak ad */
    public static PipelineConfig from(Object root, String defaultName) {
        if (root == null) {
            throw new ConfigException("yapilandirma bos. Beklenen yapi: pipeline: { source, stages, sink }");
        }
        Map<String, Object> top = asMap(root, "(dosya kokunde)");
        if (!top.containsKey("pipeline")) {
            throw new ConfigException("'pipeline:' anahtari bulunamadi (dosya kokunde bulundu: "
                    + String.join(", ", top.keySet()) + ")");
        }
        Map<String, Object> p = asMap(top.get("pipeline"), "pipeline");
        for (String key : p.keySet()) {
            if (!PIPELINE_KEYS.contains(key)) {
                throw new ConfigException("pipeline: bilinmeyen anahtar '" + key + "' (gecerli: name, source, stages, sink, errors)");
            }
        }

        Object nameValue = p.get("name");
        if (nameValue != null && !(nameValue instanceof String)) {
            throw new ConfigException("pipeline.name metin olmali, bulundu: " + Params.describe(nameValue));
        }
        String name = nameValue == null ? defaultName : (String) nameValue;

        Component source = component(p.get("source"), "pipeline.source", "source");
        Component sink = component(p.get("sink"), "pipeline.sink", "sink");

        Object stagesValue = p.get("stages");
        if (stagesValue == null) {
            throw new ConfigException("pipeline.stages eksik: en az bir asama gerekir. Ornek:\n"
                    + "  stages:\n    - type: parse");
        }
        if (!(stagesValue instanceof List<?> list)) {
            throw new ConfigException("pipeline.stages liste olmali ('- type: ...' satirlari), bulundu: "
                    + Params.describe(stagesValue));
        }
        if (list.isEmpty()) {
            throw new ConfigException("pipeline.stages bos: en az bir asama gerekir. Ornek:\n"
                    + "  stages:\n    - type: parse");
        }
        List<Component> stages = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            stages.add(component(list.get(i), "pipeline.stages[" + i + "]", "stage"));
        }
        return new PipelineConfig(name, source, List.copyOf(stages), sink, deadLetterPath(p.get("errors")));
    }

    /** {@code errors: { dead-letter: { path: ... } }}: ikincil çıktı. Yoksa dead-letter kullanılamaz. */
    private static String deadLetterPath(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> errors = asMap(value, "pipeline.errors");
        for (String key : errors.keySet()) {
            if (!key.equals("dead-letter")) {
                throw new ConfigException("pipeline.errors: bilinmeyen anahtar '" + key + "' (gecerli: dead-letter)");
            }
        }
        Object dl = errors.get("dead-letter");
        if (dl == null) {
            return null;
        }
        Map<String, Object> m = asMap(dl, "pipeline.errors.dead-letter");
        for (String key : m.keySet()) {
            if (!key.equals("path")) {
                throw new ConfigException("pipeline.errors.dead-letter: bilinmeyen anahtar '" + key + "' (gecerli: path)");
            }
        }
        Object path = m.get("path");
        if (!(path instanceof String text) || text.isBlank()) {
            throw new ConfigException("pipeline.errors.dead-letter.path metin olmali (ornek: "
                    + "errors: { dead-letter: { path: out/rejected.jsonl } }), bulundu: " + Params.describe(path));
        }
        return text;
    }

    private static String optText(Map<String, Object> m, String key, String path) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String t) || t.isBlank()) {
            throw new ConfigException(path + "." + key + " metin olmali (ornek: skip, dead-letter, fail-fast, retry(3)), bulundu: "
                    + Params.describe(v));
        }
        return t;
    }

    private static Component component(Object value, String path, String kind) {
        if (value == null) {
            throw new ConfigException(path + " eksik. Ornek: " + kind + ": { type: ..., params: { ... } }");
        }
        Map<String, Object> m = asMap(value, path);
        for (String key : m.keySet()) {
            if (!COMPONENT_KEYS.contains(key)) {
                throw new ConfigException(path + ": bilinmeyen anahtar '" + key + "' (gecerli: type, params, on-error, fallback)");
            }
        }
        Object type = m.get("type");
        if (type == null) {
            throw new ConfigException(path + ": zorunlu anahtar eksik: 'type'");
        }
        if (!(type instanceof String t) || t.isBlank()) {
            throw new ConfigException(path + ".type metin olmali, bulundu: " + Params.describe(type));
        }
        Object params = m.get("params");
        Map<String, Object> paramMap = new LinkedHashMap<>();
        if (params != null) {
            paramMap.putAll(asMap(params, path + ".params"));
        }
        return new Component(t, paramMap, optText(m, "on-error", path), optText(m, "fallback", path));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String path) {
        if (!(value instanceof Map<?, ?>)) {
            throw new ConfigException(path + " bir eslem ('anahtar: deger') olmali, bulundu: " + Params.describe(value));
        }
        return (Map<String, Object>) value;
    }
}
