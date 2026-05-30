<script setup lang="ts">
import { ref } from 'vue'
import { useInspectorStore } from '../../stores/inspector'
import { useConnectionStore } from '../../stores/connection'
import InspectorTree from './InspectorTree.vue'

const inspector = useInspectorStore()
const connection = useConnectionStore()
const codeInput = ref('')

async function handleInspect() {
  if (!codeInput.value.trim()) return
  await inspector.inspect(codeInput.value)
}

function handlePin() {
  if (!inspector.rootObject) return
  const name = prompt('Enter a name for this pin:', inspector.rootObject.name)
  if (name) {
    inspector.pinObject(name, codeInput.value)
  }
}
</script>

<template>
  <div class="h-full flex flex-col">
    <!-- Input area -->
    <div class="p-4 border-b border-zinc-800">
      <div class="flex gap-2">
        <input
          v-model="codeInput"
          :disabled="!connection.isConnected"
          placeholder="Enter Groovy expression to inspect (e.g., return mc.player)"
          class="flex-1 px-3 py-2 bg-zinc-800 border border-zinc-700 rounded-md font-mono text-sm text-zinc-200 placeholder-zinc-500 focus:outline-none focus:border-zinc-500 disabled:opacity-50"
          @keyup.enter="handleInspect"
        />
        <button
          @click="handleInspect"
          :disabled="!connection.isConnected || inspector.isLoading || !codeInput.trim()"
          class="btn btn-primary"
        >
          {{ inspector.isLoading ? 'Loading...' : 'Inspect' }}
        </button>
        <button
          v-if="inspector.rootObject"
          @click="handlePin"
          class="btn btn-secondary"
          title="Pin this object"
        >
          📌
        </button>
        <button
          v-if="inspector.rootObject"
          @click="inspector.clear"
          class="btn btn-secondary"
          title="Clear"
        >
          ✕
        </button>
      </div>

      <div v-if="!connection.isConnected" class="text-xs text-zinc-500 mt-2">
        Connect to Minecraft to use the inspector
      </div>
    </div>

    <!-- Error display -->
    <div v-if="inspector.error" class="px-4 py-2 bg-red-900/30 border-b border-red-800 text-red-400 text-sm">
      {{ inspector.error }}
    </div>

    <!-- Tree view -->
    <div class="flex-1 overflow-auto p-4">
      <div v-if="inspector.rootObject" class="border border-zinc-800 rounded-lg overflow-hidden">
        <!-- Root header -->
        <div class="flex items-center gap-2 px-3 py-2 bg-zinc-800/50">
          <span class="text-purple-400">◆</span>
          <span class="font-medium text-zinc-200">{{ inspector.rootObject.name }}</span>
          <span v-if="inspector.rootObject.className" class="text-xs text-zinc-500 font-mono">
            {{ inspector.rootObject.className }}
          </span>
        </div>

        <!-- Root content -->
        <div class="p-2">
          <InspectorTree :node="inspector.rootObject" :depth="0" />
        </div>
      </div>

      <div v-else-if="!inspector.isLoading" class="text-zinc-500 text-center py-8">
        <p>Enter a Groovy expression to inspect an object.</p>
        <p class="text-xs mt-2">
          Convenience globals: <code class="text-blue-300">mc</code>,
          <code class="text-blue-300">player</code>,
          <code class="text-blue-300">level</code> resolve to the current Minecraft
          instance, local player, and client level.
        </p>
        <p class="text-xs mt-2">Examples:</p>
        <ul class="text-xs mt-1 space-y-1">
          <li><code class="text-blue-300">return mc</code></li>
          <li><code class="text-blue-300">return player.blockPosition()</code></li>
          <li><code class="text-blue-300">return level.entitiesForRendering()</code></li>
          <li><code class="text-blue-300">return java.type("net.minecraft.client.Minecraft")</code></li>
        </ul>
        <p class="text-xs mt-3 text-zinc-600">
          Click ▶ to drill into Java objects. Double-click a row to copy its Groovy path.
        </p>
      </div>
    </div>

    <!-- Selected node details -->
    <div
      v-if="inspector.selectedNode"
      class="border-t border-zinc-800 p-3 bg-zinc-900/50 max-h-48 overflow-auto"
    >
      <div class="text-xs text-zinc-500 mb-1">Selected</div>
      <div class="font-medium text-zinc-200">{{ inspector.selectedNode.name }}</div>
      <div v-if="inspector.selectedNode.className" class="text-xs text-purple-400 mt-1 font-mono">
        {{ inspector.selectedNode.className }}
      </div>
      <div
        v-if="inspector.selectedNode.displayValue"
        class="text-sm text-green-400 mt-1 font-mono break-all"
      >
        {{ inspector.selectedNode.displayValue }}
      </div>
      <div
        class="text-xs text-zinc-600 mt-2 font-mono truncate"
        :title="inspector.selectedNode.path"
      >
        {{ inspector.selectedNode.path }}
      </div>
    </div>
  </div>
</template>
