<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppIcon from '@/shared/components/AppIcon.vue'
import CoverImage from '@/shared/components/CoverImage.vue'
import UserAvatar from '@/shared/components/UserAvatar.vue'
import StatusTag from '@/shared/components/StatusTag.vue'
import { CREDIT_LEVEL, CREDIT_TONE, GOODS_STATUS } from '@/shared/enums'
import { priceText, shortTime, uuid } from '@/shared/format'
import type { ChatMessage, ConversationDetail, ConversationItem, MyGoodsItem } from '@/shared/types'
import ReportDialog from '../components/ReportDialog.vue'
import { chatApi, commonApi, userApi } from '../api'
import { markReadViaSocket, on, sendViaSocket, USE_MOCK } from '../realtime'
import { useAuthStore } from '../stores/auth'
import { useUnreadStore } from '../stores/unread'

/** 聊天（接口文档第 7 章）：会话列表 + 当前会话。WebSocket 推送新消息，连不上时 HTTP 发送；Mock 模式轮询。 */
const route = useRoute()
const auth = useAuthStore()
const unread = useUnreadStore()

const convId = computed(() => (route.params.id as string) || '')
const convs = ref<ConversationItem[]>([])
const conv = ref<ConversationDetail | null>(null)
const msgs = ref<(ChatMessage & { pending?: boolean; failed?: boolean; clientMsgId?: string })[]>([])
const hasMore = ref(false)
const loadingOlder = ref(false)
const text = ref('')
const riskTip = ref<string | null>(null)
const box = ref<HTMLElement>()
const fileInput = ref<HTMLInputElement>()
const goodsPicker = ref(false)
const myGoods = ref<MyGoodsItem[]>([])
const more = ref(false)
const reportOpen = ref(false)
let poll: number | undefined

const myId = computed(() => auth.me?.id ?? '')
const blocked = computed(() => conv.value?.blockedByPeer || conv.value?.blockedByMe)

async function loadConvs() {
  const r = await chatApi.list({ page: 1, size: 50 })
  convs.value = r.list
}

async function openConv() {
  conv.value = null
  msgs.value = []
  riskTip.value = null
  if (!convId.value) return
  conv.value = await chatApi.detail(convId.value)
  const r = await chatApi.messages(convId.value, { size: 30 })
  msgs.value = r.list
  hasMore.value = r.hasMore
  await scrollBottom()
  markRead()
  if (typeof route.query.want === 'string') text.value = `你好，看到你在求购「${route.query.want}」，我这里有：`
}

function markRead() {
  if (!convId.value) return
  const c = convs.value.find((x) => x.id === convId.value)
  if (c) c.unread = 0
  if (!markReadViaSocket(convId.value)) chatApi.read(convId.value).then((r) => unread.set({ chat: r.totalUnread })).catch(() => {})
}

async function loadOlder() {
  if (!hasMore.value || loadingOlder.value || !msgs.value.length) return
  loadingOlder.value = true
  const el = box.value!
  const before = el.scrollHeight
  try {
    const r = await chatApi.messages(convId.value, { beforeId: msgs.value[0].id, size: 30 })
    msgs.value.unshift(...r.list)
    hasMore.value = r.hasMore
    await nextTick()
    el.scrollTop = el.scrollHeight - before
  } finally {
    loadingOlder.value = false
  }
}

async function scrollBottom() {
  await nextTick()
  if (box.value) box.value.scrollTop = box.value.scrollHeight
}

/** 合并一条服务端消息：有 clientMsgId 的替换本地占位，否则按 id 去重追加 */
function merge(m: ChatMessage, clientMsgId?: string | null) {
  const i = clientMsgId ? msgs.value.findIndex((x) => x.clientMsgId === clientMsgId) : -1
  if (i >= 0) msgs.value[i] = m
  else if (!msgs.value.some((x) => x.id === m.id)) msgs.value.push(m)
}

