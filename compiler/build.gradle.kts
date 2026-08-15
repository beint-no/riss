import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.jvm.JvmTargetValidationMode
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvmToolchain(26)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    jvmTargetValidationMode.set(JvmTargetValidationMode.IGNORE)
}

dependencies {
    implementation(project(":model"))
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.10")
    implementation("org.ow2.asm:asm:9.8")
    implementation("tools.jackson.core:jackson-databind:3.0.3")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
