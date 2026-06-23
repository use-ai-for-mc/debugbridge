#!/usr/bin/env node
// Smoke test for DebugBridge: connect to a running mod, hit each endpoint,
// validate a non-error response, print pass/fail per endpoint, exit non-zero
// on failure.
//
// Three modes (combinable):
//   default                       — basic shape check on the live response
//   --fixtures DIR                — also dump per-endpoint JSON fixtures (capture)
//   --regression DIR              — compare each live response's structural
//                                   shape to the fixture in DIR; fail on drift
//
// Usage:
//   node tools/smoke-test.mjs                                       # default ws://127.0.0.1:9876
//   node tools/smoke-test.mjs --port 9877                           # 1.19 alongside 1.21.11
//   node tools/smoke-test.mjs --version 26.1 --port 9876             # exact 26.1 standalone/default port
//   node tools/smoke-test.mjs --version 1.21.11                     # require status.version to match
//   node tools/smoke-test.mjs --version 26.1 --include-textures      # also smoke item-icon rendering
//   node tools/smoke-test.mjs --version 26.1 --game-dir-contains '/instances/26.1/'
//                                                              # also verifies the live JVM loaded the current 26.1 implementation shape
//   node tools/smoke-test.mjs --fixtures tools/fixtures/1.21.11     # capture baseline
//   node tools/smoke-test.mjs --regression tools/fixtures/1.21.11   # check against baseline
//
// Requires Node 22+ (built-in WebSocket).

import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const args = parseArgs(process.argv.slice(2));
const host = args.host ?? '127.0.0.1';
const port = Number(args.port ?? 9876);
const version = args.version ?? 'unspecified';
const expectedVersion = args.version ? String(args.version) : null;
const requiredGameDirPart = args['game-dir-contains'] ? String(args['game-dir-contains']) : null;
const fixturesDir = args.fixtures ?? null;
const regressionDir = args.regression ?? null;
const includeTextures = Boolean(args['include-textures']);
const url = `ws://${host}:${port}`;

if (typeof WebSocket === 'undefined') {
  console.error('ERROR: WebSocket is not a global. This script requires Node 22+.');
  console.error('       Install nvm + `nvm use 22`, or polyfill if you must.');
  process.exit(2);
}

if (fixturesDir) {
  mkdirSync(fixturesDir, { recursive: true });
}
if (regressionDir && !existsSync(regressionDir)) {
  console.error(`ERROR: --regression ${regressionDir} does not exist. Capture a baseline first with --fixtures.`);
  process.exit(2);
}

const modeLabel = [
  fixturesDir ? `fixtures=${fixturesDir}` : null,
  regressionDir ? `regression=${regressionDir}` : null,
  includeTextures ? 'include-textures' : null,
].filter(Boolean).join(' ');
console.log(`# DebugBridge smoke test — version=${version} url=${url}${modeLabel ? ' ' + modeLabel : ''}`);

let nextId = 1;
const pending = new Map();
const results = [];

const ws = new WebSocket(url);

await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error(`connect timeout after 3s (${url})`)), 3000);
  ws.addEventListener('open', () => { clearTimeout(t); resolve(); });
  ws.addEventListener('error', (e) => { clearTimeout(t); reject(e); });
}).catch((e) => {
  console.error(`FATAL: ${e.message ?? e}`);
  process.exit(2);
});

ws.addEventListener('message', (ev) => {
  let msg;
  try { msg = JSON.parse(ev.data); } catch { return; }
  const cb = pending.get(msg.id);
  if (cb) { pending.delete(msg.id); cb(msg); }
});

// --- harness ---

function call(type, payload, timeoutMs = 8000) {
  return new Promise((resolve) => {
    const id = String(nextId++);
    const t = setTimeout(() => {
      if (pending.has(id)) { pending.delete(id); resolve({ id, success: false, error: `timeout after ${timeoutMs}ms` }); }
    }, timeoutMs);
    pending.set(id, (msg) => { clearTimeout(t); resolve(msg); });
    ws.send(JSON.stringify({ id, type, payload: payload ?? {} }));
  });
}

