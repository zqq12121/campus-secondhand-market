<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppIcon from '@/shared/components/AppIcon.vue'
import CoverImage from '@/shared/components/CoverImage.vue'
import ImageUploader from '@/shared/components/ImageUploader.vue'
import UserAvatar from '@/shared/components/UserAvatar.vue'
import StatusTag from '@/shared/components/StatusTag.vue'
import { AFTER_SALE_STATUS, AFTER_SALE_TYPE, OPERATOR_TYPE, ORDER_STATUS, ORDER_TRADE_MODE, RATING } from '@/shared/enums'
import { remaining, shortTime } from '@/shared/format'
import type { OrderDetail, ReviewBrief } from '@/shared/types'
import { afterSaleApi, commonApi, orderApi } from '../api'
import { ACTION_TEXT, PRIMARY_ACTIONS, useOrderActions } from '../composables/useOrderActions'

/** 订单详情（接口文档 8.3 + 第 9 章售后）。按钮按 actions / afterSale.actions 渲染。 */
const route = useRoute()
const id = route.params.id as string
const o = ref<OrderDetail | null>(null)
const now = ref(Date.now())
const paying = ref(false)
const asDialog = reactive({ open: false, type: 2 as 1 | 2, reason: '', evidenceUrls: [] as string[], saving: false })
let tick: number | undefined
let payPoll: number | undefined

async function load() {
  o.value = await orderApi.detail(id)
}
const run = useOrderActions(load, (type) => Object.assign(asDialog, { open: true, type, reason: '', evidenceUrls: [] }))
const upload = (f: File) => commonApi.upload(f, 'EVIDENCE')

/** 从支付宝跳回：轮询支付结果，不以跳转本身作为支付成功依据（接口文档 8.7） */
function pollPay() {
  paying.value = true
  let n = 0
  payPoll = window.setInterval(async () => {
    n++
    const s = await orderApi.payStatus(id).catch(() => null)
    if (s && s.paymentStatus === 1) {
      clearInterval(payPoll)
      paying.value = false
      ElMessage.success('支付成功')
      load()
    } else if (n >= 15) {
      clearInterval(payPoll)
      paying.value = false
      load()
    }
  }, 2000)
}

const isBuyer = computed(() => o.value?.myRole === 'BUYER')
const peer = computed(() => (o.value ? (isBuyer.value ? o.value.seller : o.value.buyer) : null))

/** 顶部状态文案：按状态 + 我的身份 */
const headline = computed(() => {
  const x = o.value
  if (!x) return { title: '', desc: '' }
  const b = isBuyer.value
  const map: Record<number, [string, string]> = {
    10: b ? ['等待卖家确认', '卖家确认后，在聊天里约好面交时间地点。'] : ['买家已下单，请确认', '确认后请和买家约好面交时间地点。'],
    20: b ? ['待面交', '当面验货付款后，点「确认完成」。'] : ['待面交', '按约定时间地点和买家当面交易。'],
    30: b ? ['等待你付款', '请尽快完成支付，超时订单将自动取消。'] : ['等待买家付款', '买家付款后你会收到通知。'],
    40: b ? ['已付款，等待卖家交货', '卖家交货后请当面检查商品。'] : ['买家已付款，请交货', '把商品交给买家后点「已交货」。'],
    50: b ? ['卖家已交货，等待你确认收货', '请检查商品。确认后钱会打给卖家。'] : ['已交货，等待买家确认收货', '买家确认收货后，货款进入你的平台余额。'],
    60: ['售后处理中', x.afterSale ? AFTER_SALE_STATUS[x.afterSale.status] : ''],
    70: ['交易已完成', x.review.deadline ? `互评截止 ${shortTime(x.review.deadline)}` : ''],
    80: ['订单已取消', x.cancelReason ? `原因：${x.cancelReason}` : ''],
    90: ['已退款', '货款已原路退回买家。'],
  }
  const [title, desc] = map[x.status] ?? [ORDER_STATUS[x.status], '']
  return { title, desc }
})

const DEADLINE_TEXT: Record<string, string> = {
  AUTO_CANCEL_UNPAID: '后未支付自动取消', AUTO_CANCEL_UNCONFIRMED: '后卖家未确认自动取消', AUTO_COMPLETE: '后自动完成',
  AUTO_CONFIRM: '后自动确认收货', AUTO_AGREE_AFTER_SALE: '后卖家未处理自动退款',
}

