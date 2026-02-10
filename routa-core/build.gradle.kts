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
    mainClass.set("com.phodal.routa.core.cli.RoutaCliKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin standard libraries
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Koog AI Agent framework
    implementation(libs.koog.agents)
    implementation(libs.koog.prompt.executor)

    // MCP SDK for tool exposure
    implementation(libs.mcp.sdk)

    // ACP SDK for agent spawning (CRAFTER backend)
    implementation(libs.acp.sdk) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json")
    }
    implementation(libs.acp.model) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json")
    }
    implementation(libs.kotlinx.io.core)

    // YAML config reading
    implementation(libs.kaml)

    // Logging (suppress SLF4J warnings from Koog)
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
