import { defineStore } from 'pinia';
import { ref } from 'vue';
import { bridge } from '../services/bridge';

export interface InventoryItem {
  slot: number;
  id: string;         // e.g. "minecraft:diamond_sword"
  name: string;       // short name e.g. "diamond_sword"
  count: number;
  damage: number;
  maxDamage: number;
  enchantments?: string[];
  customName?: string;
  lore?: string[];
  components?: Record<string, string>;
  textureUrl?: string;
  spriteName?: string;
}

export const useInventoryStore = defineStore('inventory', () => {
  const slots = ref<(InventoryItem | null)[]>(new Array(41).fill(null));
  const selectedSlot = ref<number | null>(null);
  const isLoading = ref(false);
  const error = ref<string | null>(null);

  async function fetchInventory(): Promise<void> {
    isLoading.value = true;
    error.value = null;

    try {
      // getDescriptionId() is fast and returns proper MC item names
      const code = `
        return sync {
          def items = player.getInventory().items
          def lines = []
          for (int i = 0; i < items.size(); i++) {
            def stack = items.get(i)
            if (!stack.isEmpty()) {
              lines << "\${i}|\${stack.getItem().getDescriptionId()}|\${stack.getCount()}"
            }
          }
          lines.join("\\n")
        }
      `;

      const resp = await bridge.execute(code);
      if (!resp.success) {
        throw new Error(resp.error || 'Failed to fetch inventory');
      }

      const raw = unwrapValue(resp.result);
      const text = typeof raw === 'string' ? raw : String(raw ?? '');
      const newSlots: (InventoryItem | null)[] = new Array(41).fill(null);

      for (const line of text.split('\n')) {
        if (!line) continue;
        const [slotStr, descId, countStr] = line.split('|');
        const slotIdx = parseInt(slotStr, 10);
        if (isNaN(slotIdx)) continue;
        // descId like "item.minecraft.diamond_shovel" or "block.minecraft.pink_shulker_box"
        const parts = descId.split('.');
        const name = parts[parts.length - 1] || descId;
        const mcId = parts.length >= 2 ? `minecraft:${name}` : descId;
        newSlots[slotIdx] = {
          slot: slotIdx,
          id: mcId,
          name,
          count: parseInt(countStr, 10) || 1,
          damage: 0,
          maxDamage: 0,
        };
      }

      slots.value = newSlots;

      // Fetch textures for all non-empty slots in the background
      fetchAllTextures(newSlots);
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
    } finally {
      isLoading.value = false;
    }
  }

  async function fetchAllTextures(items: (InventoryItem | null)[]): Promise<void> {
    const nonEmpty = items.filter((it): it is InventoryItem => it !== null);
    // Fetch in parallel but don't block the UI
    await Promise.allSettled(
      nonEmpty.map(async (item) => {
        try {
          const tex = await bridge.getItemTexture(item.slot);
          item.textureUrl = `data:image/png;base64,${tex.base64Png}`;
          item.spriteName = tex.spriteName;
        } catch {
          // Texture not available for this item
        }
      })
    );
    slots.value = [...slots.value];
  }

  async function fetchItemDetails(slotIdx: number): Promise<void> {
    const item = slots.value[slotIdx];
    if (!item) return;

    try {
      const code = `
        return sync {
          def stack = player.getInventory().getItem(${slotIdx})
          if (stack == null || stack.isEmpty()) return [empty: true]

          def result = [:]

          // Display name
          try { result.displayName = "" + stack.getHoverName().getString() } catch (e) { }

          // Components: encode each via codec -> NbtOps -> SNBT
          try {
            def NbtOps = java.type('net.minecraft.nbt.NbtOps')
            def compMap = [:]
            for (comp in java.list(stack.getComponents())) {
              try {
                def ctype = comp.type()
                def cval = comp.value()
                def typeName = "" + ctype.toString()
                def encoded = ctype.codecOrThrow().encodeStart(NbtOps.INSTANCE, cval)
                def opt = encoded.result()
                if (!java.isNull(opt) && opt.isPresent()) {
                  compMap[typeName] = "" + opt.get().toString()
                } else {
                  compMap[typeName] = "" + cval
                }
              } catch (e) { }
            }
            result.components = compMap
          } catch (e) { }

          // Enchantments
          try {
            def DataComponents = java.type('net.minecraft.core.component.DataComponents')
            def enchComp = stack.get(DataComponents.ENCHANTMENTS)
            if (enchComp != null) {
              def elist = []
              for (entry in java.list(enchComp.entrySet())) {
                elist << ("" + entry.getKey().value().toString() + " " + entry.getIntValue())
              }
              result.enchantments = elist
            }
          } catch (e) { }

          result
        }
      `;

      const resp = await bridge.execute(code);
      if (!resp.success) return;

      const data = unwrapValue(resp.result) as Record<string, unknown>;
      if (data.empty) return;

      if (data.displayName) {
        item.customName = String(data.displayName);
      }

      if (data.enchantments && Array.isArray(data.enchantments)) {
        item.enchantments = (data.enchantments as unknown[]).map(e => String(e));
      }

      const rawComps = data.components;
      if (rawComps && typeof rawComps === 'object' && !Array.isArray(rawComps)) {
        const comps: Record<string, string> = {};
        for (const [key, val] of Object.entries(rawComps as Record<string, unknown>)) {
          comps[key] = String(val ?? '');
        }
        item.components = comps;

        // Extract damage/maxDamage for durability display
        if (comps['minecraft:damage']) {
          const d = parseInt(comps['minecraft:damage'], 10);
          if (!isNaN(d)) item.damage = d;
        }
        if (comps['minecraft:max_damage']) {
          const md = parseInt(comps['minecraft:max_damage'], 10);
          if (!isNaN(md)) item.maxDamage = md;
        }
      }

      // Trigger reactivity
      slots.value = [...slots.value];

      // Fetch texture (non-blocking — updates the item when ready)
      fetchItemTexture(slotIdx);
    } catch {
      // Silently fail for details
    }
  }

  async function fetchItemTexture(slotIdx: number): Promise<void> {
    const item = slots.value[slotIdx];
    if (!item) return;

    try {
      const tex = await bridge.getItemTexture(slotIdx);
      item.textureUrl = `data:image/png;base64,${tex.base64Png}`;
      item.spriteName = tex.spriteName;
      slots.value = [...slots.value];
    } catch {
      // Texture not available (older mod version or unsupported item)
    }
  }

  function selectSlot(idx: number | null) {
    selectedSlot.value = idx;
    if (idx !== null && slots.value[idx]) {
      fetchItemDetails(idx);
    }
  }

  return {
    slots,
    selectedSlot,
    isLoading,
    error,
    fetchInventory,
    fetchItemDetails,
    selectSlot,
  };
});

function unwrapValue(data: unknown): unknown {
  if (data === null || data === undefined) return data;
  if (Array.isArray(data)) return data.map(unwrapValue);
  if (typeof data !== 'object') return data;

  const obj = data as Record<string, unknown>;
  if ('type' in obj && 'value' in obj && typeof obj.type === 'string') {
    const t = obj.type as string;
    if (t === 'table') return unwrapValue(obj.value);
    if (t === 'string' || t === 'number' || t === 'boolean' || t === 'nil' || t === 'userdata') {
      return obj.value;
    }
  }

  const result: Record<string, unknown> = {};
  for (const [key, val] of Object.entries(obj)) {
    result[key] = unwrapValue(val);
  }
  return result;
}
