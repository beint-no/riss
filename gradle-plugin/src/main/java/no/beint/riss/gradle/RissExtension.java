package no.beint.riss.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * Optional Gradle fallbacks. Prefer {@code @RissDocument} for scan packages, paths,
 * and the document name.
 */
public abstract class RissExtension {
    public abstract Property<String> getGeneratedPackage();

    public abstract Property<String> getRegistryName();

    public abstract Property<String> getValidatorRegistryName();

    public abstract Property<Boolean> getValidation();

    public abstract Property<String> getSpecName();

    public abstract ListProperty<String> getScanPackages();

    public abstract ListProperty<String> getPaths();

    public abstract ListProperty<String> getExcludePaths();

    public abstract Property<String> getTitle();

    public abstract Property<String> getVersion();

    public abstract Property<Boolean> getStrict();

    /** Parameter and property names that must not be used, mapped to the name to use instead. */
    public abstract MapProperty<String, String> getLintBannedNames();

    /** Regular expressions for names that must not be used, mapped to the advice shown. No commas or '=' in either. */
    public abstract MapProperty<String, String> getLintBannedNamePatterns();

    /** Suffixes that component schema names must not end in. */
    public abstract ListProperty<String> getLintBannedSchemaSuffixes();

    /** Regular expression every enum value must match. */
    public abstract Property<String> getLintEnumPattern();

    /** Name suffixes mapped to the format a parameter or property with that suffix must have. */
    public abstract MapProperty<String, String> getLintFormatBySuffix();

    /** Schema name prefix that every operation taking {@code page} must return, together with {@code pageSize}. */
    public abstract Property<String> getLintPageSchemaPrefix();

    /** Require every integer {@code id} / {@code *Id} path and query parameter to declare {@code @Positive} or {@code @Min(1)}. */
    public abstract Property<Boolean> getLintPositiveIds();

    /** Require static path segments to be lowercase kebab-case and path variables to be camelCase. */
    public abstract Property<Boolean> getLintKebabPaths();

    /** Require every operation to declare a non-blank summary. */
    public abstract Property<Boolean> getLintRequireSummary();

    /** Require every {@code @RequestBody} parameter of a structured type to carry {@code @Valid}. */
    public abstract Property<Boolean> getLintRequireValidRequestBody();

    /** Require every OpenAPI operation to declare an authorization annotation or match {@code lintPublicPaths}. */
    public abstract Property<Boolean> getLintRequireAuthorization();

    /** Qualified names of authorization annotations recognized by {@code lintRequireAuthorization} (including meta-annotations). */
    public abstract ListProperty<String> getLintAuthorizationAnnotations();

    /** Path patterns exempted from {@code lintRequireAuthorization} (supports exact paths and {@code *}/{@code **} glob segments). */
    public abstract ListProperty<String> getLintPublicPaths();

    /** Accepted violations, one key per line. Keys that no longer violate a rule fail the build. */
    public abstract RegularFileProperty getLintBaseline();
}
