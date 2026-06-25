# DebugBridge — code review for follow-up agent

A second-pass review of the DebugBridge repo (`mod/core/`, `mod/fabric-*/`,
`web-ui/`). Items are ordered roughly by impact: high first, then medium, then
small. Each item has **What** (the smell), **Where** (file:line evidence), and
**Why / suggested fix** (what to actually do).

Read the existing `CLAUDE.md` and `AGENTS.md` first — they own the conventions
the items below are written against.

## Status as of 2026-06-23

Completed in this pass:
- **#1** `runCommand` now delegates to native `CommandProvider` implementations
  instead of Groovy script round-trips.
- **#9** Inventory slot component extraction.
- **#12** Persistent script executor no longer spawns one thread per call.
- **#14** Recording session state machine uses a single state enum for
  lifecycle transitions.
- **#16** Error formatting is centralized.
- **#17** `record_video` now defaults to temp storage with 24h TTL and safe
  cleanup.
- **#7** `record_video` payload validation parser extracted from `BridgeServer`.
- **#18** `record_video` currently returns absolute file paths by design for the
  local loopback workflow.
- **#2** `handleSearch` no longer does three full mapping passes per request.
  Search entries are built lazily once per server instance and reused.
- **#3** Web UI now has unit tests (`vitest` scripts and initial store/service
  coverage).
- **#5** `ObjectRefStore` now enforces a bounded store size.
- **#6** Entity detail and equipment-texture races are guarded against by request
  sequencing and per-entity status timers.
- **#8** `entityColor` moved into `web-ui/src/services/entity-colors.ts` and is
  now covered by unit tests.
- **#10** `BridgeConfig` now uses `Gson#fromJson` deserialization with explicit
  validation (`validate()`).
- **#13** `MappingResolver` now declares `contributesToSearch()` with a
  passthrough-safe default.
- **#11** Disconnect-path error handling now distinguishes malformed JSON from
  internal processing failures, and now skips response attempts on closed sockets.
- **#15** Entity-store `new` status timers are now tracked and cleared when
  polling stops or the store scope is disposed.
- **#4** Response helper names now make null-field behavior explicit, and the
  web UI accepts both explicit-null and omitted-null `lookedAtEntity` responses.

Still open for discussion or a separate focused pass:
- None.

---

## High-impact

### 1. `runCommand` is a script-runtime round-trip hiding inside a non-script endpoint

**Status.** Completed.

**What.** This was the planned fix for the old round-trip design, and it is now
implemented: `handleRunCommand` validates input and delegates to a version-specific
`CommandProvider` instead of synthesizing Groovy code.

**Where.**
- `mod/core/src/main/java/com/debugbridge/core/server/BridgeServer.java:751-767`
  (`handleRunCommand`)
 - `mod/core/src/main/java/com/debugbridge/core/command/CommandProvider.java`
 - `mod/core/src/main/java/com/debugbridge/core/lifecycle/AbstractDebugBridgeMod.java:153-155`
   (`setCommandProvider(createCommandProvider())` / `setRunCommandEnabled(...)`)
 - `mod/core/src/main/java/com/debugbridge/core/server/BridgeServer.java:751-767`
   (`handleRunCommand` now calls `provider.execute(...)`)
 - Each fabric module returns its native provider from `createCommandProvider()`.

**Why / fix.**
- Keep as-is.

### 2. `handleSearch` does three full passes over the mapping database per request

**Status.** Completed.

**What.** `handleSearch` walks `resolver.getAllClassNames()` once per scope,
  and for the `method`/`field` scopes iterates every method signature of every
class. On the 1.21.11 / 26.1 mappings that is ~20,000 classes × tens of
methods each = ~200k–1M iterations per search, three times. Search is a
synchronous websocket handler.

The ReDoS hardening (`TimeoutCharSequence` in `BridgeServer.java:516-547`) is
excellent and should be preserved — it's only the iteration that's wasteful.

**Where.**
- `mod/core/src/main/java/com/debugbridge/core/server/BridgeServer.java:419-487`
  (`handleSearch`)
