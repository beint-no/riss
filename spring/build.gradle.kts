plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

dependencies {
    api(project(":runtime"))
    api(libs.spring.webmvc)
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.servlet.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.spring.test)
    testImplementation(libs.servlet.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
}
