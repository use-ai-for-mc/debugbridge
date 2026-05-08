plugins {
    java
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

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }
}
