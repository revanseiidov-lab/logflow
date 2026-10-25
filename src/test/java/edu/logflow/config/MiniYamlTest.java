package edu.logflow.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MiniYamlTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(String yaml) {
        return (Map<String, Object>) MiniYaml.parse(yaml);
    }

    @Test
    void parsesBlockMappingWithScalars() {
        Map<String, Object> m = map("a: 1\nb: hello world\nc: true\nd: ~\ne: 2.5\nf: -7\n");

        assertEquals(1, m.get("a"));
        assertEquals("hello world", m.get("b"));
        assertEquals(Boolean.TRUE, m.get("c"));
        assertEquals(null, m.get("d"));
        assertTrue(m.containsKey("d"));
        assertEquals(2.5, m.get("e"));
        assertEquals(-7, m.get("f"));
    }

    @Test
    void quotedValuesStayStringsAndKeepSpecialCharacters() {
        Map<String, Object> m = map("op: \">=\"\nn: \"400\"\ns: 'it''s'\nh: \"a # not comment\"\nq: \"say \\\"hi\\\"\"\n");

        assertEquals(">=", m.get("op"));
        assertEquals("400", m.get("n"));
        assertEquals("it's", m.get("s"));
        assertEquals("a # not comment", m.get("h"));
        assertEquals("say \"hi\"", m.get("q"));
    }

    @Test
    void parsesNestedMappingsAndBlockLists() {
        Map<String, Object> m = map("outer:\n  inner:\n    x: 1\n  list:\n    - a\n    - b\n");

        @SuppressWarnings("unchecked")
        Map<String, Object> outer = (Map<String, Object>) m.get("outer");
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) outer.get("inner");
        assertEquals(1, inner.get("x"));
        assertEquals(List.of("a", "b"), outer.get("list"));
    }

    @Test
    void listItemsCanBeMappings() {
        Map<String, Object> m = map("stages:\n  - type: parse\n  - type: filter\n    params: { field: status, value: 400 }\n");

        @SuppressWarnings("unchecked")
        List<Object> stages = (List<Object>) m.get("stages");
        assertEquals(2, stages.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) stages.get(1);
        assertEquals("filter", second.get("type"));
        Map<String, Object> expectedParams = new LinkedHashMap<>();
        expectedParams.put("field", "status");
        expectedParams.put("value", 400);
        assertEquals(expectedParams, second.get("params"));
    }

    @Test
    void parsesFlowMappingsAndListsIncludingNested() {
        Map<String, Object> m = map("p: { fields: [timestamp, clientIp, status], path: out/x.txt, empty: [], e2: {} }\n");

        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) m.get("p");
        assertEquals(List.of("timestamp", "clientIp", "status"), p.get("fields"));
        assertEquals("out/x.txt", p.get("path"));
        assertEquals(List.of(), p.get("empty"));
        assertEquals(Map.of(), p.get("e2"));
    }

    @Test
    void sequenceMayStartAtTheSameIndentAsItsKey() {
        Map<String, Object> m = map("items:\n- a\n- b\nother: 1\n");

        assertEquals(List.of("a", "b"), m.get("items"));
        assertEquals(1, m.get("other"));
    }

    @Test
    void ignoresCommentsBlankLinesAndDocumentStart() {
        Map<String, Object> m = map("---\n# basi yorum\n\na: 1   # satir sonu yorumu\n\n# ara yorum\nb: 2\n");

        assertEquals(1, m.get("a"));
        assertEquals(2, m.get("b"));
    }

    @Test
    void emptyOrCommentOnlyInputYieldsNull() {
        assertEquals(null, MiniYaml.parse(""));
        assertEquals(null, MiniYaml.parse("# sadece yorum\n\n"));
    }

    @Test
    void timestampsWithColonsStayPlainStrings() {
        Map<String, Object> m = map("t: 2023-10-10T11:00:00Z\np: { at: 2023-10-10T11:00:00Z }\n");

        assertEquals("2023-10-10T11:00:00Z", m.get("t"));
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) m.get("p");
        assertEquals("2023-10-10T11:00:00Z", p.get("at"));
    }

    @Test
    void errorsCarryLineNumbers() {
        ConfigException tab = assertThrows(ConfigException.class, () -> MiniYaml.parse("a:\n\tb: 1\n"));
        assertTrue(tab.getMessage().startsWith("satir 2:"));
        assertTrue(tab.getMessage().contains("TAB"));

        ConfigException dup = assertThrows(ConfigException.class, () -> MiniYaml.parse("a: 1\nb: 2\na: 3\n"));
        assertTrue(dup.getMessage().startsWith("satir 3:"));

        ConfigException unterminated = assertThrows(ConfigException.class, () -> MiniYaml.parse("a: [1, 2\n"));
        assertTrue(unterminated.getMessage().startsWith("satir 1:"));

        ConfigException quote = assertThrows(ConfigException.class, () -> MiniYaml.parse("a: \"abc\n"));
        assertTrue(quote.getMessage().contains("tirnak"));
    }

    @Test
    void rejectsUnsupportedOrMalformedConstructsLoudly() {
        assertThrows(ConfigException.class, () -> MiniYaml.parse("a: |\n  text\n"));       // blok skaler
        assertThrows(ConfigException.class, () -> MiniYaml.parse("a: 1\n  b: 2\n"));        // yanlis girinti
        assertThrows(ConfigException.class, () -> MiniYaml.parse("just a line\n"));         // 'anahtar: deger' degil
        assertThrows(ConfigException.class, () -> MiniYaml.parse("a: { b: 1 } extra\n"));   // akis sonrasi fazlalik
        assertThrows(ConfigException.class, () -> MiniYaml.parse("a: 1\n- x\n"));           // karisik liste/esleme
    }
}
