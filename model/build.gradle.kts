plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

// The model intentionally has no production dependencies outside the JDK.

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