function check(name, resp, validate) {
  let ok = resp.success === true;
  let detail = '';
  if (!ok) {
    detail = resp.error ?? '(no error string)';
  } else if (validate) {
    try {
      const r = validate(resp);
      if (r === true || r === undefined) ok = true;
      else { ok = false; detail = String(r); }
    } catch (e) {
      ok = false;
      detail = `validator threw: ${e.message ?? e}`;
    }
  }

  // --- fixtures: capture mode ---
  const safe = name.replace(/[^a-zA-Z0-9_.-]/g, '_');
  const fileName = `${safe}.json`;
  // Strip the volatile id from any persisted/compared form.
  const { id: _id, ...stable } = resp;

  if (ok && fixturesDir) {
    writeFileSync(join(fixturesDir, fileName), JSON.stringify(stable, null, 2) + '\n');
  }

  // --- fixtures: regression mode ---
  if (ok && regressionDir) {
    const fixturePath = join(regressionDir, fileName);
    if (!existsSync(fixturePath)) {
      ok = false;
      detail = `no fixture at ${fixturePath} — run with --fixtures to capture`;
    } else {
      const fixture = JSON.parse(readFileSync(fixturePath, 'utf8'));
      const errors = [];
      compareShapes('result', fixture, stable, errors);
      if (errors.length > 0) {
        ok = false;
        detail = `wire shape drifted from fixture (${errors.length} site${errors.length===1?'':'s'}):\n    `
          + errors.slice(0, 5).join('\n    ')
          + (errors.length > 5 ? `\n    ... and ${errors.length - 5} more` : '');
      }
    }
  }

  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
}

// --- structural shape comparison ---
//
// Walks fixture and live JSON in parallel. Reports paths where:
//   - one side has a key the other doesn't (key set drifted)
//   - both sides have the key but with different value types
//   - one side has null where the other has a value
// Tolerates value differences (transient game state) and array length
// differences (pre-fills with first non-empty side's element shape if both
// have one; ignores element shape if either side is empty).

function compareShapes(path, a, b, errors) {
  if (a === null && b === null) return;
  if (a === null) { errors.push(`${path}: null in fixture, ${typeName(b)} in live`); return; }
  if (b === null) { errors.push(`${path}: ${typeName(a)} in fixture, null in live`); return; }
  const aIsArr = Array.isArray(a), bIsArr = Array.isArray(b);
  if (aIsArr !== bIsArr) {
    errors.push(`${path}: ${aIsArr ? 'array' : typeName(a)} in fixture, ${bIsArr ? 'array' : typeName(b)} in live`);
    return;
  }
  if (aIsArr) {
    if (a.length > 0 && b.length > 0) {
      compareShapes(`${path}[0]`, a[0], b[0], errors);
    }
    return;
  }
  const aIsObj = typeof a === 'object', bIsObj = typeof b === 'object';
  if (aIsObj !== bIsObj) {
    errors.push(`${path}: ${aIsObj ? 'object' : typeName(a)} in fixture, ${bIsObj ? 'object' : typeName(b)} in live`);
    return;
  }
  if (aIsObj) {
    const aKeys = Object.keys(a);
    const bKeys = new Set(Object.keys(b));
    for (const k of aKeys) {
      if (!bKeys.has(k)) errors.push(`${path}.${k}: present in fixture, missing in live`);
      else compareShapes(`${path}.${k}`, a[k], b[k], errors);
      bKeys.delete(k);
    }
    for (const k of bKeys) {
      errors.push(`${path}.${k}: missing in fixture, present in live`);
    }
    return;
  }
  if (typeof a !== typeof b) {
    errors.push(`${path}: ${typeName(a)} in fixture, ${typeName(b)} in live`);
  }
}

function typeName(v) {
  if (v === null) return 'null';
  if (Array.isArray(v)) return 'array';
  return typeof v;
}

function unwrapBridgeValue(v) {
  if (v === null || v === undefined) return v;
  if (Array.isArray(v)) return v.map(unwrapBridgeValue);
  if (typeof v !== 'object') return v;
  if (Object.hasOwn(v, 'type') && Object.hasOwn(v, 'value')) {
    return unwrapBridgeValue(v.value);
  }
  return Object.fromEntries(Object.entries(v).map(([k, value]) => [k, unwrapBridgeValue(value)]));
}

// --- endpoint coverage ---
//
// Kernel-candidate providers (per MULTIVERSION_PLAN.md triage).
// `screenshot` is deliberately excluded — it's a large binary response and
// not part of the kernel/adapter wire contract. Item-texture endpoints are
// opt-in with --include-textures because they also return PNG payloads, but
// they are useful for version lines such as exact 26.1 where item rendering is
// the main compatibility risk.

