<script setup lang="ts">
import { ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'

/** 商品图：加载失败或无图时显示原型同款色块占位 */
const props = withDefaults(defineProps<{ src?: string | null; alt?: string; tint?: number }>(), { src: null, alt: '', tint: 1 })
const failed = ref(false)
watch(() => props.src, () => { failed.value = false })
</script>

<template>
  <img v-if="src && !failed" class="cover-img" :src="src" :alt="alt" loading="lazy" @error="failed = true" />
  <div v-else class="cover-img" :class="`tint-${tint}`" role="img" :aria-label="alt || '暂无图片'"><AppIcon name="image" size="lg" /></div>
</template>

<style scoped>
.cover-img { width: 100%; height: 100%; object-fit: cover; display: grid; place-items: center; }
</style>
