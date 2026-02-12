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
    mainClass.set("com.phodal.routa.example.a2a.scenario.RunIntegrationKt")
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

    // Koog AI Agent framework
    implementation(libs.koog.agents)
    implementation(libs.koog.prompt.executor)

    // Koog A2A support (server + client + transport)
    implementation(libs.koog.a2a.server)
    implementation(libs.koog.a2a.client)
    implementation(libs.koog.a2a.core)
    implementation(libs.koog.a2a.transport.server.http)
    implementation(libs.koog.a2a.transport.client.http)

    // Ktor (required by A2A transport)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    // Logging
    runtimeOnly(libs.slf4j.simple)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Register run tasks for individual components
fun registerRunTask(name: String, mainClassName: String) = tasks.register<JavaExec>(name) {
    doFirst {
        standardInput = System.`in`
        standardOutput = System.out
    }
    mainClass.set(mainClassName)
    classpath = sourceSets["main"].runtimeClasspath
}

registerRunTask("runHubServer", "com.phodal.routa.example.a2a.hub.AgentHubA2AServerKt")
registerRunTask("runPlannerServer", "com.phodal.routa.example.a2a.planner.PlannerA2AServerKt")
registerRunTask("runWorkerServer", "com.phodal.routa.example.a2a.worker.WorkerA2AServerKt")
registerRunTask("runIntegration", "com.phodal.routa.example.a2a.scenario.RunIntegrationKt")
registerRunTask("runRealAgents", "com.phodal.routa.example.a2a.scenario.RunRealAgentsKt")

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
