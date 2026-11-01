plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvmToolchain(27)
}

dependencies {
    implementation(project(":model"))
    implementation(libs.ksp.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
