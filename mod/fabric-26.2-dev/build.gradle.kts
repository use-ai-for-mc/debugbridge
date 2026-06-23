plugins {
    id("net.fabricmc.fabric-loom") version "1.17.12"
}

base {
    archivesName.set("debugbridge-26.2")
}

java {
    // The 26.2 line declares a Java 25 runtime.
    // JDK 25 is sufficient for compile and run.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(project(":core"))
    implementation("org.apache.groovy:groovy:5.0.6")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("com.google.code.gson:gson:2.14.0")
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.3")

    include(project(":core"))
    include("org.apache.groovy:groovy:5.0.6")
    include("org.java-websocket:Java-WebSocket:1.6.0")
    include("com.google.code.gson:gson:2.14.0")
}

tasks.withType<JavaCompile>().configureEach {
    // The 26.2 metadata declares Java runtime 25.
    options.release.set(25)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}