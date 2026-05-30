# DebugBridge — project notes

## What this is
A Fabric client mod (Minecraft 1.19, 1.21.11, and 26.2-dev snapshot) that exposes a local WebSocket server for a Vue web UI and for MCP clients to introspect/control the running client. Used for dev-time debugging, not gameplay.

## Repo layout
- `mod/core/` — shared Java: WebSocket server (`BridgeServer`), Groovy runtime, mapping resolver, provider interfaces (`NearbyEntitiesProvider`, `NearbyBlocksProvider`, `LookedAtEntityProvider`, `ScreenshotProvider`, `ItemTextureProvider`, `ScreenInspectProvider`, `ChatHistoryProvider`, `GameStateProvider`, `FrameCapturer` + recording orchestrator).
- `mod/fabric-1.19/`, `mod/fabric-1.21.11/`, `mod/fabric-26.2-dev/` — version-specific Fabric mods. Each has its own provider impls + mixins.
- `web-ui/` — Vue 3 + Pinia + Tailwind app.
- `build-and-deploy.sh` (1.19) and `build-and-deploy-1.21.11.sh` — build the jar and copy into `~/Library/Application Support/ModrinthApp/profiles/ImagineFun/mods/`.

## Ports
- Default: 9876 (1.21.11), wraparound range 9876–9886.
- User typically runs 1.21.11 on 9876 and 1.19 on 9877 simultaneously.

## Build requirements
- Gradle needs **JDK 21**. System JDK (25) fails. Build scripts already set `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
- Node for `web-ui` needs **≥20.19** (Vite requirement). If your default `node` is older, run `nvm use 20` first.
- Start web UI: `cd web-ui && npm run dev` → http://localhost:5173.

## Request dispatch pattern
`BridgeServer.handleRequest()` is a switch on `req.type`. To add a new endpoint:
1. Add a `case "yourType" -> handleYourType(req);` line.
2. Add a `handleYourType(BridgeRequest req)` method.
3. If it needs version-specific Java, add a method to an existing provider interface (or create a new one) in `core/`, implement in each version module, and register via `server.setXxxProvider()` in each `DebugBridgeMod.java`.
4. Add a typed wrapper in `web-ui/src/services/bridge.ts`.

## Mapping Fabric intermediary names to Mojang names
`MappingResolver.unresolveClass(runtimeClassName)` converts intermediary names (`class_XXXX`) to Mojang names. Do this in `BridgeServer` handlers before sending over the wire — keeps version-specific providers simple (they just emit `entity.getClass().getName()`). Already applied to `nearbyEntities.type`, `entityDetails.type`/`vehicle`/`passengers[]`, `nearbyBlocks.type`, `blockDetails.type`, and `snapshot.{vehicle.type, target.entityType}`. **Not yet applied** to `screenInspect.{type, menuClass, slots[].container}` — see review queue.

## Refs / Object Browser
`java.ref(id)` in Groovy resolves a stable ref ID (minted by `ResultSerializer` via `ObjectRefStore`, WeakReferences) back to its object. MCP clients learn to use refs through tool descriptions — no runtime registration needed.

## Scripting runtime (Groovy)
The `execute` endpoint runs **Groovy** (Apache Groovy 4.x), not Lua. Code lives in `mod/core/.../script/`:
- `ScriptRuntime` — GroovyShell host: shared `Binding` for persistent state, `@ThreadInterrupt` AST transform for timeouts, `SecureASTCustomizer` import blocklist (mirrors `SecurityPolicy`), `out` binding to capture `println`.
- `GroovyBridge` + `GroovyJavaObject`/`GroovyJavaClass` (extend `GroovyObjectSupport`) — mapping-aware property/method dispatch so Mojang names work on obfuscated builds. `java.type(name)` loads a class by Mojang name (the runtime class is `class_NNNN`); construct via `Cls(args)` or `Cls.create(args)`.
- `JavaHelpers` — the `java.*` surface: `ref`, `describe`, `methods`, `fields`, `supers`, `find`, `type`, `list`, `typeName`, plus `sync { }`.
- **Field vs. method** is disambiguated by Groovy syntax: `obj.foo` reads a field (JavaBean getter fallback), `obj.foo(args)` calls a method — no colon-call recovery needed.
- **`sync { }`** runs its closure entirely on the game thread in one hop (a `ThreadLocal` flag makes nested wrapper calls skip the per-call dispatch) — use it to batch bulk loops.

## Mixins
Each version module has a mixin package + `debugbridge.mixins.json` listing the client-side mixins. Current ones:
- `MinecraftClientMixin` — taps two hooks: TAIL of `Minecraft.tick()` (20 Hz logic tick → `onClientTick`, drives startup messages + warning screen + per-tick housekeeping) and TAIL of `Minecraft.runTick(boolean)` (frame-rate render tick → `onRenderFrame`, drives `RecordingProvider.onRenderFrame` for `record_video`).
- `EntityGlowMixin` — forces `Entity.isCurrentlyGlowing()` to return `true` for IDs in `ClientEntityGlowManager`, so the web UI can outline selected entities without server authority.

## Native entity/texture endpoints
Do NOT iterate entities/blocks, resolve textures, scan inventories, or read chat via Groovy — the per-call Java↔script bridge overhead causes 10s timeouts with ~100+ items. Use the native Java endpoints instead:
- `nearbyEntities` / `entityDetails` / `lookedAtEntity` via `NearbyEntitiesProvider` + `LookedAtEntityProvider` (both versions).
- `nearbyBlocks` / `blockDetails` via `NearbyBlocksProvider` (signs, chests, banners, beacons, furnaces, etc. — block-entity scan). **Caveat:** vanilla MC keeps chest / hopper / dispenser / furnace / brewing-stand contents server-only — `blockDetails.items` is `[]` for those even when the chest has items, because the client never receives them. Use `screenInspect` while the menu is open. `blockDetails.items` only populates for BlockEntities whose items participate in rendering (lecterns, chiseled bookshelves, jukeboxes).
- `screenInspect` via `ScreenInspectProvider` — current open GUI: type, title, container slots with item stacks. Supports `includeIcons` for one-shot container visibility.
- `chatHistory` via `ChatHistoryProvider` — recent client-side chat messages. Supports `includeJson` for styled-component access.
- `getItemTexture` / `getItemTextureById` / `getEntityItemTexture` via `ItemTextureProvider`:
  - **1.21.11**: renders offscreen through `ItemModelResolver` + `GuiRenderer` → GPU texture → PNG. Honors damage/CMD resource-pack overrides.
  - **1.19**: extracts pixels from the baked model's sprite via reflection (no GPU render pipeline in that version).
- `record_video` via `RecordingProvider` (kernel-side orchestrator in `core/recording/`) + per-version `FrameCapturer`:
  - Captures N frames of the main framebuffer driven from the `runTick` mixin tail. Output is one JPEG grid or N per-frame JPEGs under `<gameDir>/debugbridge-recordings/<reqId>/`.
  - Protocol contract lives at `../mcdev-mcp/docs/RECORD_VIDEO_PROTOCOL.md` — that's the canonical spec; mirror changes there if you touch the wire.
  - `BUSY` is enforced both for concurrent `record_video` requests and for single-shot `screenshot` calls while a recording is in progress (shared render thread).
  - Cleanup policy: leak. Files accumulate in `debugbridge-recordings/` until manually wiped. Subdir-per-recording layout exists so a future retention sweep is one `find … -mtime` away.

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
