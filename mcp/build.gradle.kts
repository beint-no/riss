plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    pom {
        name.set("Riss MCP")
        description.set("Independent JDK-only OpenAPI-to-MCP compiler and tools server.")
    }
}

tasks.jar {
    manifest.attributes("Main-Class" to "no.beint.riss.mcp.Main")
}

val verifyMcp = tasks.register<JavaExec>("verifyMcp") {
    group = "verification"
    description = "Runs the dependency-free MCP protocol and integration checks"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("no.beint.riss.mcp.McpTest")
}

tasks.check { dependsOn(verifyMcp) }

// Verification uses a Java main so even the test classpath has no external dependencies.
tasks.test {
    enabled = false
    dependsOn(verifyMcp)
}
