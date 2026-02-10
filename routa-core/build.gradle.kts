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

    // YAML config reading
    implementation(libs.kaml)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