{
  const r = await call('status');
  check('status', r, (r) => {
    if (!r.result || typeof r.result !== 'object') return 'result missing';
    if (expectedVersion && r.result.version !== expectedVersion) {
      return `expected version ${expectedVersion}, got ${r.result.version ?? '(missing)'}`;
    }
    if (requiredGameDirPart) {
      if (typeof r.result.gameDir !== 'string') return 'gameDir missing';
      if (!r.result.gameDir.includes(requiredGameDirPart)) {
        return `expected gameDir to contain ${requiredGameDirPart}, got ${r.result.gameDir}`;
      }
    }
    return true;
  });
  if (r.success === true && r.result && typeof r.result === 'object') {
    const gameDir = r.result.gameDir ? ` gameDir=${r.result.gameDir}` : '';
    console.log(`       status.version=${r.result.version ?? '(missing)'}${gameDir}`);
  }
}
if (expectedVersion === '26.1') {
  const r = await call('execute', {
    code: `
      def methods = com.debugbridge.fabric261.Minecraft261ItemTextureProvider.class.declaredMethods.collect { it.name }.unique().sort()
      def loader = com.debugbridge.fabric261.DebugBridgeMod.class.classLoader
      def mixinJsonStream = loader.getResourceAsStream('debugbridge.mixins.json')
      def mixinJson = mixinJsonStream == null ? '' : mixinJsonStream.getText('UTF-8')
      return [
        itemProviderMethods: methods,
        blockGlowMixinResource: loader.getResource('com/debugbridge/fabric261/mixin/BlockGlowGizmoMixin.class') != null,
        featureDispatcherAccessorResource: loader.getResource('com/debugbridge/fabric261/mixin/FeatureRenderDispatcherAccessor.class') != null,
        mixinJsonHasBlockGlow: mixinJson.contains('BlockGlowGizmoMixin'),
        mixinJsonHasFeatureDispatcherAccessor: mixinJson.contains('FeatureRenderDispatcherAccessor')
      ]
    `,
    timeoutMs: 5000,
  }, 8000);
  check('26.1 runtime implementation shape', r, (r) => {
    const result = unwrapBridgeValue(r.result);
    if (!result || typeof result !== 'object') return 'result missing';
    const methods = Array.isArray(result.itemProviderMethods) ? result.itemProviderMethods : [];
    const requiredMethods = ['close', 'renderVanillaItem', 'renderItemToTexture', 'readItemTexture'];
    const missing = requiredMethods.filter((name) => !methods.includes(name));
    if (missing.length > 0) {
      return `live JVM looks stale; missing ${missing.join(', ')} from Minecraft261ItemTextureProvider`;
    }
    if (result.blockGlowMixinResource !== true || result.mixinJsonHasBlockGlow !== true) {
      return 'BlockGlowGizmoMixin resource or mixin JSON entry missing';
    }
    if (
      result.featureDispatcherAccessorResource !== true
      || result.mixinJsonHasFeatureDispatcherAccessor !== true
    ) {
      return 'FeatureRenderDispatcherAccessor resource or mixin JSON entry missing';
    }
    return true;
  });
}
{
  const r = await call('snapshot');
  check('snapshot', r, (r) => r.result && typeof r.result === 'object');
}
{
  const r = await call('search', { pattern: 'Minecraft', kind: 'class', limit: 5 });
  check('search', r, (r) => Array.isArray(r.result) || (r.result && Array.isArray(r.result.results)));
}
{
  const r = await call('nearbyEntities', { range: 16, limit: 50 });
  check('nearbyEntities', r, (r) => r.result && (Array.isArray(r.result) || Array.isArray(r.result.entities)));
}
{
  const r = await call('nearbyBlocks', { range: 16, limit: 50 });
  check('nearbyBlocks', r, (r) => r.result && (Array.isArray(r.result) || Array.isArray(r.result.blocks)));
}
{
  const r = await call('lookedAtEntity', { range: 32 });
  check('lookedAtEntity', r, () => true);
}
{
  const r = await call('screenInspect', {});
  check('screenInspect', r, (r) => r.result && typeof r.result === 'object');
}
{
  const r = await call('chatHistory', { limit: 20 });
  check('chatHistory', r, (r) => r.result && (Array.isArray(r.result) || Array.isArray(r.result.messages)));
}
if (includeTextures) {
  const r = await call('getItemTextureById', { itemId: 'minecraft:stone' }, 15_000);
  check('getItemTextureById', r, (r) => {
    const result = r.result;
    if (!result || typeof result !== 'object') return 'result missing';
    if (typeof result.base64Png !== 'string' || result.base64Png.length < 32) return 'base64Png missing/short';
    if (typeof result.width !== 'number' || result.width <= 0) return `bad width=${result.width}`;
    if (typeof result.height !== 'number' || result.height <= 0) return `bad height=${result.height}`;
    if (typeof result.spriteName !== 'string') return 'spriteName missing';
    if (result.spriteName.startsWith('fallback:')) return `renderer fallback: ${result.spriteName}`;
    return true;
  });
}

// --- close + summary ---

ws.close();

const failed = results.filter((r) => !r.ok);
const total = results.length;
const passed = total - failed.length;
console.log(`\n# ${passed}/${total} passed (${version})`);
process.exit(failed.length ? 1 : 0);

// --- args ---

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const k = a.slice(2);
      const v = argv[i + 1];
      if (v && !v.startsWith('--')) { out[k] = v; i++; }
      else { out[k] = true; }
    }
  }
  return out;
}
