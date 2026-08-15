package no.beint.riss.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.stream.Collectors;

@DisableCachingByDefault(because = "output lists machine-local absolute classpath paths")
public abstract class RissClasspath extends DefaultTask {
    @Classpath
    public abstract ConfigurableFileCollection getCompileClasspath();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void write() throws IOException {
        var path = getCompileClasspath().getFiles().stream()
                .map(java.io.File::getAbsolutePath)
                .collect(Collectors.joining(System.getProperty("path.separator")));
        var file = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(file.getParent());
        Files.writeString(file, path, StandardCharsets.UTF_8);
    }
}
