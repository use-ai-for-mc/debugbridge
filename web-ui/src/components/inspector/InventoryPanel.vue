<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { useInventoryStore, type InventoryItem } from '../../stores/inventory'
import { useConnectionStore } from '../../stores/connection'
import SnbtTree from './SnbtTree.vue'
import InventorySlot from './InventorySlot.vue'
import { parseSnbt, extractLoreLines, mcColorToHex } from '../../services/snbt'

const inventory = useInventoryStore()
const connection = useConnectionStore()

onMounted(() => {
  if (connection.isConnected) {
    inventory.fetchInventory()
  }
})

const hotbar = computed(() => inventory.slots.slice(0, 9))
const mainGrid = computed(() => inventory.slots.slice(9, 36))
const armor = computed(() => {
  // Armor: 36=feet, 37=legs, 38=chest, 39=head — display top to bottom
  return [inventory.slots[39], inventory.slots[38], inventory.slots[37], inventory.slots[36]]
})
const offhand = computed(() => inventory.slots[40])

const selectedItem = computed(() => {
  if (inventory.selectedSlot === null) return null
  return inventory.slots[inventory.selectedSlot]
})

function slotLabel(idx: number): string {
  if (idx < 9) return `Hotbar ${idx + 1}`
  if (idx < 36) return `Slot ${idx}`
  if (idx === 36) return 'Boots'
  if (idx === 37) return 'Leggings'
  if (idx === 38) return 'Chestplate'
  if (idx === 39) return 'Helmet'
  if (idx === 40) return 'Offhand'
  return `Slot ${idx}`
}

function itemColor(item: InventoryItem): string {
  const n = item.name
  if (n.includes('diamond')) return '#4ee4e4'
  if (n.includes('netherite')) return '#6a4e6a'
  if (n.includes('iron')) return '#d8d8d8'
  if (n.includes('gold') || n.includes('golden')) return '#ffd700'
  if (n.includes('stone')) return '#888'
  if (n.includes('wood') || n.includes('oak') || n.includes('birch') || n.includes('spruce')) return '#b5854b'
  if (n.includes('shulker')) return '#9d65c9'
  if (n.includes('potion') || n.includes('splash')) return '#ff66cc'
  if (n.includes('enchant')) return '#a080ff'
  if (n.includes('apple')) return '#ff4444'
  if (n.includes('carrot')) return '#ff9922'
  if (n.includes('sword') || n.includes('axe') || n.includes('pickaxe')) return '#aaa'
  return '#7a9955'
}

const loreLines = computed(() => {
  if (!selectedItem.value?.components?.['minecraft:lore']) return null
  const lines = extractLoreLines(selectedItem.value.components['minecraft:lore'])
  return lines.length > 0 ? lines : null
})

const armorSlotIdx = [39, 38, 37, 36]
</script>

