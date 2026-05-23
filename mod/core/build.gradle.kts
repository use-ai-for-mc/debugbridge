plugins {
    java
}

dependencies {
    // LuaJ - pure Java Lua 5.2 implementation
    implementation("org.luaj:luaj-jse:3.0.1")

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
