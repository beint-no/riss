plugins {
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish")
}

dependencies {
    implementation(libs.ksp.gradle.plugin)
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

tasks.jar {
    manifest.attributes["Implementation-Version"] = project.version
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}
