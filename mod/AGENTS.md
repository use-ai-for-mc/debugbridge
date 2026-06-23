# DebugBridge Mod Notes

This directory contains the Java/Fabric side of DebugBridge.

## Project Shape

- `core`: protocol, server, Groovy bridge, mapping, snapshots, screenshots, textures, and provider interfaces. It must stay independent of Fabric implementation packages.
- `fabric-*`: Minecraft-version-specific adapters. Version APIs stay inside their own Fabric module.

## Verification

Use the Gradle wrapper from this directory:

```powershell
.\gradlew.bat :core:test :fabric-26.2-dev:jar --console=plain
```

For exact 26.1 packaging, use Java 25 and the dedicated module:

```powershell
.\gradlew.bat :fabric-26.1:jar --console=plain
```

On the local macOS Prism Launcher setup, prefer the repo-root deploy helper:

```bash
JAVA_HOME_26_1=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  PRISM_INSTANCE_NAME=26.1 \
  ./build-and-deploy-26.1.sh
```

For broader checks, run:

```powershell
.\gradlew.bat build --console=plain
```

## Working Notes

- Use PowerShell-native search (`Get-ChildItem`, `Select-String`) if `rg` fails with `Access is denied` in Codex Desktop.
- Prefer MCP tool calls for Minecraft live checks: `mc_execute`, `mc_snapshot`, and other native runtime tools.
- Treat `fabric-26.1/src/main/resources/fabric.mod.json` and `fabric-26.2-dev/src/main/resources/fabric.mod.json` as potentially user-edited unless your task explicitly touches metadata.
