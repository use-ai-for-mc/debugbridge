<script setup lang="ts">
import { onMounted, onUnmounted, computed, ref, watch } from 'vue'
import { useEntitiesStore, type NearbyEntity } from '../../stores/entities'
import { useConnectionStore } from '../../stores/connection'
import JsonTree from './JsonTree.vue'
import { entityColor } from '../../services/entity-colors'

const entities = useEntitiesStore()
const connection = useConnectionStore()

onMounted(() => {
  if (connection.isConnected) {
    entities.fetchEntities()
  }
})

// In grid mode, prefetch the entity's actual rendered equipment texture.
// Keyed per-entity (not per-item) so damage/CMD overrides render correctly,
// matching what the detail panel shows for the same slot.
watch(
  () => entities.viewMode === 'grid' ? entities.sortedEntities : null,
  (list) => {
    if (!list) return
    for (const e of list) {
      const pe = e.primaryEquipment
      if (!pe) continue
      const key = entities.entityPrimaryKey(e.id, pe.slot, pe.itemId)
      if (entities.entityPrimaryTextures[key]) continue
      entities.fetchEntityPrimaryTextureCached(e.id, pe.slot, pe.itemId)
    }
  },
  { immediate: true, deep: false },
)

function entityThumbUrl(e: NearbyEntity): string | undefined {
  const pe = e.primaryEquipment
  if (!pe) return undefined
  return entities.entityPrimaryTextures[entities.entityPrimaryKey(e.id, pe.slot, pe.itemId)]
}

function entityThumbIsDegraded(e: NearbyEntity): boolean {
  const pe = e.primaryEquipment
  if (!pe) return false
  return Boolean(entities.entityPrimaryTextureDegraded[entities.entityPrimaryKey(e.id, pe.slot, pe.itemId)])
}

onUnmounted(() => {
  entities.stopAutoRefresh()
  entities.stopFollowGaze()
})

function toggleAutoRefresh() {
  if (entities.autoRefreshEnabled) {
    entities.stopAutoRefresh()
  } else {
    entities.startAutoRefresh()
  }
}

function toggleFollowGaze() {
  if (entities.followGazeEnabled) {
    entities.stopFollowGaze()
  } else {
    entities.startFollowGaze()
  }
}

function onRangeChange(e: Event) {
  const val = parseInt((e.target as HTMLInputElement).value, 10)
  if (!isNaN(val)) entities.setRange(val)
}

const selectedEntity = computed(() => {
  if (entities.selectedEntityId === null) return null
  return entities.entities.find(e => e.id === entities.selectedEntityId) ?? null
})

function formatPos(n: number): string {
  return n.toFixed(1)
}

function equipLabel(slot: string): string {
  const map: Record<string, string> = {
    MAINHAND: 'Main Hand',
    OFFHAND: 'Off Hand',
    HEAD: 'Helmet',
    CHEST: 'Chestplate',
    LEGS: 'Leggings',
    FEET: 'Boots',
  }
  return map[slot] ?? slot
}

function shortItemName(id: string): string {
  // Item ids ("minecraft:diamond") are registry keys; displayBlock is still
  // a block descriptionId ("block.minecraft.diamond_block"). Strip both forms.
  return id
    .replace(/^minecraft:/, '')
    .replace(/^(item|block)\.minecraft\./, '')
    .replace(/_/g, ' ')
}

function shortClassName(fullName: string): string {
  // Take the last segment after '.' or '$' (handles inner classes like Display$ItemDisplay)
  return fullName.replace(/.*[.$]/, '') || fullName
}

function isArmorStand(e: NearbyEntity): boolean {
  return e.type === 'armor_stand' || /ArmorStand/.test(e.fullType)
}

// Minimal armor-stand silhouette: head circle, torso bar, arms, base.
const ARMOR_STAND_SVG = `
<svg viewBox="0 0 32 32" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
  <g fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round">
    <circle cx="16" cy="7" r="3" />
    <line x1="16" y1="10" x2="16" y2="22" />
    <line x1="8"  y1="14" x2="24" y2="14" />
    <line x1="10" y1="28" x2="22" y2="28" />
    <line x1="16" y1="22" x2="16" y2="28" />
  </g>
</svg>
`.trim()

const rawTreeExpanded = ref(false)
</script>

