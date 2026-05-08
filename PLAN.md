# DebugBridge improvement plan

Pre-publication hardening + API improvements, derived from analyzing ~2,000 `mc_*` agent tool calls captured in Claude Code session transcripts (`~/.claude/projects/*/*.jsonl`) across 40 sessions.

All *Pre-publication hygiene* and *Tier 1* items are done as of 2026-04-28; this doc is now history + the post-tier follow-up queue.

## Top-line evidence

| Tool | Calls | Errors | Implication |
|---|---|---|---|
| `mc_search` | 779 | low | Workhorse — no change needed |
| `mc_get_class` | 428 | 14 token-cap hits | Add `view` param (#8) |
| `mc_get_method` | 358 | low | Healthy |
| `mc_execute` | 324 | ~30% | Most errors point at missing native endpoints (#4) or JPMS iteration (#7) |
| `mc_screenshot` | 25 | rare | Healthy |
| `mc_snapshot` | 3 | 0 | Underused — agents don't know it exists (#5) |
| `mc_logger` | **2** | **2 (100%)** | **Remove (#1)** |
| `mc_run_command` | 2 | 2 | **Hide unless enabled (#2)** |

Two things to keep in mind while reading the rest:

- **Static tools dominate.** ~1640 of ~2000 calls (82%) are mappings/source lookups. Runtime tools matter, but the bridge is on the smaller side of the call mix.
- **`mc_execute` is doing too much.** It's used for things a typed endpoint should serve (player state, slot walks, chat history). Most of Tier 1 is about reclaiming `mc_execute` for what it's actually good at: agent-driven exploration of the Java API.

## Pre-publication hygiene (blockers for the next public tag)

### 1. ~~Hide / remove `injectLogger` family from the public release~~
- ~~Evidence: `mc_logger` was called 2 times across 40 sessions, both 100% errored.~~
- **Done 2026-04-28** via option (a) gating: `BridgeConfig.loggerInjectionEnabled` (default false) → `BridgeServer.setLoggerInjectionEnabled` → injectLogger/cancelLogger/listLoggers cases return `Unknown request type` when off. mcdev-mcp side: `mc_logger` only registered when `MCDEV_LOGGER_INJECTION=1`. Both sides default off.
- Re-enable (dev only): set `logger_injection_enabled: true` in `~/.minecraft/config/debugbridge.json` AND `MCDEV_LOGGER_INJECTION=1` for the mcdev-mcp process.
- [x] Done

### 2. ~~Hide `runCommand` unless explicitly enabled~~
- **Done 2026-04-28**, same shape as #1. Bridge: `BridgeConfig.runCommandEnabled` (default false) → `BridgeServer.setRunCommandEnabled` → returns `Unknown request type` when off. mcdev-mcp: `mc_run_command` only registered when `MCDEV_RUN_COMMAND=1`. Web UI's stale `bridge.runCommand` method has zero call-sites — gating off is non-breaking.
- [x] Done

### 3. ~~Fix `mc_find_refs` `Cannot find module 'bindings'` crash (in mcdev-mcp)~~
- ~~Surfaces for any user who installs the public mcdev-mcp release. better-sqlite3 native binding issue.~~
- **Resolved 2026-04-16** by migrating from `better-sqlite3` (native) → `node:sqlite` → `sql.js` (pure JS + WASM). Last occurrence: 2026-04-16 05:07 UTC; 0 errors across 50+ subsequent `mc_find_refs` calls. Migration commits in `~/if-local/mcdev-mcp/`: `31185a3`, `d6babbc`, `62e4ca5`.
- [x] Done

## Tier 1 — high-leverage agent UX

### 4. ~~Wrap the existing native runtime endpoints as MCP tools~~  ← was the highest leverage item
- **Done 2026-04-28.** All 11 wrappers added in `~/if-local/mcdev-mcp/src/tools/runtime/`: `nearby-entities.ts`, `entity-details.ts`, `nearby-blocks.ts`, `block-details.ts`, `looked-at-entity.ts`, `set-entity-glow.ts`, `set-block-glow.ts`, `clear-block-glow.ts`, `get-item-texture.ts`, `get-entity-item-texture.ts`, `get-item-texture-by-id.ts`. All registered in `runtime/index.ts`. Default tool count went 7 → 15 (counting the dev-gated removals from #1, #2).
- Open follow-ups now visible: agents will start using these instead of `mc_execute`, which means the `mc_execute` Lua-iteration timeouts should drop. Re-run the analysis in a few weeks to see if any new patterns emerge that suggest more wrappers (per #6 — chat history, screen inspect).
- [x] Done

### 5. ~~Enrich `snapshot` payload + advertise it in `mc_execute` description~~
- **Done 2026-04-28.** Bridge: `Minecraft{119,12111}StateProvider.captureSnapshot()` now adds yaw, pitch, hotbarSlot, velocity {x,y,z}, look {x,y,z}, vehicle {entityId, type}, target {type, x/y/z, face} or {entityId, entityType} (from `mc.hitResult`). Class names go through `MappingResolver.unresolveClass` in `BridgeServer.handleSnapshot`. mcdev-mcp: `execute.ts` description now lists native tools at the top and explicitly tells the model to use `mc_snapshot` for player state.
- [x] Done

### 6. ~~Add `chatHistory` and `screenInspect` endpoints to the bridge~~
- **Done 2026-04-28.** New core interfaces `ChatHistoryProvider` and `ScreenInspectProvider` (`mod/core/.../chat/` and `mod/core/.../screen/`). Bridge handlers `chatHistory` (returns `{messages:[{plain, addedTime}], count}`) and `screenInspect` (returns `{open, type, title, menuClass?, slots?:[{idx, container, item:{itemId, count, damage?, maxDamage?, name?}}]}`). 1.21.11 + 1.19 provider impls each: chat uses reflection on `ChatComponent.allMessages` (private field in both versions); screen reads `AbstractContainerMenu.slots` directly. mcdev-mcp wrappers: `mc_chat_history`, `mc_screen_inspect`. Default tool count went 15 → 17.
- [x] Done

## Tier 2 — friction reduction

### 7. ~~Make `java.iter()` use interface dispatch before reflection~~
- **Done 2026-04-28.** Root cause was wider than just `java.iter`: every Lua method call (including `:iterator()`) goes through `MethodCallWrapper` which calls `setAccessible(true)` and fails on JPMS-sealed types like `HashMap$KeySet`. Added `preferAccessibleMethod()` helper in `MethodCallWrapper`: if the candidate method's declaring class is in a JPMS module that doesn't export to us, walks the class+interface hierarchy via the existing `collectHierarchy` and returns the same signature on the first JPMS-exported declaration (e.g. `Iterable.iterator()`). Falls back to the original method if no accessible equivalent exists. `mc_execute` description also clarifies that `java.iter()` works on JPMS-private types.
- [x] Done

### 8. ~~Add `view` parameter to `mc_get_class`~~
- **Done 2026-04-28.** New `view` parameter on `mc_get_class`: `"summary"` (default — one-line method+field signatures, no bodies), `"methods"` (signatures only), `"fields"` (declarations only), `"full"` (current behavior — full decompiled source). Tool description steers the model to start with summary and only request full when implementation is needed. File: `~/if-local/mcdev-mcp/src/tools/static/get-class.ts`.
- [x] Done

### 9. ~~Structured connect-failure response~~
- **Done 2026-04-28.** On `mc_connect` failure, returns a JSON object: `{connected: false, action: "start_minecraft" | "investigate", ports_tried: [9876..9885], message: "...", raw_error: "..."}`. ECONNREFUSED / "Could not connect" maps to `start_minecraft`; other failures to `investigate`. File: `~/if-local/mcdev-mcp/src/tools/runtime/connect.ts`.
- [x] Done

## Tier 3 — for the next data-gathering cycle

### 10. ~~Add lightweight per-request logging to `BridgeServer`~~
- **Done 2026-04-28 — scope reduced to mcdev-mcp side only.** Decision: keep the MCP-server-side logging (already shipped in `~/if-local/mcdev-mcp/src/tools/runtime/script-logger.ts`, gated on `MCDEV_SCRIPT_LOGS=1`, writes `mc_execute` calls to `~/Library/Application Support/mcdev-mcp/script-logs/all.jsonl` with 10MB rotation). The bridge itself does NOT need request logging — adding it would log the same calls twice for MCP traffic and we don't currently need visibility into the small set of non-MCP clients (Web UI, raw scripts).
- Trade-off accepted: other agent clients (Cursor, Cline, etc.) will be invisible until / unless they grow significant usage. Revisit if that changes.
- [x] Done

## Surprises from the data (background, no action items)

- `mc_search` is by far the most-called tool (779) but agents almost never filter by `type` (1/779). The "search → get_class" pipeline is dominant — agents grep names broadly, then drill.
- Connect/execute ratio is healthy (~8 executes per connect). Agents keep sessions alive and use `_G` for cross-call state. The maintainer probably worried about reconnect storms; doesn't happen in practice.
- Nobody uses `mc_run_command`. Agents prefer Lua because it returns values they can branch on.
- Long `mc_execute` chains in luabridge's own session (33-call chain in transcript `86390bec-…`) were exploration of the item-render pipeline. That kind of exploration is exactly what `mc_execute` *should* support — just not for things a native endpoint already covers (#4–6).

## Follow-ups (post-tier)

### A. Texture wrappers return MCP image content, not stringified JSON
- **Done 2026-04-28** ([mcdev-mcp e1c580d](https://github.com/weikengchen/mcdev-mcp/commit/e1c580d)). `mc_get_item_texture`, `mc_get_entity_item_texture`, `mc_get_item_texture_by_id` now return `[{type:"image", data, mimeType:"image/png"}, {type:"text", text:"<W>x<H> sprite=<name>"}]` — the model can see the rendered item directly instead of getting a wall of base64 string. Verified end-to-end on both ports (1.19=16x16, 1.21.11=32x32, both with valid PNG signatures).
- [x] Done

### B. `mc_chat_history` `includeJson` for styled-component access
- **Done 2026-04-28** ([debugbridge b303d7b](https://github.com/weikengchen/debugbridge/commit/b303d7b), [mcdev-mcp f5faef0](https://github.com/weikengchen/mcdev-mcp/commit/f5faef0)). New `includeJson` boolean parameter (default false) on `mc_chat_history`. When true, each message also returns a `json` field with the full Component serialized — preserves colors, styles, click events, hover events. 1.21.11 uses `ComponentSerialization.CODEC.encodeStart(JsonOps, c)`; 1.19 uses `Component.Serializer.toJson(c)` re-parsed for consistent wire shape. Default off keeps the common-case response compact.
- [x] Done

### C. `mc_screen_inspect` `includeIcons` for one-shot container visibility
- **Done 2026-04-28** ([debugbridge 4047006](https://github.com/weikengchen/debugbridge/commit/4047006), [mcdev-mcp bfda47d](https://github.com/weikengchen/mcdev-mcp/commit/bfda47d)). When `includeIcons=true`, the bridge collects unique `itemId`s across slots, renders each via `ItemTextureProvider.getItemTextureById`, and attaches a top-level `icons` map keyed by itemId — agent sees a container's contents in one shot instead of N follow-up `mc_get_item_texture_by_id` calls. Dedup means a 90-slot chest of stone+dirt only ships two icons. Default off keeps the basic response small.
- [x] Done

### D. `mc_nearby_entities` `includeIcons` for entity-held items
- **Done 2026-04-28** (same commits as C). Same shape: when true, walks `primaryEquipment.itemId` across the entity list, renders unique items, attaches the `icons` map. Lets the agent answer "what's that armor stand wearing?" without per-entity texture calls.
- [x] Done

### E. Richer `mc_search` per-result context
- **Done 2026-04-28** ([mcdev-mcp bfda47d](https://github.com/weikengchen/mcdev-mcp/commit/bfda47d)). Each search hit now includes enough context that `mc_get_class` / `mc_get_method` follow-ups are unnecessary in trivial cases:
  - `[class] FQN extends Super implements I1, I2 (Nf, Mm)` — kind (class/interface/record/enum), extends, implements, field/method counts.
  - `[method] FQN#name: public static int foo(int bar) (line N)` — full signature with modifiers.
  - `[field] FQN#name: private static final int MAX_HEALTH` — modifiers + declaration.
- Verified locally: 19 `Inventory` class hits, 50 `getX` method hits, 7 `MAX_HEALTH` field hits — each one richer than before.
- [x] Done

## How to use this doc

1. Pick an item, work it, check the box.
2. If the item turns out to need a different approach than described, **edit the item before doing the work** — don't let the doc drift behind the code.
3. Once shipped, leave the box checked. We can compress to a `Done` section later if it gets unwieldy.
4. New evidence (more transcripts, real user reports) → add a new item or amend the relevant one. The numbered IDs are for reference in commit messages, not a fixed order.
