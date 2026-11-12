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

    public abstract Property<String> getSpecName();

    public abstract ListProperty<String> getScanPackages();

    public abstract ListProperty<String> getPaths();

    public abstract ListProperty<String> getExcludePaths();

    public abstract Property<String> getTitle();

    public abstract Property<String> getVersion();

    public abstract Property<Boolean> getStrict();

    /** Parameter and property names that must not be used, mapped to the name to use instead. */
    public abstract MapProperty<String, String> getLintBannedNames();

    /** Suffixes that component schema names must not end in. */
    public abstract ListProperty<String> getLintBannedSchemaSuffixes();

    /** Regular expression every enum value must match. */
    public abstract Property<String> getLintEnumPattern();

    /** Name suffixes mapped to the format a parameter or property with that suffix must have. */
    public abstract MapProperty<String, String> getLintFormatBySuffix();

    /** Schema name prefix that every operation taking {@code page} must return, together with {@code pageSize}. */
    public abstract Property<String> getLintPageSchemaPrefix();

    /** Accepted violations, one key per line. Keys that no longer violate a rule fail the build. */
    public abstract RegularFileProperty getLintBaseline();
}
