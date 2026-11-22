package no.beint.riss.gradle;

import com.google.devtools.ksp.gradle.KspExtension;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Gradle plugin that generates Riss compile-time validators without compiling an OpenAPI spec.
 * Intended for web-app, admin, or domain DTO modules that do not publish an OpenAPI document.
 */
public final class RissValidationPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().withPlugin("org.jetbrains.kotlin.jvm", ignored -> configureKotlin(project));
        project.getPluginManager().withPlugin("java", ignored -> project.afterEvaluate(evaluated -> {
            if (!project.getPluginManager().hasPlugin("org.jetbrains.kotlin.jvm")) {
                throw new GradleException("Riss validation currently requires the Kotlin JVM plugin");
            }
        }));
    }

    private void configureKotlin(Project project) {
        project.getPluginManager().apply("com.google.devtools.ksp");
        var version = RissPlugin.implementationVersion(project);
        project.getDependencies().add("implementation", "no.beint.riss:runtime:" + version);
        project.getDependencies().add("ksp", "no.beint.riss:compiler:" + version);

        var ksp = project.getExtensions().getByType(KspExtension.class);
        ksp.arg("riss.package", project.provider(() -> RissPlugin.generatedPackage(project)));
        ksp.arg("riss.openApi", "false");
        ksp.arg("riss.validation", "true");
    }
}