async function send(type: 1 | 2 | 3, content: string) {
  if (!conv.value || !content) return
  const clientMsgId = `c-${uuid()}`
  msgs.value.push({ id: clientMsgId, senderId: myId.value, type, content, createdAt: '', pending: true, clientMsgId })
  scrollBottom()
  if (sendViaSocket(convId.value, { type, content, clientMsgId })) return
  try {
    const r = await chatApi.send(convId.value, { type, content, clientMsgId })
    merge(r.message, clientMsgId)
    riskTip.value = r.riskTip
    loadConvs()
  } catch {
    const m = msgs.value.find((x) => x.clientMsgId === clientMsgId)
    if (m) { m.pending = false; m.failed = true }
  }
}

function sendText() {
  const t = text.value.trim()
  if (!t) return
  if (t.length > 500) return ElMessage.warning('一条消息最多 500 字')
  text.value = ''
  send(1, t)
}

async function onImage(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  ;(e.target as HTMLInputElement).value = ''
  if (!f) return
  if (f.size > 5 * 1024 * 1024) return ElMessage.error('图片不能超过 5MB')
  const { url } = await commonApi.upload(f, 'CHAT')
  send(2, url)
}

async function openGoodsPicker() {
  goodsPicker.value = true
  myGoods.value = (await userApi.myGoods({ status: 2, page: 1, size: 50 })).list
}

function pickGoods(g: MyGoodsItem) {
  goodsPicker.value = false
  send(3, g.id)
}

async function toggleBlock() {
  if (!conv.value) return
  more.value = false
  if (conv.value.blockedByMe) {
    await userApi.unblock(conv.value.peer.id)
    ElMessage.success('已取消拉黑')
  } else {
    await ElMessageBox.confirm('拉黑后对方不能再给你发消息，也不能购买你的商品。', '拉黑', { confirmButtonText: '拉黑', cancelButtonText: '取消', type: 'warning' })
    await userApi.block(conv.value.peer.id)
    ElMessage.success('已拉黑')
  }
  conv.value = await chatApi.detail(convId.value)
}

/** Mock 模式：没有推送，每 4 秒拉一次最新消息和会话列表 */
async function pollOnce() {
  loadConvs()
  if (!convId.value) return
  const r = await chatApi.messages(convId.value, { size: 30 })
  const before = msgs.value.length
  r.list.forEach((m) => merge(m))
  if (msgs.value.length > before) { scrollBottom(); markRead() }
}

const offs: (() => void)[] = []
onMounted(async () => {
  if (!auth.me) await auth.fetchMe()
  await loadConvs()
  await openConv()
  offs.push(on('message', (e) => {
    if (e.conversationId === convId.value) {
      merge(e.message, e.clientMsgId)
      if (e.riskTip) riskTip.value = e.riskTip
      scrollBottom()
      if (e.message.senderId !== myId.value) markRead()
    }
    loadConvs()
  }))
  offs.push(on('error', (e) => {
    const m = msgs.value.find((x) => x.clientMsgId === e.clientMsgId)
    if (m) { m.pending = false; m.failed = true }
    ElMessage.error(e.message)
  }))
  if (USE_MOCK) poll = window.setInterval(pollOnce, 4000)
})
onBeforeUnmount(() => { offs.forEach((f) => f()); clearInterval(poll) })
watch(convId, openConv)
</script>

