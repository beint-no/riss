package no.beint.riss.spring;

import jakarta.servlet.http.HttpServletRequest;
import no.beint.riss.SpecSet;
import no.beint.riss.SpecSets;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.function.Function;

@RestController
public class RissCompatibilityController {
    private final RissSpecResponse.Encoded primarySpec;
    private final String primaryUiPath;
    private final boolean uiEnabled;

    public RissCompatibilityController(RissProperties properties) {
        this(SpecSets.load(), properties);
    }

    RissCompatibilityController(List<SpecSet> specs, RissProperties properties) {
        this(specs, properties, spec -> RissSpecResponse.Encoded.of(spec.json()));
    }

    /** Reuses the documents the canonical controller already loaded and encoded at startup. */
    RissCompatibilityController(RissController controller, RissProperties properties) {
        this(controller.specs(), properties, controller::encoded);
    }

    private RissCompatibilityController(
            List<SpecSet> specs,
            RissProperties properties,
            Function<SpecSet, RissSpecResponse.Encoded> encoder
    ) {
        var primary = resolvePrimarySpec(specs, properties.getCompatibility().getPrimaryDocument());
        this.primarySpec = encoder.apply(primary);
        this.primaryUiPath = specs.size() == 1
                ? "/openapi/ui"
                : "/openapi/" + primary.name() + "/ui";
        this.uiEnabled = properties.isUiEnabled();
    }

    @GetMapping(
            path = {"/openapi.json", "/v3/api-docs", "/api-docs"},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<byte[]> spec(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding
    ) {
        return RissSpecResponse.json(primarySpec, ifNoneMatch, acceptEncoding);
    }

    @GetMapping(path = {"/swagger-ui", "/swagger-ui/", "/swagger-ui.html", "/swagger-ui/index.html"})
    public ResponseEntity<Void> ui(HttpServletRequest request) {
        if (!uiEnabled) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .cacheControl(CacheControl.noStore())
                .location(URI.create(RissRequestPath.resolve(request, primaryUiPath)))
                .build();
    }

    private static SpecSet resolvePrimarySpec(List<SpecSet> specs, String configuredName) {
        if (specs.isEmpty()) {
            throw new IllegalStateException("No compiled Riss spec is on the classpath");
        }
        if (specs.size() == 1 && (configuredName == null || configuredName.isBlank())) {
            return specs.getFirst();
        }
        if (configuredName == null || configuredName.isBlank()) {
            throw new IllegalStateException(
                    "Multiple Riss specs are on the classpath; configure riss.compatibility.primary-document"
            );
        }
        return specs.stream()
                .filter(spec -> spec.name().equals(configuredName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown primary Riss spec '" + configuredName + "'"));
    }
}
