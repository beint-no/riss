import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.publish.PublishingExtension

plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("org.springframework.boot") version "4.1.0" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

allprojects {
    group = "no.beint.riss"
    version = "0.1.9"
}

tasks.register("printReleaseVersion") {
    val releaseVersion = version.toString()
    doLast { println(releaseVersion) }
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(26))
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set(26)
        }
    }

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/beint-no/riss")
                    credentials {
                        username = providers.gradleProperty("gpr.user")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                            .orElse("beint-no")
                            .get()
                        password = providers.gradleProperty("gpr.key")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                            .orElse(providers.environmentVariable("GH_TOKEN"))
                            .orElse("")
                            .get()
                    }
                }
            }
        }
    }

    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension>("mavenPublishing") {
            publishToMavenCentral()
            signAllPublications()

            when {
                plugins.hasPlugin("java-gradle-plugin") -> this.configure(
                    GradlePlugin(
                        javadocJar = JavadocJar.Empty(),
                        sourcesJar = SourcesJar.Sources(),
                    ),
                )

                plugins.hasPlugin("org.jetbrains.kotlin.jvm") -> this.configure(
                    KotlinJvm(
                        javadocJar = JavadocJar.Empty(),
                        sourcesJar = SourcesJar.Sources(),
                    ),
                )

                plugins.hasPlugin("java-library") -> this.configure(
                    JavaLibrary(
                        javadocJar = JavadocJar.Empty(),
                        sourcesJar = SourcesJar.Sources(),
                    ),
                )
            }

            pom {
                name.set("Riss ${project.name}")
                description.set(
                    when (project.name) {
                        "model" -> "OpenAPI 3.1 document model and JSON writer for Riss."
                        "runtime" -> "Dependency-free Java runtime that serves a compiled Riss spec."
                        "compiler" -> "Build-time Kotlin and Java OpenAPI compiler for Riss."
                        "spring" -> "Spring MVC adapter that serves the Riss spec and UI."
                        "gradle-plugin" -> "Gradle plugin for compiling Riss OpenAPI documents."
                        else -> "Compile-time OpenAPI 3.1 publisher for Java and Kotlin applications."
                    },
                )
                inceptionYear.set("2026")
                url.set("https://github.com/beint-no/riss")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("beint-no")
                        name.set("Beint")
                        url.set("https://github.com/beint-no")
                    }
                }
                scm {
                    url.set("https://github.com/beint-no/riss")
                    connection.set("scm:git:https://github.com/beint-no/riss.git")
                    developerConnection.set("scm:git:ssh://git@github.com/beint-no/riss.git")
                }
            }
        }
    }
}
