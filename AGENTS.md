# DebugBridge — project notes

## What this is

A Fabric client mod (Minecraft 1.19 and 1.21.11) that exposes a local WebSocket server for a Vue web UI and for MCP clients to introspect/control the running client. Used for dev-time debugging, not gameplay.

## Repo layout

- `mod/core/` — shared Java: WebSocket server (`BridgeServer`), Lua runtime, mapping resolver, provider interfaces (`NearbyEntitiesProvider`, `ScreenshotProvider`, `ItemTextureProvider`, `GameStateProvider`).
- `mod/fabric-1.19/` and `mod/fabric-1.21.11/` — version-specific Fabric mods. Each has its own provider impls + mixins.
- `web-ui/` — Vue 3 + Pinia + Tailwind app.
- `build-and-deploy.sh` (1.19) and `build-and-deploy-1.21.11.sh` — build the jar and copy into `~/Library/Application Support/ModrinthApp/profiles/ImagineFun/mods/`.

## Ports

- Default: 9876 (1.21.11), wraparound range 9876–9886.
- User typically runs 1.21.11 on 9876 and 1.19 on 9877 simultaneously.

## Build requirements

- Gradle needs **JDK 21**. System JDK (25) fails. Build scripts already set `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
- Node for `web-ui` needs **≥20.19** (Vite requirement). The default `node` on PATH is 18; use `/Users/cusgadmin/.nvm/versions/node/v20.19.4/bin/npm` or `nvm use 20`.
- Start web UI: `cd web-ui && /Users/cusgadmin/.nvm/versions/node/v20.19.4/bin/npm run dev` → <http://localhost:5173>.

## Request dispatch pattern

`BridgeServer.handleRequest()` is a switch on `req.type`. To add a new endpoint:

1. Add a `case "yourType" -> handleYourType(req);` line.
2. Add a `handleYourType(BridgeRequest req)` method.
3. If it needs version-specific Java, add a method to an existing provider interface (or create a new one) in `core/`, implement in each version module, and register via `server.setXxxProvider()` in each `DebugBridgeMod.java`.
4. Add a typed wrapper in `web-ui/src/services/bridge.ts`.

## Mapping Fabric intermediary names to Mojang names

`MappingResolver.unresolveClass(runtimeClassName)` converts intermediary names (`class_XXXX`) to Mojang names. Do this in `BridgeServer` handlers before sending over the wire — keeps version-specific providers simple (they just emit `entity.getClass().getName()`). Already done for `nearbyEntities.type`, `entityDetails.type`/`vehicle`/`passengers[]`.

## Refs / Object Browser

`java.ref(obj)` in Lua returns a stable ref ID backed by `ObjectRefStore` (WeakReferences). MCP clients learn to use refs through tool descriptions — no runtime registration needed.

## Mixins

Each version module has a mixin package + `debugbridge.mixins.json` listing the client-side mixins. Current ones:

- `MinecraftClientMixin` — taps the end of `Minecraft.tick()` for our `onClientTick` callback.
- `EntityGlowMixin` — forces `Entity.isCurrentlyGlowing()` to return `true` for IDs in `ClientEntityGlowManager`, so the web UI can outline selected entities without server authority.

## Native entity/texture endpoints

Do NOT iterate entities or resolve textures via Lua — the per-call Java↔Lua bridge overhead causes 10s timeouts with ~100+ entities. Native Java endpoints:

- `nearbyEntities` / `entityDetails` via `NearbyEntitiesProvider` (both versions).
- `getItemTexture` / `getEntityItemTexture` via `ItemTextureProvider`:
  - **1.21.11**: renders offscreen through `ItemModelResolver` + `GuiRenderer` → GPU texture → PNG. Honors damage/CMD resource-pack overrides.
  - **1.19**: extracts pixels from the baked model's sprite via reflection (no GPU render pipeline in that version).

## 1.19 vs 1.21.11 API quirks

- `GameProfile.name()` (record accessor) in 1.21.11 vs `GameProfile.getName()` in 1.19.
- `Display.TextDisplay` / `ItemDisplay` / `BlockDisplay` exist in 1.21.11 only (added in 1.19.4). Our 1.19 module targets 1.19.0, so skip display-entity extraction there entirely.
- 1.21.11 render states expose accessors like `itemRenderState().itemStack()`; 1.19 uses direct `ItemRenderer.getModel(stack, level, entity, seed)` + sprite extraction.

## Web UI conventions

- Pinia stores in `web-ui/src/stores/`, components in `web-ui/src/components/`.
- Entity detail panel (`EntitiesPanel.vue`) sits in its own overflow container under the list so it's always reachable (earlier bug: detail below the fold when list was long).
- Auto-refresh pattern: `setInterval` + in-flight flag to skip overlapping ticks; cleanup in `onUnmounted`.
- Icons for items use `image-rendering: pixelated` and a 2x display (32×32 for a 32×32 render of a 16×16 native sprite).

## Gotchas

- `WebSocketServer` must set `setReuseAddress(true)` **before** bind; our port-probe uses the same setting so probe and actual bind agree.
- When adding new detail fields, also thread them into `EntityDetails` in `entities.ts` and the `raw` passthrough (used by the Raw Object JsonTree view).
- HMR works for Vue edits; Java changes need a full rebuild + client restart.

## Known limits

- Glow outline color is always the team color / white — no per-selection color yet. Mixin into `Entity.getTeamColor()` if that's ever needed.
- Entity ID stability depends on the chunk staying loaded. If the user drifts far enough, glow "sticks" to a stale ID (harmless — just ignored).
