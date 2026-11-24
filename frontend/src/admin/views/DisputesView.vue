<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import StatusTag from '@/shared/components/StatusTag.vue'
import { AFTER_SALE_STATUS } from '@/shared/enums'
import { shortTime } from '@/shared/format'
import { disputeApi } from '../api'
import OrderDetailBody from '../components/OrderDetailBody.vue'
import type { DisputeDetail, DisputeItem } from '../types'

/** 纠纷裁决（接口文档 13.7）：列表默认「平台介入中」，详情含双方说明、凭证、聊天记录 */
const list = ref<DisputeItem[]>([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ status: 3, page: 1 })
const d = reactive({ open: false, data: null as DisputeDetail | null, result: 'REFUND' as 'REFUND' | 'RELEASE', reason: '', saving: false, loadingMore: false })

async function load(page = q.page) {
  q.page = page
  loading.value = true
  try {
    const r = await disputeApi.list({ status: q.status, page, size: 10 })
    list.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

async function open(item: DisputeItem) {
  Object.assign(d, { data: await disputeApi.detail(item.afterSale.id), result: 'REFUND', reason: '', open: true })
}

async function more() {
  if (!d.data || !d.data.messages.length) return
  d.loadingMore = true
  try {
    const r = await disputeApi.messages(d.data.afterSale.id, { beforeId: d.data.messages[0].id, size: 50 })
    d.data.messages.unshift(...r.list)
    d.data.messagesHasMore = r.hasMore
  } finally {
    d.loadingMore = false
  }
}

async function arbitrate() {
  if (!d.data || !d.reason.trim()) return ElMessage.warning('请填写裁决理由')
  d.saving = true
  try {
    await disputeApi.arbitrate(d.data.afterSale.id, d.result, d.reason.trim())
    ElMessage.success(d.result === 'REFUND' ? '已裁决退款，原路退回买家' : '已裁决放款给卖家')
    d.open = false
    load()
  } finally {
    d.saving = false
  }
}

const sender = (id: string) => {
  const o = d.data?.order.order
  return !o ? '' : id === o.buyer.id ? `买家 ${o.buyer.nickname}` : id === o.seller.id ? `卖家 ${o.seller.nickname}` : '系统'
}
onMounted(() => load(1))
</script>

<template>
  <section class="card">
    <div class="toolbar">
      <select v-model.number="q.status" class="select" aria-label="按售后状态筛选" @change="load(1)">
        <option v-for="s in [3, 4, 5]" :key="s" :value="s">{{ AFTER_SALE_STATUS[s] }}</option>
      </select>
    </div>
    <el-table v-loading="loading" :data="list" row-key="afterSale.id" empty-text="没有需要裁决的纠纷">
      <el-table-column label="订单号" width="160"><template #default="{ row }"><span class="num">{{ row.order.orderNo }}</span></template></el-table-column>
      <el-table-column label="商品" min-width="180"><template #default="{ row }">{{ row.order.goodsTitle }}</template></el-table-column>
      <el-table-column label="买家 / 卖家" width="160"><template #default="{ row }">{{ row.order.buyer.nickname }} / {{ row.order.seller.nickname }}</template></el-table-column>
      <el-table-column label="金额" width="100"><template #default="{ row }"><span class="price">¥{{ row.afterSale.refundAmount }}</span></template></el-table-column>
      <el-table-column label="退款原因" min-width="160"><template #default="{ row }">{{ row.afterSale.reason }}</template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="{ row }"><StatusTag :text="AFTER_SALE_STATUS[row.afterSale.status]" :tone="row.afterSale.status === 3 ? 'red' : ''" /></template></el-table-column>
      <el-table-column label="申请介入" width="110"><template #default="{ row }"><span class="subtle">{{ row.appealedAt ? shortTime(row.appealedAt) : '—' }}</span></template></el-table-column>
      <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><button class="btn btn-primary btn-sm" @click="open(row as DisputeItem)">{{ row.afterSale.status === 3 ? '裁决' : '查看' }}</button></template></el-table-column>
    </el-table>
    <el-pagination v-if="total > 10" background layout="total, prev, pager, next" :total="total" :page-size="10" :current-page="q.page" @current-change="load" />
  </section>

  <el-dialog v-model="d.open" :title="`纠纷裁决 · ${d.data?.order.order.orderNo ?? ''}`" width="min(820px, 94vw)">
    <div v-if="d.data" class="stack">
      <div class="grid-2" style="gap: 12px">
        <div class="party"><b>买家 · {{ d.data.order.order.buyer.nickname }}</b><p class="muted">{{ d.data.afterSale.reason }}</p>
          <p v-if="d.data.buyerAppealRemark" class="muted">补充说明：{{ d.data.buyerAppealRemark }}</p>
          <div class="evid"><a v-for="u in d.data.afterSale.evidenceUrls" :key="u" :href="u" target="_blank" rel="noopener"><img :src="u" alt="买家凭证" /></a></div></div>
        <div class="party"><b>卖家 · {{ d.data.order.order.seller.nickname }}</b><p class="muted">{{ d.data.afterSale.sellerReply || '未回复' }}</p></div>
      </div>
      <div class="field"><label>聊天记录</label>
        <div class="chat-log">
          <button v-if="d.data.messagesHasMore" class="btn btn-ghost btn-sm" :disabled="d.loadingMore" @click="more">加载更早的消息</button>
          <span v-for="m in d.data.messages" :key="m.id"><b>{{ sender(m.senderId) }}</b> {{ shortTime(m.createdAt) }}：{{ m.type === 2 ? '[图片]' : m.type === 3 ? '[商品卡片]' : m.content }}</span>
          <span v-if="!d.data.messages.length" class="subtle">双方没有聊天记录</span>
        </div></div>
      <el-collapse><el-collapse-item title="订单详情与状态日志"><OrderDetailBody :order="d.data.order" /></el-collapse-item></el-collapse>
      <template v-if="d.data.afterSale.status === 3">
        <div class="field"><label>裁决结果<span class="req">*</span></label><div class="radio-group">
          <button type="button" class="radio-card" :class="{ active: d.result === 'REFUND' }" @click="d.result = 'REFUND'">全额退款给买家（卖家 −5 信用分）</button>
          <button type="button" class="radio-card" :class="{ active: d.result === 'RELEASE' }" @click="d.result = 'RELEASE'">放款给卖家（买家 −5 信用分）</button></div></div>
        <div class="field"><label for="jd-r">裁决理由<span class="req">*</span></label><el-input id="jd-r" v-model="d.reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="将通知双方" /></div>
      </template>
      <p v-else class="muted">裁决结果：{{ AFTER_SALE_STATUS[d.data.afterSale.status] }}。{{ d.data.afterSale.arbitrationReason }}</p>
    </div>
    <template #footer>
      <el-button @click="d.open = false">关闭</el-button>
      <el-button v-if="d.data?.afterSale.status === 3" type="primary" :loading="d.saving" @click="arbitrate">提交裁决</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.party { padding: 16px; border-radius: 12px; background: #F8FAFC; display: flex; flex-direction: column; gap: 8px; font-size: 14px; }
.evid { display: flex; gap: 8px; flex-wrap: wrap; }
.evid img { width: 72px; height: 72px; border-radius: 10px; object-fit: cover; }
.chat-log { max-height: 240px; overflow-y: auto; font-size: 13px; display: flex; flex-direction: column; gap: 6px; padding: 12px; border-radius: 12px; border: 1px solid var(--border); }
</style>
