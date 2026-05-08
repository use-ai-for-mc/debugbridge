# Multi-version restructuring plan

Long-term plan to scale DebugBridge to N Minecraft versions (currently 1.19 + 1.21.11; 1.22+ as they ship) without code duplication. Living tracker — stays in repo while the work is in flight.

## Why

Today: `mod/core/` defines provider interfaces; each `mod/fabric-X.Y/` ships its own implementations. The architecture is right but the discipline is uneven — `FabricMojangResolver.java` duplicates ~400 lines across the two version modules (differs by one comment line), and similar overlap exists in entity/block/chat providers. Every fix or feature ships twice. As more versions land, this doesn't scale.

## Target shape

Strict kernel + adapter:

- **`mod/core/`** never imports `net.minecraft.*` or `net.fabricmc.*`. Houses: protocol DTOs, wire serialization, request validation, mapping-resolver application, business logic, and SPIs the version modules implement.
- **`mod/fabric-X.Y/`** implements the SPIs against the version's MC API. Stays small — only the parts that genuinely differ.

The line: anything that walks game state, shapes JSON, or applies mapping resolution → kernel. Anything that touches MC's render/asset pipeline → adapter.

## Provider triage

After surveying the eight providers:

| Provider | Lines (1.19 / 1.21) | Verdict | Notes |
|---|---|---|---|
| `FabricMojangResolver` | 404 / 403 | **kernel** | One-line diff. First migration target (Phase 3). |
| `NearbyEntitiesProvider` | 264 / 324 | **kernel** | Iteration + JSON; some 1.21-only fields (display entities). |
| `NearbyBlocksProvider` | 202 / 218 | **kernel** | Iteration + JSON; sign API differs. |
| `LookedAtEntityProvider` | 49 / 50 | **kernel** | Tiny near-clone; second proof-of-shape. |
| `ChatHistoryProvider` | 95 / 67 | **kernel** | 1.19 reflection vs 1.21 record accessors — small SPI hides the difference. |
| `ScreenInspectProvider` | 58 / 59 | **kernel** | Iterates `slots`; near-clone. |
| `ItemTextureProvider` | 532 / 393 | **adapter** | Render API genuinely diverges (1.21 GPU pipeline; 1.19 reflection on baked-model sprites). Don't force-share. |
| `ScreenshotProvider` | 85 / 77 | **adapter** | Framebuffer API differs; small enough to leave alone. |

Kernel-candidate total today: ~1075 lines × 2 = ~2150 lines. After migration: ~1075 in core + ~150 (small adapters per version).

## Wire contract (prerequisite)

Lock each endpoint's response shape as a Java DTO in `mod/core/protocol/dto/`. Both version impls produce instances of the same DTO; the adapter is responsible for filling them in. This is what makes cross-version testing possible — the wire shape becomes a single source of truth.

Locking the schema also surfaces drift the audit already flagged (e.g. unmapped `screenInspect.type`, unmapped `snapshot.target.entityType`) — fix as the schema is locked, not after.

## Test strategy

Three layers, each automated where it pays off:

1. **Core unit tests** — pure JVM, run on every PR. After kernel extraction, these cover most of the codebase (the bodies move from version modules into core).
2. **Wire-contract tests** — drive a stub provider through `BridgeServer`, assert wire shape matches the DTO schema. Catches cross-version protocol drift. Lives in `mod/core/test/`.
3. **Smoke test script** — one command per running version, ~30s. Connects to `ws://127.0.0.1:9876`, hits every endpoint, validates response shape. Run manually after `./build-and-deploy*.sh`. Skeleton in `tools/smoke-test.mjs` (Phase 0 — extend per phase).

A version-compat matrix CI workflow runs layers 1+2 against each version's jar (`build.yml` already produces both jars). Layer 3 stays manual until version #3 — at which point invest in PrismLauncher/Modrinth CLI automation.

## Phased migration

Each phase is small enough to ship in one or two PRs. Edit-then-do (this doc updates as scope changes), not do-then-edit.

