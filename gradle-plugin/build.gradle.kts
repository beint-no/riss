plugins {
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish")
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.10")
    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
