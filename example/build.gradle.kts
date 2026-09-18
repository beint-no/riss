import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.ksp)
    alias(libs.plugins.spring.boot)
}

kotlin {
    jvmToolchain(27)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_26)
}

dependencies {
    implementation(kotlin("reflect"))
    implementation(project(":spring"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    compileOnly(libs.swagger.annotations)
    ksp(project(":compiler"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testRuntimeOnly(libs.junit.platform.launcher)
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