<template>
  <div class="h-full flex flex-col">
    <!-- Toolbar -->
    <div class="p-3 border-b border-zinc-800 flex items-center gap-3 flex-wrap">
      <button
        @click="entities.refreshEntities"
        :disabled="!connection.isConnected || entities.isLoading"
        class="px-3 py-1.5 bg-zinc-800 hover:bg-zinc-700 rounded-md text-sm disabled:opacity-50 transition-colors"
      >
        {{ entities.isLoading ? '↻ Loading...' : '↻ Refresh' }}
      </button>

      <button
        @click="toggleAutoRefresh"
        :disabled="!connection.isConnected"
        class="px-3 py-1.5 rounded-md text-sm transition-colors"
        :class="entities.autoRefreshEnabled
          ? 'bg-green-800 hover:bg-green-700 text-green-200'
          : 'bg-zinc-800 hover:bg-zinc-700'"
      >
        {{ entities.autoRefreshEnabled ? '● Auto' : '○ Auto' }}
      </button>

      <button
        @click="toggleFollowGaze"
        :disabled="!connection.isConnected"
        class="px-3 py-1.5 rounded-md text-sm transition-colors"
        :class="entities.followGazeEnabled
          ? 'bg-amber-800 hover:bg-amber-700 text-amber-200'
          : 'bg-zinc-800 hover:bg-zinc-700'"
        title="Auto-select the entity the player is looking at"
      >
        {{ entities.followGazeEnabled ? '◉ Gaze' : '○ Gaze' }}
      </button>

      <div class="flex items-center gap-2 text-sm">
        <span class="text-zinc-500">Range:</span>
        <input
          type="range"
          :value="entities.range"
          @input="onRangeChange"
          min="10"
          max="128"
          step="1"
          class="w-24 accent-green-500"
        />
        <input
          type="number"
          :value="entities.range"
          @change="onRangeChange"
          min="10"
          max="128"
          class="w-14 bg-zinc-800 border border-zinc-700 rounded px-1.5 py-0.5 text-xs text-center"
        />
      </div>

      <div class="flex items-center gap-2 text-sm">
        <span class="text-zinc-500">Sort:</span>
        <select
          v-model="entities.sortBy"
          class="bg-zinc-800 border border-zinc-700 rounded px-2 py-0.5 text-xs"
        >
          <option value="distance">Distance</option>
          <option value="type">Type</option>
          <option value="id">ID</option>
        </select>
      </div>

      <div class="view-toggle flex items-center">
        <button
          @click="entities.setViewMode('list')"
          class="view-toggle-btn"
          :class="{ active: entities.viewMode === 'list' }"
          title="List view"
        >☰</button>
        <button
          @click="entities.setViewMode('grid')"
          class="view-toggle-btn"
          :class="{ active: entities.viewMode === 'grid' }"
          title="Thumbnail grid view"
        >▦</button>
      </div>

      <span class="text-xs text-zinc-500 ml-auto">
        {{ entities.aliveCount }} {{ entities.aliveCount === 1 ? 'entity' : 'entities' }}
      </span>
    </div>

    <!-- Error -->
    <div v-if="entities.error" class="px-4 py-2 bg-red-900/30 border-b border-red-800 text-red-400 text-sm">
      {{ entities.error }}
    </div>

    <div v-if="!connection.isConnected" class="text-zinc-500 text-center py-8">
      Connect to Minecraft to view nearby entities
    </div>

    <div v-else class="flex-1 flex flex-col overflow-hidden">
      <!-- Entity list (scrollable) -->
      <div class="flex-1 overflow-auto">
        <div v-if="entities.sortedEntities.length === 0 && !entities.isLoading" class="text-zinc-500 text-center py-8">
          No entities within {{ entities.range }} blocks
        </div>

        <!-- List view -->
        <div v-if="entities.viewMode === 'list'" class="divide-y divide-zinc-800/50">
          <div
            v-for="entity in entities.sortedEntities"
            :key="entity.id"
            @click="entities.selectEntity(entity.id)"
            class="entity-row flex items-center gap-2 px-4 py-1.5 cursor-pointer hover:bg-zinc-800/50 transition-all"
            :class="{
              'entity-selected': entities.selectedEntityId === entity.id,
              'entity-new': entity.status === 'new',
              'entity-despawned': entity.status === 'despawned',
            }"
          >
            <!-- Status dot -->
            <span
              class="entity-dot flex-shrink-0"
              :class="{
                'entity-dot-new': entity.status === 'new',
                'entity-dot-despawned': entity.status === 'despawned',
              }"
              :style="{ backgroundColor: entity.status === 'despawned' ? '#666' : entityColor(entity) }"
            ></span>

            <!-- Type + name -->
            <div class="flex-1 min-w-0">
              <span class="text-sm text-zinc-200">{{ entity.type }}</span>
              <span v-if="entity.customName" class="text-sm text-yellow-400 ml-1.5">
                ({{ entity.customName }})
              </span>
            </div>

            <!-- Distance -->
            <span class="text-xs text-zinc-400 font-mono flex-shrink-0 w-16 text-right">
              {{ entity.distance.toFixed(1) }}m
            </span>

            <!-- Position -->
            <span class="text-xs text-zinc-600 font-mono flex-shrink-0 hidden sm:inline">
              {{ formatPos(entity.x) }}, {{ formatPos(entity.y) }}, {{ formatPos(entity.z) }}
            </span>
          </div>
        </div>

        <!-- Grid view -->
        <div v-else class="entity-grid">
          <div
            v-for="entity in entities.sortedEntities"
            :key="entity.id"
            @click="entities.selectEntity(entity.id)"
            class="entity-tile"
            :class="{
              'entity-selected': entities.selectedEntityId === entity.id,
              'entity-new': entity.status === 'new',
              'entity-despawned': entity.status === 'despawned',
            }"
            :title="`${entity.type}${entity.customName ? ' (' + entity.customName + ')' : ''} — ${entity.distance.toFixed(1)}m`"
          >
            <div class="entity-tile-thumb">
              <img
                v-if="entityThumbUrl(entity)"
                :src="entityThumbUrl(entity)"
                class="entity-tile-img"
                alt=""
              />
              <span
                v-else-if="isArmorStand(entity)"
                v-html="ARMOR_STAND_SVG"
                class="entity-tile-fallback armor-stand-svg"
              ></span>
              <span
                v-else
                class="entity-tile-fallback letter"
                :style="{ color: entityColor(entity), borderColor: entityColor(entity), background: entityColor(entity) + '22' }"
              >{{ entity.type.charAt(0).toUpperCase() }}</span>
              <!-- Status dot overlay -->
              <span
                class="entity-tile-dot"
                :style="{ backgroundColor: entity.status === 'despawned' ? '#666' : entityColor(entity) }"
              ></span>
              <span
                v-if="entityThumbIsDegraded(entity)"
                class="entity-tile-renderer-fallback"
                title="26.1 renderer returned a generated fallback icon"
              >FB</span>
            </div>
            <div class="entity-tile-label">{{ entity.type }}</div>
            <div class="entity-tile-distance">{{ entity.distance.toFixed(1) }}m</div>
          </div>
        </div>
      </div>

      <!-- Detail panel (fixed at bottom, own scroll) -->
      <div v-if="selectedEntity" class="border-t border-zinc-800 p-4 bg-zinc-900/50 flex-shrink-0 overflow-auto max-h-[50%]">
        <div class="flex items-start gap-3">
          <!-- Entity icon -->
          <div
            class="entity-icon flex-shrink-0"
            :style="{ backgroundColor: entityColor(selectedEntity) + '22', borderColor: entityColor(selectedEntity) }"
          >
            <span :style="{ color: entityColor(selectedEntity) }">
              {{ selectedEntity.type.charAt(0) }}
            </span>
          </div>

          <div class="flex-1 min-w-0">
            <div class="text-zinc-200 font-medium">
              {{ selectedEntity.customName || selectedEntity.type }}
            </div>
            <div class="text-xs text-zinc-500 font-mono mt-0.5">{{ selectedEntity.fullType }}</div>
            <div class="text-xs text-zinc-400 mt-1">
              Entity ID: {{ selectedEntity.id }}
              <span class="text-zinc-600 mx-1">&middot;</span>
              {{ selectedEntity.distance.toFixed(1) }} blocks away
            </div>
            <div class="text-xs text-zinc-500 mt-0.5 font-mono">
              {{ formatPos(selectedEntity.x) }}, {{ formatPos(selectedEntity.y) }}, {{ formatPos(selectedEntity.z) }}
            </div>

            <!-- Despawned notice -->
            <div v-if="selectedEntity.status === 'despawned'" class="mt-2 text-xs text-red-400">
              Entity no longer in range
            </div>

            <!-- Loading details -->
            <div v-if="entities.isLoadingDetails" class="mt-2 text-xs text-zinc-500">
              Loading details...
            </div>

            <!-- Details -->
            <template v-if="entities.selectedDetails && selectedEntity.status !== 'despawned'">
              <!-- Text Display -->
              <div v-if="entities.selectedDetails.displayText !== null" class="mt-2">
                <div class="text-xs text-zinc-500 mb-0.5">Display Text</div>
                <div class="display-text-block">{{ entities.selectedDetails.displayText }}</div>
              </div>

              <!-- Item Display -->
              <div v-if="entities.selectedDetails.displayItem" class="mt-2">
                <div class="text-xs text-zinc-500 mb-0.5">Display Item</div>
                <div class="text-xs font-mono text-zinc-300">
                  {{ shortItemName(entities.selectedDetails.displayItem.itemId) }}
                  <span v-if="entities.selectedDetails.displayItem.count > 1" class="text-zinc-500">
                    ×{{ entities.selectedDetails.displayItem.count }}
                  </span>
                  <span v-if="entities.selectedDetails.displayItem.name" class="text-yellow-400 ml-1.5">
                    "{{ entities.selectedDetails.displayItem.name }}"
                  </span>
                </div>
              </div>

              <!-- Block Display -->
              <div v-if="entities.selectedDetails.displayBlock" class="mt-2">
                <div class="text-xs text-zinc-500 mb-0.5">Display Block</div>
                <div class="text-xs font-mono text-zinc-300">
                  {{ shortItemName(entities.selectedDetails.displayBlock) }}
                </div>
              </div>

              <!-- Frame Item (ItemFrame content) -->
              <div v-if="entities.selectedDetails.frameItem" class="mt-2">
                <div class="text-xs text-zinc-500 mb-0.5">Frame Item</div>
                <div class="flex items-start gap-1.5 text-xs font-mono text-zinc-400 py-0.5">
                  <img
                    v-if="entities.equipmentTextures['FRAME']"
                    :src="entities.equipmentTextures['FRAME']"
                    class="equip-icon"
                    alt=""
                  />
                  <span v-else class="equip-icon-placeholder"></span>
                  <div class="flex-1 min-w-0">
                    <div class="flex items-center gap-1.5 flex-wrap">
                      <span class="text-zinc-300">{{ shortItemName(entities.selectedDetails.frameItem.itemId) }}</span>
                      <span v-if="entities.selectedDetails.frameItem.count > 1" class="text-zinc-500">
                        ×{{ entities.selectedDetails.frameItem.count }}
                      </span>
                      <span v-if="entities.selectedDetails.frameItem.name" class="text-yellow-400">
                        "{{ entities.selectedDetails.frameItem.name }}"
                      </span>
                      <span
                        v-if="entities.selectedDetails.frameItem.maxDamage && entities.selectedDetails.frameItem.maxDamage > 0"
                        class="text-zinc-500"
                        title="damage / maxDamage"
                      >
                        {{ entities.selectedDetails.frameItem.damage ?? 0 }} / {{ entities.selectedDetails.frameItem.maxDamage }}
                      </span>
                      <span
                        v-if="entities.equipmentTextureDegraded['FRAME']"
                        class="rounded border border-amber-500/40 bg-amber-500/10 px-1 py-0.5 text-[10px] uppercase tracking-wide text-amber-300"
                        title="26.1 renderer returned a generated fallback icon"
                      >
                        Renderer fallback icon
                      </span>
                    </div>
                    <div v-if="entities.equipmentSpriteNames['FRAME']" class="text-[10px] text-zinc-600 break-all mt-0.5">
                      {{ entities.equipmentSpriteNames['FRAME'] }}
                    </div>
                  </div>
                </div>
              </div>

              <div v-if="entities.selectedDetails.health !== null" class="mt-2">
                <div class="text-xs text-zinc-500">Health</div>
                <div class="flex items-center gap-2 mt-0.5">
                  <div class="health-bar-bg">
                    <div
                      class="health-bar-fill"
                      :style="{ width: ((entities.selectedDetails.health / (entities.selectedDetails.maxHealth || 20)) * 100) + '%' }"
                    />
                  </div>
                  <span class="text-xs text-zinc-300 font-mono">
                    {{ entities.selectedDetails.health.toFixed(1) }} / {{ (entities.selectedDetails.maxHealth ?? 20).toFixed(1) }}
                  </span>
                </div>
              </div>

              <div v-if="entities.selectedDetails.armor !== null && entities.selectedDetails.armor > 0" class="mt-1.5 text-xs">
                <span class="text-zinc-500">Armor:</span>
                <span class="text-zinc-300 ml-1">{{ entities.selectedDetails.armor }}</span>
              </div>

              <!-- Equipment -->
              <div v-if="Object.keys(entities.selectedDetails.equipment).length > 0" class="mt-2">
                <div class="text-xs text-zinc-500 mb-0.5">Equipment</div>
                <div
                  v-for="(item, slot) in entities.selectedDetails.equipment"
                  :key="slot"
                  class="flex items-start gap-1.5 text-xs font-mono text-zinc-400 py-0.5"
                >
                  <img
                    v-if="entities.equipmentTextures[String(slot)]"
                    :src="entities.equipmentTextures[String(slot)]"
                    class="equip-icon"
                    alt=""
                  />
                  <span v-else class="equip-icon-placeholder"></span>
                  <div class="flex-1 min-w-0">
                    <div class="flex items-center gap-1.5 flex-wrap">
                      <span class="text-zinc-500">{{ equipLabel(String(slot)) }}:</span>
                      <span class="text-zinc-300">{{ shortItemName(item.itemId) }}</span>
                      <span v-if="item.name" class="text-yellow-400">
                        "{{ item.name }}"
                      </span>
                      <span v-if="item.maxDamage && item.maxDamage > 0" class="text-zinc-500" title="damage / maxDamage">
                        {{ item.damage ?? 0 }} / {{ item.maxDamage }}
                      </span>
                      <span
                        v-if="entities.equipmentTextureDegraded[String(slot)]"
                        class="rounded border border-amber-500/40 bg-amber-500/10 px-1 py-0.5 text-[10px] uppercase tracking-wide text-amber-300"
                        title="26.1 renderer returned a generated fallback icon"
                      >
                        Renderer fallback icon
                      </span>
                    </div>
                    <div v-if="entities.equipmentSpriteNames[String(slot)]" class="text-[10px] text-zinc-600 break-all mt-0.5">
                      {{ entities.equipmentSpriteNames[String(slot)] }}
                    </div>
                  </div>
                </div>
              </div>

              <!-- Tags -->
              <div v-if="entities.selectedDetails.tags.length > 0" class="mt-2">
                <div class="text-xs text-zinc-500 mb-0.5">Tags</div>
                <div class="flex flex-wrap gap-1">
                  <span
                    v-for="tag in entities.selectedDetails.tags"
                    :key="tag"
                    class="text-xs bg-zinc-800 text-zinc-400 rounded px-1.5 py-0.5 font-mono"
                  >{{ tag }}</span>
                </div>
              </div>

              <!-- State flags -->
              <div class="mt-2 flex gap-3 text-xs">
                <span v-if="entities.selectedDetails.isOnFire" class="text-orange-400">On Fire</span>
                <span v-if="entities.selectedDetails.isSprinting" class="text-blue-400">Sprinting</span>
                <span v-if="entities.selectedDetails.vehicle" class="text-zinc-400">
                  Riding: {{ shortClassName(entities.selectedDetails.vehicle) }}
                </span>
              </div>

              <!-- Passengers -->
              <div v-if="entities.selectedDetails.passengers.length > 0" class="mt-1.5">
                <div class="text-xs text-zinc-500">Passengers</div>
                <span
                  v-for="(p, i) in entities.selectedDetails.passengers"
                  :key="i"
                  class="text-xs text-zinc-400 font-mono"
                >{{ shortClassName(p) }}{{ i < entities.selectedDetails.passengers.length - 1 ? ', ' : '' }}</span>
              </div>

              <!-- Refresh details -->
              <button
                @click="entities.fetchEntityDetails(selectedEntity!.id)"
                class="mt-2 text-xs text-zinc-500 hover:text-zinc-300 transition-colors"
              >
                ↻ Refresh details
              </button>

              <!-- Raw object tree -->
              <div class="mt-3 border-t border-zinc-800/70 pt-2">
                <button
                  @click="rawTreeExpanded = !rawTreeExpanded"
                  class="text-xs text-zinc-400 hover:text-zinc-200 transition-colors flex items-center gap-1"
                >
                  <span>{{ rawTreeExpanded ? '▼' : '▶' }}</span>
                  <span>Raw object</span>
                </button>
                <div v-if="rawTreeExpanded" class="mt-1.5">
                  <JsonTree :value="entities.selectedDetails.raw" :depth="0" :root="true" />
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.entity-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  transition: all 0.3s;
}

