# DebugBridge

A Fabric client mod for Minecraft (1.19, 1.21.11, and 26.2 development snapshots) that exposes game state over a local WebSocket server, plus a Vue web UI for visual inspection. Built for AI-assisted Minecraft development and debugging.

## What It Does

DebugBridge runs a localhost-only WebSocket server (default port 9876, scans 9876–9885) inside Minecraft. External tools — CLI scripts, the bundled Vue web UI, or MCP clients like Claude Code — can inspect and interact with the running game through two complementary APIs:

### Native endpoints (fast, high-level)

Purpose-built Java endpoints that return structured JSON in a single round-trip — no Lua overhead:

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
| `getItemTexture` / `getItemTextureById` / `getEntityItemTexture` | Render item icons as PNG (honors damage, custom model data, dyed leather, player heads) |
| `setEntityGlow` / `setBlockGlow` / `clearBlockGlow` | Highlight entities or blocks with an in-world outline |
| `search` | Search loaded classes by name pattern |
| `status` | Server health and connection info |

### Lua execution (`execute` endpoint)

Run Lua 5.2 scripts inside the Minecraft JVM with full access to Minecraft APIs via a Java reflection bridge. Convenience globals `mc`, `player`, and `level` are pre-bound. The sandbox allows file I/O for reading/writing data. Each request has a configurable timeout (default 10s, max 5 min).

```lua
-- Convenience globals already available
print("Player at: " .. player:blockPosition():toShortString())
print("Dimension: " .. mc.level:dimension().location())

-- Import anything else you need
local Vec3 = java.import("net.minecraft.world.phys.Vec3")

-- Iterate Java collections
for entity in java.iter(level:entitiesForRendering()) do
    if entity:distanceTo(player) < 10 then
        print("Nearby: " .. java.typeof(entity))
    end
end
```

## Vue Web UI

The `web-ui/` directory contains a Vue 3 + Pinia + Tailwind app for visual inspection of game state:

- **Dashboard** — Player snapshot overview
- **Entities panel** — Nearby entity list with detail drill-down, equipment icons, glow toggling
- **Blocks panel** — Nearby block-entity browser (signs, chests, etc.)
- **Screen inspector** — Current GUI/inventory slot viewer
- **Lua inspector** — Drill-down object browser for Lua values and Java objects
- **Console** — Interactive Lua REPL connected to the running client
- **Chat history** — Recent client-side messages

Start it with:
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
|  github.com/weikengchen/mcdev-mcp |
+---------------+-------------------+
                | WebSocket (localhost:9876–9885)
+---------------v-------------------+
|  DebugBridge Mod [THIS REPO]      |
|  +-----------------------------+  |
|  | BridgeServer (WebSocket)    |  |
|  | Native endpoints + execute  |  |
|  | Lua runtime + Java bridge   |  |
|  | Mojang mapping resolver     |  |
|  +-----------------------------+  |
+-----------------------------------+
                ^
                | WebSocket (same port)
+---------------+-------------------+
|  Vue Web UI (localhost:5173)      |
|  Dashboard, Inspector, Console    |
+-----------------------------------+
```

## Mojang Mapping Support

The mod automatically downloads official Mojang mappings at startup and uses them to translate human-readable names (`net.minecraft.client.Minecraft`) to the obfuscated names used at runtime. In 1.21.11+, Mojang ships unobfuscated names and mapping is a no-op.

The 26.2 development build targets Mojang-named snapshot classes directly and skips mapping download/remap entirely.

## Security Model

**DebugBridge binds exclusively to localhost (127.0.0.1).** Only processes running on the same machine can connect. The debug port is never exposed to the network.

This is a **development and debugging tool**, not a remote administration system. Anyone with localhost access already has full control over the Minecraft process, so the bridge does not introduce new attack surface.

- **Client-side only** — runs entirely on the client, cannot affect servers or other players
- **No outbound connections** — only startup mapping downloads from Mojang's official APIs
- **Gated features** — `runCommand` is disabled by default (opt-in via config)
- **Developer warning** — first-launch screen informs the user the mod is active

## Repo Layout

```
mod/
  core/          — Shared Java: BridgeServer, Lua runtime, mapping resolver, provider interfaces
  fabric-1.19/   — Fabric mod for Minecraft 1.19.x (provider impls + mixins)
  fabric-1.21.11/— Fabric mod for Minecraft 1.21.11 (provider impls + mixins)
  fabric-26.2-dev/— Fabric mod for Minecraft 26.2 development snapshots
web-ui/          — Vue 3 + Pinia + Tailwind inspection app
```

## Building

Requires **JDK 21+** for the stable Fabric modules, **JDK 26** for `fabric-26.2-dev`, and **Node ≥20.19** for the web UI.

```bash
# Fabric mods
cd mod
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew build
# JARs -> mod/fabric-*/build/libs/

# 26.2 development snapshot bridge
cd mod
./gradlew :core:test :fabric-26.2-dev:jar

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

Covers the Lua bridge runtime, mapping resolver kernel (with stubbed Fabric SPI), and wire-contract tests for every endpoint (using stub providers).

**2. Smoke test against a live mod** — `tools/smoke-test.mjs`. Connects via WebSocket, hits each kernel-candidate endpoint, validates the response. ~30 seconds. Requires Node 22+ for the built-in `WebSocket` global.

```bash
# Smoke test against running 1.21.11 mod
node tools/smoke-test.mjs --port 9876 --version 1.21.11

# 1.19 alongside (default ports: 1.21.11=9876, 1.19=9877)
node tools/smoke-test.mjs --port 9877 --version 1.19
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

Install [mcdev-mcp](https://github.com/weikengchen/mcdev-mcp) and configure it in your MCP client. The MCP server auto-connects to DebugBridge (scans ports 9876–9885). Tools include:

- **Runtime** (requires this mod): `mc_execute`, `mc_snapshot`, `mc_nearby_entities`, `mc_entity_details`, `mc_looked_at_entity`, `mc_nearby_blocks`, `mc_block_details`, `mc_screen_inspect`, `mc_chat_history`, `mc_screenshot`, `mc_get_item_texture`, `mc_set_entity_glow`, `mc_set_block_glow`, `mc_clear_block_glow`
- **Static** (works offline): `mc_get_class`, `mc_get_method`, `mc_search`, `mc_find_refs`, `mc_find_hierarchy`

### Direct WebSocket

Connect to `ws://127.0.0.1:9876` and send JSON:

```json
{
  "id": "1",
  "type": "execute",
  "payload": {
    "code": "return player:blockPosition():toShortString()"
  }
}
```

Each request gets a matching `{id, type, payload}` response.

## Dependencies Bundled in JAR

- **LuaJ 3.0.1** — Pure Java Lua 5.2 (MIT)
- **Java-WebSocket 1.5.7** — WebSocket server (MIT)
- **Gson 2.11.0** — JSON parsing (Apache 2.0)

## License

MIT License — see LICENSE file for details.
