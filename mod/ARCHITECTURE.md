# DebugBridge Mod Architecture

DebugBridge is split between shared protocol code and version-specific Fabric adapters.

## Module Boundaries

Allowed production dependency directions:

- `core`: no DebugBridge module dependencies.
- `fabric-*`: may depend on `core` and on Minecraft/Fabric APIs for that module's target version.

Disallowed production edges:

- `core -> fabric-*`
- `fabric-* -> other fabric-*`

## Runtime Model

- `BridgeServer` owns the localhost WebSocket protocol.
- `ScriptRuntime` executes Groovy scripts through the Java bridge and dispatches Minecraft state access onto the game thread.
- Provider interfaces in `core` expose native fast paths for snapshots, screenshots, entities, blocks, screen inspection, chat history, and item textures.
- Each Fabric module registers the providers it can support for its Minecraft version.

## Verification

Primary 26.2 command:

```powershell
.\gradlew.bat :core:test :fabric-26.2-dev:jar --console=plain
```

Live Minecraft verification starts with `docs/live-smoke.md`.