.entity-dot-new {
  box-shadow: 0 0 6px currentColor;
  animation: pulse-glow 1s ease-in-out 2;
}

.entity-dot-despawned {
  opacity: 0.4;
}

.entity-row {
  transition: opacity 0.3s, background-color 0.15s;
}

.entity-selected {
  background: rgba(34, 197, 94, 0.08) !important;
  border-left: 2px solid #22c55e;
}

.entity-despawned {
  opacity: 0.4;
}

.entity-despawned .text-zinc-200 {
  text-decoration: line-through;
}

.entity-new {
  animation: new-flash 0.5s ease-out;
}

.entity-icon {
  width: 44px;
  height: 44px;
  min-width: 44px;
  border: 2px solid #333;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 700;
  text-transform: uppercase;
}

.health-bar-bg {
  width: 120px;
  height: 6px;
  background: #333;
  border-radius: 3px;
  overflow: hidden;
}

.health-bar-fill {
  height: 100%;
  background: #ef4444;
  border-radius: 3px;
  transition: width 0.3s;
}

@keyframes pulse-glow {
  0%, 100% { box-shadow: 0 0 2px currentColor; }
  50% { box-shadow: 0 0 8px currentColor; }
}

@keyframes new-flash {
  from { background-color: rgba(34, 197, 94, 0.15); }
  to { background-color: transparent; }
}

