<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import StatusTag from '@/shared/components/StatusTag.vue'
import { ORDER_STATUS, ORDER_STATUS_TONE, ORDER_TRADE_MODE } from '@/shared/enums'
import { shortTime } from '@/shared/format'
import { orderApi } from '../api'
import OrderDetailBody from '../components/OrderDetailBody.vue'
import type { AdminOrder, AdminOrderDetail } from '../types'

/** 订单查询（接口文档 13.6）：只读，后台不能直接改订单状态 */
const list = ref<AdminOrder[]>([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ orderNo: '', status: '', tradeMode: '', range: [] as string[], page: 1 })
const detail = reactive({ open: false, data: null as AdminOrderDetail | null })

async function load(page = q.page) {
  q.page = page
  loading.value = true
  try {
    const r = await orderApi.list({
      orderNo: q.orderNo || undefined, status: q.status === '' ? undefined : Number(q.status), tradeMode: q.tradeMode === '' ? undefined : Number(q.tradeMode),
      startTime: q.range?.[0] ? `${q.range[0]} 00:00:00` : undefined, endTime: q.range?.[1] ? `${q.range[1]} 23:59:59` : undefined, page, size: 10,
    })
    list.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}
async function open(o: AdminOrder) {
  detail.data = await orderApi.detail(o.id)
  detail.open = true
}
onMounted(() => load(1))
</script>

<template>
  <section class="card">
    <div class="toolbar">
      <input v-model="q.orderNo" class="input" placeholder="订单号" aria-label="按订单号搜索" @keyup.enter="load(1)" />
      <select v-model="q.status" class="select" aria-label="按订单状态筛选" @change="load(1)"><option value="">全部状态</option><option v-for="(t, k) in ORDER_STATUS" :key="k" :value="k">{{ t }}</option></select>
      <select v-model="q.tradeMode" class="select" aria-label="按交易方式筛选" @change="load(1)"><option value="">全部方式</option><option value="1">面交</option><option value="2">担保</option></select>
      <el-date-picker v-model="q.range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" style="width: 260px" @change="load(1)" />
      <button class="btn btn-primary btn-sm" @click="load(1)">查询</button>
    </div>
    <el-table v-loading="loading" :data="list" row-key="id" empty-text="没有符合条件的订单">
      <el-table-column label="订单号" width="160"><template #default="{ row }"><span class="num">{{ row.orderNo }}</span></template></el-table-column>
      <el-table-column label="商品" prop="goodsTitle" min-width="200" />
      <el-table-column label="买家 / 卖家" width="160"><template #default="{ row }">{{ row.buyer.nickname }} / {{ row.seller.nickname }}</template></el-table-column>
      <el-table-column label="方式" width="90"><template #default="{ row }">{{ ORDER_TRADE_MODE[row.tradeMode] }}</template></el-table-column>
      <el-table-column label="金额" width="100"><template #default="{ row }"><span class="price">¥{{ row.totalAmount }}</span></template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="{ row }"><StatusTag :text="ORDER_STATUS[row.status]" :tone="ORDER_STATUS_TONE[row.status]" /></template></el-table-column>
      <el-table-column label="创建时间" width="110"><template #default="{ row }"><span class="subtle">{{ shortTime(row.createdAt) }}</span></template></el-table-column>
      <el-table-column label="操作" width="80" fixed="right"><template #default="{ row }"><button class="btn btn-outline btn-sm" @click="open(row as AdminOrder)">详情</button></template></el-table-column>
    </el-table>
    <el-pagination v-if="total > 10" background layout="total, prev, pager, next" :total="total" :page-size="10" :current-page="q.page" @current-change="load" />
    <p class="subtle note">后台不能直接修改订单状态，只能通过纠纷裁决处理。</p>
  </section>
  <el-dialog v-model="detail.open" title="订单详情" width="min(760px, 94vw)"><OrderDetailBody v-if="detail.data" :order="detail.data" /></el-dialog>
</template>

<style scoped>
.note { padding: 0 24px 16px; }
</style>
