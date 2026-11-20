<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '@/shared/components/AppIcon.vue'
import { shortTime } from '@/shared/format'
import type { NotificationItem } from '@/shared/types'
import { notificationApi } from '../api'
import { useUnreadStore } from '../stores/unread'

/** 消息通知（接口文档第 11 章）。点击按 refType 跳转：1 订单 / 2 商品 / 3 求购帖。 */
const router = useRouter()
const unread = useUnreadStore()

const TABS = [
  { type: undefined, label: '全部' }, { type: 1, label: '交易' }, { type: 2, label: '审核' },
  { type: 3, label: '求购匹配' }, { type: 4, label: '举报结果' }, { type: 5, label: '信用分' }, { type: 6, label: '系统' },
]
const ICON: Record<number, [string, number]> = { 1: ['package', 2], 2: ['audit', 5], 3: ['megaphone', 3], 4: ['flag', 4], 5: ['trend', 6], 6: ['bell', 1] }

const type = ref<number | undefined>()
const list = ref<NotificationItem[]>([])
const total = ref(0)
const page = ref(1)
const byType = ref<Record<string, number>>({})
const loading = ref(false)

async function load(p = 1) {
  page.value = p
  loading.value = true
  try {
    const r = await notificationApi.list({ type: type.value, page: p, size: 20 })
    list.value = r.list
    total.value = r.total
    const c = await notificationApi.unreadCount()
    byType.value = c.byType
    unread.set(c)
  } finally {
    loading.value = false
  }
}

async function open(n: NotificationItem) {
  if (!n.isRead) {
    n.isRead = true
    notificationApi.read(n.id).then(() => unread.refresh())
  }
  const to = n.refType === 1 ? `/order/${n.refId}` : n.refType === 2 ? `/goods/${n.refId}` : n.refType === 3 ? '/wants' : null
  if (to && n.refId) router.push(to)
}

async function readAll() {
  await notificationApi.readAll(type.value)
  load(page.value)
}

onMounted(() => load())
</script>

<template>
  <main class="page">
    <div class="container wrap stack">
      <div class="between">
        <h1 class="section-title">消息通知</h1>
        <button class="btn btn-ghost btn-sm" @click="readAll"><AppIcon name="check" size="sm" />{{ type ? '本类全部已读' : '全部已读' }}</button>
      </div>
      <section class="card">
        <div class="tabs" style="padding: 0 16px" role="tablist">
          <button v-for="t in TABS" :key="t.label" class="tab" :class="{ active: type === t.type }" role="tab" @click="type = t.type; load()">
            {{ t.label }}<span v-if="t.type && byType[t.type]" class="count">{{ byType[t.type] }}</span>
          </button>
        </div>
        <div v-loading="loading" style="min-height: 120px">
          <button v-for="n in list" :key="n.id" type="button" class="noti" :class="{ unread: !n.isRead }" @click="open(n)">
            <span class="ic" :class="`tint-${ICON[n.type]?.[1] ?? 1}`"><AppIcon :name="ICON[n.type]?.[0] ?? 'bell'" /></span>
            <span class="body"><b>{{ n.title }}</b><span class="muted txt">{{ n.content }}</span><span class="subtle">{{ shortTime(n.createdAt) }}</span></span>
          </button>
          <div v-if="!loading && !list.length" class="empty">暂无通知</div>
        </div>
        <el-pagination v-if="total > 20" background layout="prev, pager, next" :total="total" :page-size="20" :current-page="page" @current-change="load" />
      </section>
    </div>
  </main>
</template>

<style scoped>
.wrap { max-width: 800px; margin: 0 auto; }
.noti { display: flex; gap: 14px; padding: 16px 24px; border: 0; border-bottom: 1px solid var(--border); position: relative; transition: background .15s; width: 100%; background: none; text-align: left; cursor: pointer; font: inherit; color: inherit; }
.noti:hover { background: #FAFBFA; }
.noti.unread::after { content: ""; position: absolute; right: 24px; top: 22px; width: 8px; height: 8px; border-radius: 50%; background: var(--danger); }
.ic { width: 40px; height: 40px; border-radius: 12px; display: grid; place-items: center; flex: none; }
.body { flex: 1; min-width: 0; padding-right: 16px; display: flex; flex-direction: column; }
.txt { margin: 2px 0; }
@media (max-width: 768px) { .noti { padding: 14px 16px; } }
</style>
