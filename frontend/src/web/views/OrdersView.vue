<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CoverImage from '@/shared/components/CoverImage.vue'
import StatusTag from '@/shared/components/StatusTag.vue'
import { ORDER_STATUS, ORDER_STATUS_TONE, ORDER_TRADE_MODE } from '@/shared/enums'
import { priceText, shortTime } from '@/shared/format'
import type { OrderItem } from '@/shared/types'
import { orderApi } from '../api'
import { ACTION_TEXT, PRIMARY_ACTIONS, useOrderActions } from '../composables/useOrderActions'

/** 我的订单（接口文档 8.2）：按 role 区分买到的 / 卖出的，按钮完全按 actions 渲染 */
const route = useRoute()
const router = useRouter()

const TABS: { key: string; label: string; status?: number[] }[] = [
  { key: 'all', label: '全部' },
  { key: 'todo', label: '待确认/付款', status: [10, 30] },
  { key: 'deliver', label: '待交付', status: [20, 40] },
  { key: 'receive', label: '待收货', status: [50] },
  { key: 'after', label: '售后', status: [60] },
  { key: 'done', label: '已完成', status: [70] },
  { key: 'closed', label: '已关闭', status: [80, 90] },
]

const role = computed(() => (route.query.role === 'SELLER' ? 'SELLER' : 'BUYER'))
const tab = computed(() => TABS.find((t) => t.key === route.query.tab) ?? TABS[0])
const page = computed(() => Number(route.query.page) || 1)
const list = ref<OrderItem[]>([])
const total = ref(0)
const counts = ref<Record<string, number>>({})
const loading = ref(false)

const tabCount = (t: (typeof TABS)[number]) => (t.status ?? []).reduce((s, k) => s + (counts.value[k] ?? 0), 0)

async function load() {
  loading.value = true
  try {
    const r = await orderApi.list({ role: role.value, status: tab.value.status, page: page.value, size: 10 })
    list.value = r.list
    total.value = r.total
    counts.value = r.counts ?? {}
  } finally {
    loading.value = false
  }
}
const run = useOrderActions(load)
const go = (q: Record<string, string | undefined>) => router.replace({ query: { ...route.query, page: undefined, ...q } })

watch(() => route.fullPath, () => route.name === 'orders' && load())
onMounted(load)
</script>

<template>
  <main class="page">
    <div class="container stack">
      <div class="between">
        <h1 class="section-title">我的订单</h1>
        <div class="row">
          <button class="chip" :class="{ active: role === 'BUYER' }" @click="go({ role: 'BUYER', tab: undefined })">我买到的</button>
          <button class="chip" :class="{ active: role === 'SELLER' }" @click="go({ role: 'SELLER', tab: undefined })">我卖出的</button>
        </div>
      </div>

      <section class="card">
        <div class="tabs" style="padding: 0 16px" role="tablist">
          <button v-for="t in TABS" :key="t.key" class="tab" :class="{ active: t.key === tab.key }" role="tab" @click="go({ tab: t.key === 'all' ? undefined : t.key })">
            {{ t.label }}<span v-if="t.status && tabCount(t)" class="count">{{ tabCount(t) }}</span>
          </button>
        </div>
        <div v-loading="loading" style="min-height: 120px">
          <div v-for="o in list" :key="o.id" class="order">
            <div class="order-top">
              <span>订单号 {{ o.orderNo }} · {{ ORDER_TRADE_MODE[o.tradeMode] }} · {{ shortTime(o.createdAt) }}</span>
              <StatusTag :text="ORDER_STATUS[o.status]" :tone="ORDER_STATUS_TONE[o.status]" />
            </div>
            <RouterLink class="list-item" :to="`/order/${o.id}`">
              <span class="thumb item-thumb"><CoverImage :src="o.goodsCover" :alt="o.goodsTitle" /></span>
              <div style="flex: 1; min-width: 0">{{ o.goodsTitle }}
                <div class="subtle">{{ role === 'BUYER' ? '卖家' : '买家' }}：{{ o.counterpart.nickname }} · ¥{{ priceText(o.unitPrice) }} × {{ o.quantity }}</div></div>
              <span class="price">¥{{ o.totalAmount }}</span>
            </RouterLink>
            <div v-if="o.actions.length" class="order-ops">
              <button v-for="a in o.actions" :key="a" class="btn btn-sm" :class="a === 'PAY' ? 'btn-buy' : PRIMARY_ACTIONS.includes(a) ? 'btn-primary' : 'btn-outline'" @click="run(a, o.id)">{{ ACTION_TEXT[a] }}</button>
            </div>
          </div>
          <div v-if="!loading && !list.length" class="empty">暂无订单</div>
        </div>
        <el-pagination v-if="total > 10" background layout="prev, pager, next" :total="total" :page-size="10" :current-page="page" @current-change="(p: number) => go({ page: String(p) })" />
      </section>
    </div>
  </main>
</template>

<style scoped>
.order { border-bottom: 1px solid var(--border); }
.order:last-child { border-bottom: 0; }
.order-top { display: flex; justify-content: space-between; align-items: center; padding: 12px 24px; background: #FAFBFA; font-size: 13px; color: var(--text-2); gap: 8px; }
.order .list-item { border-bottom: 0; }
.item-thumb { overflow: hidden; }
.order-ops { display: flex; justify-content: flex-end; gap: 8px; padding: 0 24px 16px; flex-wrap: wrap; }
@media (max-width: 768px) { .order-top, .order-ops { padding-left: 16px; padding-right: 16px; } }
</style>