.display-text-block {
  background: rgba(24, 24, 27, 0.8);
  border-left: 2px solid #22c55e;
  padding: 6px 10px;
  border-radius: 4px;
  font-size: 0.85em;
  color: #e4e4e7;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, monospace;
}

.equip-icon {
  width: 32px;
  height: 32px;
  image-rendering: pixelated;
  background: rgba(39, 39, 42, 0.6);
  border-radius: 3px;
  flex-shrink: 0;
}

.equip-icon-placeholder {
  width: 32px;
  height: 32px;
  background: rgba(39, 39, 42, 0.4);
  border-radius: 3px;
  flex-shrink: 0;
}

.view-toggle {
  border: 1px solid #3f3f46;
  border-radius: 4px;
  overflow: hidden;
}

.view-toggle-btn {
  padding: 2px 8px;
  font-size: 14px;
  background: #27272a;
  color: #a1a1aa;
  transition: background 0.15s, color 0.15s;
}

.view-toggle-btn:hover {
  background: #3f3f46;
  color: #f4f4f5;
}

.view-toggle-btn.active {
  background: #166534;
  color: #dcfce7;
}

.entity-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(72px, 1fr));
  gap: 8px;
  padding: 12px;
}

.entity-tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 6px 4px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.15s, opacity 0.3s;
  background: rgba(39, 39, 42, 0.3);
}