- `BridgeServer.java:516-553` (the existing `TimeoutCharSequence` machinery to
  reuse)

**Why / fix.**
- Build an inverted index in the resolver (or in a wrapper) once per resolver:
  `Map<String, List<String>>` keyed by lowercased substring of class /
  method / field name, plus the prefix tag (`[class]` / `[method]` /
  `[field]`) so callers can still filter by scope.
- Populate lazily on first search; keep the per-string `timedFind` guard so
  pathological patterns still bail.
- The existing `methodCandidateCache` / `reverseMethodCache` in `GroovyBridge`
  prove the pattern.
- Drops search latency from tens of ms to sub-ms for warm queries.

### 3. The frontend has zero unit tests for a 28-file Vue 3 / Pinia / TS app

**Status.** Completed.

**What.** `package.json` has no `vitest` / `jest` / `@vue/test-utils`. The
`src/` tree has no `*.test.ts` or `*.spec.ts`. The GitHub workflow's `test`
section runs only Java. Yet the stores contain exactly the kind of stateful
logic that needs tests.

**Where.**
- `web-ui/package.json` — no test runner.
- `web-ui/src/stores/entities.ts:97-105` (sort comparator).
- `web-ui/src/stores/entities.ts:203-223` (spawn/despawn detection with a
  `DESPAWN_LINGER_MS` race window).
- `web-ui/src/stores/blocks.ts:62-70` (sort by xyz).
- `web-ui/src/stores/entities.ts:119-152` (`entityPrimaryKey` cache + in-flight
  map).
- `web-ui/src/services/bridge.ts:144-165` (disconnect-timeout-vs-reconnect
  logic).

**Why / fix.**
- Add `vitest` + `@vue/test-utils` to `web-ui/devDependencies`.
- Write `entities.test.ts` first (highest-risk store), then `blocks.test.ts`,
  then `bridge.test.ts` for the reconnect logic.
- None of these need a running browser — they're plain TS / Pinia state.
- Add a `test` step to the `build-web-ui` job in `.github/workflows/build.yml`
  so CI catches regressions.

### 4. The wire protocol has a footgun: `BridgeResponse.toJson` and the two `Gson` instances must stay in sync

**Status.** Completed with guardrails; no protocol-wide null-shape change.

**What.** `BridgeServer` keeps two `Gson` instances — `GSON` with
`serializeNulls()` for `lookedAtEntity` (which wants `entityId: null` rather
than the key disappearing) and `GSON_OMIT_NULLS` for everything else. The
asymmetry is documented in the constants' javadoc. But every new endpoint
forces a choice at the call site, and the "wrong" choice is silent:
`lookedAtEntity` with `GSON_OMIT_NULLS` would have shipped `{}` and a
client-side parse error rather than a clean "no target" signal.

**Where.**
- `mod/core/src/main/java/com/debugbridge/core/server/BridgeServer.java:63,68`
  (the two Gsons).
- `BridgeServer.java:1133` (the one place using `GSON` directly).
- Every other handler call site uses `GSON_OMIT_NULLS.toJsonTree(...)`.

**Fix implemented.**
- Renamed the response helpers to make the choice explicit:
  `successDtoOmitNullFields(...)` vs `successDtoPreserveNullFields(...)`.
- Kept `lookedAtEntity` on the preserve-null path, with the existing contract
  test asserting `{ "entityId": null }` for no target.
- Made the web UI parser tolerate both explicit `null` and omitted `entityId`
  as "no target", while still rejecting malformed non-numeric values.

---

## Medium-impact

### 5. `ObjectRefStore` is unbounded for the lifetime of a connection

**Status.** Completed.

**What.** `ObjectRefStore.store` mints a new `$ref_N` for every Java object
that crosses `ResultSerializer.serializeJavaObject` — including transient ones
(positions, vectors, hit results). At a 30s auto-refresh rate on an
entity-heavy area, a script returning `level.entities` would mint thousands
of refs per minute. The store keeps them all (weak refs, so they don't pin
memory), but `refs.size()` keeps growing — and the counter never resets
until `clear()` is called on WebSocket close.

