<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppIcon from '@/shared/components/AppIcon.vue'
import CoverImage from '@/shared/components/CoverImage.vue'
import { CONDITION } from '@/shared/enums'
import { priceText, uuid } from '@/shared/format'
import type { GoodsDetail } from '@/shared/types'
import { goodsApi, orderApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { useMetaStore } from '../stores/meta'

/**
 * 确认下单（接口文档 8.1）。
 * requestId 在进入页面时生成，重复点击提交拿到的是同一个订单。
 * 接口约定没有买家留言字段（附录 D #9），双方通过聊天约定时间地点。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const meta = useMetaStore()
const goodsId = route.params.goodsId as string
const requestId = uuid()

const g = ref<GoodsDetail | null>(null)
const tradeMode = ref(1)
const quantity = ref(1)
const submitting = ref(false)

const free = computed(() => Number(g.value?.price) === 0)
/** 平台开放担保、商品支持担保，且卖家信用 ≥ 60 才能选担保 */
const escrowOk = computed(() => meta.config.guaranteeEnabled && !!g.value && g.value.tradeMode !== 1 && g.value.seller.creditScore >= 60 && !free.value)
const total = computed(() => (Number(g.value?.price ?? 0) * quantity.value).toFixed(2))

onMounted(async () => {
  const [detail] = await Promise.all([goodsApi.detail(goodsId), meta.load()])
  g.value = detail
  if (!g.value.canBuy || (g.value.tradeMode === 2 && !meta.config.guaranteeEnabled)) {
    ElMessage.warning(g.value.canBuy ? '这件商品仅支持担保交易，担保交易暂未开放' : '这件商品当前不能购买')
    return router.replace(`/goods/${goodsId}`)
  }
  tradeMode.value = g.value.tradeMode === 2 && escrowOk.value ? 2 : g.value.tradeMode === 1 || !escrowOk.value ? 1 : 2
  if (!auth.me) auth.fetchMe()
})

async function submit() {
  submitting.value = true
  try {
    const r = await orderApi.create({ goodsId, quantity: quantity.value, tradeMode: tradeMode.value, requestId })
    if (r.status === 30) {
      ElMessage.success('订单已提交，请在 15 分钟内完成支付')
      router.replace({ name: 'order', params: { id: r.id }, query: { pay: '1' } })
    } else {
      ElMessage.success('订单已提交，等待卖家确认')
      router.replace({ name: 'order', params: { id: r.id } })
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="page">
    <div v-if="g" class="container wrap stack">
      <h1 class="section-title">确认下单</h1>

      <section class="card">
        <div class="list-item">
          <span class="thumb item-thumb"><CoverImage :src="g.images[0]" :alt="g.title" /></span>
          <div style="flex: 1; min-width: 0"><div>{{ g.title }}</div>
            <div class="subtle">卖家：{{ g.seller.nickname }} · {{ g.campusName }} · {{ CONDITION[g.conditionLevel] }}</div></div>
          <span class="price" style="font-size: 18px">{{ free ? '免费送' : `¥${priceText(g.price)}` }}</span>
        </div>
        <div class="list-item between"><span>数量</span><el-input-number v-model="quantity" :min="1" :max="g.stock" size="small" /></div>
      </section>

      <section class="card card-pad stack">
        <h3 style="font-size: 16px">交易方式</h3>
        <div class="radio-group">
          <button v-if="g.tradeMode !== 2" type="button" class="radio-card mode-card" :class="{ active: tradeMode === 1 }" @click="tradeMode = 1">
            <span class="t"><AppIcon name="pin" />面交</span><span class="d">卖家确认后约时间地点，当面验货付款。平台不经手资金。</span></button>
          <button v-if="g.tradeMode !== 1 && meta.config.guaranteeEnabled" type="button" class="radio-card mode-card" :class="{ active: tradeMode === 2 }" :disabled="!escrowOk" @click="tradeMode = 2">
            <span class="t"><AppIcon name="shield" />担保交易</span><span class="d">{{ escrowOk ? '现在付款到平台，确认收货后才打款给卖家，有问题可申请退款。' : '卖家信用分低于 60，暂不能使用担保交易。' }}</span></button>
        </div>
        <div class="alert alert-info"><AppIcon name="message" /><span>面交时间、地点请和卖家在聊天里约定。<RouterLink :to="`/goods/${g.id}`" class="link">返回商品页</RouterLink></span></div>
        <div v-if="tradeMode === 2" class="alert alert-warn"><AppIcon name="clock" /><span>下单后请在 <b>15 分钟</b>内完成支付，超时订单自动取消。</span></div>
        <div v-else class="alert alert-warn"><AppIcon name="clock" /><span>卖家 24 小时内未确认，订单自动取消。</span></div>
      </section>

      <section class="card card-pad total between">
        <div>合计 <span class="price" style="font-size: 26px"><small>¥</small>{{ total }}</span></div>
        <button class="btn btn-buy btn-lg" :disabled="submitting" @click="submit">{{ submitting ? '提交中…' : tradeMode === 2 ? '提交订单并支付' : '提交订单' }}</button>
      </section>
      <p v-if="tradeMode === 2" class="subtle" style="text-align: center">支付使用支付宝沙箱环境，仅用于演示</p>
    </div>
  </main>
</template>

<style scoped>
.wrap { max-width: 760px; margin: 0 auto; }
.item-thumb { overflow: hidden; }
.mode-card { flex: 1; flex-direction: column; align-items: flex-start; gap: 4px; padding: 16px; min-width: 220px; text-align: left; }
.mode-card[disabled] { opacity: .5; cursor: not-allowed; }
.mode-card .t { display: flex; align-items: center; gap: 8px; font-weight: 700; }
.mode-card .d { font-size: 13px; color: var(--text-2); font-weight: 400; }
.total { position: sticky; bottom: 0; }
.link { color: var(--primary); margin-left: 6px; }
</style>
