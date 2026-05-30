<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'

const props = defineProps<{
  modelValue: string
  disabled?: boolean
  placeholder?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'keydown': [event: KeyboardEvent]
}>()

const textareaRef = ref<HTMLTextAreaElement | null>(null)

function handleInput(event: Event) {
  const target = event.target as HTMLTextAreaElement
  emit('update:modelValue', target.value)
  autoResize()
}

function handleKeyDown(event: KeyboardEvent) {
  // Handle Tab key for indentation
  if (event.key === 'Tab') {
    event.preventDefault()
    const textarea = textareaRef.value
    if (!textarea) return

    const start = textarea.selectionStart
    const end = textarea.selectionEnd
    const value = textarea.value

    // Insert 2 spaces
    const newValue = value.substring(0, start) + '  ' + value.substring(end)
    emit('update:modelValue', newValue)

    // Move cursor after the inserted spaces
    setTimeout(() => {
      textarea.selectionStart = textarea.selectionEnd = start + 2
    }, 0)
    return
  }

  emit('keydown', event)
}

function autoResize() {
  const textarea = textareaRef.value
  if (!textarea) return

  textarea.style.height = 'auto'
  textarea.style.height = Math.min(textarea.scrollHeight, 200) + 'px'
}

onMounted(() => {
  autoResize()
})

watch(() => props.modelValue, () => {
  autoResize()
})
</script>

<template>
  <textarea
    ref="textareaRef"
    :value="modelValue"
    :disabled="disabled"
    :placeholder="placeholder"
    @input="handleInput"
    @keydown="handleKeyDown"
    class="w-full px-3 py-2 bg-zinc-800 border border-zinc-700 rounded-md font-mono text-sm text-zinc-200 placeholder-zinc-500 resize-none focus:outline-none focus:border-zinc-500 disabled:opacity-50 disabled:cursor-not-allowed"
    rows="3"
    spellcheck="false"
  ></textarea>
</template>
