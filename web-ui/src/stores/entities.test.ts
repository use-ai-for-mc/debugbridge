import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const bridgeMock = vi.hoisted(() => ({
  getNearbyEntities: vi.fn(),
  getEntityDetails: vi.fn(),
  getEntityItemTexture: vi.fn(),
  setEntityGlow: vi.fn(),
}));

vi.mock('../services/bridge', () => ({
  bridge: bridgeMock,
}));

import { useEntitiesStore } from './entities';

function installLocalStorage() {
  const values = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    get length() {
      return values.size;
    },
    clear: vi.fn(() => values.clear()),
    getItem: vi.fn((key: string) => values.get(key) ?? null),
    key: vi.fn((index: number) => Array.from(values.keys())[index] ?? null),
    removeItem: vi.fn((key: string) => {
      values.delete(key);
    }),
    setItem: vi.fn((key: string, value: string) => {
      values.set(key, String(value));
    }),
  });
}

function rawEntity(id: number, distance: number) {
  return {
    id,
    type: 'net.minecraft.world.entity.monster.Zombie',
    typeId: 'entity.minecraft.zombie',
    distance,
    x: id,
    y: 64,
    z: -id,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('entities store', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    installLocalStorage();
    setActivePinia(createPinia());

    bridgeMock.getNearbyEntities.mockReset();
    bridgeMock.getEntityDetails.mockReset();
    bridgeMock.getEntityItemTexture.mockReset();
    bridgeMock.setEntityGlow.mockReset();
    bridgeMock.setEntityGlow.mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('keeps new status timers scoped to the entity that spawned', async () => {
    vi.useFakeTimers();
    const store = useEntitiesStore();

    bridgeMock.getNearbyEntities.mockResolvedValueOnce({
      count: 1,
      entities: [rawEntity(1, 3)],
    });
    await store.fetchEntities();

    expect(store.entities.map(e => [e.id, e.status])).toEqual([[1, 'new']]);

    await vi.advanceTimersByTimeAsync(1000);
    bridgeMock.getNearbyEntities.mockResolvedValueOnce({
      count: 2,
      entities: [rawEntity(1, 3), rawEntity(2, 1)],
    });
    await store.fetchEntities();

    expect(store.entities.find(e => e.id === 1)?.status).toBe('stable');
    expect(store.entities.find(e => e.id === 2)?.status).toBe('new');

    await vi.advanceTimersByTimeAsync(1000);
    expect(store.entities.find(e => e.id === 2)?.status).toBe('new');

    await vi.advanceTimersByTimeAsync(1000);
    expect(store.entities.find(e => e.id === 2)?.status).toBe('stable');
  });

  it('clears pending new status timers when auto refresh stops', async () => {
    vi.useFakeTimers();
    const store = useEntitiesStore();

    bridgeMock.getNearbyEntities.mockResolvedValueOnce({
      count: 1,
      entities: [rawEntity(1, 3)],
    });
    await store.fetchEntities();

    expect(store.entities.find(e => e.id === 1)?.status).toBe('new');
    expect(vi.getTimerCount()).toBe(1);

    store.stopAutoRefresh();

    expect(store.entities.find(e => e.id === 1)?.status).toBe('stable');
    expect(vi.getTimerCount()).toBe(0);
  });

  it('ignores stale detail and texture responses after selection changes', async () => {
    const store = useEntitiesStore();
    const staleDetails = deferred<Record<string, unknown>>();

    bridgeMock.getEntityDetails.mockImplementation((entityId: number) => {
      if (entityId === 100) return staleDetails.promise;
      return Promise.resolve({
        customName: 'Current',
        equipment: {
          HEAD: { itemId: 'minecraft:diamond_helmet' },
        },
        tags: ['selected'],
      });
    });
    bridgeMock.getEntityItemTexture.mockImplementation((entityId: number, slot: string) =>
      Promise.resolve({
        base64Png: `png-${entityId}-${slot}`,
        width: 32,
        height: 32,
        spriteName: 'minecraft:item/diamond_helmet',
      }),
    );

    store.selectEntity(100);
    store.selectEntity(200);

    await vi.waitFor(() => {
      expect(store.selectedDetails?.entityId).toBe(200);
      expect(store.equipmentTextures.HEAD).toBe('data:image/png;base64,png-200-HEAD');
    });

    staleDetails.resolve({
      customName: 'Old',
      equipment: {
        HEAD: { itemId: 'minecraft:iron_helmet' },
      },
    });
    await Promise.resolve();
    await Promise.resolve();

    expect(store.selectedEntityId).toBe(200);
    expect(store.selectedDetails?.customName).toBe('Current');
    expect(store.equipmentTextures.HEAD).toBe('data:image/png;base64,png-200-HEAD');
    expect(bridgeMock.getEntityItemTexture).not.toHaveBeenCalledWith(100, 'HEAD');
  });

  it('deduplicates in-flight primary equipment texture fetches', async () => {
    const store = useEntitiesStore();
    const texture = deferred<{
      base64Png: string;
      width: number;
      height: number;
      spriteName: string;
    }>();
    bridgeMock.getEntityItemTexture.mockReturnValue(texture.promise);

    const first = store.fetchEntityPrimaryTextureCached(7, 'MAINHAND', 'minecraft:diamond_sword');
    const second = store.fetchEntityPrimaryTextureCached(7, 'MAINHAND', 'minecraft:diamond_sword');

    expect(bridgeMock.getEntityItemTexture).toHaveBeenCalledTimes(1);

    texture.resolve({
      base64Png: 'sword',
      width: 32,
      height: 32,
      spriteName: 'minecraft:item/diamond_sword',
    });

    await expect(first).resolves.toBe('data:image/png;base64,sword');
    await expect(second).resolves.toBe('data:image/png;base64,sword');
    expect(store.entityPrimaryTextures['7:MAINHAND:minecraft:diamond_sword'])
      .toBe('data:image/png;base64,sword');
  });
});
