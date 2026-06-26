plugins {
    application
}

description = "Real-world demos showing java-ai-agent's usefulness (consumes the published artifacts)."

repositories {
    mavenCentral()
}

dependencies {
    // One BOM pins every java-ai-agent module to the same release.
    implementation(platform("io.github.vaiju1981:agent-bom:0.5.0"))
    implementation("io.github.vaiju1981:agent-core")
    implementation("io.github.vaiju1981:agent-langchain4j")
    // The multi-agent showcase reaches one specialist over Agent-to-Agent (HTTP).
    implementation("io.github.vaiju1981:agent-a2a")
    // The production trust layer the demos run through: governed runtime, validation, durable stores.
    implementation("io.github.vaiju1981:agent-tools-jsonschema")
    implementation("io.github.vaiju1981:agent-store-jdbc")
    implementation("io.github.vaiju1981:agent-store-sqlite")
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")

    testImplementation(platform("org.junit:junit-bom:6.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    // Override with -PmainClass=...<Demo> to run a different demo.
    mainClass.set(providers.gradleProperty("mainClass")
        .orElse("dev.vaijanath.aiagent.demos.data.DataAnalystDemo"))
    // SQLite loads a native lib; opt in so JDK 22+ doesn't warn.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Compile with the running JDK but target a Java 21 baseline (the version the library artifacts are
// built to), so no separate JDK 21 toolchain needs to be installed.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
