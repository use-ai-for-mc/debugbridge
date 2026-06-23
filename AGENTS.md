# DebugBridge — project notes

## What this is

A Fabric client mod (Minecraft 1.19, 1.21.11, exact 26.1, and 26.2-dev) that exposes a local WebSocket server for a Vue web UI and for MCP clients to introspect/control the running client. Used for dev-time debugging, not gameplay.

## Repo layout

- `mod/core/` — shared Java: WebSocket server (`BridgeServer`), Groovy runtime, mapping resolver, provider interfaces (`NearbyEntitiesProvider`, `ScreenshotProvider`, `ItemTextureProvider`, `GameStateProvider`).
- `mod/fabric-1.19/`, `mod/fabric-1.21.11/`, `mod/fabric-26.1/`, and `mod/fabric-26.2-dev/` — version-specific Fabric mods. Each has its own provider impls + mixins.
- `web-ui/` — Vue 3 + Pinia + Tailwind app.
- `build-and-deploy.sh` (1.19) and `build-and-deploy-1.21.11.sh` — build the jar and copy into `~/Library/Application Support/ModrinthApp/profiles/ImagineFun/mods/`.
- `build-and-deploy-26.1.sh` — builds exact `fabric-26.1` with Java 25 and installs to Prism Launcher. Defaults: `PRISM_INSTANCE_NAME=26.1`, `PRISM_INSTANCES_DIR=~/Library/Application Support/PrismLauncher/instances`, and `JAVA_HOME_26_1=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`. It verifies the built/staged jar metadata for exact Minecraft `26.1` before replacing the target jar and prints guarded smoke commands with `--version 26.1` and `--game-dir-contains`.

## Ports

- Default: 9876, wraparound range 9876–9886.
- User often runs multiple clients simultaneously; confirm which bridge port belongs to which instance before making live runtime assumptions.

## Build requirements

- Stable 1.x Fabric modules need **JDK 21**. Build scripts already set `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
- Exact `fabric-26.1` and `fabric-26.2-dev` need **JDK 25**. For 26.1 Prism install, prefer `./build-and-deploy-26.1.sh`.
- Node for `web-ui` needs **≥20.19** (Vite requirement). The default `node` on PATH is 18; use `/Users/cusgadmin/.nvm/versions/node/v20.19.4/bin/npm` or `nvm use 20`.
- Start web UI: `cd web-ui && /Users/cusgadmin/.nvm/versions/node/v20.19.4/bin/npm run dev` → <http://localhost:5173>.
- Smoke scripts (`tools/smoke-test.mjs`, `tools/record-video-smoke.mjs`) need **Node 22+** for built-in `WebSocket`. `build-and-deploy-26.1.sh` detects an installed Node 22+ binary for the post-deploy commands; override with `SMOKE_NODE=/path/to/node` if needed.

## Request dispatch pattern

`BridgeServer.handleRequest()` is a switch on `req.type`. To add a new endpoint:

1. Add a `case "yourType" -> handleYourType(req);` line.
2. Add a `handleYourType(BridgeRequest req)` method.
3. If it needs version-specific Java, add a method to an existing provider interface (or create a new one) in `core/`, implement in each version module, and register via `server.setXxxProvider()` in each `DebugBridgeMod.java`.
4. Add a typed wrapper in `web-ui/src/services/bridge.ts`.

## Mapping Fabric intermediary names to Mojang names

`MappingResolver.unresolveClass(runtimeClassName)` converts intermediary names (`class_XXXX`) to Mojang names. Do this in `BridgeServer` handlers before sending over the wire — keeps version-specific providers simple (they just emit `entity.getClass().getName()`). Already done for `nearbyEntities.type`, `entityDetails.type`/`vehicle`/`passengers[]`.

## Refs / Object Browser

`java.ref(id)` in Groovy resolves a stable ref ID backed by `ObjectRefStore` (WeakReferences). MCP clients learn to use refs through tool descriptions — no runtime registration needed.

## Mixins

Each version module has a mixin package + `debugbridge.mixins.json` listing the client-side mixins. Current ones:

- `MinecraftClientMixin` — taps the end of `Minecraft.tick()` for our `onClientTick` callback.
- `EntityGlowMixin` — forces `Entity.isCurrentlyGlowing()` to return `true` for IDs in `ClientEntityGlowManager`, so the web UI can outline selected entities without server authority.
- `BlockGlowGizmoMixin` (26.1 only) — emits DebugBridge-owned block highlight gizmos during `LevelRenderer.extractLevel(...)`, after vanilla GameTest gizmos. Do not route 26.1 block glow through `gameTestBlockHighlightRenderer.clear()` / `highlightPos(...)`; that clobbers unrelated vanilla GameTest markers.

## Native entity/texture endpoints

Do NOT iterate entities or resolve textures via Groovy — the per-call Java↔script bridge overhead causes 10s timeouts with ~100+ entities. Native Java endpoints:

- `nearbyEntities` / `entityDetails` via `NearbyEntitiesProvider` (both versions).
- `getItemTexture` / `getEntityItemTexture` via `ItemTextureProvider`:
  - **26.1**: renders offscreen through the exact-26.1 GUI item pipeline verified through MCDEV: `GuiGraphicsExtractor.item(...)` → `TrackingItemStackRenderState` → `ItemModelResolver.updateForTopItem(...)` → `ItemStackRenderState.submit(...)`, rendered through `FeatureRenderDispatcher` into a bridge-owned copy-readable GPU texture. Filled maps use CPU map-color extraction. Vanilla's final atlas entrypoint is private (`GuiItemAtlas.drawToSlot(...)`), so the bridge intentionally copies that small render body instead of calling it directly. The remaining intentional caveat is the mixin accessor for `FeatureRenderDispatcher.bufferSource`, because 26.1 exposes `getSubmitNodeStorage()` but not the buffer source.
  - **1.21.11**: renders offscreen through `ItemModelResolver` + `GuiRenderer` → GPU texture → PNG. Honors damage/CMD resource-pack overrides.
  - **1.19**: extracts pixels from the baked model's sprite via reflection (no GPU render pipeline in that version).

## Exact 26.1 API quirks

- Exact 26.1 is Mojang-named at runtime in this project; `DebugBridgeMod.createNamespaceLookup()` intentionally returns `null`, which makes the core use `PassthroughResolver` and skip Mojang mapping download/remap.
- Use JDK 25 for `fabric-26.1`; JDK 21 is still required for the older 1.19/1.21.11 modules.
- Always verify 26.1 Minecraft APIs and mixin targets through MCDEV before using them. Do not infer 26.1 names from 1.21.11 or 26.2-dev snapshots.
- Do not restart the live Prism/Minecraft client while the user is on a ride unless they explicitly approve the restart.

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
- 26.1 block glow is rendered as DebugBridge-owned gizmos, not vanilla GameTest markers. It is exact to `ClientBlockGlowManager.snapshot()` each render extraction but uses a fixed green fill/text style for now.
