# DebugBridge

A Fabric client mod for Minecraft (1.19, 1.21.11, exact 26.1, and stable 26.2) that exposes game state over a local WebSocket server, plus a Vue web UI for visual inspection. Built for AI-assisted Minecraft development and debugging.

## What It Does

DebugBridge runs a localhost-only WebSocket server (default port 9876, scans 9876–9886) inside Minecraft. External tools — CLI scripts, the bundled Vue web UI, or MCP clients like Claude Code — can inspect and interact with the running game through two complementary APIs:

### Native endpoints (fast, high-level)

Purpose-built Java endpoints that return structured JSON in a single round-trip — no scripting overhead:

| Endpoint | What it returns |
|---|---|
| `snapshot` | Player position, health, food, dimension, gamemode, time, weather |
| `nearbyEntities` | Entities within range: type, position, equipment, distance (with optional `includeIcons` for item textures) |
| `entityDetails` | Full entity info: equipment slots with damage/custom names, vehicle, passengers, attributes, frame contents |
| `lookedAtEntity` | The entity the player is aiming at (raycast) |
| `nearbyBlocks` | Block-entities within range: signs, chests, banners, beacons, furnaces, etc. |
| `blockDetails` | Block-entity contents: sign lines, chest inventory, skull profile, beacon level |
| `screenInspect` | Current open screen/gui: type, title, container slots with item stacks (with optional `includeIcons`) |
| `chatHistory` | Recent client-side chat messages (with optional `includeJson` for styled components) |
| `screenshot` | Capture the framebuffer as JPEG |
| `record_video` | Capture N framebuffer frames (every frame or at a fixed interval) as a JPEG contact-sheet grid or per-frame files |
| `getItemTexture` / `getItemTextureById` / `getEntityItemTexture` | Render item icons as PNG (honors damage, custom model data, dyed leather, player heads) |
| `setEntityGlow` / `setBlockGlow` / `clearBlockGlow` | Highlight entities or blocks with an in-world outline |
| `search` | Search loaded classes by name pattern |
| `status` | Server health and connection info |

Two endpoint families are **gated off by default** in `config/debugbridge.json`: `runCommand` (`run_command_enabled`) sends commands as the player, and the session-control trio `disconnect` / `joinServer` / `quit` (`session_control_enabled`) lets an automation loop leave a world, join a server, or shut the client down. `joinServer` pre-accepts the server resource pack and defers the connect until the client has settled (no loading overlay — joining during the startup resource reload would silently drop the server pack), acking only once the connect attempt has actually started; it's safe to fire the moment the bridge port answers after a launch.

### Groovy execution (`execute` endpoint)

Run Groovy scripts inside the Minecraft JVM with full access to Minecraft APIs via a mapping-aware Java bridge — write Mojang names and they resolve to the runtime (intermediary) names automatically, even on obfuscated builds. Convenience globals `mc`, `player`, and `level` are pre-bound. The sandbox allows file I/O for reading/writing data. Each request has a configurable timeout (default 10s, max 5 min).

```groovy
// Convenience globals already available
println "Player at: " + player.blockPosition().toShortString()
println "Dimension: " + mc.level.dimension().location()

// Load anything else by Mojang name (works on obfuscated builds too).
// Prefer single quotes: double-quoted GStrings interpolate the $ in
// inner-class names like Display$TextDisplay.
def Vec3 = java.type('net.minecraft.world.phys.Vec3')

// Iterate Java collections. Wrap a bulk loop in sync { } so the per-call
// reflective dispatch batches into a single game-thread hop.
sync {
    java.list(level.entitiesForRendering()).each { entity ->
        if (entity.distanceTo(player) < 10) {
            println "Nearby: " + java.typeName(entity)
        }
    }
}
```

## Vue Web UI

The `web-ui/` directory contains a Vue 3 + Pinia + Tailwind app for visual inspection of game state:

- **Dashboard** — Player snapshot overview
- **Entities panel** — Nearby entity list with detail drill-down, equipment icons, glow toggling
- **Blocks panel** — Nearby block-entity browser (signs, chests, etc.)
- **Screen inspector** — Current GUI/inventory slot viewer
- **Groovy inspector** — Drill-down object browser for Java objects
- **Console** — Interactive Groovy REPL connected to the running client
- **Chat history** — Recent client-side messages

**Bundled since v2.0.0** — the mod serves the built UI itself at
`http://localhost:<bridge port + 100>` (default **http://localhost:9976**;
the join-time chat message prints the exact URL as a clickable link).
Loopback-only, static
assets only, and the served page connects to the bridge instance that served
it, so side-by-side game instances each get their own UI. Disable with
`"web_ui_enabled": false` in `config/debugbridge.json`.

