#!/usr/bin/env node
// End-to-end smoke test for the `record_video` endpoint.
//
// Targets a live DebugBridge mod (launch the client first). Runs four
// scenarios per invocation:
//   1. Grid mode    — 12 frames every render tick, contact-sheet JPEG.
//   2. Frames mode  — 6 frames at 50ms apart, one JPEG per frame.
//   3. INVALID_INPUT — frames=1000 (over MAX_FRAMES=300).
//   4. BUSY         — start a slower recording on connection A, fire a
//                     single-shot screenshot on connection B while it runs
//                     (expects BUSY). Needs two connections because
//                     java-websocket serializes messages from a single
//                     connection through one worker thread.
//
// For each pass, verifies the response wire shape AND stats the output file(s)
// on disk to confirm the mod actually wrote what it claimed.
//
// Usage:
//   node tools/record-video-smoke.mjs                 # ws://127.0.0.1:9876 (default 1.21.11 port)
//   node tools/record-video-smoke.mjs --port 9877     # 1.19 alongside
//   node tools/record-video-smoke.mjs --port 9876 --version 26.1
//   node tools/record-video-smoke.mjs --port 9876 --version 26.1 --game-dir-contains '/instances/26.1/'
//                                                    # also verifies the live JVM loaded the current 26.1 implementation shape
//   node tools/record-video-smoke.mjs --port 9878     # 26.2 alongside
//
// Requires Node 22+ (built-in WebSocket).

import { existsSync, statSync } from 'node:fs';

const args = parseArgs(process.argv.slice(2));
const host = args.host ?? '127.0.0.1';
const port = Number(args.port ?? 9876);
const version = args.version ?? 'unspecified';
const expectedVersion = args.version ? String(args.version) : null;
const requiredGameDirPart = args['game-dir-contains'] ? String(args['game-dir-contains']) : null;
const url = `ws://${host}:${port}`;

if (typeof WebSocket === 'undefined') {
  console.error('ERROR: WebSocket is not a global. This script requires Node 22+.');
  process.exit(2);
}

console.log(`# record_video smoke — version=${version} url=${url}`);

async function openConnection() {
  const sock = new WebSocket(url);
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`connect timeout (${url})`)), 3000);
    sock.addEventListener('open', () => { clearTimeout(t); resolve(); });
    sock.addEventListener('error', (e) => { clearTimeout(t); reject(e); });
  });
  const pending = new Map();
  sock.addEventListener('message', (ev) => {
    let msg;
    try { msg = JSON.parse(ev.data); } catch { return; }
    const cb = pending.get(msg.id);
    if (cb) { pending.delete(msg.id); cb(msg); }
  });
  let nextId = 1;
  return {
    sock,
    call(id, type, payload, timeoutMs = 60_000) {
      return new Promise((resolve) => {
        const reqId = id ?? `smoke-${nextId++}`;
        const t = setTimeout(() => {
          if (pending.has(reqId)) { pending.delete(reqId); resolve({ id: reqId, success: false, error: `timeout after ${timeoutMs}ms` }); }
        }, timeoutMs);
        pending.set(reqId, (msg) => { clearTimeout(t); resolve(msg); });
        sock.send(JSON.stringify({ id: reqId, type, payload: payload ?? {} }));
      });
    },
  };
}

const a = await openConnection().catch((e) => {
  console.error(`FATAL: ${e.message ?? e}`);
  process.exit(2);
});
const call = a.call;

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
}

// --- 0. Status/version gate ---
{
  const r = await call(null, 'status', {}, 3_000);
  const result = unwrapBridgeValue(r.result ?? {});
  let ok = r.success === true && typeof result === 'object' && result !== null;
  let detail = '';
  if (!ok) {
    detail = r.error ?? 'status failed';
  } else if (expectedVersion && result.version !== expectedVersion) {
    ok = false;
    detail = `expected version ${expectedVersion}, got ${result.version ?? '(missing)'}`;
  } else if (requiredGameDirPart && typeof result.gameDir !== 'string') {
    ok = false;
    detail = 'gameDir missing';
  } else if (requiredGameDirPart && !result.gameDir.includes(requiredGameDirPart)) {
    ok = false;
    detail = `expected gameDir to contain ${requiredGameDirPart}, got ${result.gameDir}`;
  } else {
    detail = `version=${result.version ?? '(missing)'}${result.gameDir ? ` gameDir=${result.gameDir}` : ''}`;
  }
  check('status', ok, detail);
  if (!ok) {
    a.sock.close();
    process.exit(1);
  }
}

