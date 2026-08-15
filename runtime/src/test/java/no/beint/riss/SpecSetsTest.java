package no.beint.riss;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpecSetsTest {
    @Test
    void missingSpecFailsClosed() {
        var error = assertThrows(IllegalStateException.class, () -> SpecSets.required("missing"));
        assertEquals("Unknown Riss spec 'missing'", error.getMessage());
    }
}
