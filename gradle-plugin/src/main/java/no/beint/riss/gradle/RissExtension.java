package no.beint.riss.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

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
}
