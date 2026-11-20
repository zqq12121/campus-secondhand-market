<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '@/shared/components/AppIcon.vue'
import type { AnnouncementDetail } from '@/shared/types'
import { commonApi } from '../api'

/** 公告详情（接口文档 2.6）。正文按纯文本展示，保留换行，不渲染 HTML。 */
const route = useRoute()
const a = ref<AnnouncementDetail | null>(null)
onMounted(async () => { a.value = await commonApi.announcement(route.params.id as string) })
</script>

<template>
  <main class="page">
    <div v-if="a" class="container wrap stack">
      <nav class="subtle row"><RouterLink to="/" class="row"><AppIcon name="left" size="sm" />返回首页</RouterLink></nav>
      <article class="card card-pad stack">
        <div class="row"><span class="tag tag-blue">平台公告</span><span class="subtle">{{ a.createdAt.slice(0, 10) }} 发布</span></div>
        <h1 class="title">{{ a.title }}</h1>
        <div class="body">{{ a.content }}</div>
        <div class="divider" />
        <div class="row" style="flex-wrap: wrap"><RouterLink class="btn btn-primary" to="/publish"><AppIcon name="plus" />发布闲置</RouterLink><RouterLink class="btn btn-outline" to="/">去首页看看</RouterLink></div>
      </article>
    </div>
  </main>
</template>

<style scoped>
.wrap { max-width: 800px; margin: 0 auto; }
.title { font-size: 24px; text-wrap: balance; }
.body { white-space: pre-wrap; color: var(--text-2); line-height: 1.8; }
@media (max-width: 768px) { .title { font-size: 20px; } }
</style>
