<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'
import { useConsoleStore } from '../../stores/console'
import { useConnectionStore } from '../../stores/connection'
import CodeEditor from './CodeEditor.vue'

const consoleStore = useConsoleStore()
const connection = useConnectionStore()
const outputRef = ref<HTMLElement | null>(null)
const editorCode = ref('')

async function handleExecute() {
  if (!editorCode.value.trim()) return
  await consoleStore.execute(editorCode.value)
  editorCode.value = ''
  scrollToBottom()
}

function handleKeyDown(event: KeyboardEvent) {
  // Ctrl+Enter or Cmd+Enter to execute
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault()
    handleExecute()
    return
  }

  // Arrow up/down for history (when at start/end of input)
  if (event.key === 'ArrowUp' && !event.shiftKey) {
    const code = consoleStore.navigateHistory('up')
    if (code !== null) {
      editorCode.value = code
    }
  } else if (event.key === 'ArrowDown' && !event.shiftKey) {
    const code = consoleStore.navigateHistory('down')
    if (code !== null) {
      editorCode.value = code
    }
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (outputRef.value) {
      outputRef.value.scrollTop = outputRef.value.scrollHeight
    }
  })
}

function formatTimestamp(date: Date): string {
  return date.toLocaleTimeString('en-US', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

// Auto-scroll on new entries
watch(() => consoleStore.entries.length, scrollToBottom)
</script>

<template>
  <div class="h-full flex flex-col">
    <!-- Output area -->
    <div ref="outputRef" class="flex-1 overflow-auto p-4 font-mono text-sm">
      <div v-if="consoleStore.entries.length === 0" class="text-zinc-500 text-center py-8">
        <p>Groovy console ready.</p>
        <p class="text-xs mt-2">Press Ctrl+Enter to execute</p>
      </div>

      <div v-for="entry in consoleStore.entries" :key="entry.id" class="mb-2">
        <!-- Input -->
        <div v-if="entry.type === 'input'" class="flex gap-2">
          <span class="text-zinc-600 text-xs">{{ formatTimestamp(entry.timestamp) }}</span>
          <span class="text-blue-400">›</span>
          <pre class="text-zinc-300 whitespace-pre-wrap flex-1">{{ entry.content }}</pre>
        </div>

        <!-- Output (stdout) -->
        <div v-else-if="entry.type === 'output'" class="flex gap-2 ml-16">
          <pre class="text-zinc-400 whitespace-pre-wrap">{{ entry.content }}</pre>
        </div>

        <!-- Result -->
        <div v-else-if="entry.type === 'result'" class="flex gap-2 ml-16">
          <span class="text-green-400">←</span>
          <pre class="text-green-400 whitespace-pre-wrap flex-1">{{ entry.content }}</pre>
          <span v-if="entry.duration" class="text-zinc-600 text-xs">{{ entry.duration }}ms</span>
        </div>

        <!-- Error -->
        <div v-else-if="entry.type === 'error'" class="flex gap-2 ml-16">
          <span class="text-red-400">✕</span>
          <pre class="text-red-400 whitespace-pre-wrap flex-1">{{ entry.content }}</pre>
          <span v-if="entry.duration" class="text-zinc-600 text-xs">{{ entry.duration }}ms</span>
        </div>
      </div>

      <!-- Loading indicator -->
      <div v-if="consoleStore.isExecuting" class="flex gap-2 ml-16 text-yellow-400">
        <span class="animate-pulse">⋯</span>
        <span>Executing...</span>
      </div>
    </div>

    <!-- Input area -->
    <div class="border-t border-zinc-800 p-4">
      <div class="flex gap-2">
        <div class="flex-1">
          <CodeEditor
            v-model="editorCode"
            :disabled="!connection.isConnected"
            placeholder="Enter Groovy code... (Ctrl+Enter to execute)"
            @keydown="handleKeyDown"
            class="min-h-[80px] max-h-[200px]"
          />
        </div>
        <div class="flex flex-col gap-2">
          <button
            @click="handleExecute"
            :disabled="!connection.isConnected || consoleStore.isExecuting || !editorCode.trim()"
            class="btn btn-primary h-10"
          >
            Run
          </button>
          <button
            @click="consoleStore.clear"
            class="btn btn-secondary text-xs"
            title="Clear console"
          >
            Clear
          </button>
        </div>
      </div>

      <div v-if="!connection.isConnected" class="text-xs text-zinc-500 mt-2">
        Connect to Minecraft to use the console
      </div>
    </div>
  </div>
</template>
