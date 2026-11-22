plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

group = "ch.lauzhack.twin"
version = "0.0.1"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.compression)
    implementation(libs.logback.classic)
    implementation(libs.paho.mqttv3)
    implementation(libs.sqlite.jdbc)
    implementation(libs.anthropic.java)
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}

// Load secrets from a local, git-ignored .env into `./gradlew run` (MQTT_BROKER_URL, MQTT_USERNAME, MQTT_PASSWORD).
tasks.named<JavaExec>("run") {
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .forEach { line ->
                val (k, v) = line.split("=", limit = 2)
                if (System.getenv(k.trim()) == null) environment(k.trim(), v.trim())
            }
    }
}

// Passenger voice assistant (client/, React + Vite, ex sbb-client): built into build/client/static/assistant/ and
// packaged as resources, served at /assistant/. Needs Node + npm on the PATH. `-PskipClient=true` skips npm and
// packages whatever build/client already holds (the Docker build compiles the client in its own Node stage).
val skipClient = providers.gradleProperty("skipClient").map { it.toBoolean() }.getOrElse(false)
val npmCi by tasks.registering(Exec::class) {
    onlyIf { !skipClient }
    workingDir = file("client")
    commandLine("npm", "ci", "--no-audit", "--no-fund")
    inputs.file("client/package-lock.json")
    outputs.dir("client/node_modules")
}
val buildClient by tasks.registering(Exec::class) {
    onlyIf { !skipClient }
    dependsOn(npmCi)
    workingDir = file("client")
    commandLine("npm", "run", "build")
    inputs.dir("client/src")
    inputs.dir("client/public")
    inputs.files("client/index.html", "client/vite.config.ts", "client/package-lock.json")
    outputs.dir(layout.buildDirectory.dir("client"))
}
sourceSets.main { resources.srcDir(buildClient.map { it.outputs.files.singleFile }) }