**Where.**
- `mod/core/src/main/java/com/debugbridge/core/refs/ObjectRefStore.java:19-23`.

**Why / fix.**
- Add a soft cap (say, 8k entries) to `store()` that prunes the oldest when
  full. Cheap, bounded, and consistent with the existing "WeakReferences"
  intent.

### 6. `entities.ts` despawn-detection logic has a race; equipment textures are unscoped

**Status.** Completed.

**What.** Two related smells:
1. Between two auto-refresh ticks, a `setTimeout(..., NEW_STATUS_MS)`
   callback (`entities.ts:226-230`) can clear a `status: 'new'` flag that
   was correctly set. The window is small but the code is implicit about
   it.
2. If the user clicks entity 100, then entity 200 before entity 100's
   details resolve (`fetchEntityDetails` is async), entity 200's response
   clobbers entity 100's `equipmentTextures`. The detail panel shows a
   mix.

**Where.**
- `web-ui/src/stores/entities.ts:226-230` (`setTimeout` race).
- `web-ui/src/stores/entities.ts:243-354` (`fetchEntityDetails`).
- `web-ui/src/stores/entities.ts:321-348` (texture assignment is unscoped).

**Why / fix.**
- For the texture clobber: tag each `selectedDetails` write with the entity
  ID and bail if it doesn't match the current selection, OR
- scope `equipmentTextures` per-entity
  (`Record<entityId, Record<slot, dataUrl>>`) — the panel always shows the
  right thing and the change is ~10 lines.
- For the `setTimeout`: prefer `setTimeout` cancellation tied to the next
  refresh, or just let Vue's reactive system handle it (it's a small
  inefficiency, not a correctness bug).

### 7. `handleRecordVideo` validation lives in 200 lines of imperative Gson-poking

**Status: completed.**

**What.** This logic used to live inline in `BridgeServer` (`validateRecordVideoPayload`);
it is now moved into `RecordingRequestParams.fromPayload`, which keeps protocol
parsing in one place and leaves the endpoint handler focused on orchestration.

**Where.**
- `mod/core/src/main/java/com/debugbridge/core/recording/RecordingRequestParams.java`
  (`fromPayload`).
- `mod/core/src/main/java/com/debugbridge/core/server/BridgeServer.java`
  (`handleRecordVideo`, now delegating).
- `BridgeServer.java` (`recordingResultToJson`) — touches the same
  fields again.

**Why / fix.**
- Keep the parser central in `RecordingRequestParams`, and keep `recordingResultToJson`
  focused on output formatting.

### 8. `EntitiesPanel.vue` is 829 lines and contains the entity-color taxonomy

**Status.** Completed.