<template>
  <main class="page chat-page">
    <div class="container">
      <div class="card chat" :class="{ 'has-conv': !!convId }">
        <aside class="chat-list" aria-label="会话列表">
          <div class="card-head"><h3>消息</h3><span class="subtle">{{ unread.chat }} 条未读</span></div>
          <RouterLink v-for="c in convs" :key="c.id" :to="`/chat/${c.id}`" class="chat-item" :class="{ active: c.id === convId }">
            <UserAvatar :name="c.peer.nickname" :src="c.peer.avatarUrl" />
            <div class="info">
              <div class="between"><b>{{ c.peer.nickname }}</b><span class="subtle">{{ shortTime(c.lastMessageAt) }}</span></div>
              <div class="last">{{ c.lastMessage || '暂无消息' }}</div>
            </div>
            <span v-if="c.unread" class="unread">{{ c.unread }}</span>
          </RouterLink>
          <div v-if="!convs.length" class="empty">还没有会话。在商品详情页点「聊一聊」开始。</div>
        </aside>

        <section v-if="conv" class="chat-main">
          <div class="between chat-top">
            <div class="row">
              <RouterLink class="icon-btn show-sm" to="/chat" aria-label="返回会话列表"><AppIcon name="left" /></RouterLink>
              <b>{{ conv.peer.nickname }}</b>
              <StatusTag v-if="conv.peer.creditLevel" :text="CREDIT_LEVEL[conv.peer.creditLevel]" :tone="CREDIT_TONE[conv.peer.creditLevel]" />
              <span v-if="conv.peer.online" class="subtle">在线</span>
            </div>
            <button class="icon-btn" aria-label="更多操作" @click="more = true"><AppIcon name="more" /></button>
          </div>
          <div v-if="conv.goods" class="chat-goods">
            <RouterLink class="thumb goods-thumb" :to="`/goods/${conv.goods.id}`"><CoverImage :src="conv.goods.coverUrl" :alt="conv.goods.title" /></RouterLink>
            <div class="goods-info"><div class="ellipsis">{{ conv.goods.title }}</div><span class="price">¥{{ priceText(conv.goods.price) }}</span>
              <span v-if="conv.goods.status !== 2" class="tag" style="margin-left: 8px">{{ GOODS_STATUS[conv.goods.status] }}</span></div>
            <RouterLink v-if="conv.goods.status === 2 && conv.goods.id && auth.me" class="btn btn-buy btn-sm" :to="`/checkout/${conv.goods.id}`">立即购买</RouterLink>
          </div>

          <div ref="box" class="chat-msgs" aria-live="polite" @scroll.passive="($event.target as HTMLElement).scrollTop < 40 && loadOlder()">
            <div v-if="hasMore" class="msg-sys">{{ loadingOlder ? '加载中…' : '上滑查看更早的消息' }}</div>
            <template v-for="m in msgs" :key="m.clientMsgId || m.id">
              <div v-if="m.type === 4" class="msg-sys">{{ m.content }}</div>
              <div v-else class="msg" :class="{ me: m.senderId === myId }">
                <UserAvatar size="sm" :name="m.senderId === myId ? auth.me?.nickname : conv.peer.nickname" :src="m.senderId === myId ? auth.me?.avatarUrl : conv.peer.avatarUrl" />
                <div class="bubble" :class="{ 'img-bubble': m.type !== 1 }">
                  <template v-if="m.type === 1">{{ m.content }}</template>
                  <a v-else-if="m.type === 2" :href="m.content" target="_blank" rel="noopener"><img class="msg-img" :src="m.content" alt="聊天图片" /></a>
                  <RouterLink v-else-if="m.type === 3 && m.goods" class="card-msg" :to="`/goods/${m.goods.id}`">
                    <span class="thumb card-thumb"><CoverImage :src="m.goods.coverUrl" /></span>
                    <span><span class="card-title">{{ m.goods.title }}</span><br /><span class="price">¥{{ priceText(m.goods.price) }}</span>
                      <span v-if="m.goods.status !== 2" class="tag">{{ GOODS_STATUS[m.goods.status] }}</span></span>
                  </RouterLink>
                  <template v-else>[商品卡片]</template>
                </div>
                <span v-if="m.pending" class="subtle">发送中</span>
                <button v-else-if="m.failed" class="btn btn-ghost btn-sm fail" @click="msgs.splice(msgs.indexOf(m), 1); send(m.type as 1 | 2 | 3, m.content)">发送失败，重试</button>
              </div>
            </template>
            <div v-if="riskTip" class="alert alert-warn risk"><AppIcon name="alert" /><span>{{ riskTip }}</span></div>
          </div>

          <div v-if="blocked" class="alert alert-danger blocked">{{ conv.blockedByPeer ? '对方已将你拉黑，无法发送消息' : '你已拉黑对方，取消拉黑后才能发送消息' }}</div>
          <form v-else class="chat-input" @submit.prevent="sendText">
            <button type="button" class="icon-btn" aria-label="发送图片" @click="fileInput?.click()"><AppIcon name="image" /></button>
            <button type="button" class="icon-btn" aria-label="发送我的商品" @click="openGoodsPicker"><AppIcon name="package" /></button>
            <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" hidden @change="onImage" />
            <input v-model="text" class="input" maxlength="500" placeholder="输入消息，最多 500 字" aria-label="消息内容" />
            <button class="btn btn-primary" type="submit"><AppIcon name="send" />发送</button>
          </form>
        </section>
        <section v-else class="chat-main chat-empty hide-sm"><div class="empty">选择左侧会话开始聊天</div></section>
      </div>
    </div>

    <el-dialog v-model="goodsPicker" title="发送我的在售商品" width="min(480px, 92vw)">
      <div v-if="!myGoods.length" class="empty">你还没有在售商品</div>
      <button v-for="g in myGoods" :key="g.id" type="button" class="pick-row" @click="pickGoods(g)">
        <span class="thumb pick-thumb"><CoverImage :src="g.coverUrl" /></span>
        <span class="ellipsis" style="flex: 1">{{ g.title }}</span><span class="price">¥{{ priceText(g.price) }}</span>
      </button>
    </el-dialog>

    <el-dialog v-if="conv" v-model="more" title="更多操作" width="min(360px, 92vw)">
      <div class="stack" style="gap: 10px">
        <RouterLink class="btn btn-outline btn-block" :to="`/users/${conv.peer.id}`"><AppIcon name="user" />查看 Ta 的主页</RouterLink>
        <button class="btn btn-outline btn-block" @click="more = false; reportOpen = true"><AppIcon name="flag" />举报该用户</button>
        <button class="btn btn-danger-outline btn-block" @click="toggleBlock"><AppIcon name="ban" />{{ conv.blockedByMe ? '取消拉黑' : '拉黑' }}</button>
      </div>
    </el-dialog>
    <ReportDialog v-if="conv" v-model="reportOpen" :target-type="2" :target-id="conv.peer.id" />
  </main>