For UI development, the dev server still works (with HMR):
```bash
cd web-ui
npm run dev          # → http://localhost:5173
```

The web UI connects directly to the WebSocket server — no MCP layer required.

## Architecture

```
+-----------------------------------+
|  MCP Client (Claude Code, etc.)   |
+---------------+-------------------+
                | MCP Protocol (stdio)
+---------------v-------------------+
|  mcdev-mcp Server (TypeScript)    |
|  Runtime + static analysis tools  |
| github.com/use-ai-for-mc/mcdev-mcp|
+---------------+-------------------+
                | WebSocket (localhost:9876–9886)
+---------------v-------------------+
|  DebugBridge Mod [THIS REPO]      |
|  +-----------------------------+  |
|  | BridgeServer (WebSocket)    |  |
|  | Native endpoints + execute  |  |
|  | Groovy runtime + Java bridge |  |
|  | Mojang mapping resolver     |  |
|  +-----------------------------+  |
+-----------------------------------+
                ^
                | WebSocket (same port)
+---------------+-------------------+
|  Vue Web UI (bundled, :9976;      |
|  npm dev server :5173)            |
+-----------------------------------+
```

## Mojang Mapping Support

The mod automatically downloads official Mojang mappings at startup and uses them to translate human-readable names (`net.minecraft.client.Minecraft`) to the obfuscated names used at runtime. In 1.21.11+, Mojang ships unobfuscated names and mapping is a no-op.

The exact 26.1 and 26.2 stable builds target Mojang-named classes directly and skip mapping download/remap entirely.

## Security Model

**DebugBridge binds exclusively to localhost (127.0.0.1).** Only processes running on the same machine can connect. The debug port is never exposed to the network.

This is a **development and debugging tool**, not a remote administration system. Anyone with localhost access already has full control over the Minecraft process, so the bridge does not introduce new attack surface.

- **Client-side only** — runs entirely on the client, cannot affect servers or other players
- **No outbound connections** — only startup mapping downloads from Mojang's official APIs
- **Gated features** — `runCommand` and session control are disabled by default (opt-in via config)
- **Developer warning** — first-launch screen informs the user the mod is active; nothing serves until accepted
- **Web UI server** — same loopback-only posture; serves only the static assets bundled in the jar (GET-only, no directory listing, path-traversal rejected)

## Repo Layout

```
mod/
  core/          — Shared Java: BridgeServer, Groovy runtime, mapping resolver, provider interfaces
  fabric-1.19/   — Fabric mod for Minecraft 1.19.x (provider impls + mixins)
  fabric-1.21.11/— Fabric mod for Minecraft 1.21.11 (provider impls + mixins)
  fabric-26.1/   — Fabric mod for exact Minecraft 26.1 (provider impls + mixins)
  fabric-26.2-dev/— Fabric mod for Minecraft 26.2 stable
web-ui/          — Vue 3 + Pinia + Tailwind inspection app
```

## Installation

