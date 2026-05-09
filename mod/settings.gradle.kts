pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "debugbridge"

include(":core")
// Fabric modules require Loom plugin + compatible Gradle version.
// Uncomment when building with Gradle 8.x and Fabric Loom.
include(":fabric-1.19")
include(":fabric-1.21.11")
include(":fabric-26.2-dev")
// include(":fabric-26.1")