/** 进度条：面交 / 担保两条链路 */
const steps = computed(() => {
  const x = o.value
  if (!x) return []
  const t = x.timeline
  const list = x.tradeMode === 1
    ? [['提交订单', t.createdAt], ['卖家确认', t.sellerConfirmedAt], ['当面交易', t.finishedAt], ['双方互评', null]]
    : [['提交订单', t.createdAt], ['已付款', t.paidAt], ['卖家交货', t.deliveredAt], ['确认收货', t.finishedAt], ['双方互评', null]]
  const reviewed = !!x.review.mine
  const firstUndone = list.findIndex(([, at], i) => !(at || (i === list.length - 1 && reviewed)))
  return list.map(([name, at], i) => ({ name: name as string, at: at as string | null, state: i < firstUndone || firstUndone < 0 ? 'done' : i === firstUndone ? 'current' : '' }))
})
const closed = computed(() => o.value && [80, 90].includes(o.value.status))

async function submitAfterSale() {
  if (!asDialog.reason.trim()) return ElMessage.warning('请填写原因')
  if (asDialog.type === 2 && !asDialog.evidenceUrls.length) return ElMessage.warning('申请退款需要上传 1–6 张凭证图片')
  asDialog.saving = true
  try {
    await afterSaleApi.apply(id, { type: asDialog.type, reason: asDialog.reason.trim(), evidenceUrls: asDialog.evidenceUrls })
    ElMessage.success('已提交，等待卖家处理')
    asDialog.open = false
    load()
  } finally {
    asDialog.saving = false
  }
}

async function afterSaleAction(a: 'AGREE' | 'REJECT' | 'APPEAL' | 'WITHDRAW') {
  const as = o.value?.afterSale
  if (!as) return
  try {
    if (a === 'AGREE') {
      await ElMessageBox.confirm(`同意后 ¥${as.refundAmount} 将原路退回买家。`, '同意退款', { confirmButtonText: '同意', cancelButtonText: '取消' })
      await afterSaleApi.agree(as.id)
    } else if (a === 'REJECT') {
      const { value } = await ElMessageBox.prompt('拒绝理由（必填，最多 200 字）', '拒绝退款', { inputPattern: /^.{1,200}$/s, inputErrorMessage: '请填写 1–200 字', confirmButtonText: '拒绝', cancelButtonText: '取消' })
      await afterSaleApi.reject(as.id, value.trim())
    } else if (a === 'APPEAL') {
      const { value } = await ElMessageBox.prompt('补充说明（选填，最多 200 字），管理员会查看双方说明和聊天记录后裁决。', '申请平台介入', { inputPattern: /^.{0,200}$/s, inputErrorMessage: '最多 200 字', confirmButtonText: '提交', cancelButtonText: '取消' })
      await afterSaleApi.appeal(as.id, value?.trim() || undefined)
    } else {
      await ElMessageBox.confirm('撤销后订单回到原来的状态，自动确认收货继续计时。', '撤销售后', { confirmButtonText: '撤销', cancelButtonText: '取消' })
      await afterSaleApi.withdraw(as.id)
    }
    ElMessage.success('操作成功')
    load()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') throw e
  }
}
const AS_ACTION_TEXT = { AGREE: '同意退款', REJECT: '拒绝', APPEAL: '申请平台介入', WITHDRAW: '撤销申请' }

const theirReview = computed(() => {
  const t = o.value?.review.theirs
  return t && 'rating' in t ? (t as ReviewBrief) : null
})

onMounted(async () => {
  await load()
  if (route.query.from === 'alipay' && o.value?.status === 30) pollPay()
  tick = window.setInterval(() => (now.value = Date.now()), 1000)
})
onBeforeUnmount(() => { clearInterval(tick); clearInterval(payPoll) })
</script>

