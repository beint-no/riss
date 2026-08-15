import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.jvm.JvmTargetValidationMode
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.spring") version "2.4.10"
    id("com.google.devtools.ksp")
    id("org.springframework.boot")
}

kotlin {
    jvmToolchain(26)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    jvmTargetValidationMode.set(JvmTargetValidationMode.IGNORE)
}

dependencies {
    implementation(kotlin("reflect"))
    implementation(project(":spring"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc:4.1.0")
    implementation("org.springframework.boot:spring-boot-starter-validation:4.1.0")
    implementation("io.swagger.core.v3:swagger-annotations-jakarta:2.2.38")
    ksp(project(":compiler"))

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test:4.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

ksp {
    arg("riss.package", "no.beint.riss.example.generated")
    arg("riss.registry", "ExampleSpec")
    arg("riss.specName", "example")
    arg("riss.scanPackages", "no.beint.riss.example")
    arg("riss.paths", "/api/**")
    arg("riss.strict", "true")
}

tasks.test {
    useJUnitPlatform()
}