// --- 0b. 26.1 loaded-implementation gate ---
if (expectedVersion === '26.1') {
  const r = await call(null, 'execute', {
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
  const result = unwrapBridgeValue(r.result ?? {});
  const methods = Array.isArray(result.itemProviderMethods) ? result.itemProviderMethods : [];
  const missing = ['close', 'renderVanillaItem', 'renderItemToTexture', 'readItemTexture']
    .filter((name) => !methods.includes(name));
  let ok = r.success === true;
  let detail = '';
  if (!ok) {
    detail = r.error ?? 'execute failed';
  } else if (missing.length > 0) {
    ok = false;
    detail = `live JVM looks stale; missing ${missing.join(', ')} from Minecraft261ItemTextureProvider`;
  } else if (result.blockGlowMixinResource !== true || result.mixinJsonHasBlockGlow !== true) {
    ok = false;
    detail = 'BlockGlowGizmoMixin resource or mixin JSON entry missing';
  } else if (
    result.featureDispatcherAccessorResource !== true
    || result.mixinJsonHasFeatureDispatcherAccessor !== true
  ) {
    ok = false;
    detail = 'FeatureRenderDispatcherAccessor resource or mixin JSON entry missing';
  } else {
    detail = 'current 26.1 implementation classes loaded';
  }
  check('26.1 runtime implementation shape', ok, detail);
  if (!ok) {
    a.sock.close();
    process.exit(1);
  }
}

// --- 1. Grid mode (default cadence) ---
{
  const reqId = `smoke-grid-${Date.now()}`;
  const r = await call(reqId, 'record_video', { frames: 12, interval: 'frame', output: 'grid' }, 30_000);
  if (!r.success) {
    check('grid', false, r.error);
  } else {
    const res = r.result ?? {};
    const errs = [];
    if (res.mode !== 'grid') errs.push(`mode=${res.mode}`);
    if (typeof res.path !== 'string') errs.push('path missing');
    if (typeof res.width !== 'number' || res.width <= 0) errs.push('width bad');
    if (typeof res.height !== 'number' || res.height <= 0) errs.push('height bad');
    if (typeof res.frameWidth !== 'number') errs.push('frameWidth missing');
    if (typeof res.frameHeight !== 'number') errs.push('frameHeight missing');
    if (typeof res.gridCols !== 'number') errs.push('gridCols missing');
    if (typeof res.gridRows !== 'number') errs.push('gridRows missing');
    if (res.frameCount !== 12) errs.push(`frameCount=${res.frameCount}`);
    if (typeof res.dropped !== 'number') errs.push('dropped missing');
    if (res.mimeType !== 'image/jpeg') errs.push('mimeType bad');
    if (typeof res.path === 'string' && !existsSync(res.path)) errs.push(`file not on disk: ${res.path}`);
    if (typeof res.path === 'string' && existsSync(res.path)) {
      const sz = statSync(res.path).size;
      if (sz <= 0) errs.push('file empty');
      if (sz !== res.sizeBytes) errs.push(`sizeBytes ${res.sizeBytes} != on-disk ${sz}`);
    }
    check('grid', errs.length === 0,
      errs.length ? errs.join(', ')
      : `path=${res.path} grid=${res.gridCols}x${res.gridRows} dropped=${res.dropped} captureMs=${res.captureMs}`);
  }
}

// --- 2. Frames mode ---
{
  const reqId = `smoke-frames-${Date.now()}`;
  const r = await call(reqId, 'record_video', { frames: 6, interval: 50, output: 'frames', downscale: 4 }, 30_000);
  if (!r.success) {
    check('frames', false, r.error);
  } else {
    const res = r.result ?? {};
    const errs = [];
    if (res.mode !== 'frames') errs.push(`mode=${res.mode}`);
    if (!Array.isArray(res.paths) || res.paths.length !== 6) errs.push(`paths.length=${res.paths?.length}`);
    if (res.frameCount !== 6) errs.push(`frameCount=${res.frameCount}`);
    if (res.dropped !== 0) errs.push(`dropped should be 0 for numeric interval, got ${res.dropped}`);
    if (Array.isArray(res.paths)) {
      let totalOnDisk = 0;
      for (const [i, p] of res.paths.entries()) {
        if (!existsSync(p)) { errs.push(`paths[${i}] missing on disk: ${p}`); break; }
        totalOnDisk += statSync(p).size;
      }
      if (errs.length === 0 && totalOnDisk !== res.sizeBytes) {
        errs.push(`sizeBytes ${res.sizeBytes} != on-disk total ${totalOnDisk}`);
      }
    }
    check('frames', errs.length === 0,
      errs.length ? errs.join(', ')
      : `dir=${res.paths?.[0]?.replace(/\/frame-\d+\.jpg$/, '')} captureMs=${res.captureMs}`);
  }
}

// --- 3. INVALID_INPUT (over cap) ---
{
  const r = await call(null, 'record_video', { frames: 1000 }, 3_000);
  const ok = r.success === false && typeof r.error === 'string' && r.error.startsWith('INVALID_INPUT');
  check('over-cap-rejected', ok, ok ? r.error : `unexpected response: ${JSON.stringify(r)}`);
}

// --- 4. BUSY (screenshot from a 2nd connection during recording) ---
{
  const b = await openConnection().catch((e) => null);
  if (!b) {
    check('busy-blocks-screenshot', false, 'could not open second connection');
  } else {
    const recId = `smoke-busy-${Date.now()}`;
    const recPromise = a.call(recId, 'record_video', { frames: 30, interval: 100, output: 'grid' }, 30_000);
    await new Promise(r => setTimeout(r, 500)); // let the recording register as active
    const shot = await b.call(null, 'screenshot', {}, 5_000);
    const ok = shot.success === false && typeof shot.error === 'string' && shot.error.includes('BUSY');
    check('busy-blocks-screenshot', ok, ok ? shot.error : `unexpected screenshot response: ${JSON.stringify(shot)}`);
    await recPromise;
    b.sock.close();
  }
}

const failed = results.filter(r => !r.ok).length;
console.log(`\n# ${results.length - failed}/${results.length} passed`);
a.sock.close();
process.exit(failed === 0 ? 0 : 1);

function unwrapBridgeValue(v) {
  if (v === null || v === undefined) return v;
  if (Array.isArray(v)) return v.map(unwrapBridgeValue);
  if (typeof v !== 'object') return v;
  if (Object.hasOwn(v, 'type') && Object.hasOwn(v, 'value')) {
    return unwrapBridgeValue(v.value);
  }
  return Object.fromEntries(Object.entries(v).map(([k, value]) => [k, unwrapBridgeValue(value)]));
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const k = a.slice(2);
      const v = (i + 1 < argv.length && !argv[i + 1].startsWith('--')) ? argv[++i] : true;
      out[k] = v;
    }
  }
  return out;
}