<template>
  <div class="h-full flex flex-col">
    <!-- Toolbar -->
    <div class="p-3 border-b border-zinc-800 flex items-center gap-3">
      <button
        @click="inventory.fetchInventory"
        :disabled="!connection.isConnected || inventory.isLoading"
        class="px-3 py-1.5 bg-zinc-800 hover:bg-zinc-700 rounded-md text-sm disabled:opacity-50 transition-colors"
      >
        {{ inventory.isLoading ? '↻ Loading...' : '↻ Refresh' }}
      </button>
      <span class="text-xs text-zinc-500">
        Click a slot to inspect item details
      </span>
    </div>

    <!-- Error -->
    <div v-if="inventory.error" class="px-4 py-2 bg-red-900/30 border-b border-red-800 text-red-400 text-sm">
      {{ inventory.error }}
    </div>

      <div v-if="!connection.isConnected" class="text-zinc-500 text-center py-8">
        Connect to Minecraft to view inventory
      </div>

      <div v-else class="flex-1 overflow-auto p-4">
        <div class="flex gap-6">
          <div class="flex flex-col gap-4">
            <!-- Main inventory (3 rows x 9 cols, slots 9-35) -->
            <div>
              <div class="text-xs text-zinc-500 mb-1.5">Inventory</div>
              <div class="grid grid-cols-9 gap-1">
                <InventorySlot
                  v-for="(item, i) in mainGrid"
                  :key="'main-' + i"
                  :item="item"
                  :selected="inventory.selectedSlot === i + 9"
                  :title="item ? item.id + ' x' + item.count : slotLabel(i + 9)"
                  :show-count="true"
                  :show-durability="true"
                  @select="inventory.selectSlot(i + 9)"
                />
              </div>
            </div>

            <!-- Hotbar (1 row x 9 cols, slots 0-8) -->
            <div>
              <div class="text-xs text-zinc-500 mb-1.5">Hotbar</div>
              <div class="grid grid-cols-9 gap-1">
                <InventorySlot
                  v-for="(item, i) in hotbar"
                  :key="'hot-' + i"
                  :item="item"
                  :selected="inventory.selectedSlot === i"
                  :title="item ? item.id + ' x' + item.count : slotLabel(i)"
                  :show-count="true"
                  :show-durability="true"
                  @select="inventory.selectSlot(i)"
                />
              </div>
            </div>
          </div>

          <!-- Right: Armor + Offhand -->
          <div class="flex flex-col gap-4">
            <div>
              <div class="text-xs text-zinc-500 mb-1.5">Armor</div>
              <div class="flex flex-col gap-1">
                <InventorySlot
                  v-for="(item, i) in armor"
                  :key="'armor-' + i"
                  :item="item"
                  :selected="inventory.selectedSlot === armorSlotIdx[i]"
                  :title="item ? item.id : slotLabel(armorSlotIdx[i])"
                  variant="armor"
                  :empty-label="['Head', 'Chest', 'Legs', 'Feet'][i]"
                  @select="inventory.selectSlot(armorSlotIdx[i])"
                />
              </div>
            </div>

            <div>
              <div class="text-xs text-zinc-500 mb-1.5">Offhand</div>
              <InventorySlot
                :item="offhand"
                :selected="inventory.selectedSlot === 40"
                :title="offhand ? offhand.id : 'Offhand'"
                variant="armor"
                empty-label="Off"
                @select="inventory.selectSlot(40)"
              />
            </div>
          </div>
        </div>

      <!-- Item detail panel -->
      <div v-if="selectedItem" class="mt-4 border border-zinc-800 rounded-lg p-4 bg-zinc-900/50">
        <div class="flex items-start gap-3">
          <div class="inv-slot-large" :style="{ borderColor: itemColor(selectedItem) }">
            <img v-if="selectedItem.textureUrl" :src="selectedItem.textureUrl" class="inv-item-texture-large" :alt="selectedItem.name" />
            <div v-else class="inv-item-icon-large" :style="{ backgroundColor: itemColor(selectedItem) + '33', borderColor: itemColor(selectedItem) }">
              <span :style="{ color: itemColor(selectedItem) }">{{ selectedItem.name.replace(/_/g, ' ') }}</span>
            </div>
          </div>
          <div class="flex-1 min-w-0">
            <div class="text-zinc-200 font-medium">
              {{ selectedItem.customName || selectedItem.name.replace(/_/g, ' ') }}
            </div>
            <div class="text-xs text-zinc-500 font-mono mt-0.5">{{ selectedItem.id }}</div>
            <div v-if="selectedItem.textureDegraded" class="inv-texture-degraded mt-1">
              Renderer fallback icon
            </div>
            <div class="text-xs text-zinc-400 mt-1">
              {{ slotLabel(selectedItem.slot) }}
              <span v-if="selectedItem.count > 1"> &middot; Count: {{ selectedItem.count }}</span>
            </div>
            <div v-if="selectedItem.maxDamage > 0" class="text-xs mt-1">
              <span class="text-zinc-500">Durability:</span>
              <span class="text-zinc-300 ml-1">
                {{ selectedItem.maxDamage - selectedItem.damage }} / {{ selectedItem.maxDamage }}
              </span>
            </div>

            <!-- Enchantments -->
            <div v-if="selectedItem.enchantments?.length" class="mt-2">
              <div class="text-xs text-purple-400 mb-1">Enchantments</div>
              <div v-for="ench in selectedItem.enchantments" :key="ench" class="text-xs text-purple-300 font-mono">
                {{ ench }}
              </div>
            </div>

            <!-- Lore (rendered) -->
            <div v-if="loreLines" class="mt-2">
              <div class="text-xs text-zinc-500 mb-1">Lore</div>
              <div class="bg-zinc-900 rounded p-2 space-y-0.5">
                <div v-for="(line, li) in loreLines" :key="li" class="text-xs font-mono leading-snug min-h-[1.1em]">
                  <template v-if="line.segments.length === 0">
                    <span class="text-zinc-600">&nbsp;</span>
                  </template>
                  <span
                    v-for="(seg, si) in line.segments"
                    :key="si"
                    :style="{
                      color: seg.color ? mcColorToHex(seg.color) : '#AAAAAA',
                      fontWeight: seg.bold ? 'bold' : 'normal',
                      fontStyle: seg.italic ? 'italic' : 'normal',
                      textDecoration: [seg.strikethrough ? 'line-through' : '', seg.underlined ? 'underline' : ''].filter(Boolean).join(' ') || 'none',
                    }"
                    :class="{ 'lore-obfuscated': seg.obfuscated }"
                  >{{ seg.text }}</span>
                </div>
              </div>
            </div>

            <!-- Data Components -->
            <div v-if="selectedItem.components && Object.keys(selectedItem.components).length" class="mt-2">
              <div class="text-xs text-zinc-500 mb-1">Data Components</div>
              <div class="max-h-80 overflow-auto">
                <SnbtTree
                  v-for="(val, key) in selectedItem.components"
                  :key="key"
                  :node="{ ...parseSnbt(val), key: String(key) }"
                  :depth="0"
                />
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.inv-slot-large {
  width: 56px;
  height: 56px;
  min-width: 56px;
  background: #1a1a1a;
  border: 2px solid #333;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.inv-item-icon-large {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
  text-align: center;
  text-transform: capitalize;
  padding: 2px;
  word-break: break-word;
  line-height: 1.1;
}

.inv-item-texture-large {
  width: 100%;
  height: 100%;
  object-fit: contain;
  image-rendering: pixelated;
  padding: 4px;
}

.inv-texture-degraded {
  display: inline-flex;
  width: fit-content;
  border: 1px solid rgba(251, 191, 36, 0.35);
  border-radius: 999px;
  background: rgba(251, 191, 36, 0.12);
  color: #fbbf24;
  font-size: 11px;
  line-height: 1;
  padding: 3px 7px;
}

.lore-obfuscated {
  filter: blur(2px);
  user-select: none;
}
</style>
