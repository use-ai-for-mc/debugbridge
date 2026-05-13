import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    java
    id("com.diffplug.spotless") version "7.0.2" apply false
}

allprojects {
    group = "com.debugbridge"
    version = "1.1.0"

    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }

    configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            palantirJavaFormat("2.90.0")
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}
