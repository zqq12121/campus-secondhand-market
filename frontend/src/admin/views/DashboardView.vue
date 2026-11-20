<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { dashboardApi } from '../api'
import TrendChart from '../components/TrendChart.vue'
import type { CategoryShare, Overview, Trend } from '../types'

/** 数据看板（接口文档 13.2） */
const ov = ref<Overview | null>(null)
const trend = ref<Trend | null>(null)
const share = ref<CategoryShare[]>([])
const days = ref(14)

async function loadTrend() {
  trend.value = await dashboardApi.trend(days.value)
}
onMounted(async () => {
  ;[ov.value, share.value] = await Promise.all([dashboardApi.overview(), dashboardApi.categoryShare()])
  loadTrend()
})
const n = (v: number | string) => Number(v).toLocaleString('zh-CN')
</script>

<template>
  <template v-if="ov">
    <section class="stats-grid">
      <div class="card card-pad stat"><span class="l">今日新增用户</span><span class="v">{{ n(ov.today.newUsers) }}</span><span class="d subtle">累计 {{ n(ov.total.users) }}</span></div>
      <div class="card card-pad stat"><span class="l">今日新增商品</span><span class="v">{{ n(ov.today.newGoods) }}</span><span class="d subtle">累计 {{ n(ov.total.goods) }}</span></div>
      <div class="card card-pad stat"><span class="l">今日订单</span><span class="v">{{ n(ov.today.newOrders) }}</span><span class="d subtle">累计 {{ n(ov.total.orders) }}</span></div>
      <div class="card card-pad stat"><span class="l">今日成交额（元）</span><span class="v">{{ n(ov.today.gmv) }}</span><span class="d subtle">累计 {{ n(ov.total.gmv) }}</span></div>
    </section>

    <section class="card card-pad stack">
      <div class="between"><h3 class="h3">待处理</h3><span class="subtle">统计更新于 {{ ov.statUpdatedAt }}</span></div>
      <div class="todo">
        <RouterLink to="/audit"><span class="ic tint-3"><AppIcon name="audit" /></span><span><b class="num big">{{ ov.pending.goodsAudit }}</b><br /><span class="subtle">商品待审核</span></span></RouterLink>
        <RouterLink to="/disputes"><span class="ic tint-5"><AppIcon name="scale" /></span><span><b class="num big">{{ ov.pending.disputes }}</b><br /><span class="subtle">纠纷待裁决</span></span></RouterLink>
        <RouterLink to="/reports"><span class="ic tint-2"><AppIcon name="flag" /></span><span><b class="num big">{{ ov.pending.reports }}</b><br /><span class="subtle">举报待处理</span></span></RouterLink>
      </div>
    </section>

    <div class="layout-main-aside">
      <section class="card card-pad stack">
        <div class="between">
          <h3 class="h3">近 {{ days }} 天订单与成交额</h3>
          <div class="row">
            <div class="legend"><span><i style="background: var(--primary-border)" />订单数</span><span><i style="background: var(--price)" />成交额（元）</span></div>
            <select v-model.number="days" class="select days" aria-label="统计天数" @change="loadTrend"><option :value="7">7 天</option><option :value="14">14 天</option><option :value="30">30 天</option><option :value="90">90 天</option></select>
          </div>
        </div>
        <TrendChart v-if="trend" :dates="trend.dates" :bars="trend.newOrders" :line="trend.gmv.map(Number)" />
      </section>
      <section class="card card-pad stack">
        <h3 class="h3">在售商品分类占比</h3>
        <div v-for="c in share" :key="c.categoryId" class="bar-row">
          <span>{{ c.name }}</span><span class="bar"><span :style="{ width: `${c.percent}%` }" /></span><span class="num subtle">{{ c.percent }}%</span>
        </div>
      </section>
    </div>
  </template>
</template>

<style scoped>
.h3 { font-size: 16px; }
.big { font-size: 20px; }
.todo { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.todo a { display: flex; align-items: center; gap: 12px; padding: 16px; border-radius: 12px; border: 1px solid var(--border); transition: border-color .15s; }
.todo a:hover { border-color: var(--primary); }
.ic { width: 40px; height: 40px; border-radius: 10px; display: grid; place-items: center; }
.bar-row { display: grid; grid-template-columns: 72px 1fr 48px; gap: 10px; align-items: center; font-size: 14px; }
.bar { height: 10px; border-radius: 999px; background: #F1F5F9; overflow: hidden; }
.bar span { display: block; height: 100%; background: var(--primary); border-radius: 999px; }
.legend { display: flex; gap: 16px; font-size: 13px; color: var(--text-2); }
.legend i { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 6px; }
.days { width: auto; height: 32px; font-size: 13px; }
@media (max-width: 768px) { .todo { grid-template-columns: 1fr; } .legend { display: none; } }
</style>
