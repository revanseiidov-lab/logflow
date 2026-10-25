package edu.logflow.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bağımlılıksız, küçük bir YAML ayrıştırıcısı. YAML'ın yalnızca yapılandırma dosyalarımızın
 * kullandığı alt kümesini destekler:
 * <ul>
 *   <li>blok eşlemeleri ({@code anahtar: değer}) ve blok listeleri ({@code - öğe}), boşlukla girintili;</li>
 *   <li>akış (flow) biçimi: {@code { a: 1, b: [x, y] }} ve {@code [x, y]};</li>
 *   <li>skalerler: tırnaklı metin, tam sayı, ondalık, true/false, null/~, düz metin;</li>
 *   <li>{@code #} yorumları ve açılış {@code ---}.</li>
 * </ul>
 * Desteklenmeyenler (çok satırlı skaler | >, çapa/etiket, TAB girinti) <b>sessizce yanlış
 * okunmak yerine</b> satır numarasıyla hata verir. Sonuç: Map (sıra korunur), List, String, Integer,
 * Long, Double, Boolean ya da null.
 */
public final class MiniYaml {

    private record Line(int no, int indent, String text) {
    }

    private static final Pattern INTEGER = Pattern.compile("-?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("-?\\d+\\.\\d+");

    private final List<Line> lines = new ArrayList<>();
    private int pos;

    private MiniYaml() {
    }

    /** Metni ayrıştırır. Yalnızca yorum/boşluk içeren girdi için null döner. */
    public static Object parse(String text) {
        MiniYaml y = new MiniYaml();
        y.tokenize(text);
        if (y.lines.isEmpty()) {
            return null;
        }
        Object root = y.parseBlock(y.lines.get(0).indent());
        if (y.pos < y.lines.size()) {
            Line l = y.lines.get(y.pos);
            throw error(l.no(), "beklenmeyen girinti: '" + l.text() + "'");
        }
        return root;
    }

    // ---- satırlara ayırma ------------------------------------------------------------------

    private void tokenize(String text) {
        String[] raw = text.split("\n", -1);
        for (int i = 0; i < raw.length; i++) {
            String line = raw[i].replace("\r", "");
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            if (indent < line.length() && line.charAt(indent) == '\t') {
                throw error(i + 1, "girintide TAB kullanilamaz, bosluk kullanin");
            }
            String body = stripComment(line.substring(indent)).stripTrailing();
            if (body.isEmpty() || (body.equals("---") && lines.isEmpty())) {
                continue;
            }
            lines.add(new Line(i + 1, indent, body));
        }
    }

    /** Tırnak dışında, başında boşluk olan '#' işaretinden sonrasını atar. */
    private static String stripComment(String s) {
        char quote = 0;
        boolean canOpen = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if ((c == '"' || c == '\'') && canOpen) {
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))) {
                return s.substring(0, i);
            }
            if (!Character.isWhitespace(c)) {
                canOpen = c == ':' || c == '[' || c == '{' || c == ',' || c == '-';
            }
        }
        return s;
    }

    // ---- blok yapıları ---------------------------------------------------------------------

    private static boolean isSeqItem(String text) {
        return text.equals("-") || text.startsWith("- ");
    }

    private Object parseBlock(int indent) {
        return isSeqItem(lines.get(pos).text()) ? parseSequence(indent) : parseMapping(indent);
    }

    private Map<String, Object> parseMapping(int indent) {
        Map<String, Object> map = new LinkedHashMap<>();
        while (pos < lines.size()) {
            Line l = lines.get(pos);
            if (l.indent() < indent) {
                break;
            }
            if (l.indent() > indent) {
                throw error(l.no(), "beklenmeyen girinti: '" + l.text() + "'");
            }
            if (isSeqItem(l.text())) {
                throw error(l.no(), "beklenmeyen liste ogesi '-' (once bir 'anahtar:' gerekir)");
            }
            int colon = findMappingColon(l.text());
            if (colon < 0) {
                throw error(l.no(), "'anahtar: deger' bekleniyordu, bulundu: '" + l.text() + "'");
            }
            String key = unquoteKey(l.text().substring(0, colon).strip(), l.no());
            String valueText = l.text().substring(colon + 1).strip();
            if (map.containsKey(key)) {
                throw error(l.no(), "'" + key + "' anahtari iki kez tanimlanmis");
            }
            pos++;
            Object value;
            if (valueText.isEmpty()) {
                value = parseNested(indent);
            } else {
                value = parseInline(valueText, l.no());
            }
            map.put(key, value);
        }
        return map;
    }

    /** "anahtar:" satirindan sonraki girintili blok (eşleme ya da liste) ya da null. */
    private Object parseNested(int indent) {
        if (pos >= lines.size()) {
            return null;
        }
        Line next = lines.get(pos);
        if (next.indent() > indent) {
            return parseBlock(next.indent());
        }
        if (next.indent() == indent && isSeqItem(next.text())) {
            return parseSequence(indent);       // "anahtar:\n- öğe" biçimi
        }
        return null;
    }

    private List<Object> parseSequence(int indent) {
        List<Object> list = new ArrayList<>();
        while (pos < lines.size()) {
            Line l = lines.get(pos);
            if (l.indent() < indent || (l.indent() == indent && !isSeqItem(l.text()))) {
                break;
            }
            if (l.indent() > indent) {
                throw error(l.no(), "beklenmeyen girinti: '" + l.text() + "'");
            }
            String rest = l.text().substring(1).stripLeading();
            int column = indent + (l.text().length() - rest.length());
            if (rest.isEmpty()) {
                pos++;
                list.add(parseNested(indent));
            } else if (findMappingColon(rest) >= 0) {
                lines.set(pos, new Line(l.no(), column, rest));   // "- anahtar: değer": öğe bir eşleme
                list.add(parseMapping(column));
            } else {
                pos++;
                list.add(parseInline(rest, l.no()));
            }
        }
        return list;
    }

    /** İlk "anahtar:" iki noktasının indeksi (tırnak/akış dışında, ardından boşluk ya da satır sonu); yoksa -1. */
    private static int findMappingColon(String s) {
        if (s.startsWith("{") || s.startsWith("[")) {
            return -1;
        }
        char quote = 0;
        boolean canOpen = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if ((c == '"' || c == '\'') && canOpen) {
                quote = c;
            } else if (c == ':' && (i + 1 == s.length() || s.charAt(i + 1) == ' ')) {
                return i;
            }
            if (!Character.isWhitespace(c)) {
                canOpen = c == '-';
            }
        }
        return -1;
    }

    private static String unquoteKey(String key, int no) {
        if (key.isEmpty()) {
            throw error(no, "bos anahtar");
        }
        Object v = scalar(key, no);
        return String.valueOf(v);
    }

    // ---- satır içi (akış) değerler ---------------------------------------------------------

    private static Object parseInline(String text, int no) {
        if (text.equals("|") || text.equals(">") || text.startsWith("|-") || text.startsWith(">-")
                || text.startsWith("|+") || text.startsWith(">+")) {
            throw error(no, "cok satirli metin ('|' ve '>') desteklenmiyor; degeri tek satirda ve tirnak icinde yazin");
        }
        Flow f = new Flow(text, no);
        Object v = f.value(false);
        f.skipWs();
        if (f.i < text.length()) {
            throw error(no, "beklenmeyen karakter '" + text.charAt(f.i) + "' (deger: " + text + ")");
        }
        return v;
    }

    private static final class Flow {
        final String s;
        final int no;
        int i;

        Flow(String s, int no) {
            this.s = s;
            this.no = no;
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object value(boolean inFlow) {
            skipWs();
            if (i >= s.length()) {
                throw error(no, "deger eksik: '" + s + "'");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return map();
            }
            if (c == '[') {
                return list();
            }
            if (c == '"' || c == '\'') {
                return scalar(quoted(), no);
            }
            int start = i;
            while (i < s.length() && (!inFlow || ",}]".indexOf(s.charAt(i)) < 0)) {
                i++;
            }
            return scalar(s.substring(start, i).strip(), no);
        }

        Map<String, Object> map() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;                                       // {
            skipWs();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                String key = key();
                skipWs();
                Object v = null;
                if (peek() == ':') {
                    i++;
                    skipWs();
                    v = (peek() == ',' || peek() == '}') ? null : value(true);
                } else {
                    throw error(no, "'{ anahtar: deger }' icinde ':' bekleniyordu: '" + s + "'");
                }
                if (m.containsKey(key)) {
                    throw error(no, "'" + key + "' anahtari iki kez tanimlanmis");
                }
                m.put(key, v);
                skipWs();
                char c = peek();
                i++;
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw error(no, "',' ya da '}' bekleniyordu: '" + s + "'");
                }
            }
        }

        String key() {
            skipWs();
            if (peek() == '"' || peek() == '\'') {
                return quoted();
            }
            int start = i;
            while (i < s.length() && s.charAt(i) != ':' && s.charAt(i) != ',' && s.charAt(i) != '}') {
                i++;
            }
            String k = s.substring(start, i).strip();
            if (k.isEmpty()) {
                throw error(no, "bos anahtar: '" + s + "'");
            }
            return k;
        }

        List<Object> list() {
            List<Object> l = new ArrayList<>();
            i++;                                       // [
            skipWs();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value(true));
                skipWs();
                char c = peek();
                i++;
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw error(no, "',' ya da ']' bekleniyordu: '" + s + "'");
                }
            }
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        /** Tırnaklı metni (tırnaklarıyla birlikte) okur ve tırnakları dahil ham dizgiyi döndürür. */
        String quoted() {
            char q = s.charAt(i);
            int start = i++;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (q == '"' && c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == q) {
                    if (q == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    i++;
                    return s.substring(start, i);
                }
                i++;
            }
            throw error(no, "tirnak kapatilmamis: " + s.substring(start));
        }
    }

    // ---- skalerler -------------------------------------------------------------------------

    private static Object scalar(String text, int no) {
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return unescapeDouble(text.substring(1, text.length() - 1), no);
        }
        if (text.length() >= 2 && text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\'') {
            return text.substring(1, text.length() - 1).replace("''", "'");
        }
        if (text.startsWith("\"") || text.startsWith("'")) {
            throw error(no, "tirnak kapatilmamis: " + text);
        }
        if (text.isEmpty() || text.equals("~") || text.equals("null")) {
            return null;
        }
        if (text.equals("true")) {
            return Boolean.TRUE;
        }
        if (text.equals("false")) {
            return Boolean.FALSE;
        }
        if (INTEGER.matcher(text).matches()) {
            try {
                long v = Long.parseLong(text);
                return (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) ? (Object) (int) v : (Object) v;
            } catch (NumberFormatException tooBig) {
                return text;
            }
        }
        if (DECIMAL.matcher(text).matches()) {
            return Double.parseDouble(text);
        }
        return text;
    }

    private static String unescapeDouble(String s, int no) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++i >= s.length()) {
                throw error(no, "gecersiz kacis dizisi: \"" + s + "\"");
            }
            char e = s.charAt(i);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                default -> throw error(no, "desteklenmeyen kacis dizisi: \\" + e);
            }
        }
        return sb.toString();
    }

    private static ConfigException error(int lineNo, String message) {
        return new ConfigException("satir " + lineNo + ": " + message);
    }
}
