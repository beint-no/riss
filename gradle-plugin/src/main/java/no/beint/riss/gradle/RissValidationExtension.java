package no.beint.riss.gradle;

import org.gradle.api.provider.Property;

/**
 * Configuration for the {@code no.beint.riss.validation} Gradle plugin.
 */
public abstract class RissValidationExtension {
    /** Require every {@code @RequestBody} parameter of a structured type to carry {@code @Valid}. */
    public abstract Property<Boolean> getRequireValidRequestBody();
}
