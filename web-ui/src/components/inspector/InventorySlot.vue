<script setup lang="ts">
import { computed } from 'vue'
import { type InventoryItem } from '../../stores/inventory'

const props = withDefaults(
    defineProps<{
        item: InventoryItem | null
        selected: boolean
        title: string
        variant?: 'normal' | 'armor'
        showCount?: boolean
        showDurability?: boolean
        emptyLabel?: string
    }>(),
    {
        variant: 'normal',
        showCount: false,
        showDurability: false,
        emptyLabel: '',
    },
)

const emit = defineEmits<{
    (event: 'select'): void
}>()

const shouldShowCount = computed(() => props.showCount && props.item !== null && props.item.count > 1)
const shouldShowDurability = computed(
    () => props.showDurability && props.item !== null && props.item.damage > 0 && props.item.maxDamage > 0,
)

const durabilityPercent = computed(() => {
    if (!props.item || props.item.maxDamage <= 0) return 0
    return Math.max(0, Math.min(1, 1 - props.item.damage / props.item.maxDamage)) * 100
})

const durabilityColor = computed(() => {
    if (!props.item || props.item.maxDamage <= 0) return '#44ff44'
    const ratio = props.item.damage / props.item.maxDamage
    return ratio > 0.7 ? '#ff4444' : ratio > 0.4 ? '#ffaa00' : '#44ff44'
})

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

const itemThemeColor = computed(() => {
    if (!props.item) return '#7a9955'
    return itemColor(props.item)
})
</script>

<template>
  <div
    class="inv-slot"
    :class="{
      'inv-slot-selected': selected,
      'inv-slot-armor': variant === 'armor'
    }"
    :title="title"
    @click="emit('select')"
  >
    <template v-if="item">
      <img
        v-if="item.textureUrl"
        :src="item.textureUrl"
        class="inv-item-texture"
        :alt="item.name"
      />
      <div
        v-else
        class="inv-item-icon"
        :style="{ backgroundColor: itemThemeColor + '33', borderColor: itemThemeColor }"
      >
        <span class="inv-item-label" :style="{ color: itemThemeColor }">{{ itemShortLabel(item) }}</span>
      </div>
      <span v-if="shouldShowCount" class="inv-count">{{ item?.count }}</span>
      <div v-if="shouldShowDurability" class="inv-durability">
        <div class="inv-durability-bar" :style="{ width: durabilityPercent + '%', backgroundColor: durabilityColor }" />
      </div>
    </template>

    <template v-else-if="emptyLabel">
      <span class="inv-slot-empty-label">{{ emptyLabel }}</span>
    </template>
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
  text-shadow:
    1px 1px 0 #000,
    -1px -1px 0 #000,
    1px -1px 0 #000,
    -1px 1px 0 #000;
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

.inv-item-texture {
  width: 100%;
  height: 100%;
  object-fit: contain;
  image-rendering: pixelated;
  padding: 2px;
}

.inv-slot-empty-label {
  color: #a3a3a3;
  font-size: 9px;
}
</style>