.entity-tile:hover {
  background: rgba(63, 63, 70, 0.7);
}

.entity-tile.entity-selected {
  background: rgba(34, 197, 94, 0.15);
  outline: 2px solid #22c55e;
}

.entity-tile.entity-despawned {
  opacity: 0.4;
}

.entity-tile.entity-despawned .entity-tile-label {
  text-decoration: line-through;
}

.entity-tile.entity-new {
  animation: new-flash 0.5s ease-out;
}

.entity-tile-thumb {
  position: relative;
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(24, 24, 27, 0.6);
  border-radius: 4px;
}

.entity-tile-img {
  width: 40px;
  height: 40px;
  image-rendering: pixelated;
}

.entity-tile-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
}

.entity-tile-fallback.letter {
  border: 2px solid;
  border-radius: 4px;
  font-weight: 700;
  font-size: 18px;
}

.entity-tile-fallback.armor-stand-svg {
  color: #a1a1aa;
}

.entity-tile-fallback.armor-stand-svg :deep(svg) {
  width: 36px;
  height: 36px;
}

.entity-tile-dot {
  position: absolute;
  bottom: 2px;
  right: 2px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  border: 1px solid #18181b;
}

.entity-tile-renderer-fallback {
  position: absolute;
  top: 1px;
  right: 1px;
  padding: 0 3px;
  border: 1px solid rgba(245, 158, 11, 0.55);
  border-radius: 3px;
  background: rgba(24, 24, 27, 0.95);
  color: #fcd34d;
  font-size: 9px;
  font-weight: 700;
  line-height: 13px;
  letter-spacing: 0.04em;
}

.entity-tile-label {
  font-size: 10px;
  color: #d4d4d8;
  max-width: 64px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.entity-tile-distance {
  font-size: 9px;
  color: #71717a;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, monospace;
}
</style>
