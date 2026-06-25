import { describe, expect, it } from 'vitest';
import { parseLookedAtEntityResult } from './bridge';

describe('parseLookedAtEntityResult', () => {
  it('accepts explicit null and omitted entity ids as no target', () => {
    expect(parseLookedAtEntityResult({ entityId: null })).toBeNull();
    expect(parseLookedAtEntityResult({})).toBeNull();
    expect(parseLookedAtEntityResult(null)).toBeNull();
    expect(parseLookedAtEntityResult(undefined)).toBeNull();
  });

  it('accepts numeric entity ids from object or bare legacy results', () => {
    expect(parseLookedAtEntityResult({ entityId: 42 })).toBe(42);
    expect(parseLookedAtEntityResult({ entityId: '42' })).toBe(42);
    expect(parseLookedAtEntityResult(42)).toBe(42);
  });

  it('rejects malformed entity ids', () => {
    expect(() => parseLookedAtEntityResult({ entityId: 'not-a-number' })).toThrow(/Malformed lookedAtEntity/);
    expect(() => parseLookedAtEntityResult({ entityId: true })).toThrow(/Malformed lookedAtEntity/);
  });
});
