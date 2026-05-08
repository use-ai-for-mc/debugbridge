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
//   node tools/smoke-test.mjs --version 1.21.11                     # label only, for output
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
const fixturesDir = args.fixtures ?? null;
const regressionDir = args.regression ?? null;
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

// --- endpoint coverage ---
//
// Kernel-candidate providers (per MULTIVERSION_PLAN.md triage).
// `screenshot` and item-texture endpoints are deliberately excluded — they're
// large binary responses and not part of the kernel/adapter wire contract.

{
  const r = await call('status');
  check('status', r, (r) => typeof r.result === 'object' && r.result !== null);
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
