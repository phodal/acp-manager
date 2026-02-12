plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    application
}

group = "com.phodal.routa"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.phodal.routa.hub.mcp.AgentHubCliKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

repositories {
    mavenCentral()
}

dependencies {
    // Routa core for agent tools, models, stores, events
    implementation(project(":routa-core"))

    // Kotlin standard libraries
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // MCP SDK for tool exposure
    implementation(libs.mcp.sdk)

    // Ktor for MCP WebSocket/SSE server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.sse)

    // Logging
    runtimeOnly(libs.slf4j.simple)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