</template>

<style scoped>
.chat-page { padding-bottom: 24px; }
.chat-item .unread { position: absolute; right: 16px; bottom: 16px; min-width: 18px; height: 18px; border-radius: 999px; background: var(--danger); color: #fff; font-size: 12px; text-align: center; line-height: 18px; padding: 0 5px; font-family: var(--font-num); }
.chat-item .info { min-width: 0; flex: 1; }
.chat-top { padding: 12px 20px; border-bottom: 1px solid var(--border); }
.goods-thumb { width: 52px; height: 52px; overflow: hidden; }
.goods-info { flex: 1; min-width: 0; }
.ellipsis { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.img-bubble { padding: 4px; }
.msg-img { max-width: 200px; max-height: 240px; border-radius: 12px; }
.card-msg { display: flex; gap: 10px; align-items: center; background: #fff; border-radius: 12px; padding: 8px; width: 240px; color: var(--text); }
.card-thumb { width: 48px; height: 48px; overflow: hidden; }
.card-title { font-size: 13px; }
.fail { color: var(--danger); align-self: center; }
.risk { align-self: center; max-width: 420px; }
.blocked { margin: 12px 16px; justify-content: center; }
.chat-empty { display: grid; place-items: center; }
.pick-row { display: flex; align-items: center; gap: 12px; width: 100%; padding: 10px; border: 0; border-bottom: 1px solid var(--border); background: none; cursor: pointer; text-align: left; }
.pick-row:hover { background: var(--primary-soft); }
.pick-thumb { width: 48px; height: 48px; overflow: hidden; }
/* 手机端：没选会话时显示列表，选了会话只显示对话 */
@media (max-width: 768px) {
  .chat:not(.has-conv) .chat-list { display: block; }
  .chat:not(.has-conv) { height: auto; min-height: 60vh; }
}
</style>
