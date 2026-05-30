<script setup lang="ts">
import { ref } from 'vue'
import { useBrowserStore, ENTRY_POINTS } from '../../stores/browser'
import { useConnectionStore } from '../../stores/connection'
import BrowserTree from './BrowserTree.vue'

const browser = useBrowserStore()
const connection = useConnectionStore()
const showCodeInput = ref(false)
const codeInput = ref('')

async function handleLoadCode() {
  if (!codeInput.value.trim()) return
  await browser.loadFromGroovy(codeInput.value)
  codeInput.value = ''
  showCodeInput.value = false
}
</script>

<template>
  <div class="h-full flex flex-col">
    <!-- Toolbar -->
    <div class="p-3 border-b border-zinc-800 space-y-3">
      <!-- Entry Points -->
      <div class="flex flex-wrap gap-2">
        <button
          v-for="entry in ENTRY_POINTS"
          :key="entry.name"
          @click="browser.loadEntryPoint(entry)"
          :disabled="!connection.isConnected || browser.isLoading"
          class="px-3 py-1.5 bg-zinc-800 hover:bg-zinc-700 rounded-md text-sm flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          :title="entry.description"
        >
          <span>{{ entry.icon }}</span>
          <span>{{ entry.name }}</span>
        </button>
      </div>

      <!-- Custom Groovy input -->
      <div class="flex gap-2">
        <button
          @click="showCodeInput = !showCodeInput"
          class="px-3 py-1.5 bg-zinc-800 hover:bg-zinc-700 rounded-md text-sm"
        >
          {{ showCodeInput ? '− Groovy' : '+ Groovy' }}
        </button>

        <button
          v-if="browser.roots.length > 0"
          @click="browser.clearAll"
          class="px-3 py-1.5 bg-zinc-800 hover:bg-zinc-700 rounded-md text-sm text-zinc-400"
        >
          Clear All
        </button>

        <!-- Navigation -->
        <div class="ml-auto flex gap-1">
          <button
            @click="browser.goBack"
            :disabled="!browser.canGoBack"
            class="px-2 py-1 bg-zinc-800 hover:bg-zinc-700 rounded text-sm disabled:opacity-30"
            title="Back"
          >
            ←
          </button>
          <button
            @click="browser.goForward"
            :disabled="!browser.canGoForward"
            class="px-2 py-1 bg-zinc-800 hover:bg-zinc-700 rounded text-sm disabled:opacity-30"
            title="Forward"
          >
            →
          </button>
        </div>
      </div>

      <!-- Groovy input field -->
      <div v-if="showCodeInput" class="flex gap-2">
        <input
          v-model="codeInput"
          placeholder="Enter Groovy expression (e.g., return mc.player)"
          class="flex-1 px-3 py-2 bg-zinc-800 border border-zinc-700 rounded-md text-sm font-mono placeholder-zinc-500 focus:outline-none focus:border-zinc-500"
          @keyup.enter="handleLoadCode"
        />
        <button
          @click="handleLoadCode"
          :disabled="!connection.isConnected || !codeInput.trim()"
          class="btn btn-primary"
        >
          Load
        </button>
      </div>
    </div>

    <!-- Error display -->
    <div v-if="browser.error" class="px-4 py-2 bg-red-900/30 border-b border-red-800 text-red-400 text-sm">
      {{ browser.error }}
    </div>

    <!-- Loading indicator -->
    <div v-if="browser.isLoading" class="px-4 py-2 text-yellow-400 text-sm flex items-center gap-2">
      <span class="animate-spin">↻</span>
      Loading...
    </div>

    <!-- Tree view -->
    <div class="flex-1 overflow-auto p-4">
      <div v-if="!connection.isConnected" class="text-zinc-500 text-center py-8">
        Connect to Minecraft to browse objects
      </div>

      <div v-else-if="browser.roots.length === 0" class="text-zinc-500 text-center py-8">
        <p>Click an entry point above to start browsing.</p>
        <p class="text-xs mt-2">Or use "+ Groovy" to evaluate a custom expression.</p>
      </div>

      <div v-else class="space-y-2">
        <div v-for="root in browser.roots" :key="root.id" class="border border-zinc-800 rounded-lg overflow-hidden">
          <!-- Root header -->
          <div class="flex items-center gap-2 px-3 py-2 bg-zinc-800/50">
            <span class="text-purple-400">◆</span>
            <span class="font-medium text-zinc-200">{{ root.name }}</span>
            <span v-if="root.className" class="text-xs text-zinc-500">{{ root.className }}</span>
            <button
              @click="browser.removeRoot(root.id)"
              class="ml-auto text-zinc-500 hover:text-red-400 text-sm"
              title="Remove"
            >
              ×
            </button>
          </div>

          <!-- Root content -->
          <div class="p-2">
            <BrowserTree :node="root" :depth="0" />
          </div>
        </div>
      </div>
    </div>

    <!-- Selected node details -->
    <div v-if="browser.selectedNode" class="border-t border-zinc-800 p-3 bg-zinc-900/50 max-h-48 overflow-auto">
      <div class="text-xs text-zinc-500 mb-1">Selected</div>
      <div class="font-medium text-zinc-200">{{ browser.selectedNode.name }}</div>
      <div v-if="browser.selectedNode.className" class="text-xs text-purple-400 mt-1">
        {{ browser.selectedNode.className }}
      </div>
      <div v-if="browser.selectedNode.displayValue" class="text-sm text-green-400 mt-1 font-mono">
        {{ browser.selectedNode.displayValue }}
      </div>
      <div class="text-xs text-zinc-600 mt-2 font-mono truncate" :title="browser.selectedNode.path">
        {{ browser.selectedNode.path }}
      </div>
    </div>
  </div>
</template>
