<script setup lang="ts">
import { onMounted, computed } from 'vue'
import { useInventoryStore, type InventoryItem } from '../../stores/inventory'
import { useConnectionStore } from '../../stores/connection'
import SnbtTree from './SnbtTree.vue'
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

function itemShortLabel(item: InventoryItem): string {
  const parts = item.name.split('_')
  if (parts.length <= 2) return item.name.replace(/_/g, ' ')
  return parts.slice(-2).join(' ')
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
        <!-- Left: Inventory grid -->
        <div class="flex flex-col gap-4">
          <!-- Main inventory (3 rows x 9 cols, slots 9-35) -->
          <div>
            <div class="text-xs text-zinc-500 mb-1.5">Inventory</div>
            <div class="grid grid-cols-9 gap-1">
              <div
                v-for="(item, i) in mainGrid"
                :key="'main-' + i"
                @click="inventory.selectSlot(i + 9)"
                class="inv-slot"
                :class="{ 'inv-slot-selected': inventory.selectedSlot === i + 9 }"
                :title="item ? item.id + ' x' + item.count : slotLabel(i + 9)"
              >
                <template v-if="item">
                  <img v-if="item.textureUrl" :src="item.textureUrl" class="inv-item-texture" :alt="item.name" />
                  <div v-else class="inv-item-icon" :style="{ backgroundColor: itemColor(item) + '33', borderColor: itemColor(item) }">
                    <span class="inv-item-label" :style="{ color: itemColor(item) }">
                      {{ itemShortLabel(item) }}
                    </span>
                  </div>
                  <span v-if="item.count > 1" class="inv-count">{{ item.count }}</span>
                  <div v-if="item.damage > 0 && item.maxDamage > 0" class="inv-durability">
                    <div
                      class="inv-durability-bar"
                      :style="{
                        width: ((1 - item.damage / item.maxDamage) * 100) + '%',
                        backgroundColor: item.damage / item.maxDamage > 0.7 ? '#ff4444' : item.damage / item.maxDamage > 0.4 ? '#ffaa00' : '#44ff44'
                      }"
                    />
                  </div>
                </template>
              </div>
            </div>
          </div>

          <!-- Hotbar (1 row x 9 cols, slots 0-8) -->
          <div>
            <div class="text-xs text-zinc-500 mb-1.5">Hotbar</div>
            <div class="grid grid-cols-9 gap-1">
              <div
                v-for="(item, i) in hotbar"
                :key="'hot-' + i"
                @click="inventory.selectSlot(i)"
                class="inv-slot"
                :class="{ 'inv-slot-selected': inventory.selectedSlot === i }"
                :title="item ? item.id + ' x' + item.count : slotLabel(i)"
              >
                <template v-if="item">
                  <img v-if="item.textureUrl" :src="item.textureUrl" class="inv-item-texture" :alt="item.name" />
                  <div v-else class="inv-item-icon" :style="{ backgroundColor: itemColor(item) + '33', borderColor: itemColor(item) }">
                    <span class="inv-item-label" :style="{ color: itemColor(item) }">
                      {{ itemShortLabel(item) }}
                    </span>
                  </div>
                  <span v-if="item.count > 1" class="inv-count">{{ item.count }}</span>
                  <div v-if="item.damage > 0 && item.maxDamage > 0" class="inv-durability">
                    <div
                      class="inv-durability-bar"
                      :style="{
                        width: ((1 - item.damage / item.maxDamage) * 100) + '%',
                        backgroundColor: item.damage / item.maxDamage > 0.7 ? '#ff4444' : item.damage / item.maxDamage > 0.4 ? '#ffaa00' : '#44ff44'
                      }"
                    />
                  </div>
                </template>
              </div>
            </div>
          </div>
        </div>

        <!-- Right: Armor + Offhand -->
        <div class="flex flex-col gap-4">
          <div>
            <div class="text-xs text-zinc-500 mb-1.5">Armor</div>
            <div class="flex flex-col gap-1">
              <div
                v-for="(item, i) in armor"
                :key="'armor-' + i"
                @click="inventory.selectSlot(armorSlotIdx[i])"
                class="inv-slot inv-slot-armor"
                :class="{ 'inv-slot-selected': inventory.selectedSlot === armorSlotIdx[i] }"
                :title="item ? item.id : slotLabel(armorSlotIdx[i])"
              >
                <template v-if="item">
                  <img v-if="item.textureUrl" :src="item.textureUrl" class="inv-item-texture" :alt="item.name" />
                  <div v-else class="inv-item-icon" :style="{ backgroundColor: itemColor(item) + '33', borderColor: itemColor(item) }">
                    <span class="inv-item-label" :style="{ color: itemColor(item) }">
                      {{ itemShortLabel(item) }}
                    </span>
                  </div>
                </template>
                <span v-else class="text-zinc-600 text-[9px]">{{ ['Head', 'Chest', 'Legs', 'Feet'][i] }}</span>
              </div>
            </div>
          </div>

          <div>
            <div class="text-xs text-zinc-500 mb-1.5">Offhand</div>
            <div
              @click="inventory.selectSlot(40)"
              class="inv-slot inv-slot-armor"
              :class="{ 'inv-slot-selected': inventory.selectedSlot === 40 }"
              :title="offhand ? offhand.id : 'Offhand'"
            >
              <template v-if="offhand">
                <img v-if="offhand.textureUrl" :src="offhand.textureUrl" class="inv-item-texture" :alt="offhand.name" />
                <div v-else class="inv-item-icon" :style="{ backgroundColor: itemColor(offhand) + '33', borderColor: itemColor(offhand) }">
                  <span class="inv-item-label" :style="{ color: itemColor(offhand) }">
                    {{ itemShortLabel(offhand) }}
                  </span>
                </div>
              </template>
              <span v-else class="text-zinc-600 text-[9px]">Off</span>
            </div>
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
.inv-slot {
  width: 44px;
  height: 44px;
  background: #1a1a1a;
  border: 1px solid #333;
  border-radius: 4px;
  position: relative;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  transition: border-color 0.15s;
}

.inv-slot:hover {
  border-color: #555;
}

.inv-slot-selected {
  border-color: #22c55e !important;
  box-shadow: 0 0 6px rgba(34, 197, 94, 0.3);
}

.inv-slot-armor {
  width: 44px;
  height: 44px;
}

.inv-item-icon {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: 3px;
}

.inv-item-label {
  font-size: 8px;
  font-weight: 600;
  text-align: center;
  line-height: 1.1;
  word-break: break-word;
  padding: 1px;
  text-transform: capitalize;
}

.inv-count {
  position: absolute;
  bottom: 1px;
  right: 2px;
  font-size: 10px;
  font-weight: bold;
  color: white;
  text-shadow: 1px 1px 0 #000, -1px -1px 0 #000, 1px -1px 0 #000, -1px 1px 0 #000;
  pointer-events: none;
}

.inv-durability {
  position: absolute;
  bottom: 2px;
  left: 3px;
  right: 3px;
  height: 2px;
  background: #333;
  border-radius: 1px;
}

.inv-durability-bar {
  height: 100%;
  border-radius: 1px;
  transition: width 0.3s;
}

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

.inv-item-texture {
  width: 100%;
  height: 100%;
  object-fit: contain;
  image-rendering: pixelated;
  padding: 2px;
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