### Phase 0 — Stage setup
- [x] `MULTIVERSION_PLAN.md` (this file). _2026-05-08_
- [x] `tools/smoke-test.mjs` — covers status, snapshot, search + the six kernel-candidate provider endpoints. Validates basic shape, dumps fixtures with `--fixtures DIR`, exits non-zero on failure. _2026-05-08_
- [x] Ran against both versions: 1.21.11 (9876) 8/8 pass, 1.19 (9877) 8/8 pass. _2026-05-08_
- [x] Baselines captured to `tools/fixtures/1.21.11/` and `tools/fixtures/1.19/`. _2026-05-08_

**Cross-version wire drifts surfaced by baseline diff (feeds Phase 1 contract work):**
- `snapshot.fps` is a `number` on 1.21.11 (`58`) but a `string` on 1.19 (`"83 fps T: 120 vsyncfast fancy-clouds B: 2"` — the raw `Minecraft.fpsString` field). Type drift; same field, different wire types.
- `snapshot.player.vehicle` present in 1.21.11 (player was mounted), absent in 1.19. Likely conditional emission; needs schema decision (omit vs `null` vs always-present).
- `nearbyEntities.entities[].primaryEquipment` present in 1.21.11, absent in 1.19. Same conditional question.
- `nearbyEntities.entities[].customName` present in 1.19 first entity, absent in 1.21.11. Likely conditional; same question.

**Re-checked with a chest open in both versions** (`tools/fixtures/_explore/{1.19-chest,1.21.11-chest}/screenInspect.json`):
- `screenInspect.type` and `screenInspect.menuClass` ARE already Mojang names on both versions (`ContainerScreen`, `ChestMenu`). BridgeServer's `unresolveClass` path covers these. Audit's review-Theme-1 partially overstated.
- `screenInspect.slots[].container` IS still raw intermediary on both versions (`net.minecraft.class_1277`, `net.minecraft.class_1661` — `SimpleContainer` and `Inventory` in Mojang). Same identical drift on both, so the fix is a single missed `unresolveClass` call inside the slot-iteration loop, not two divergent paths. Confirmed Phase 2 target.
- 1.19 emits `slots[].item.name = ""` for slots whose item has no custom name; 1.21.11 omits the `name` key entirely. Same `present-or-absent` schema-decision pattern as the snapshot drifts. Inventory of slot-item keys is identical otherwise (`count`, `itemId`, `name`). Confirmed Phase 1 schema-locking target.

**Spot checks that look fine:**
- `nearbyEntities.entities[].type` shows full Mojang names on both versions.
- Top-level `screenInspect.{type, menuClass}` already mapped on both versions.

### Phase 1 — Wire contracts

**Pattern locked _2026-05-08_** with the starter slice. Each endpoint migration follows: DTO class in `protocol/dto/`, `handleX()` builds the DTO + serializes via `GSON` (keep nulls) or `GSON_OMIT_NULLS` (drop nulls), `ContractTest` adds positive + edge-case assertions on the wire shape.

- [x] Package + two-Gson convention (`GSON` keep-nulls / `GSON_OMIT_NULLS` drop-nulls) — both committed in `BridgeServer.java`. _2026-05-08_
- [x] `StatusDto` + `handleStatus` migration. Demonstrates the omit-nulls path (conditional log-path block). _2026-05-08_
- [x] `LookedAtEntityDto` + `handleLookedAtEntity` migration. Demonstrates the keep-nulls path (`entityId: null` is a meaningful value). _2026-05-08_
- [x] `ContractTest.java` scaffold — 4 tests covering both endpoints' positive + conditional shapes. Stub providers, no live MC required. _2026-05-08_

Remaining endpoints to migrate (smallest-first to keep each PR scoped):

- [x] `chatHistory` → `ChatHistoryDto` + `ChatMessageDto`. Provider interface return type changed from `JsonArray` to `List<ChatMessageDto>`. Both 1.19 and 1.21.11 impls updated. 3 new contract tests cover empty, omit-nulls (`addedTime` absent), and `includeJson` pass-through. **First migration in Phase 1 to change a provider interface — proves the adapter pattern.** _2026-05-08_
- [x] `screenInspect` → `ScreenInspectDto` + `SlotDto` + `ItemStackDto`. Provider interface return type changed from `JsonObject` to `ScreenInspectDto`. Both 1.19 and 1.21.11 impls updated. 4 new contract tests cover closed/open shapes, omit-nulls behavior, and the **Theme 1 mapping fix is now landed**: handler iterates `slots[].container` and applies `unresolveClass`, completing the cleanup that BridgeServer was already doing for `type`/`menuClass`. _2026-05-08_
- [x] `nearbyBlocks` + `blockDetails` → `NearbyBlocksDto` + `BlockSummaryDto` + `BlockDetailsDto` + `BlockItemDto`. Provider returns `List<BlockSummaryDto>` and `BlockDetailsDto?`. Both 1.19 and 1.21.11 impls updated; the 1.21.11 sign-specific extras (`signLinesBack`, `isWaxed`) are explicit DTO fields that the 1.19 impl simply leaves null (omit-nulls drops them) — clean version-specific divergence inside one shared DTO. 5 new contract tests cover empty list, type mapping, gone shape, sign vs. container details, and the optional-field omission behavior. _2026-05-08_

