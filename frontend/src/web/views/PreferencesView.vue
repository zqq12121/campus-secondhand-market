<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppIcon from '@/shared/components/AppIcon.vue'
import { memoryApi, type MemoryItem } from '../assistant/api'
import { useAssistantStore } from '../stores/assistant'

/** 助手的长期记忆（ai-service /memory）：查看、逐条删除、全部清空 */
const items = ref<MemoryItem[]>([])
const loading = ref(true)
const assistant = useAssistantStore()

async function load() {
  loading.value = true
  try {
    items.value = await memoryApi.list()
  } finally {
    loading.value = false
  }
}

async function remove(item: MemoryItem) {
  await memoryApi.remove(item.key)
  items.value = items.value.filter((i) => i.key !== item.key)
  ElMessage.success(`已删除「${item.label}」`)
}

async function clearAll() {
  try {
    await ElMessageBox.confirm('清空后，助手不再记得你的校区、预算等偏好，以后可以重新告诉它。', '清空全部偏好？',
      { confirmButtonText: '清空', cancelButtonText: '取消', type: 'warning' })
  } catch {
    return
  }
  await memoryApi.clear()
  items.value = []
  ElMessage.success('已清空')
}

// 失败时 http 已弹出错误提示，这里只避免未处理的 Promise 拒绝
onMounted(() => load().catch(() => undefined))
</script>

<template>
  <main class="page">
    <div class="container wrap stack">
      <div class="between">
        <h1 class="section-title">助手偏好</h1>
        <button v-if="items.length" type="button" class="btn btn-danger-outline btn-sm" @click="clearAll"><AppIcon name="trash" size="sm" />全部清空</button>
      </div>
      <p class="muted">校园二手助手会从对话中记住你明确说过的偏好，下次找东西时默认按这些条件来。手机号、聊天内容和订单金额不会被记录。</p>

      <section class="card">
        <div v-if="loading" class="empty">加载中…</div>
        <div v-else-if="!items.length" class="empty">
          还没有记住任何偏好。可以在助手里说「我平时在廷根校区，预算 300 以内」。
          <button type="button" class="btn btn-primary btn-sm mt-16" @click="assistant.open = true"><AppIcon name="bot" size="sm" />打开助手</button>
        </div>
        <ul v-else class="pref-list">
          <li v-for="item in items" :key="item.key" class="pref">
            <div class="pref-main">
              <span class="subtle">{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
            </div>
            <span class="subtle hide-sm">更新于 {{ item.updatedAt }}</span>
            <button type="button" class="icon-btn" :aria-label="`删除${item.label}`" title="删除" @click="remove(item)"><AppIcon name="trash" /></button>
          </li>
        </ul>
      </section>
    </div>
  </main>
</template>

<style scoped>
.wrap { max-width: 720px; margin: 0 auto; }
.empty { display: flex; flex-direction: column; align-items: center; }
.pref-list { list-style: none; margin: 0; padding: 0; }
.pref { display: flex; align-items: center; gap: 16px; padding: 16px 20px; border-bottom: 1px solid var(--border); }
.pref:last-child { border-bottom: 0; }
.pref-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
</style>
