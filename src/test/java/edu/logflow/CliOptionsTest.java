package edu.logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CliOptionsTest {

    @Test
    void parsesAllOptions() {
        CliOptions o = CliOptions.parse(new String[] {
                "in.log", "--min-status", "400", "--path-prefix", "/api",
                "--fields", "timestamp,clientIp,status", "--output", "out.txt",
                "--since", "2023-10-10T10:00:00Z", "--until", "2023-10-10T11:00:00Z"});

        assertEquals(Path.of("in.log"), o.input);
        assertEquals(Path.of("out.txt"), o.output);
        assertEquals(Integer.valueOf(400), o.minStatus);
        assertEquals("/api", o.pathPrefix);
        assertEquals(List.of("timestamp", "clientIp", "status"), o.fields);
        assertEquals(Instant.parse("2023-10-10T10:00:00Z"), o.since);
        assertEquals(Instant.parse("2023-10-10T11:00:00Z"), o.until);
    }

    @Test
    void defaultsWhenOnlyInputIsGiven() {
        CliOptions o = CliOptions.parse(new String[] {"in.log"});

        assertEquals(null, o.output);
        assertEquals(null, o.minStatus);
        assertEquals(null, o.pathPrefix);
        assertEquals(null, o.since);
        assertEquals(null, o.until);
        assertEquals(ProjectionStage.ALL_FIELDS, o.fields);
    }

    @Test
    void optionOrderDoesNotMatterAndInputMayComeLast() {
        CliOptions o = CliOptions.parse(new String[] {"--min-status", "500", "in.log"});

        assertEquals(Path.of("in.log"), o.input);
        assertEquals(Integer.valueOf(500), o.minStatus);
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[] {}));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[] {"--min-status", "400"}));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[] {"a.log", "b.log"}));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[] {"a.log", "--min-status"}));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[] {"a.log", "--min-status", "abc"}));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[] {"a.log", "--since", "yesterday"}));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[] {"a.log", "--bogus", "1"}));
    }
}
