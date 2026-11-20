<script setup lang="ts">
import { initial } from '../format'

withDefaults(defineProps<{ name?: string | null; src?: string | null; size?: 'sm' | 'md' | 'lg' }>(), { name: '', src: null, size: 'md' })
</script>

<template>
  <span class="avatar" :class="size === 'sm' ? 'avatar-sm' : size === 'lg' ? 'avatar-lg' : ''">
    <img v-if="src" :src="src" alt="" />
    <template v-else>{{ initial(name) }}</template>
  </span>
</template>

<style scoped>
img { width: 100%; height: 100%; object-fit: cover; }
</style>
