package no.beint.riss.gradle;

import com.google.devtools.ksp.gradle.KspAATask;
import com.google.devtools.ksp.gradle.KspExtension;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSet;

import javax.lang.model.SourceVersion;
import java.util.Locale;

public final class RissPlugin implements Plugin<Project> {
    public static final String SWAGGER_ANNOTATIONS = "io.swagger.core.v3:swagger-annotations-jakarta:2.2.38";

    @Override
    public void apply(Project project) {
        var extension = project.getExtensions().create("riss", RissExtension.class);
        extension.getGeneratedPackage().convention(project.provider(() -> generatedPackage(project)));
        extension.getRegistryName().convention("RissSpec");
        extension.getSpecName().convention("api");
        extension.getScanPackages().convention(java.util.List.of());
        extension.getPaths().convention(java.util.List.of());
        extension.getExcludePaths().convention(java.util.List.of());
        extension.getTitle().convention("");
        extension.getVersion().convention("1");
        extension.getStrict().convention(true);

        project.getPluginManager().withPlugin("org.jetbrains.kotlin.jvm", ignored -> configureKotlin(project, extension));
        project.getPluginManager().withPlugin("java", ignored -> project.afterEvaluate(evaluated -> {
            if (!project.getPluginManager().hasPlugin("org.jetbrains.kotlin.jvm")) {
                throw new GradleException("Riss currently requires the Kotlin JVM plugin");
            }
        }));
    }

    private void configureKotlin(Project project, RissExtension extension) {
        project.getPluginManager().apply("com.google.devtools.ksp");
        var version = implementationVersion(project);
        project.getDependencies().add("implementation", "no.beint.riss:spring:" + version);
        project.getDependencies().add("ksp", "no.beint.riss:compiler:" + version);
        project.getDependencies().add("compileOnly", SWAGGER_ANNOTATIONS);

        var classpathFile = project.getLayout().getBuildDirectory().file("riss/classpath.txt");
        var ksp = project.getExtensions().getByType(KspExtension.class);
        ksp.arg("riss.package", extension.getGeneratedPackage());
        ksp.arg("riss.registry", extension.getRegistryName());
        ksp.arg("riss.specName", extension.getSpecName());
        ksp.arg("riss.scanPackages", extension.getScanPackages().map(values -> String.join(",", values)));
        ksp.arg("riss.paths", extension.getPaths().map(values -> String.join(",", values)));
        ksp.arg("riss.excludePaths", extension.getExcludePaths().map(values -> String.join(",", values)));
        ksp.arg("riss.title", extension.getTitle());
        ksp.arg("riss.version", extension.getVersion());
        ksp.arg("riss.strict", extension.getStrict().map(String::valueOf));
        ksp.arg("riss.classpathFile", classpathFile.map(file -> file.getAsFile().getAbsolutePath()));

        var writeClasspath = project.getTasks().register("rissClasspath", RissClasspath.class, task -> {
            task.setGroup("build");
            task.setDescription("Writes the compile classpath for the Riss compiler");
            var sourceSets = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
            task.getCompileClasspath().from(sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).getCompileClasspath());
            task.getOutputFile().set(classpathFile);
        });

        project.getTasks().withType(KspAATask.class).configureEach(task -> task.dependsOn(writeClasspath));
        var kspKotlin = project.getTasks().withType(KspAATask.class).matching(task -> task.getName().equals("kspKotlin"));
        project.getTasks().register("rissSpec", Copy.class, task -> {
            task.setGroup("build");
            task.setDescription("Copies the compiled OpenAPI JSON to build/riss/spec");
            task.from(project.getLayout().getBuildDirectory().dir("generated/ksp/main/resources"));
            task.include("**/*.json");
            task.into(project.getLayout().getBuildDirectory().dir("riss/spec"));
            task.dependsOn(kspKotlin);
        });
        project.getTasks().register("rissCheck", task -> {
            task.setGroup("verification");
            task.setDescription("Compiles the Riss OpenAPI document");
            task.dependsOn(kspKotlin);
        });
    }

    private String implementationVersion(Project project) {
        var version = RissPlugin.class.getPackage().getImplementationVersion();
        return version == null ? project.getVersion().toString() : version;
    }

    private String generatedPackage(Project project) {
        var group = project.getGroup().toString();
        var prefix = group.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*") ? group + "." : "";
        var projectPath = project.getPath().replaceFirst("^:", "").replace(':', '.');
        if (projectPath.isEmpty()) {
            projectPath = project.getName();
        }
        var packageName = new StringBuilder(prefix);
        for (var identifier : projectPath.split("\\.")) {
            if (!packageName.isEmpty() && packageName.charAt(packageName.length() - 1) != '.') {
                packageName.append('.');
            }
            packageName.append(javaIdentifier(identifier));
        }
        return packageName.append(".riss.generated").toString();
    }

    private String javaIdentifier(String value) {
        var output = new StringBuilder(value.length());
        value.toLowerCase(Locale.ROOT).codePoints().forEach(character -> {
            if (Character.isJavaIdentifierPart(character)) {
                output.appendCodePoint(character);
            } else {
                output.append('_');
            }
        });
        if (output.isEmpty() || !Character.isJavaIdentifierStart(output.codePointAt(0)) || SourceVersion.isKeyword(output)) {
            output.insert(0, '_');
        }
        return output.toString();
    }
}
