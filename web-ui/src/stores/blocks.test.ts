import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const bridgeMock = vi.hoisted(() => ({
  getNearbyBlocks: vi.fn(),
  getBlockDetails: vi.fn(),
  getItemTextureById: vi.fn(),
  setBlockGlow: vi.fn(),
}));

vi.mock('../services/bridge', () => ({
  bridge: bridgeMock,
}));

import { useBlocksStore, type BlockDetails } from './blocks';

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

function rawBlock(x: number, y: number, z: number, distance: number, type: string) {
  return {
    x,
    y,
    z,
    distance,
    type,
    blockId: 'minecraft:oak_sign',
    preview: null,
  };
}

describe('blocks store', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    installLocalStorage();
    setActivePinia(createPinia());

    bridgeMock.getNearbyBlocks.mockReset();
    bridgeMock.getBlockDetails.mockReset();
    bridgeMock.getItemTextureById.mockReset();
    bridgeMock.setBlockGlow.mockReset();
    bridgeMock.setBlockGlow.mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('normalizes nearby block types and sorts by position', async () => {
    const store = useBlocksStore();
    bridgeMock.getNearbyBlocks.mockResolvedValueOnce({
      count: 3,
      blocks: [
        rawBlock(4, 64, 1, 7, 'net.minecraft.world.level.block.entity.ChestBlockEntity'),
        rawBlock(1, 70, 5, 2, 'net.minecraft.world.level.block.entity.SignBlockEntity'),
        rawBlock(1, 64, 2, 5, 'net.minecraft.world.level.block.entity.BannerBlockEntity'),
      ],
    });

    await store.fetchBlocks();

    expect(store.blocks.map(b => b.type)).toEqual([
      'ChestBlockEntity',
      'SignBlockEntity',
      'BannerBlockEntity',
    ]);

    store.setSortBy('pos');
    expect(store.sortedBlocks.map(b => store.blockKey(b))).toEqual([
      '1,64,2',
      '1,70,5',
      '4,64,1',
    ]);
  });

  it('clears a selected block when it disappears from the nearby list', async () => {
    const store = useBlocksStore();
    store.selectedKey = '1,64,2';
    store.selectedDetails = {
      x: 1,
      y: 64,
      z: 2,
      type: 'net.minecraft.world.level.block.entity.SignBlockEntity',
      blockId: 'minecraft:oak_sign',
      signLines: null,
      signLinesBack: null,
      isWaxed: null,
      items: null,
      containerSize: null,
      raw: {},
    } satisfies BlockDetails;
    store.slotTextures = { 0: 'data:image/png;base64,old' };
    store.slotTextureDegraded = { 0: false };

    bridgeMock.getNearbyBlocks.mockResolvedValueOnce({
      count: 1,
      blocks: [
        rawBlock(9, 65, 9, 3, 'net.minecraft.world.level.block.entity.ChestBlockEntity'),
      ],
    });

    await store.fetchBlocks();

    expect(bridgeMock.setBlockGlow).toHaveBeenCalledWith(1, 64, 2, false);
    expect(store.selectedKey).toBeNull();
    expect(store.selectedDetails).toBeNull();
    expect(store.slotTextures).toEqual({});
    expect(store.slotTextureDegraded).toEqual({});
  });

  it('loads container slot textures from block details', async () => {
    const store = useBlocksStore();
    bridgeMock.getBlockDetails.mockResolvedValueOnce({
      type: 'net.minecraft.world.level.block.entity.ChestBlockEntity',
      blockId: 'minecraft:chest',
      items: [
        { slot: 5, itemId: 'minecraft:diamond', count: 3, name: 'Diamond' },
      ],
      containerSize: 27,
    });
    bridgeMock.getItemTextureById.mockResolvedValueOnce({
      base64Png: 'diamond',
      width: 32,
      height: 32,
      spriteName: 'minecraft:item/diamond',
    });

    await store.fetchBlockDetails(1, 64, 2);

    expect(store.selectedDetails?.items?.[0]).toMatchObject({
      slot: 5,
      itemId: 'minecraft:diamond',
      count: 3,
      name: 'Diamond',
    });
    expect(store.slotTextures[5]).toBe('data:image/png;base64,diamond');
    expect(store.slotTextureDegraded[5]).toBe(false);
  });
});
