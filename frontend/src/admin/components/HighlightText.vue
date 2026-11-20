<script setup lang="ts">
import { computed } from 'vue'

/** 高亮命中的敏感词（不用 v-html，按片段渲染，避免 XSS） */
const props = defineProps<{ text: string; words: string[] }>()
const parts = computed(() => {
  const words = props.words.filter(Boolean)
  if (!words.length) return [{ t: props.text, hit: false }]
  const re = new RegExp(`(${words.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`, 'gi')
  return props.text.split(re).filter(Boolean).map((t) => ({ t, hit: words.some((w) => w.toLowerCase() === t.toLowerCase()) }))
})
</script>

<template>
  <span><template v-for="(p, i) in parts" :key="i"><mark v-if="p.hit">{{ p.t }}</mark><template v-else>{{ p.t }}</template></template></span>
</template>

<style scoped>
mark { background: #FEE2E2; color: var(--danger); padding: 0 2px; border-radius: 3px; }
</style>
