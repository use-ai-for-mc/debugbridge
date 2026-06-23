<script setup lang="ts">
import { onMounted, onUnmounted, computed } from 'vue'
import { useBlocksStore, type NearbyBlock } from '../../stores/blocks'
import { useConnectionStore } from '../../stores/connection'
import JsonTree from './JsonTree.vue'

const blocks = useBlocksStore()
const connection = useConnectionStore()

onMounted(() => {
  if (connection.isConnected) {
    blocks.fetchBlocks()
  }
})

onUnmounted(() => {
  blocks.setAutoRefresh(false)
  blocks.clearSelection()
})

function toggleAutoRefresh() {
  blocks.setAutoRefresh(!blocks.autoRefreshEnabled)
}

function onRangeChange(e: Event) {
  const val = parseInt((e.target as HTMLInputElement).value, 10)
  if (!isNaN(val)) blocks.setRange(val)
}

const selectedBlock = computed<NearbyBlock | null>(() => {
  if (!blocks.selectedKey) return null
  return blocks.blocks.find(b => blocks.blockKey(b) === blocks.selectedKey) ?? null
})

function shortBlockId(id: string): string {
  // "minecraft:oak_sign" -> "oak sign"
  return id.replace(/^minecraft:/, '').replace(/_/g, ' ')
}

function shortItemName(id: string): string {
  // "minecraft:diamond" -> "diamond"
  return id.replace(/^minecraft:/, '').replace(/_/g, ' ')
}

function blockColor(b: NearbyBlock): string {
  const t = b.type.toLowerCase()
  if (t.includes('sign')) return '#ffd24a'
  if (t.includes('chest') || t.includes('barrel') || t.includes('shulker')
      || t.includes('hopper') || t.includes('dispenser') || t.includes('dropper')
      || t.includes('brewing')) return '#a78bfa'
  if (t.includes('furnace') || t.includes('smoker') || t.includes('blast')) return '#f97316'
  if (t.includes('beacon')) return '#22d3ee'
  if (t.includes('banner')) return '#ec4899'
  if (t.includes('skull')) return '#a3a3a3'
  if (t.includes('beehive')) return '#facc15'
  if (t.includes('jukebox') || t.includes('lectern') || t.includes('enchant')) return '#84cc16'
  return '#71717a'
}
</script>

