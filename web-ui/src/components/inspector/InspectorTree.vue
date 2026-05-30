<script setup lang="ts">
import { computed } from 'vue'
import { useInspectorStore, type InspectorNode } from '../../stores/inspector'

const props = defineProps<{
  node: InspectorNode
  depth: number
}>()

const inspector = useInspectorStore()

function copyToClipboard(text: string) {
  navigator.clipboard.writeText(text)
}

const indent = computed(() => props.depth * 16)

const isSelected = computed(() => inspector.selectedNode?.id === props.node.id)

const valueColorClass = computed(() => {
  const val = props.node.value
  if (val === null || val === undefined) return 'text-zinc-500'
  if (typeof val === 'string') return 'text-green-400'
  if (typeof val === 'number') return 'text-yellow-400'
  if (typeof val === 'boolean') return 'text-pink-400'
  return 'text-zinc-300'
})

const typeLabel = computed(() => {
  if (props.node.shortName) return props.node.shortName
  if (props.node.className) return props.node.className.split('.').pop()
  return null
})

async function handleClick() {
  inspector.selectNode(props.node)
  if (props.node.expandable) {
    await inspector.expandNode(props.node)
  }
}

function handleDoubleClick() {
  // Copy the Groovy path of this node for pasting into the console
  navigator.clipboard.writeText(props.node.path)
}
</script>

<template>
  <div class="select-none">
    <!-- Node row -->
    <div
      @click="handleClick"
      @dblclick="handleDoubleClick"
      class="flex items-center gap-1.5 py-1 px-1 rounded cursor-pointer group"
      :class="isSelected ? 'bg-zinc-700' : 'hover:bg-zinc-800/50'"
      :style="{ paddingLeft: `${indent + 4}px` }"
    >
      <!-- Expand toggle -->
      <span
        v-if="node.expandable"
        class="w-4 h-4 flex items-center justify-center text-zinc-500 text-xs"
      >
        <span v-if="node.loading" class="animate-spin">↻</span>
        <span v-else>{{ node.expanded ? '▼' : '▶' }}</span>
      </span>
      <span v-else class="w-4 h-4 flex items-center justify-center text-zinc-700 text-xs">•</span>

      <!-- Field name -->
      <span class="text-blue-300 text-sm font-mono">{{ node.name }}</span>

      <!-- Type badge -->
      <span v-if="typeLabel" class="text-xs text-purple-400 opacity-70">
        {{ typeLabel }}
      </span>

      <!-- Value / Display -->
      <span
        v-if="node.displayValue && !node.expanded"
        :class="valueColorClass"
        class="text-sm truncate max-w-xs ml-1 font-mono"
        :title="node.displayValue"
      >
        = {{ node.displayValue }}
      </span>

      <!-- Error indicator -->
      <span v-if="node.error" class="text-red-400 text-xs ml-2" :title="node.error">
        ⚠
      </span>

      <!-- Actions (on hover) -->
      <div class="ml-auto opacity-0 group-hover:opacity-100 flex gap-1">
        <button
          @click.stop="copyToClipboard(node.path)"
          class="text-xs text-zinc-500 hover:text-zinc-300 px-1"
          title="Copy Groovy path"
        >
          📋
        </button>
      </div>
    </div>

    <!-- Error message -->
    <div
      v-if="node.error && node.expanded"
      class="text-xs text-red-400 py-1"
      :style="{ paddingLeft: `${indent + 24}px` }"
    >
      {{ node.error }}
    </div>

    <!-- Children -->
    <div v-if="node.expanded && node.children && node.children.length > 0">
      <InspectorTree
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :depth="depth + 1"
      />
    </div>

    <!-- Empty state -->
    <div
      v-else-if="node.expanded && (!node.children || node.children.length === 0) && !node.loading"
      class="text-xs text-zinc-500 py-1 italic"
      :style="{ paddingLeft: `${indent + 24}px` }"
    >
      (empty)
    </div>
  </div>
</template>
