package no.beint.riss.spring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RissSpecResponseTest {
    @Test
    void acceptsGzipHandlesQualityValuesAndWhitespace() {
        assertFalse(RissSpecResponse.acceptsGzip(null));
        assertFalse(RissSpecResponse.acceptsGzip(""));
        assertFalse(RissSpecResponse.acceptsGzip("br, deflate"));
        assertTrue(RissSpecResponse.acceptsGzip("gzip"));
        assertTrue(RissSpecResponse.acceptsGzip("GZIP"));
        assertTrue(RissSpecResponse.acceptsGzip("br, gzip, deflate"));
        assertTrue(RissSpecResponse.acceptsGzip("br, gzip;q=0.8"));
        assertTrue(RissSpecResponse.acceptsGzip("br, gzip ; Q=1.0"));
        assertTrue(RissSpecResponse.acceptsGzip("gzip;q=0.001"));
        assertFalse(RissSpecResponse.acceptsGzip("gzip;q=0"));
        assertFalse(RissSpecResponse.acceptsGzip("gzip; q=0.0"));
        assertFalse(RissSpecResponse.acceptsGzip("br, gzip;q=0.000, deflate"));
        assertFalse(RissSpecResponse.acceptsGzip("x-gzip"));
    }

    @Test
    void matchesHandlesStrongWeakWildcardAndLists() {
        var etag = "\"0123456789abcdef\"";
        assertFalse(RissSpecResponse.matches(null, etag));
        assertFalse(RissSpecResponse.matches("", etag));
        assertFalse(RissSpecResponse.matches("   ", etag));
        assertTrue(RissSpecResponse.matches("*", etag));
        assertTrue(RissSpecResponse.matches(etag, etag));
        assertTrue(RissSpecResponse.matches("W/" + etag, etag));
        assertTrue(RissSpecResponse.matches("\"other\", W/" + etag + ", \"more\"", etag));
        assertFalse(RissSpecResponse.matches("\"other\", \"more\"", etag));
        assertFalse(RissSpecResponse.matches("w/" + etag, etag));
    }
}
