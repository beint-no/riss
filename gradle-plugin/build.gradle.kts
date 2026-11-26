plugins {
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish")
}

val testPluginClasspath = configurations.create("testPluginClasspath")

dependencies {
    implementation(libs.ksp.gradle.plugin)

    testPluginClasspath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")

    testImplementation(gradleTestKit())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("riss") {
            id = "no.beint.riss"
            implementationClass = "no.beint.riss.gradle.RissPlugin"
            displayName = "Riss compiler"
            description = "Compile Spring MVC APIs into an OpenAPI 3.1 JSON document and compile-time validators"
        }
        create("rissValidation") {
            id = "no.beint.riss.validation"
            implementationClass = "no.beint.riss.gradle.RissValidationPlugin"
            displayName = "Riss compile-time validation"
            description = "Compile Riss validation annotations into reflection-free Kotlin validators"
        }
    }
}

tasks.named<PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(testPluginClasspath)
}

val testRepoDir = layout.buildDirectory.dir("test-repo")
val rissVersion = project.version.toString()

val prepareTestRepo = tasks.register<Sync>("prepareTestRepo") {
    into(testRepoDir)
    listOf(":model", ":runtime", ":compiler", ":spring").forEach { modulePath ->
        val moduleName = modulePath.removePrefix(":")
        val artifactDir = "no/beint/riss/$moduleName/$rissVersion"
        from(project(modulePath).tasks.named("jar")) {
            into(artifactDir)
            rename(".*", "$moduleName-$rissVersion.jar")
        }
        from(project(modulePath).tasks.named("generatePomFileForMavenPublication")) {
            into(artifactDir)
            rename(".*", "$moduleName-$rissVersion.pom")
        }
    }
}

tasks.jar {
    manifest.attributes["Implementation-Version"] = project.version
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(prepareTestRepo)
    systemProperty("riss.testRepo", testRepoDir.get().asFile.absolutePath)
    systemProperty("riss.version", rissVersion)
    systemProperty("riss.springVersion", libs.versions.spring.asProvider().get())
}
