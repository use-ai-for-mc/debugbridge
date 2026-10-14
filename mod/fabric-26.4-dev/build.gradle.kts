plugins {
    id("net.fabricmc.fabric-loom") version "1.17.20"
}

base {
    archivesName.set("debugbridge-26.4")
}

java {
    // The 26.4 snapshots declare a Java 25 runtime.
    // JDK 25 is sufficient for compile and run.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(project(":core"))
    implementation("org.apache.groovy:groovy:5.1.1")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("com.google.code.gson:gson:2.14.0")
    minecraft("com.mojang:minecraft:26.4-snapshot-3")
    implementation("net.fabricmc:fabric-loader:0.19.5")

    include(project(":core"))
    include("org.apache.groovy:groovy:5.1.1")
    include("org.java-websocket:Java-WebSocket:1.6.0")
    include("com.google.code.gson:gson:2.14.0")
}

loom {
    runs {
        configureEach {
            // The snapshot's launcher metadata starts the game with this; Loom does not pass
            // it on. Without it shader compilation in shaderc overflows the stack of the
            // calling thread and the client dies at startup with an access violation.
            vmArg("-XX:StackShadowPages=32")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    // The 26.4 snapshot metadata declares Java runtime 25.
    options.release.set(25)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
