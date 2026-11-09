package edu.logflow.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ErrorPolicyTest {

    @Test
    void parsesAllPolicyNamesCaseAndSeparatorInsensitive() {
        assertEquals(ErrorPolicy.FAIL_FAST, ErrorPolicy.parse("fail-fast"));
        assertEquals(ErrorPolicy.FAIL_FAST, ErrorPolicy.parse(" FAIL_FAST "));
        assertEquals(ErrorPolicy.SKIP, ErrorPolicy.parse("skip"));
        assertEquals(ErrorPolicy.DEAD_LETTER, ErrorPolicy.parse("dead-letter"));
        assertEquals(ErrorPolicy.DEAD_LETTER, ErrorPolicy.parse("DEAD_LETTER"));
        assertEquals(ErrorPolicy.retry(3), ErrorPolicy.parse("retry(3)"));
        assertEquals(ErrorPolicy.retry(2), ErrorPolicy.parse("Retry( 2 )"));
    }

    @Test
    void rejectsUnknownAndMalformedPolicies() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ErrorPolicy.parse("ignore"));
        assertTrue(e.getMessage().contains("bilinmeyen hata politikasi 'ignore'"));
        assertTrue(e.getMessage().contains("fail-fast, skip, dead-letter, retry(n)"));
        assertThrows(IllegalArgumentException.class, () -> ErrorPolicy.parse("retry(x)"));
        assertThrows(IllegalArgumentException.class, () -> ErrorPolicy.parse("retry(0)"));
        assertThrows(IllegalArgumentException.class, () -> ErrorPolicy.parse("retry(-1)"));
    }

    @Test
    void toStringRoundTrips() {
        for (String s : new String[] {"fail-fast", "skip", "dead-letter", "retry(4)"}) {
            assertEquals(s, ErrorPolicy.parse(s).toString());
        }
    }
}
