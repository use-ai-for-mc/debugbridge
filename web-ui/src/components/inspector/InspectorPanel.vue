<script setup lang="ts">
import { ref } from 'vue'
import BrowserPanel from './BrowserPanel.vue'
import GroovyInspector from './GroovyInspector.vue'
import InventoryPanel from './InventoryPanel.vue'
import EntitiesPanel from './EntitiesPanel.vue'
import BlocksPanel from './BlocksPanel.vue'

type Mode = 'browser' | 'groovy' | 'inventory' | 'entities' | 'blocks'
const mode = ref<Mode>('browser')

const tabs: { id: Mode; label: string }[] = [
  { id: 'inventory', label: '🎒 Inventory' },
  { id: 'entities', label: '👁 Entities' },
  { id: 'blocks', label: '🧱 Blocks' },
  { id: 'browser', label: '🌳 Object Browser' },
  { id: 'groovy', label: '📝 Groovy Inspector' },
]
</script>

<template>
  <div class="h-full flex flex-col">
    <!-- Mode toggle -->
    <div class="flex border-b border-zinc-800">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        @click="mode = tab.id"
        class="px-4 py-2 text-sm transition-colors"
        :class="mode === tab.id
          ? 'text-zinc-100 border-b-2 border-green-500'
          : 'text-zinc-400 hover:text-zinc-200'"
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- Content -->
    <div class="flex-1 overflow-hidden">
      <InventoryPanel v-if="mode === 'inventory'" />
      <EntitiesPanel v-else-if="mode === 'entities'" />
      <BlocksPanel v-else-if="mode === 'blocks'" />
      <BrowserPanel v-else-if="mode === 'browser'" />
      <GroovyInspector v-else />
    </div>
  </div>
</template>