<template>
  <main class="page">
    <div v-if="o" class="container stack">
      <nav class="subtle row"><RouterLink :to="{ name: 'orders', query: { role: o.myRole } }" class="row"><AppIcon name="left" size="sm" />我的订单</RouterLink></nav>

      <section class="status-hero" :class="{ grey: closed }">
        <div>
          <h1>{{ headline.title }}</h1>
          <p>{{ headline.desc }}</p>
          <p v-if="o.deadline" class="deadline"><AppIcon name="clock" size="sm" /> {{ remaining(o.deadline.at, now) }} {{ DEADLINE_TEXT[o.deadline.action] ?? '' }}</p>
          <p v-if="paying" class="deadline">正在确认支付结果…</p>
        </div>
        <div class="row" style="flex-wrap: wrap">
          <button v-for="a in o.actions" :key="a" class="btn" :class="PRIMARY_ACTIONS.includes(a) || a === 'PAY' ? 'btn-light' : 'btn-outline-light'" @click="run(a, o.id)">{{ ACTION_TEXT[a] }}</button>
        </div>
      </section>

      <section v-if="!closed" class="card card-pad">
        <div class="steps" aria-label="交易进度">
          <div v-for="(s, i) in steps" :key="s.name" class="step" :class="s.state">
            <span class="dot"><AppIcon v-if="s.state === 'done'" name="check" size="sm" /><template v-else>{{ i + 1 }}</template></span>
            {{ s.name }}<span v-if="s.at" class="subtle">{{ shortTime(s.at) }}</span>
          </div>
        </div>
      </section>

      <section v-if="o.afterSale" class="card card-pad stack">
        <div class="between"><h3 class="h3">售后 · {{ AFTER_SALE_TYPE[o.afterSale.type] }}</h3><StatusTag :text="AFTER_SALE_STATUS[o.afterSale.status]" tone="red" /></div>
        <dl class="kv">
          <dt>退款金额</dt><dd class="price">¥{{ o.afterSale.refundAmount }}</dd>
          <dt>买家原因</dt><dd>{{ o.afterSale.reason }}</dd>
          <template v-if="o.afterSale.evidenceUrls.length"><dt>凭证</dt><dd class="evid"><a v-for="u in o.afterSale.evidenceUrls" :key="u" :href="u" target="_blank" rel="noopener"><img :src="u" alt="凭证图片" /></a></dd></template>
          <template v-if="o.afterSale.sellerReply"><dt>卖家回复</dt><dd>{{ o.afterSale.sellerReply }}</dd></template>
          <template v-if="o.afterSale.arbitrationReason"><dt>平台裁决</dt><dd>{{ o.afterSale.arbitrationReason }}</dd></template>
          <template v-if="o.afterSale.status === 0"><dt>处理时限</dt><dd>卖家需在 {{ shortTime(o.afterSale.sellerDeadline) }} 前处理，超时自动同意退款</dd></template>
        </dl>
        <div v-if="o.afterSale.actions.length" class="row" style="justify-content: flex-end; flex-wrap: wrap">
          <button v-for="a in o.afterSale.actions" :key="a" class="btn btn-sm" :class="a === 'AGREE' ? 'btn-primary' : 'btn-outline'" @click="afterSaleAction(a)">{{ AS_ACTION_TEXT[a] }}</button>
        </div>
      </section>

      <div class="layout-main-aside">
        <div class="stack">
          <section class="card">
            <div class="card-head"><h3>商品信息</h3><span class="tag" :class="o.tradeMode === 2 ? 'tag-green' : 'tag-blue'"><AppIcon v-if="o.tradeMode === 2" name="shield" size="sm" />{{ ORDER_TRADE_MODE[o.tradeMode] }}</span></div>
            <RouterLink class="list-item" :to="`/goods/${o.goods.id}`">
              <span class="thumb item-thumb"><CoverImage :src="o.goods.cover" :alt="o.goods.title" /></span>
              <div style="flex: 1; min-width: 0">{{ o.goods.title }}<div class="subtle">¥{{ o.goods.unitPrice }} × {{ o.quantity }}</div></div>
              <span class="price" style="font-size: 18px">¥{{ o.totalAmount }}</span>
            </RouterLink>
          </section>
          <section class="card card-pad">
            <dl class="kv">
              <dt>订单号</dt><dd class="num">{{ o.orderNo }}</dd>
              <dt>实付金额</dt><dd class="price">¥{{ o.totalAmount }}</dd>
              <template v-if="o.payment"><dt>支付</dt><dd>支付宝（沙箱）{{ o.payment.paidAt ? `· ${o.payment.paidAt}` : '· 未支付' }}</dd></template>
              <dt>交易地点</dt><dd>{{ o.goods.campusName }}{{ o.goods.location ? ` · ${o.goods.location}` : '' }}</dd>
              <dt>下单时间</dt><dd>{{ o.timeline.createdAt }}</dd>
              <template v-if="o.cancelReason"><dt>取消原因</dt><dd>{{ o.cancelReason }}（{{ OPERATOR_TYPE[o.cancelBy ?? 3] }}）</dd></template>
            </dl>
          </section>
          <section v-if="o.status === 70" class="card card-pad stack">
            <h3 class="h3">评价</h3>
            <p v-if="o.review.mine">我的评价：<b>{{ RATING[o.review.mine.rating] }}</b> {{ o.review.mine.content }}</p>
            <p v-else class="muted">你还没有评价<RouterLink v-if="o.actions.includes('REVIEW')" :to="`/order/${o.id}/review`" class="link">去评价</RouterLink></p>
            <p v-if="theirReview">对方的评价：<b>{{ RATING[theirReview.rating] }}</b> {{ theirReview.content }}</p>
            <p v-else-if="o.review.theirs" class="subtle">对方已评价，双方都评价后或到期后公开</p>
          </section>
        </div>
        <aside class="stack">
          <section v-if="peer" class="card card-pad">
            <div class="row">
              <UserAvatar :name="peer.nickname" :src="peer.avatarUrl" />
              <div style="flex: 1"><b>{{ peer.nickname }}</b><div class="subtle">{{ isBuyer ? '卖家' : '买家' }}</div></div>
              <RouterLink v-if="o.conversationId" class="btn btn-outline btn-sm" :to="`/chat/${o.conversationId}`">联系{{ isBuyer ? '卖家' : '买家' }}</RouterLink>
            </div>
          </section>
          <section class="card card-pad stack">
            <h3 class="h3">订单动态</h3>
            <ul class="timeline">
              <li v-for="(l, i) in [...o.logs].reverse()" :key="i"><span class="d" />
                <div>{{ ORDER_STATUS[l.toStatus] }}<span v-if="l.remark" class="muted">：{{ l.remark }}</span><div class="subtle">{{ shortTime(l.createdAt) }} · {{ OPERATOR_TYPE[l.operatorType] }}</div></div></li>
            </ul>
          </section>
        </aside>
      </div>
    </div>

    <el-dialog v-model="asDialog.open" :title="asDialog.type === 1 ? '申请取消并退款' : '申请退款'" width="min(520px, 92vw)">
      <div class="stack">
        <div class="field"><label for="as-r">{{ asDialog.type === 1 ? '取消原因' : '问题说明' }}<span class="req">*</span></label>
          <el-input id="as-r" v-model="asDialog.reason" type="textarea" :rows="3" maxlength="200" show-word-limit /></div>
        <div class="field"><label>凭证图片<span v-if="asDialog.type === 2" class="req">*</span>（最多 6 张）</label>
          <ImageUploader v-model="asDialog.evidenceUrls" :uploader="upload" :max="6" /></div>
        <div class="alert alert-info"><AppIcon name="scale" /><span>
          {{ asDialog.type === 1 ? '卖家 24 小时内处理，超时自动同意并退款。' : '卖家 48 小时内处理，超时自动同意并退款；卖家拒绝后你可以申请平台介入。售后期间暂停自动确认收货。' }}</span></div>
      </div>
      <template #footer>
        <el-button @click="asDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="asDialog.saving" @click="submitAfterSale">提交申请</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.status-hero { background: linear-gradient(120deg, #047857, #10B981); color: #fff; border-radius: 16px; padding: 24px; display: flex; justify-content: space-between; align-items: center; gap: 16px; flex-wrap: wrap; }
