<script setup lang="ts">
import { computed } from 'vue'
import { ICONS } from '../icons'

const props = withDefaults(defineProps<{ name: string; size?: 'sm' | 'md' | 'lg' }>(), { size: 'md' })
const cls = computed(() => ['icon', props.size === 'sm' ? 'icon-sm' : props.size === 'lg' ? 'icon-lg' : ''])
</script>

<template>
  <!-- 图标路径来自本地常量，不含用户输入 -->
  <svg :class="cls" viewBox="0 0 24 24" aria-hidden="true" v-html="ICONS[name] || ''" />
</template>
