plugins {
    java
}

dependencies {
    // Groovy - JVM scripting runtime for the `execute` endpoint (mapping-aware
    // Java/Minecraft interop). Replaces the former LuaJ runtime.
    implementation("org.apache.groovy:groovy:4.0.32")

    // Java-WebSocket - lightweight WebSocket server
    implementation("org.java-websocket:Java-WebSocket:1.6.0")

    // Gson - JSON serialization
    implementation("com.google.code.gson:gson:2.14.0")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