<template>
  <div class="h-full flex flex-col">
    <!-- Header -->
    <div class="flex items-center gap-2 px-3 py-2 border-b border-zinc-800 text-xs">
      <button
        @click="blocks.fetchBlocks()"
        :disabled="blocks.isLoading || !connection.isConnected"
        class="px-2 py-1 bg-zinc-800 hover:bg-zinc-700 rounded disabled:opacity-50"
        title="Refresh nearby blocks"
      >↻ Refresh</button>
      <button
        @click="toggleAutoRefresh"
        :disabled="!connection.isConnected"
        class="px-2 py-1 rounded"
        :class="blocks.autoRefreshEnabled
          ? 'bg-green-700 hover:bg-green-600 text-white'
          : 'bg-zinc-800 hover:bg-zinc-700 text-zinc-300'"
        title="Auto-refresh every 1.5s"
      >{{ blocks.autoRefreshEnabled ? '● Auto' : '○ Auto' }}</button>

      <label class="flex items-center gap-1 ml-2 text-zinc-400">
        Range:
        <input
          type="range"
          :min="4" :max="48" :step="2"
          :value="blocks.range"
          @input="onRangeChange"
          class="w-24"
        />
        <input
          type="number"
          :min="4" :max="48"
          :value="blocks.range"
          @input="onRangeChange"
          class="w-12 bg-zinc-900 px-1 rounded text-zinc-300"
        />
      </label>

      <label class="flex items-center gap-1 ml-2 text-zinc-400">
        Sort:
        <select
          :value="blocks.sortBy"
          @change="blocks.setSortBy(($event.target as HTMLSelectElement).value as 'distance' | 'type' | 'pos')"
          class="bg-zinc-900 text-zinc-300 px-1 rounded"
        >
          <option value="distance">Distance</option>
          <option value="type">Type</option>
          <option value="pos">Position</option>
        </select>
      </label>

      <span class="ml-auto text-zinc-500">{{ blocks.blocks.length }} blocks</span>
    </div>

    <!-- List + Detail -->
    <div class="flex-1 flex flex-col overflow-hidden">
      <!-- List -->
      <div class="flex-1 overflow-auto">
        <div v-if="blocks.error" class="p-3 text-xs text-red-400">{{ blocks.error }}</div>
        <div v-if="blocks.blocks.length === 0 && !blocks.isLoading" class="p-3 text-xs text-zinc-500">
          No block entities within {{ blocks.range }} blocks. Try increasing the range.
        </div>
        <div
          v-for="b in blocks.sortedBlocks"
          :key="blocks.blockKey(b)"
          @click="blocks.selectBlock(b.x, b.y, b.z)"
          class="px-3 py-1.5 cursor-pointer hover:bg-zinc-800 border-l-2 transition-colors"
          :class="blocks.selectedKey === blocks.blockKey(b)
            ? 'bg-zinc-800 border-green-500'
            : 'border-transparent'"
        >
          <div class="flex items-center gap-2 text-xs font-mono">
            <span
              class="inline-block w-2 h-2 rounded-sm shrink-0"
              :style="{ backgroundColor: blockColor(b) }"
            ></span>
            <span class="text-zinc-300 shrink-0">{{ shortBlockId(b.blockId) }}</span>
            <span class="text-zinc-500 shrink-0">({{ b.x }}, {{ b.y }}, {{ b.z }})</span>
            <span class="text-zinc-600 ml-auto shrink-0">{{ b.distance }}m</span>
          </div>
          <div v-if="b.preview" class="text-[11px] text-zinc-500 ml-4 truncate">{{ b.preview }}</div>
        </div>
      </div>

      <!-- Detail (resizable panel below) -->
      <div v-if="selectedBlock" class="border-t border-zinc-800 max-h-[55%] overflow-auto p-3 text-xs">
        <div v-if="blocks.isLoadingDetails && !blocks.selectedDetails" class="text-zinc-500">
          Loading…
        </div>

        <div v-if="blocks.selectedDetails">
          <div class="flex items-center gap-2 mb-2">
            <span
              class="w-2 h-2 rounded-sm"
              :style="{ backgroundColor: blockColor(selectedBlock) }"
            ></span>
            <span class="text-zinc-200 font-semibold">{{ shortBlockId(blocks.selectedDetails.blockId) }}</span>
            <span class="text-zinc-500 font-mono">
              ({{ blocks.selectedDetails.x }}, {{ blocks.selectedDetails.y }}, {{ blocks.selectedDetails.z }})
            </span>
            <button
              @click="blocks.fetchBlockDetails(blocks.selectedDetails.x, blocks.selectedDetails.y, blocks.selectedDetails.z)"
              class="ml-auto text-zinc-500 hover:text-zinc-300"
              title="Refresh details"
            >↻</button>
          </div>

          <div class="text-[10px] text-zinc-600 break-all mb-2 font-mono">{{ blocks.selectedDetails.type }}</div>

          <!-- Sign text -->
          <div v-if="blocks.selectedDetails.signLines" class="mb-3">
            <div class="text-zinc-500 mb-1">
              Sign{{ blocks.selectedDetails.isWaxed ? ' (waxed)' : '' }}
            </div>
            <div class="bg-zinc-900 p-2 rounded font-mono">
              <div
                v-for="(line, i) in blocks.selectedDetails.signLines"
                :key="`f${i}`"
                class="text-zinc-300 min-h-[1em]"
              >{{ line || ' ' }}</div>
            </div>
            <div v-if="blocks.selectedDetails.signLinesBack" class="mt-2">
              <div class="text-zinc-500 mb-1 text-[10px]">Back side</div>
              <div class="bg-zinc-900 p-2 rounded font-mono">
                <div
                  v-for="(line, i) in blocks.selectedDetails.signLinesBack"
                  :key="`b${i}`"
                  class="text-zinc-300 min-h-[1em]"
                >{{ line || ' ' }}</div>
              </div>
            </div>
          </div>

          <!-- Container items -->
          <div v-if="blocks.selectedDetails.items" class="mb-3">
            <div class="text-zinc-500 mb-1">
              Contents
              <span class="text-zinc-600">
                {{ blocks.selectedDetails.items.length }} / {{ blocks.selectedDetails.containerSize }}
              </span>
            </div>
            <div v-if="blocks.selectedDetails.items.length === 0" class="text-zinc-600 italic ml-1">
              empty
            </div>
            <div
              v-for="item in blocks.selectedDetails.items"
              :key="item.slot"
              class="flex items-start gap-1.5 py-0.5 font-mono"
            >
              <img
                v-if="blocks.slotTextures[item.slot]"
                :src="blocks.slotTextures[item.slot]"
                class="equip-icon"
                alt=""
              />
              <span v-else class="equip-icon-placeholder"></span>
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-1.5 flex-wrap">
                  <span class="text-zinc-500">[{{ item.slot }}]</span>
                  <span class="text-zinc-300">{{ shortItemName(item.itemId) }}</span>
                  <span v-if="item.count > 1" class="text-zinc-500">×{{ item.count }}</span>
                  <span v-if="item.name" class="text-yellow-400">"{{ item.name }}"</span>
                  <span
                    v-if="item.maxDamage && item.maxDamage > 0"
                    class="text-zinc-500"
                    title="damage / maxDamage"
                  >
                    {{ item.damage ?? 0 }} / {{ item.maxDamage }}
                  </span>
                  <span
                    v-if="blocks.slotTextureDegraded[item.slot]"
                    class="rounded border border-amber-500/40 bg-amber-500/10 px-1 py-0.5 text-[10px] uppercase tracking-wide text-amber-300"
                    title="26.1 renderer returned a generated fallback icon"
                  >
                    Renderer fallback icon
                  </span>
                </div>
              </div>
            </div>
          </div>

          <!-- Raw NBT -->
          <details class="mt-2">
            <summary class="cursor-pointer text-zinc-500 hover:text-zinc-300">Raw object</summary>
            <div class="mt-1 max-h-64 overflow-auto bg-zinc-900 rounded p-1">
              <JsonTree :value="blocks.selectedDetails.raw" :depth="0" root />
            </div>
          </details>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.equip-icon {
  width: 16px;
  height: 16px;
  image-rendering: pixelated;
  flex-shrink: 0;
}
.equip-icon-placeholder {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}
</style>
