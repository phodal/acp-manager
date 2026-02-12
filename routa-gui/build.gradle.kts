plugins {
    alias(libs.plugins.kotlin)
    application
}

group = "com.phodal.routa"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.phodal.routa.gui.RoutaGuiAppKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":routa-core"))

    // Kotlin coroutines (core + Swing dispatcher)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)

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
