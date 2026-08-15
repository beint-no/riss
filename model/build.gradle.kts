plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

// The model intentionally has no production dependencies outside the JDK.

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