- [x] `nearbyEntities` + `entityDetails` → 6 DTOs total (`NearbyEntitiesDto`, `EntitySummaryDto`, `EntityPrimaryEquipmentDto`, `EntityDetailsDto`, `EntityFrameItemDto`, `EntityEquipmentItemDto`). The Display.* branches (1.21.11 only) populate `displayItem`/`displayText`/`displayBlock` that the 1.19 impl leaves null — same divergence pattern as the sign extras. Handler maps `type`, `vehicle`, AND each passenger entry uniformly. 6 new contract tests pin empty/populated summary, multi-field class mapping, and required-only details shape. _2026-05-08_

**Item-id convention drift** surfaced across all migrations so far (logged as future cleanup, not blocking): the codebase has **three** distinct ways to identify an item on the wire:
- `BlockItemDto.itemId` and `EntityFrameItemDto.itemId` and `EntityEquipmentItemDto.itemId` use `Item#getDescriptionId()` (e.g. `item.minecraft.diamond`).
- `ItemStackDto.itemId` (screenInspect slots) and `EntityPrimaryEquipmentDto.itemId` use the registry key (e.g. `minecraft:diamond`).
- These are not interchangeable for clients (the icon-fetch endpoints accept registry keys).

A follow-up should pick one canonical form (registry key, since that's what the texture endpoints consume) and converge all sites. Phase 1 schema-locks the existing behavior so the divergence is at least visible in the DTO definitions.
- [x] `snapshot` → `SnapshotDto` + `SnapshotPlayerDto` + `SnapshotVehicleDto` + `SnapshotTargetDto` + `SnapshotWorldDto` + `Vec3Dto`. **Two wire-shape fixes landed**: (1) `fps` is now `int` on both versions — the 1.19 provider parses the leading number from `Minecraft.fpsString` instead of leaking the whole debug string; (2) the legacy `"player": "not in world"` string variant is gone — `player` is now either a typed object or absent (clients do a presence check, which is the cleaner contract). Handler is now ~10 lines: build the DTO, run two `unresolveOrNull` calls on `player.vehicle.type` and `target.entityType`, serialize. Old `unresolveClassField(JsonObject, String)` helper removed (it had no remaining callers after this migration). 6 new contract tests pin the minimal shape, fps-as-integer, vehicle/target class mapping, mutually-exclusive block-vs-entity target fields, and the world block. _2026-05-08_
- [x] `search` → `SearchResultDto`. Smallest schema lift — no provider interface (uses the resolver directly), one DTO, ~15-line handler refactor. Wire shape is a bare array, so the handler returns `Gson.toJsonTree(List<SearchResultDto>)` directly without a wrapper. 5 new contract tests cover empty result, class hits omitting `owner` (omit-nulls end-to-end), method/field hits including `owner`, and default-scope behavior. _2026-05-08_

**Phase 1 complete — `BridgeServer` no longer builds any wire JSON inline.** Every endpoint that returns structured data now flows through a DTO + `GSON_OMIT_NULLS.toJsonTree()` (or the keep-nulls Gson for `lookedAtEntity`). `JsonObject` only appears in the handler for incoming payload parsing and for the `icons` pass-through field; all outgoing structure is type-safe.

Notes for the remaining migrations:
- Provider interfaces that currently return `JsonObject` get retyped to the DTO. Each version impl changes from `out.addProperty(...)` to `dto.field = ...`. Adapter pattern proof points start landing here.
- `ContractTest` should grow one new section per migration; aim for ~3 assertions per endpoint (key presence, conditional behavior, type pinning).
- After each migration, re-run the smoke test against both ports + diff the live response against the captured baseline. Diffs should be empty modulo transient game state.

### Phase 2 — Mapping-coverage cleanup (Theme 1 in dream review)
- [ ] Apply `MappingResolver.unresolveClass` to `screenInspect.{type, menuClass, slots[].container}`.
- [ ] Verify `snapshot.{vehicle.type, target.entityType}` already routed through `unresolveClass` (audit said partially done — confirm).
- [ ] Verify via the contract tests + smoke-test fixtures.

### Phase 3 — `FabricMojangResolver` proof-of-shape migration  _2026-05-08_

- [x] Defined `FabricNamespaceLookup` SPI in `mod/core/.../mapping/FabricNamespaceLookup.java` (4-method interface — `runtimeForObfuscatedClass`, `runtimeForObfuscatedMethod`, `runtimeForObfuscatedField`, `obfuscatedForRuntimeClass`). Lives next to `MappingResolver` rather than in a separate `spi/` package, matching the codebase convention where adapter-facing interfaces live with their feature.
- [x] Moved the full ~380-line resolver body into `mod/core/.../mapping/FabricMojangResolver.java`. Constructor takes `(version, ParsedMappings, FabricNamespaceLookup)`. Zero `net.fabricmc.*` imports remain in core.
- [x] Each version module ships a ~30-line `FabricLoaderNamespaceLookup` (in 1.19 + 1.21.11), wrapping `FabricLoader.getInstance().getMappingResolver()`. The two adapters are byte-identical except for their package declaration — that duplication is intentional (each version module owns its own compile-time link to Fabric).
- [x] Both `DebugBridgeMod`s do `new FabricMojangResolver(MC_VERSION, mappings, new FabricLoaderNamespaceLookup())`.
- [x] Both old `FabricMojangResolver.java` files in the version modules deleted (was 805 duplicate lines across 1.19 + 1.21.11).
- [x] **6 new kernel unit tests** for `FabricMojangResolver` (`FabricMojangResolverTest`) using a tiny in-memory `StubLookup`. These tests were impossible before Phase 3 — the resolver pulled in `net.fabricmc.*` statically and could not run in core's pure-JVM test suite. They cover class resolution, reverse lookup, method+field resolution with descriptor conversion, fallback when nothing matches, and cache hit-once behavior.
- [x] Smoke test passes on 1.21.11; fixture wire shape unchanged.

**Net effect:** ~805 duplicate lines collapsed to ~380 (kernel) + 30×2 = 60 (adapters). Adding a third MC version's mapping support is now: implement `FabricNamespaceLookup` in the new module, wire it up, smoke-test. No new resolver logic.

### Phase 4 — Closed (absorbed by Phase 1; no further extraction warranted) _2026-05-08_

Phase 4 was originally planned to do for the other providers what Phase 3 did for `FabricMojangResolver` — extract the version-agnostic body into core behind a small SPI. **Post-Phase 1 there's nothing left to extract that pays off.** Phase 1's DTO migration already moved every provider's wire-format and class-name-mapping concerns into core; what remains in each version's provider is the genuinely-MC-API-touching code.

**Survey of post-Phase 1 provider line counts** (per version):

| Provider | 1.19 | 1.21 | Genuinely shareable | Notes |
|---|---|---|---|---|
| LookedAtEntity | 49 | 50 | ~5 lines | Almost entirely `mc.player.pick(...)` calls. |
| ScreenInspect | 62 | 63 | ~10 lines | `menu.slots` iteration + DTO assembly; rest is MC API. |
| ChatHistory | 95 | 68 | ~10 lines | API split is real (1.19 reflection vs 1.21 record accessors); attempted extraction grew the codebase by 134 lines, see post-mortem. |
| NearbyBlocks | 200 | 216 | ~25 lines | Chunk-walk pattern is identical but operates on `LevelChunk`/`BlockPos`/`BlockEntity` — all `net.minecraft.*`. |
| NearbyEntities | 241 | 286 | ~25 lines | Iteration + sort pattern shared but operates on `Entity`. |

Total potentially-shareable: ~75 lines. Extracting them would require either generic-typing the kernel scanner classes (`<E, P>` parameters, awkward) or having core load MC types via runtime `Class.forName` (adds reflection where there was straightforward typed code). The trade isn't worth it — the savings are marginal vs. Phase 3's 805-line collapse, and the indirection makes the per-version impls harder to follow.

**Post-mortem on the chat-history attempt** (committed and reverted in this phase): pulled `ChatHistoryReader` into core as a kernel scanner that took a `ChatMessageView` SPI for per-version member access. Result: **net +134 lines** (kernel reader 99 + SPI 32 + per-version adapters 52+114 = 297, vs original 68+95 = 163). Cause: when the duplicated logic is small (~10 lines of iteration), the extraction overhead (interface declaration + bound-resolver state + cache-management adapter class) exceeds the savings. The Phase 3 win for `FabricMojangResolver` was real because 95% of that file was pure Java string manipulation; Phase 4 candidates don't have that property.

**Decision:** Phase 4 closed as "absorbed by Phase 1." The kernel/adapter split exists where it pays off:
- `mod/core/protocol/dto/` — wire format (15 DTOs).
- `mod/core/.../mapping/FabricMojangResolver.java` + `FabricNamespaceLookup` SPI (Phase 3).
- `BridgeServer` — request handling, validation, mapping application.

The version-specific provider impls retain the shape they had at the end of Phase 1: thin "translate MC API → DTO" adapters with no wire-format concerns. Adding a third MC version is still cheap (the Phase 3 mapping-resolver split + the DTO contract make new-version onboarding a couple of days of straightforward provider impls).

**Lesson for future phases:** measure line counts before committing to a kernel extraction. The pattern only saves code when the kernel body is genuinely large and version-agnostic (Phase 3 ratio was ~95%); for small or MC-type-heavy bodies, the SPI overhead inverts the win.

### Phase 5 — Test pipeline polish _2026-05-08_

- [x] Layer 1+2 tests wired into `.github/workflows/build.yml`. The `:core:test` step runs before the Fabric build on every push/PR, with HTML test reports uploaded as a build artifact on failure. The contract tests are version-agnostic by design (stubbed providers), so a per-version matrix isn't needed — one core test run covers both 1.19 and 1.21.11 wire shapes via the same DTO definitions. (Wired during the Phase 4 cleanup detour; folded back into Phase 5 here.)
- [x] Smoke-test invocation documented in root `README.md` under a new "Testing" section. Covers all three layers (core unit tests, smoke, regression) with explicit commands and a note about the Node 22 WebSocket requirement.
- [x] **Regression mode** added to `tools/smoke-test.mjs` — `--regression DIR` reads each endpoint's fixture from disk and structurally compares to the live response, flagging path-level drift (added/removed keys, type mismatches). Tolerates value differences (transient game state) and array length differences. Verified end-to-end: a richly-captured baseline matches; a stale baseline (where the player has since started aiming at something or moved away from equipped entities) fails with clear path-level errors. Documented expected false-positive surface (conditional fields like `snapshot.target`, `nearbyEntities[].primaryEquipment`) in the README.

### Phase 6 — Add a third MC version when it ships
At this point, adding a new version should be: add `mod/fabric-X.Y/` with the small adapter classes, register in `settings.gradle.kts`, run smoke test. Targeted line count for a new version module: <500 lines total (down from ~2200 today).

## Open questions

- **Shared `DebugBridgeMod` entry-point.** Both files are 331/341 lines and largely parallel. Tentative: extract `core.AbstractDebugBridgeMod` once providers are kernel-resident; revisit at end of Phase 4.
- **MC version floor for kernel SPIs.** 1.19 is the floor today; `ItemTextureProvider` stays adapter-side specifically because the 1.19 render pipeline can't be expressed against the 1.21 SPI. Re-evaluate when 1.19 support is dropped.
- **Texture/screenshot test approach.** Layer 3 (smoke test) won't validate pixel correctness across versions. Likely manual fixture comparison; not blocked on this plan.

## How this relates to other docs

- `PLAN.md` — closed (pre-publication hygiene + Tier 1, all done 2026-04-28). Keep for history.
- `.dream/review.md` — audit-driven backlog. Themes 1, 2, 7, 8 collapse partially or fully into completing phases above.
- `CLAUDE.md` — short project notes for collaborators. Keep narrow; this doc is the strategic plan.