**What.** `entityColor` declares six hard-coded lists of mob names mapping to
colors in a single 27-line ternary chain, inside a 829-line Vue file. The
lists don't tolerate modded entities, the lists are wrong by small amounts
(`breezing` wouldn't be caught as a breeze), and there's no test asserting
which mobs map where.

**Where.**
- `web-ui/src/components/inspector/EntitiesPanel.vue:77-103` (the taxonomy).
- File length: 829 lines (`wc -l` confirms).

**Why / fix.**
- Pull `entityColor` and its lookup tables into
  `web-ui/src/services/entity-colors.ts` with one map keyed by entity type
  string.
- Write a unit test (`zombie = #ff5555`, etc.) — you'll thank yourself the
  first time a passive mob needs to be added.

### 9. `InventoryPanel.vue` duplicates the slot-template four times

**Status.** Completed.

**What.** The same `<img> + <div.icon> + <span.count> + <durability>` block
is repeated four times for hotbar / main grid / armor / offhand.

**Where.**
- `web-ui/src/components/inspector/InventoryPanel.vue:114-131, 148-165,
  184-191, 205-213`.

**Why / fix.**
- Extract an `<InventorySlot>` component
  (`slot: number, size: 'sm' | 'lg', showCount?: boolean`).
- The store already exposes `inventory.slots[slot]` keyed by index; the new
  component is just a presentational wrapper.
- Reduces `InventoryPanel.vue` to ~300 lines and makes the four places that
  need slot tweaks all agree.

### 10. `BridgeConfig` is hand-rolled `JsonObject` parsing with no validation

**Status.** Completed.

**What.** `BridgeConfig.load` reads a file with `Files.readString`, parses to
`JsonObject`, then does 12 `if (obj.has(...)) obj.get(...)` checks. Compare
to `save()` which builds a clean `JsonObject` from a typed DTO. The asymmetry
means a typo in `save()`'s key list could silently drop a field, and there's
no validation that `port` is in range or that `scriptMaxExecutionTimeMs` is
positive.

**Where.**
- `mod/core/src/main/java/com/debugbridge/core/BridgeConfig.java:59-103`
  (`load`).
- `BridgeConfig.java:108-131` (`save`).

**Why / fix.**
- Make `BridgeConfig` a real Gson-deserialized DTO (it's already a
  public-fields class, so `Gson.fromJson(json, BridgeConfig.class)` works in
  one line).
- Validate ranges in a `validate()` method called from `load()`.
- Add `@SerializedName` for the snake_case keys.

### 11. The disconnect path silently swallows WebSocket-send errors

**Status.** Completed.

**What.** `BridgeServer.onMessage` catches everything, logs once, then tries
to send an error response in another try/catch. The inner catch's comment is
"Connection may be dead" — fine, but the outer catch also swallowed all
exceptions on the success path (any handler returning a `BridgeResponse`
whose JSON serialization failed would be lost). A malformed inbound message
is silently dropped on the client side if the WS is already closing.

**Fix implemented.**
- Split parsing and runtime-failure branches in `onMessage`:
  - `JsonSyntaxException` now returns an `INVALID_JSON` error.
  - Internal failures return `INTERNAL_ERROR` with request id and are sent
    via a single response helper.
- Added safe-response helpers that skip send attempts on closed sockets and log
  send failures at `FINE`.
- Added regression coverage in `mod/core/src/test/java/com/debugbridge/core/ErrorHandlingTest.java`
  for malformed JSON + recovery on the next valid request.

---

## Low-impact but worth fixing

### 12. `ScriptRuntime.execute` spawns a fresh executor per call

**Where.** `mod/core/src/main/java/com/debugbridge/core/script/ScriptRuntime.java:101-105`.

**Fix.** Use a single `ExecutorService` (sized to 1; `sync{}` already
serializes). Saves ~1ms per invocation and one thread churn per script run.
The console panel debounces keystrokes but the executor still churns.

### 13. `MappingResolver` interface has no defaults

**Status.** Completed.

**Where.** `mod/core/src/main/java/com/debugbridge/core/mapping/MappingResolver.java:9-59`.

**Fix.** Add `default boolean contributesToSearch() { return !isObfuscated(); }`
or similar — lets `handleSearch` skip iteration on passthrough resolvers and
documents the 26.1 fast path.

### 14. `RecordingSession` uses one lifecycle state instead of multiple flags

**Status: completed.**

**Where.**
- `mod/core/src/main/java/com/debugbridge/core/recording/RecordingSession.java`.

**Fix.** Completed by replacing the separate abort flag with an explicit
`SessionState` transition (`ACTIVE` → `ABORTING` → `FAILED` / `DONE`) and
gating capture/finalization through that state.

### 15. `entities.ts` `setTimeout` mutation of an unmounted store

**Status.** Completed.

**Where.** `web-ui/src/stores/entities.ts` (`newStatusTimers`,
`clearNewStatusTimers`, `onScopeDispose`).

**Fix.** Completed by tracking per-entity new-status timers, clearing them when
auto-refresh stops, and adding an `onScopeDispose` fallback so disposed stores
cannot keep mutating shared entity state.

### 16. The error-class-name message format leaks Java internals

**Status.** Completed for `BridgeServer` and `GroovyJavaObject`.

**Where.** Throughout `BridgeServer` and `GroovyJavaObject`, errors are
formatted as `e.getClass().getSimpleName() + ": " + e.getMessage()` — about
14 places in `BridgeServer.java` alone (lines 386, 588, 612, 692, 946, 1045,
1059, 1071, 1094, 1117, 1136, 1158, 1196). For dev tools it's fine; for
anything user-facing, "NullPointerException: Cannot invoke
\"java.util.List.size()\" because \"list\" is null" is unhelpful.

**Fix.** Centralize as `ErrorFormatter.format(Throwable)` — strip the FQN,
scrub package prefixes, map common exceptions to human-friendly one-liners
(NPE → "null reference", IllegalArgumentException → just the message). Even
30 lines is enough.

### 17. No retention policy for `debugbridge-recordings/`

**Status.** Fixed for default recordings: `record_video` now defaults to
DebugBridge-owned temp storage with a 24h TTL and safe cleanup. Persistent
game-dir recordings are still available only by explicit opt-in.

`CLAUDE.md` already calls this out: *"Cleanup policy: leak. Files accumulate
in `debugbridge-recordings/` until manually wiped."* At 300 frames × ~50KB
per JPEG, a 5-second recording is 15MB. An MCP-driven agent running smoke
tests all afternoon can leave GB on disk.

**Fix.** Small `CleanupPolicy` config knob (`maxAgeDays = 7`,
`maxTotalBytes = 2GB`) and a `ScheduledExecutorService.scheduleAtFixedRate`
running once an hour. Wire it through `RecordingProvider` (or
`AbstractDebugBridgeMod`).

### 18. `handleRecordVideo` exposes absolute filesystem paths to the client

**Status.** **Accepted by design.** Absolute paths are kept in responses on purpose for
this local loopback tooling workflow.

**Where.**
- `mod/core/src/main/java/com/debugbridge/core/server/BridgeServer.java:725-750`
  (`recordingResultToJson`).

`result.path` is an absolute filesystem path on the user's machine. The
bridge is loopback-only so this isn't an external exposure, and is acceptable
for local tooling. Revisit this path model if the bridge ever supports remote
clients.

---

## What's already good (don't churn on these)

These are working as intended and worth keeping:

- **The kernel/adapter split holds.** `core/` has zero
  `import net.minecraft.*` / `import net.fabricmc.*`. Every version-specific
  call site is a provider interface. `AbstractDebugBridgeMod` is the single
  source of truth for port-probe / server-start / shutdown.
- **The mapping machinery is right.** `resolveMethodCandidates` walking the
  hierarchy and enumerating signatures per overload
  (`GroovyBridge.java:192-214`) is what obfuscation demands. Has its own
  regression test (`GroovyObfuscatedDispatchTest`).
- **`sync{}` fast path is genuinely clever.** `ThreadLocal<Boolean>
  onGameThread` in `GroovyBridge` lets a script opt into the game thread
  once and skip per-call dispatch. The single decision is what makes
  100-entity bulk loops feasible in 10s instead of 30s.
- **ReDoS hardening on `search` is correct.** `TimeoutCharSequence`
  throwing from `charAt` is the right escape valve for Java's regex
  engine.
- **The recording pipeline is honest about its limits.** Locked dimensions
  on first frame, `MAX_IN_FLIGHT` drop counter, abandoned-worker cleanup
  in `shutdown()` — comments explain why, not just what.
- **The wire protocol is stable across the Lua→Groovy rewrite.** Keeping
  the `{type, value, …}` envelope so existing clients kept working is real
  operational hygiene. Documented in `ResultSerializer.java:23-25`.
- **Comments are unusually thorough and accurate.** Read them.

---

## Recommended order of attack

No remaining CODE_REVIEW items are open. Future protocol cleanup can still
choose a single all-null response policy, but that would be a separate
compatibility discussion rather than a pending review fix.
