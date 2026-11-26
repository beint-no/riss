package no.beint.riss.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RissPluginIntegrationTest {
    private static final String TEST_REPO = System.getProperty("riss.testRepo");
    private static final String RISS_VERSION = System.getProperty("riss.version");
    private static final String SPRING_VERSION = System.getProperty("riss.springVersion");

    @TempDir
    Path projectDir;

    @Test
    void lintRequireAuthorizationSupportsDirectMetaClassPublicPathsAndBaseline() throws IOException {
        writeSettings("auth-test");
        writeFile("build.gradle.kts", """
                plugins {
                    kotlin("jvm")
                    id("no.beint.riss")
                }

                group = "no.example"
                version = "%s"

                kotlin {
                    jvmToolchain(27)
                }

                riss {
                    lintRequireAuthorization.set(true)
                    lintPublicPaths.set(listOf("/api/health", "/api/public/**"))
                    lintBaseline.set(layout.projectDirectory.file("lint-baseline.txt"))
                }
                """.formatted(RISS_VERSION));

        writeFile("src/main/kotlin/org/springframework/security/access/prepost/PreAuthorize.kt", """
                package org.springframework.security.access.prepost

                @Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
                @Retention(AnnotationRetention.RUNTIME)
                annotation class PreAuthorize(val value: String)
                """);

        writeFile("src/main/kotlin/no/example/Api.kt", """
                package no.example

                import no.beint.riss.RissDocument
                import org.springframework.security.access.prepost.PreAuthorize
                import org.springframework.web.bind.annotation.GetMapping
                import org.springframework.web.bind.annotation.PostMapping
                import org.springframework.web.bind.annotation.RequestMapping
                import org.springframework.web.bind.annotation.RestController

                @RissDocument(name = "api", title = "Auth Test", version = "1", scanPackages = ["no.example"])
                class Docs

                @PreAuthorize("hasAuthority('LEDGER_READ')")
                @Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
                @Retention(AnnotationRetention.RUNTIME)
                annotation class RequireLedgerReadPermission

                @RestController
                @RequestMapping("/api/public")
                class PublicApi {
                    @GetMapping("/info")
                    fun info(): String = "ok"
                }

                @RestController
                class HealthApi {
                    @GetMapping("/api/health")
                    fun health(): String = "ok"
                }

                @RestController
                @RequestMapping("/api/admin")
                @PreAuthorize("hasRole('ADMIN')")
                class AdminApi {
                    @GetMapping("/users")
                    fun users(): List<String> = emptyList()
                }

                @RestController
                @RequestMapping("/api/ledger")
                class LedgerApi {
                    @GetMapping("/direct")
                    @PreAuthorize("isAuthenticated()")
                    fun direct(): String = "ok"

                    @GetMapping("/meta")
                    @RequireLedgerReadPermission
                    fun meta(): String = "ok"

                    @PostMapping("/unprotected")
                    fun unprotected(): String = "oops"
                }
                """);

        var failed = runner("kspKotlin").buildAndFail();
        assertTrue(
                failed.getOutput().contains("RISS-LINT AUTH POST /api/ledger/unprotected: operation must declare an authorization annotation"),
                () -> "Unexpected output:\n" + failed.getOutput()
        );

        writeFile("lint-baseline.txt", "AUTH POST /api/ledger/unprotected # tracked\n");
        runner("kspKotlin").build();
    }

    @Test
    void lintRequireValidRequestBodyEnforcesStructuredDtosAndCollections() throws IOException {
        writeSettings("body-valid-test");
        writeFile("build.gradle.kts", """
                plugins {
                    kotlin("jvm")
                    id("no.beint.riss")
                }

                group = "no.example"
                version = "%s"

                kotlin {
                    jvmToolchain(27)
                }

                riss {
                    lintRequireValidRequestBody.set(true)
                }
                """.formatted(RISS_VERSION));

        writeFile("src/main/kotlin/no/example/Api.kt", """
                package no.example

                import no.beint.riss.RissDocument
                import no.beint.riss.validation.Valid
                import org.springframework.web.bind.annotation.PostMapping
                import org.springframework.web.bind.annotation.RequestBody
                import org.springframework.web.bind.annotation.RequestMapping
                import org.springframework.web.bind.annotation.RestController

                @RissDocument(name = "api", title = "Body Valid Test", version = "1", scanPackages = ["no.example"])
                class Docs

                data class PlainDto(val name: String)
                enum class Kind { A, B }

                @RestController
                @RequestMapping("/api/items")
                class ItemApi {
                    @PostMapping("/valid")
                    fun createValid(@Valid @RequestBody body: PlainDto): String = body.name

                    @PostMapping("/valid-list")
                    fun createValidList(@RequestBody items: List<@Valid PlainDto>): Int = items.size

                    @PostMapping("/raw-string")
                    fun rawString(@RequestBody body: String): String = body

                    @PostMapping("/raw-bytes")
                    fun rawBytes(@RequestBody body: ByteArray): Int = body.size

                    @PostMapping("/raw-enum")
                    fun rawEnum(@RequestBody body: Kind): String = body.name

                    @PostMapping("/missing")
                    fun missing(@RequestBody body: PlainDto): String = body.name
                }
                """);

        var failed = runner("kspKotlin").buildAndFail();
        assertTrue(
                failed.getOutput().contains("@RequestBody parameter 'body' of structured type 'PlainDto' must be annotated with @Valid"),
                () -> "Unexpected output:\n" + failed.getOutput()
        );

        writeFile("src/main/kotlin/no/example/Api.kt", """
                package no.example

                import no.beint.riss.RissDocument
                import no.beint.riss.validation.Valid
                import org.springframework.web.bind.annotation.PostMapping
                import org.springframework.web.bind.annotation.RequestBody
                import org.springframework.web.bind.annotation.RequestMapping
                import org.springframework.web.bind.annotation.RestController

                @RissDocument(name = "api", title = "Body Valid Test", version = "1", scanPackages = ["no.example"])
                class Docs

                data class PlainDto(val name: String)
                enum class Kind { A, B }

                @RestController
                @RequestMapping("/api/items")
                class ItemApi {
                    @PostMapping("/valid")
                    fun createValid(@Valid @RequestBody body: PlainDto): String = body.name

                    @PostMapping("/valid-list")
                    fun createValidList(@RequestBody items: List<@Valid PlainDto>): Int = items.size

                    @PostMapping("/raw-string")
                    fun rawString(@RequestBody body: String): String = body

                    @PostMapping("/raw-bytes")
                    fun rawBytes(@RequestBody body: ByteArray): Int = body.size

                    @PostMapping("/raw-enum")
                    fun rawEnum(@RequestBody body: Kind): String = body.name
                }
                """);

        runner("kspKotlin").build();
    }

    @Test
    void validationPluginExtensionEnforcesRequireValidRequestBody() throws IOException {
        writeSettings("validation-plugin-test");
        writeFile("build.gradle.kts", """
                plugins {
                    kotlin("jvm")
                    id("no.beint.riss.validation")
                }

                group = "no.example"
                version = "%s"

                kotlin {
                    jvmToolchain(27)
                }

                dependencies {
                    implementation("org.springframework:spring-webmvc:%s")
                }

                rissValidation {
                    requireValidRequestBody.set(true)
                }
                """.formatted(RISS_VERSION, SPRING_VERSION));

        writeFile("src/main/kotlin/no/example/AdminApi.kt", """
                package no.example

                import no.beint.riss.validation.Valid
                import org.springframework.web.bind.annotation.PostMapping
                import org.springframework.web.bind.annotation.RequestBody
                import org.springframework.web.bind.annotation.RestController

                data class AdminCmd(val label: String)

                @RestController
                class AdminApi {
                    @PostMapping("/admin/raw")
                    fun rawMap(@RequestBody payload: Map<String, Any>): Int = payload.size

                    @PostMapping("/admin/missing-list")
                    fun missingList(@RequestBody cmds: List<AdminCmd>): Int = cmds.size
                }
                """);

        var failed = runner("kspKotlin").buildAndFail();
        assertTrue(
                failed.getOutput().contains("@RequestBody parameter 'cmds' of structured type 'List<AdminCmd>' must be annotated with @Valid"),
                () -> "Unexpected output:\n" + failed.getOutput()
        );

        writeFile("src/main/kotlin/no/example/AdminApi.kt", """
                package no.example

                import no.beint.riss.validation.Valid
                import org.springframework.web.bind.annotation.PostMapping
                import org.springframework.web.bind.annotation.RequestBody
                import org.springframework.web.bind.annotation.RestController

                data class AdminCmd(val label: String)

                @RestController
                class AdminApi {
                    @PostMapping("/admin/raw")
                    fun rawMap(@RequestBody payload: Map<String, Any>): Int = payload.size

                    @PostMapping("/admin/valid-list")
                    fun validList(@RequestBody cmds: List<@Valid AdminCmd>): Int = cmds.size
                }
                """);

        runner("kspKotlin").build();
    }

    private GradleRunner runner(String... args) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(args)
                .forwardOutput();
    }

    private void writeSettings(String name) throws IOException {
        writeFile("gradle.properties", "version=" + RISS_VERSION + "\n");
        writeFile("settings.gradle.kts", """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }

                dependencyResolutionManagement {
                    repositories {
                        maven { url = uri("%s") }
                        mavenCentral()
                    }
                }

                rootProject.name = "%s"
                """.formatted(Path.of(TEST_REPO).toUri(), name));
    }

    private void writeFile(String relativePath, String content) throws IOException {
        var path = projectDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
