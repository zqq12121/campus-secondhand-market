<script setup lang="ts">
import type { TagTone } from '../enums'

defineProps<{ text: string; tone?: TagTone }>()
</script>

<template>
  <span class="tag" :class="tone ? `tag-${tone}` : ''"><slot />{{ text }}</span>
</template>
