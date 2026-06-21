import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { bridge } from '../services/bridge';

export interface NearbyBlock {
  x: number;
  y: number;
  z: number;
  distance: number;
  type: string;       // short class name (e.g. "SignBlockEntity")
  fullType: string;   // full class
  blockId: string;    // registry key (e.g. "minecraft:oak_sign")
  preview: string | null;
}

export interface BlockContainerItem {
  slot: number;
  itemId: string;
  count: number;
  name?: string;
  damage?: number;
  maxDamage?: number;
}

export interface BlockDetails {
  x: number;
  y: number;
  z: number;
  type: string;       // full class
  blockId: string;
  signLines: string[] | null;
  signLinesBack: string[] | null;
  isWaxed: boolean | null;
  items: BlockContainerItem[] | null;
  containerSize: number | null;
  raw: Record<string, unknown>;
}

const MAX_BLOCKS = 200;

function blockKey(b: { x: number; y: number; z: number }): string {
  return `${b.x},${b.y},${b.z}`;
}

export const useBlocksStore = defineStore('blocks', () => {
  const blocks = ref<NearbyBlock[]>([]);
  const selectedKey = ref<string | null>(null);
  const selectedDetails = ref<BlockDetails | null>(null);
  // base64 data URLs keyed by slot index, for the selected block's container.
  const slotTextures = ref<Record<number, string>>({});
  const slotTextureDegraded = ref<Record<number, boolean>>({});

  const isLoading = ref(false);
  const isLoadingDetails = ref(false);
  const error = ref<string | null>(null);
  const range = ref<number>(parseInt(localStorage.getItem('debugbridge-block-range') ?? '12', 10));
  const autoRefreshEnabled = ref(false);
  const sortBy = ref<'distance' | 'type' | 'pos'>('distance');

  let refreshTimer: ReturnType<typeof setInterval> | null = null;

  const sortedBlocks = computed(() => {
    const list = [...blocks.value];
    switch (sortBy.value) {
      case 'distance': return list.sort((a, b) => a.distance - b.distance);
      case 'type': return list.sort((a, b) => a.type.localeCompare(b.type) || a.distance - b.distance);
      case 'pos': return list.sort((a, b) => a.x - b.x || a.y - b.y || a.z - b.z);
      default: return list;
    }
  });

  function setRange(val: number) {
    range.value = val;
    localStorage.setItem('debugbridge-block-range', String(val));
  }

  function setSortBy(s: 'distance' | 'type' | 'pos') {
    sortBy.value = s;
  }

  async function fetchBlocks(): Promise<void> {
    if (isLoading.value) return;
    isLoading.value = true;
    error.value = null;

    try {
      const resp = await bridge.getNearbyBlocks(range.value, MAX_BLOCKS);
      const list: NearbyBlock[] = [];
      for (const raw of resp.blocks) {
        const b = raw as Record<string, unknown>;
        const fullType = String(b.type ?? '');
        const parts = fullType.split('.');
        const shortType = parts[parts.length - 1] || fullType;
        list.push({
          x: Number(b.x),
          y: Number(b.y),
          z: Number(b.z),
          distance: Number(b.distance),
          type: shortType,
          fullType,
          blockId: String(b.blockId ?? ''),
          preview: b.preview ? String(b.preview) : null,
        });
      }
      blocks.value = list;

      // Drop selection if it's no longer in the list (block destroyed / out of range).
      if (selectedKey.value && !list.some(b => blockKey(b) === selectedKey.value)) {
        const prev = parseKey(selectedKey.value);
        if (prev) bridge.setBlockGlow(prev[0], prev[1], prev[2], false).catch(() => {});
        selectedKey.value = null;
        selectedDetails.value = null;
        slotTextures.value = {};
        slotTextureDegraded.value = {};
      }
    } catch (err) {
      error.value = (err as Error).message ?? String(err);
    } finally {
      isLoading.value = false;
    }
  }

  async function fetchBlockDetails(x: number, y: number, z: number): Promise<void> {
    isLoadingDetails.value = true;
    try {
      const data = await bridge.getBlockDetails(x, y, z) as Record<string, unknown>;
      if (data?.gone) {
        selectedDetails.value = null;
        return;
      }

      const items: BlockContainerItem[] | null = Array.isArray(data.items)
        ? (data.items as Array<Record<string, unknown>>).map(o => ({
            slot: Number(o.slot ?? 0),
            itemId: String(o.itemId ?? ''),
            count: Number(o.count ?? 1),
            name: o.name ? String(o.name) : undefined,
            damage: o.damage != null ? Number(o.damage) : undefined,
            maxDamage: o.maxDamage != null ? Number(o.maxDamage) : undefined,
          }))
        : null;

      const signLines = Array.isArray(data.signLines)
        ? (data.signLines as unknown[]).map(s => String(s))
        : null;
      const signLinesBack = Array.isArray(data.signLinesBack)
        ? (data.signLinesBack as unknown[]).map(s => String(s))
        : null;

      selectedDetails.value = {
        x, y, z,
        type: String(data.type ?? ''),
        blockId: String(data.blockId ?? ''),
        signLines,
        signLinesBack,
        isWaxed: typeof data.isWaxed === 'boolean' ? data.isWaxed : null,
        items,
        containerSize: data.containerSize != null ? Number(data.containerSize) : null,
        raw: data,
      };

      // Fetch item icons for container slots (best-effort, parallel).
      slotTextures.value = {};
      slotTextureDegraded.value = {};
      if (items) {
        await Promise.all(items.map(async (it) => {
          try {
            const tex = await bridge.getItemTextureById(it.itemId);
            slotTextures.value = {
              ...slotTextures.value,
              [it.slot]: `data:image/png;base64,${tex.base64Png}`,
            };
            slotTextureDegraded.value = {
              ...slotTextureDegraded.value,
              [it.slot]: (tex.spriteName ?? '').startsWith('fallback:'),
            };
          } catch {
            // non-fatal
          }
        }));
      }
    } catch (err) {
      error.value = (err as Error).message ?? String(err);
    } finally {
      isLoadingDetails.value = false;
    }
  }

  function parseKey(key: string): [number, number, number] | null {
    const parts = key.split(',').map(Number);
    if (parts.length !== 3 || parts.some(Number.isNaN)) return null;
    return [parts[0], parts[1], parts[2]];
  }

  function selectBlock(x: number, y: number, z: number) {
    const previousKey = selectedKey.value;
    const key = `${x},${y},${z}`;
    selectedKey.value = key;
    selectedDetails.value = null;
    slotTextures.value = {};
    slotTextureDegraded.value = {};

    if (previousKey && previousKey !== key) {
      const prev = parseKey(previousKey);
      if (prev) bridge.setBlockGlow(prev[0], prev[1], prev[2], false).catch(() => {});
    }
    bridge.setBlockGlow(x, y, z, true).catch(() => {});

    void fetchBlockDetails(x, y, z);
  }

  function clearSelection() {
    if (selectedKey.value) {
      const prev = parseKey(selectedKey.value);
      if (prev) bridge.setBlockGlow(prev[0], prev[1], prev[2], false).catch(() => {});
    }
    selectedKey.value = null;
    selectedDetails.value = null;
    slotTextures.value = {};
    slotTextureDegraded.value = {};
  }

  function setAutoRefresh(enabled: boolean) {
    autoRefreshEnabled.value = enabled;
    if (refreshTimer) {
      clearInterval(refreshTimer);
      refreshTimer = null;
    }
    if (enabled) {
      refreshTimer = setInterval(() => {
        if (!isLoading.value) {
          void fetchBlocks();
          if (selectedKey.value && selectedDetails.value) {
            const d = selectedDetails.value;
            void fetchBlockDetails(d.x, d.y, d.z);
          }
        }
      }, 1500);
    }
  }

  return {
    blocks,
    sortedBlocks,
    selectedKey,
    selectedDetails,
    slotTextures,
    slotTextureDegraded,
    isLoading,
    isLoadingDetails,
    error,
    range,
    autoRefreshEnabled,
    sortBy,
    setRange,
    setSortBy,
    fetchBlocks,
    fetchBlockDetails,
    selectBlock,
    clearSelection,
    setAutoRefresh,
    blockKey,
  };
});
