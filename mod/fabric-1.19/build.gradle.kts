plugins {
    id("fabric-loom") version "1.17.11"
}

base {
    archivesName.set("debugbridge-1.19")
}

dependencies {
    implementation(project(":core"))
    implementation("org.java-websocket:Java-WebSocket:1.6.0")

    minecraft("com.mojang:minecraft:1.19")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.19.3")

    // Include core's dependencies
    include(project(":core"))
    include("org.apache.groovy:groovy:5.0.6")
    include("org.java-websocket:Java-WebSocket:1.6.0")
    include("com.google.code.gson:gson:2.14.0")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
