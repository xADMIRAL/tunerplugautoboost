package io.github.xadmiral.boostautotune.plugin.ecu;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class OptionMatcherTest {
    @Test
    void matchesExactlyThenByCaseThenByMeaning() {
        assertEquals("On", OptionMatcher.match(Arrays.asList("Off", "On"), "On"));
        assertEquals("ON", OptionMatcher.match(Arrays.asList("OFF", "ON"), "On"));
        assertEquals("Enabled", OptionMatcher.match(Arrays.asList("Disabled", "Enabled"), "On"));
        assertEquals("Disabled", OptionMatcher.match(Arrays.asList("Disabled", "Enabled"), "Off"));
        assertEquals("Вкл", OptionMatcher.match(Arrays.asList("Выкл", "Вкл"), "On"));
        assertEquals("Off", OptionMatcher.match(Arrays.asList("Off", "Always ON"), "Выкл"));
        // a two-way switch whose other side is unknown wording
        assertEquals("Boost control 1", OptionMatcher.match(Arrays.asList("Off", "Boost control 1"), "On"));
        assertEquals("Boost control 1", OptionMatcher.match(Arrays.asList("INVALID", "Off", "Boost control 1"), "Enabled"));
        // nothing sensible
        assertNull(OptionMatcher.match(Arrays.asList("Open-loop", "Closed-loop"), "On"));
        assertNull(OptionMatcher.match(Arrays.asList("Off", "Launch", "Launch/Flatshift"), "On"));
        assertNull(OptionMatcher.match(Arrays.asList("Off", "On"), "Closed-loop"));
    }

    @Test
    void sameComparesByMeaning() {
        assertTrue(OptionMatcher.same("Enabled", "On"));
        assertTrue(OptionMatcher.same("off", "Disabled"));
        assertTrue(OptionMatcher.same("Closed-loop", "closed-loop"));
        assertFalse(OptionMatcher.same("Closed-loop", "Open-loop"));
        assertFalse(OptionMatcher.same("On", "Off"));
        assertFalse(OptionMatcher.same(null, "On"));
        assertEquals(1, OptionMatcher.meaning("Включено"));
        assertEquals(-1, OptionMatcher.meaning("Always ON"));
    }
}