Grab the jar for your Minecraft version from the
[GitHub releases](https://github.com/use-ai-for-mc/debugbridge/releases)
(`debugbridge-1.19-*.jar`, `debugbridge-1.21.11-*.jar`, `debugbridge-26.1-*.jar`, or
`debugbridge-26.2-*.jar`), drop it into your instance's `mods/` folder, and
launch with Fabric Loader. Client-side only — nothing to install on a server.
On first run the mod shows a developer warning in-game and stays inactive
until you accept it; the same gate writes `developer_mode_accepted` into
`config/debugbridge.json`. Once accepted, the bundled web UI is at
**http://localhost:9976** (the in-game startup message prints the exact URL).

For the local exact-26.1 Prism Launcher instance, use the repo script:

```bash
JAVA_HOME_26_1=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  PRISM_INSTANCE_NAME=26.1 \
  ./build-and-deploy-26.1.sh
```

To check the selected Prism instance, Java path, parsed Gradle version, target
mods directory, and smoke-test port without building or touching the instance:

```bash
JAVA_HOME_26_1=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  PRISM_INSTANCE_NAME=26.1 \
  ./build-and-deploy-26.1.sh --preflight
```

The script builds `mod/fabric-26.1`, stages the jar, verifies it with
`unzip -tq`, and atomically swaps it into the selected Prism instance's
`mods/` directory. It verifies the built and staged jars declare exact
Minecraft `26.1` and the `com.debugbridge.fabric261.DebugBridgeMod`
entrypoint before replacing the installed jar. If the bridge uses a non-default
port for the final smoke run, set `SMOKE_PORT=<port>` when invoking the script;
the post-deploy command hints will use that port.

## Building

Requires **JDK 21+** for the stable 1.x Fabric modules, **JDK 25** for
`fabric-26.1` and stable `26.2` (module path `fabric-26.2-dev`, matching the runtime declared by the
26.x version manifests), and **Node >=20.19** for the web UI.

```bash
# Fabric mods
cd mod
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew build
# JARs -> mod/fabric-*/build/libs/

# Exact 26.1 bridge
cd mod
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ./gradlew :fabric-26.1:jar

# 26.2 stable bridge
cd mod
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ./gradlew :core:test :fabric-26.2-dev:jar

# Web UI
cd web-ui
npm install
npm run dev          # dev server at http://localhost:5173
npm run build        # production build → web-ui/dist/
```

## Testing

Three layers, automated where it pays off:

**1. Core unit tests** — pure JVM, no Minecraft. Runs every PR via `.github/workflows/build.yml`.

```bash
cd mod
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :core:test
```

Covers the Groovy bridge runtime, mapping resolver kernel (with stubbed Fabric SPI), and wire-contract tests for every endpoint (using stub providers).

**2. Smoke test against a live mod** — `tools/smoke-test.mjs`. Connects via WebSocket, hits each kernel-candidate endpoint, validates the response. ~30 seconds. Requires Node 22+ for the built-in `WebSocket` global.

```bash
# Smoke test against running 1.21.11 mod
node tools/smoke-test.mjs --port 9876 --version 1.21.11

# 1.19 alongside (default ports: 1.21.11=9876, 1.19=9877)
node tools/smoke-test.mjs --port 9877 --version 1.19

# Exact 26.1 after deploying to a Prism instance
node tools/smoke-test.mjs --port 9876 --version 26.1 --game-dir-contains '/instances/26.1/' --include-textures
node tools/record-video-smoke.mjs --port 9876 --version 26.1 --game-dir-contains '/instances/26.1/'
```

**3. Wire-shape regression mode** — same script with `--regression DIR`. Compares each live response's structural shape (key sets at every level, value types) against a captured fixture; fails on drift.

```bash
# Capture baselines once after a known-good build
node tools/smoke-test.mjs --port 9876 --version 1.21.11 \
    --fixtures tools/fixtures/1.21.11

# Later, after deploying a new build, check for drift
node tools/smoke-test.mjs --port 9876 --version 1.21.11 \
    --regression tools/fixtures/1.21.11
```

The shape comparator tolerates value differences (transient game state) but flags any structural change — added/removed keys, type mismatches. **Conditional fields can produce false positives:** `snapshot.target` is only present when the player is aiming at something; `nearbyEntities[].primaryEquipment` only when the closest entity wears something. Capture fixtures from a richly-populated state, or treat conditional-field diffs as expected.

## Usage

### With Claude Code / MCP

Install [mcdev-mcp](https://github.com/use-ai-for-mc/mcdev-mcp) and configure it in your MCP client. The MCP server auto-connects to DebugBridge (scans ports 9876–9886). Tools include:

- **Runtime** (requires this mod): `mc_execute`, `mc_snapshot`, `mc_nearby_entities`, `mc_entity_details`, `mc_looked_at_entity`, `mc_nearby_blocks`, `mc_block_details`, `mc_screen_inspect`, `mc_chat_history`, `mc_screenshot`, `mc_record_video`, `mc_get_item_texture`, `mc_set_entity_glow`, `mc_set_block_glow`, `mc_clear_block_glow`
- **Session control / dev loop** (requires `session_control_enabled`): `mc_join_server`, `mc_leave_server`, `mc_quit_client`, plus `mc_wait_for_bridge` / `mc_wait_until_in_world` for driving rebuild → relaunch → rejoin loops
- **Static** (works offline): `mc_get_class`, `mc_get_method`, `mc_search`, `mc_find_refs`, `mc_find_hierarchy`

### Direct WebSocket

Connect to `ws://127.0.0.1:9876` and send JSON:

```json
{
  "id": "1",
  "type": "execute",
  "payload": {
    "code": "return player.blockPosition().toShortString()"
  }
}
```

Each request gets a matching `{id, type, payload}` response.

## Dependencies Bundled in JAR

- **Apache Groovy 5.0.6** — JVM scripting runtime (Apache-2.0)
- **Java-WebSocket 1.6.0** — WebSocket server (MIT)
- **Gson 2.14.0** — JSON parsing (Apache 2.0)

## License

MIT License — see LICENSE file for details.
