<script setup lang="ts">
import StatusTag from '@/shared/components/StatusTag.vue'
import { AFTER_SALE_STATUS, AFTER_SALE_TYPE, OPERATOR_TYPE, ORDER_STATUS, ORDER_STATUS_TONE, ORDER_TRADE_MODE } from '@/shared/enums'
import { shortTime } from '@/shared/format'
import type { AdminOrderDetail } from '../types'

/** 后台订单详情（订单查询、纠纷裁决共用） */
defineProps<{ order: AdminOrderDetail }>()
const PAY_STATUS = ['待支付', '支付成功', '已关闭', '已全额退款']
</script>

<template>
  <div class="stack">
    <dl class="kv">
      <dt>订单号</dt><dd class="num">{{ order.orderNo }} <StatusTag :text="ORDER_STATUS[order.status]" :tone="ORDER_STATUS_TONE[order.status]" /></dd>
      <dt>商品</dt><dd>{{ order.goodsTitle }} · ¥{{ order.unitPrice }} × {{ order.quantity }} = <span class="price">¥{{ order.totalAmount }}</span></dd>
      <dt>方式</dt><dd>{{ ORDER_TRADE_MODE[order.tradeMode] }}</dd>
      <dt>买家 / 卖家</dt><dd>{{ order.buyer.nickname }} / {{ order.seller.nickname }}</dd>
      <template v-if="order.payment"><dt>支付</dt><dd>{{ PAY_STATUS[order.payment.status] }} · {{ order.payment.outTradeNo }}{{ order.payment.paidAt ? ` · ${order.payment.paidAt}` : '' }}</dd></template>
      <template v-if="order.cancelReason"><dt>取消原因</dt><dd>{{ order.cancelReason }}</dd></template>
    </dl>
    <div v-if="order.afterSales.length">
      <h4 class="h4">售后记录</h4>
      <div v-for="a in order.afterSales" :key="a.id" class="as">
        <b>{{ AFTER_SALE_TYPE[a.type] }}</b> · {{ AFTER_SALE_STATUS[a.status] }} · {{ shortTime(a.createdAt) }}
        <div class="muted">买家：{{ a.reason }}</div>
        <div v-if="a.sellerReply" class="muted">卖家：{{ a.sellerReply }}</div>
        <div v-if="a.arbitrationReason" class="muted">裁决：{{ a.arbitrationReason }}</div>
      </div>
    </div>
    <div>
      <h4 class="h4">状态日志</h4>
      <el-table :data="order.logs" size="small">
        <el-table-column label="时间" width="110"><template #default="{ row }">{{ shortTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="动作" prop="action" width="160" />
        <el-table-column label="状态" width="150"><template #default="{ row }">{{ row.fromStatus ? ORDER_STATUS[row.fromStatus] + ' → ' : '' }}{{ ORDER_STATUS[row.toStatus] }}</template></el-table-column>
        <el-table-column label="操作人" width="70"><template #default="{ row }">{{ OPERATOR_TYPE[row.operatorType] }}</template></el-table-column>
        <el-table-column label="备注"><template #default="{ row }">{{ row.remark || '—' }}</template></el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.kv { display: grid; grid-template-columns: 96px 1fr; gap: 10px; font-size: 14px; margin: 0; }
.kv dt { color: var(--text-3); }
.kv dd { margin: 0; }
.h4 { font-size: 14px; margin-bottom: 8px; }
.as { padding: 10px 12px; border-radius: 10px; background: #F8FAFC; margin-bottom: 8px; font-size: 14px; }
</style>
