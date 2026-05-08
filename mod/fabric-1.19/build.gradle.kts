plugins {
    id("fabric-loom") version "1.14.10"
}

base {
    archivesName.set("debugbridge-1.19")
}

dependencies {
    implementation(project(":core"))
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    minecraft("com.mojang:minecraft:1.19")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.14.21")

    // Include core's dependencies
    include(project(":core"))
    include("org.luaj:luaj-jse:3.0.1")
    include("org.java-websocket:Java-WebSocket:1.5.7")
    include("com.google.code.gson:gson:2.11.0")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
