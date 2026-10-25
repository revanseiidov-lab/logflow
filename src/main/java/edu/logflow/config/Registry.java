package edu.logflow.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Tür adı -> fabrika eşlemesi. Kayıt <b>veri odaklıdır</b>: sabit kodlanmış bir switch yoktur.
 * Fabrikalar {@link ServiceLoader} ile {@code META-INF/services/} dosyalarından keşfedilir; testler ya da
 * uygulamalar ayrıca {@link #register} ile fabrika ekleyebilir.
 *
 * @param <F> fabrika tipi (StageFactory, SourceFactory, SinkFactory)
 */
public class Registry<F> {

    private final String kind;
    private final Function<F, String> nameOf;
    private final Map<String, F> byName = new TreeMap<>();

    /** @param kind hata mesajlarında kullanılan tür adı: "asama (stage)", "kaynak (source)", ... */
    protected Registry(String kind, Function<F, String> nameOf) {
        this.kind = kind;
        this.nameOf = nameOf;
    }

    protected static <F, R extends Registry<F>> R discover(Class<F> service, R registry) {
        for (F factory : ServiceLoader.load(service)) {
            registry.register(factory);
        }
        return registry;
    }

    /** Aynı ad ikinci kez kaydedilirse hata verir (sessizce ezmez). */
    public void register(F factory) {
        String name = nameOf.apply(factory);
        if (byName.containsKey(name)) {
            throw new IllegalStateException(kind + " turu '" + name + "' iki kez kayitli: "
                    + byName.get(name).getClass().getName() + " ve " + factory.getClass().getName());
        }
        byName.put(name, factory);
    }

    public java.util.Set<String> types() {
        return byName.keySet();
    }

    public List<F> all() {
        return new ArrayList<>(byName.values());
    }

    /** @param where hata mesajının başında gösterilen yer, ör. {@code stages[1]} */
    public F get(String type, String where) {
        F factory = byName.get(type);
        if (factory != null) {
            return factory;
        }
        if (byName.isEmpty()) {
            throw new ConfigException(where + ": hic " + kind + " turu kayitli degil. META-INF/services "
                    + "dosyalari siniflar yoluna eklenmemis olabilir (IDE'de Maven projesini yenileyin).");
        }
        String message = where + ": bilinmeyen " + kind + " turu '" + type + "'.";
        String suggestion = closest(type);
        if (suggestion != null) {
            message += " Bunu mu demek istediniz: '" + suggestion + "'?";
        }
        throw new ConfigException(message + " Kullanilabilir turler: " + String.join(", ", byName.keySet()));
    }

    private String closest(String type) {
        String best = null;
        int bestDistance = 3;
        for (String candidate : byName.keySet()) {
            int d = distance(type, candidate);
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return best;
    }

    /** Levenshtein uzaklığı (yazım hatası önerisi için). */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = cur;
        }
        return prev[b.length()];
    }
}
