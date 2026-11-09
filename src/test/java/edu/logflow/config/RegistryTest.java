package edu.logflow.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.logflow.Stage;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RegistryTest {

    /** Yeni bir aşama türü: yalnızca bir fabrika sınıfı; hiçbir mevcut dosya değişmez. */
    private static final class UpperCaseStageFactory implements StageFactory {
        @Override
        public String type() {
            return "upper";
        }

        @Override
        public Class<?> inputType() {
            return String.class;
        }

        @Override
        public Class<?> outputType() {
            return String.class;
        }

        @Override
        public Stage<?, ?> create(Params params) {
            Stage<String, String> stage = (line, out) -> out.emit(line.toUpperCase());
            return stage;
        }
    }

    @Test
    void defaultStageRegistryIsDiscoveredFromServiceFiles() {
        assertEquals(Set.of("decode", "parse", "filter", "validate", "project"), StageRegistry.discover().types());
    }

    @Test
    void defaultSourceAndSinkRegistriesAreDiscovered() {
        assertEquals(Set.of("file", "raw-file"), SourceRegistry.discover().types());
        assertEquals(Set.of("file", "console"), SinkRegistry.discover().types());
    }

    @Test
    void newStageTypeCanBeRegisteredWithoutTouchingExistingOnes() {
        StageRegistry registry = StageRegistry.discover();

        registry.register(new UpperCaseStageFactory());

        assertEquals(Set.of("decode", "parse", "filter", "validate", "project", "upper"), registry.types());
        assertEquals("upper", registry.get("upper", "stages[0]").type());
    }

    @Test
    void registeringTheSameTypeTwiceFailsInsteadOfSilentlyOverriding() {
        StageRegistry registry = new StageRegistry();
        registry.register(new UpperCaseStageFactory());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry.register(new UpperCaseStageFactory()));

        assertTrue(e.getMessage().contains("'upper' iki kez kayitli"));
    }

    @Test
    void unknownTypeListsAvailableTypesAndSuggestsTheClosest() {
        StageRegistry registry = StageRegistry.discover();

        ConfigException e = assertThrows(ConfigException.class, () -> registry.get("fliter", "stages[2]"));

        assertTrue(e.getMessage().startsWith("stages[2]: bilinmeyen asama"));
        assertTrue(e.getMessage().contains("'fliter'"));
        assertTrue(e.getMessage().contains("Bunu mu demek istediniz: 'filter'?"));
        assertTrue(e.getMessage().contains("Kullanilabilir turler: decode, filter, parse, project, validate"));
    }

    @Test
    void noSuggestionWhenNothingIsClose() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> StageRegistry.discover().get("zzzzzzzz", "stages[0]"));

        assertTrue(!e.getMessage().contains("Bunu mu demek"));
    }

    @Test
    void emptyRegistryExplainsHowToFixTheClasspath() {
        ConfigException e = assertThrows(ConfigException.class, () -> new StageRegistry().get("parse", "stages[0]"));

        assertTrue(e.getMessage().contains("META-INF/services"));
    }

    @Test
    void editDistanceBasics() {
        assertEquals(0, Registry.distance("filter", "filter"));
        assertEquals(2, Registry.distance("fliter", "filter"));
        assertEquals(3, Registry.distance("abc", "xyz"));
    }
}