.status-hero.grey { background: linear-gradient(120deg, #475569, #64748B); }
.status-hero h1 { font-size: 22px; }
.status-hero p { opacity: .92; margin-top: 4px; }
.deadline { display: flex; align-items: center; gap: 6px; font-weight: 600; }
.btn-light { background: #fff; color: var(--primary); }
.btn-outline-light { background: transparent; border-color: rgba(255,255,255,.7); color: #fff; }
.btn-outline-light:hover { background: rgba(255,255,255,.12); }
.h3 { font-size: 16px; }
.item-thumb { overflow: hidden; }
.kv { display: grid; grid-template-columns: 96px 1fr; gap: 10px; font-size: 14px; margin: 0; }
.kv dt { color: var(--text-3); }
.kv dd { margin: 0; }
.evid { display: flex; gap: 8px; flex-wrap: wrap; }
.evid img { width: 72px; height: 72px; object-fit: cover; border-radius: 10px; }
.link { color: var(--primary); margin-left: 8px; }
.timeline { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 16px; }
.timeline li { display: flex; gap: 12px; font-size: 14px; }
.timeline .d { width: 10px; height: 10px; border-radius: 50%; background: var(--border); margin-top: 6px; flex: none; }
.timeline li:first-child .d { background: var(--primary); box-shadow: 0 0 0 4px var(--primary-soft); }
@media (max-width: 768px) { .steps .step { font-size: 12px; } }
</style>
