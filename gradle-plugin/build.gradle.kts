plugins {
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish")
}

dependencies {
    implementation(libs.ksp.gradle.plugin)
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
            description = "Compile Spring MVC APIs into an OpenAPI 3.1 JSON document"
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
}
