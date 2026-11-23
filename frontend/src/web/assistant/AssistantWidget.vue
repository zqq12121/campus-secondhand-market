<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppIcon from '@/shared/components/AppIcon.vue'
import CoverImage from '@/shared/components/CoverImage.vue'
import { CONDITION } from '@/shared/enums'
import { priceText } from '@/shared/format'
import { commonApi } from '../api'
import { USE_MOCK } from '../realtime'
import { useAssistantStore, type ConfirmCard as Card } from '../stores/assistant'
import { useAuthStore } from '../stores/auth'
import ConfirmCard from './ConfirmCard.vue'
import { renderLite } from './format'
import { LISTING_DRAFT_KEY, type ListingDraft } from './api'

/** 校园二手助手浮窗：右下角按钮展开；手机端全屏。Mock 模式没有 ai-service，不显示。 */

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const store = useAssistantStore()

const visible = computed(() => !USE_MOCK && auth.loggedIn && !route.meta.bare)
/** 手机端二级页面和发布页底部有自己的固定操作栏，不放悬浮按钮以免挡住 */
const fabHiddenOnMobile = computed(() => !!route.meta.noTabbar || route.name === 'publish')
const input = ref('')
const images = ref<string[]>([])
const uploading = ref(false)
const fileInput = ref<HTMLInputElement>()
const scroller = ref<HTMLElement>()
const MAX_IMAGES = 3
const SUGGESTIONS = ['贝克兰德 100 元以内的显示器', '确认收货后还能退款吗？', '我买的东西现在什么状态？', '帮我发个求购']

async function scrollToEnd() {
  await nextTick()
  scroller.value?.scrollTo({ top: scroller.value.scrollHeight })
}
watch(() => store.open, (v) => v && scrollToEnd())
watch(() => {
  const last = store.messages[store.messages.length - 1]
  return [store.messages.length, last?.text.length, last?.cards.length, last?.goods.length, last?.activity]
}, scrollToEnd)

async function submit(text = input.value) {
  if (store.busy || uploading.value) return
  if (!text.trim() && !images.value.length) return
  const pics = images.value
  input.value = ''
  images.value = []
  await store.send(text, pics)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    submit()
  }
}

async function onPick(e: Event) {
  const files = Array.from((e.target as HTMLInputElement).files ?? [])
  ;(e.target as HTMLInputElement).value = ''
  for (const f of files.slice(0, MAX_IMAGES - images.value.length)) {
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(f.type)) { ElMessage.error(`${f.name}：只支持 jpg / png / webp`); continue }
    if (f.size > 5 * 1024 * 1024) { ElMessage.error(`${f.name}：超过 5MB`); continue }
    uploading.value = true
    try {
      images.value.push((await commonApi.upload(f, 'GOODS')).url)
    } finally {
      uploading.value = false
    }
  }
}

function decide(card: Card, decision: 'confirm' | 'cancel', edits?: object) {
  store.decide(card, decision, edits)
}

function openPublish(draft: ListingDraft) {
  try {
    sessionStorage.setItem(LISTING_DRAFT_KEY, JSON.stringify(draft))
  } catch { /* 存不下时发布页按空表单处理 */ }
  store.open = false
  router.push({ name: 'publish', query: { from: 'assistant' } })
}

function goPreferences() {
  store.open = false
  router.push({ name: 'preferences' })
}
</script>

<template>
  <template v-if="visible">
    <button v-show="!store.open" type="button" class="assistant-fab" :class="{ 'hide-sm': fabHiddenOnMobile }"
      aria-label="打开校园二手助手" @click="store.open = true">
      <AppIcon name="bot" size="lg" />
      <span class="fab-label">问助手</span>
    </button>

    <Transition name="panel">
      <section v-if="store.open" class="assistant" role="dialog" aria-label="校园二手助手">
        <header class="as-head">
          <span class="as-logo"><AppIcon name="bot" /></span>
          <div class="as-title"><strong>校园二手助手</strong><span class="subtle">找东西 · 问规则 · 查订单 · 帮发布</span></div>
          <button type="button" class="icon-btn" title="我的偏好" aria-label="我的偏好" @click="goPreferences"><AppIcon name="sliders" /></button>
          <button type="button" class="icon-btn" title="新对话" aria-label="新对话" :disabled="store.busy" @click="store.newConversation()"><AppIcon name="plus" /></button>
          <button type="button" class="icon-btn" title="收起" aria-label="收起" @click="store.open = false"><AppIcon name="x" /></button>
        </header>

        <div ref="scroller" class="as-body" aria-live="polite">
          <div v-if="!store.messages.length" class="as-welcome">
            <p>你好，我可以帮你在平台上找在售的东西、解答交易和售后规则、查你的订单，也能帮你起草求购帖，或根据照片生成发布草稿。</p>
            <div class="as-suggest">
              <button v-for="s in SUGGESTIONS" :key="s" type="button" class="chip" @click="submit(s)">{{ s }}</button>
            </div>
          </div>

          <template v-for="m in store.messages" :key="m.id">
            <div v-if="m.role === 'user'" class="as-user">
              <div v-if="m.images.length" class="as-user-imgs"><img v-for="u in m.images" :key="u" :src="u" alt="上传的图片" /></div>
              <div v-if="m.text" class="as-bubble">{{ m.text }}</div>
            </div>

            <div v-else class="as-reply">
              <!-- 文本已在 renderLite 中整体转义，只保留加粗和列表 -->
              <div v-if="m.text" class="as-text" v-html="renderLite(m.text)" />
              <div v-if="m.activity" class="as-activity"><span class="dots" aria-hidden="true" />{{ m.activity }}</div>
              <div v-else-if="m.streaming && !m.text" class="as-activity"><span class="dots" aria-hidden="true" />正在思考</div>

              <div v-if="m.goods.length" class="as-goods">
                <RouterLink v-for="g in m.goods" :key="g.id" class="as-good" :to="`/goods/${g.id}`" @click="store.open = false">
                  <CoverImage class="as-good-img" :src="g.coverUrl" :alt="g.title" :tint="(Number(g.id) % 6) + 1" />
                  <div class="as-good-info">
                    <div class="as-good-title">{{ g.title }}</div>
                    <div class="row"><span class="price"><small>¥</small>{{ priceText(g.price) }}</span></div>
                    <div class="subtle">{{ CONDITION[g.conditionLevel] }} · {{ g.campusName }}</div>
                  </div>
                </RouterLink>
              </div>

              <ConfirmCard v-for="c in m.cards" :key="c.interruptId" :card="c" :disabled="store.busy"
                @decide="(d, edits) => decide(c, d, edits)" />

              <button v-if="m.listing" type="button" class="btn btn-primary btn-sm as-go" @click="openPublish(m.listing)">
                <AppIcon name="edit" size="sm" />去发布页检查并发布
              </button>

              <div v-if="m.error" class="alert alert-danger as-error"><AppIcon name="alert" size="sm" /><span>{{ m.error }}</span></div>
            </div>
          </template>
        </div>

        <footer class="as-foot">
          <div v-if="images.length" class="as-pending">
            <div v-for="(u, i) in images" :key="u" class="as-thumb">
              <img :src="u" alt="待发送的图片" />
              <button type="button" aria-label="移除图片" @click="images.splice(i, 1)"><AppIcon name="x" size="sm" /></button>
            </div>
          </div>
          <div class="as-input">
            <button type="button" class="icon-btn" title="上传商品照片" aria-label="上传商品照片"
              :disabled="images.length >= MAX_IMAGES || uploading || store.busy" @click="fileInput?.click()">
              <AppIcon name="image" />
            </button>
            <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" multiple hidden @change="onPick" />
            <textarea v-model="input" class="textarea" rows="1" maxlength="1000" :placeholder="uploading ? '图片上传中…' : '想找什么、想问什么？Enter 发送'"
              aria-label="输入消息" @keydown="onKeydown" />
            <button v-if="store.busy" type="button" class="btn btn-outline btn-sm" @click="store.stop()">停止</button>
            <button v-else type="button" class="btn btn-primary btn-sm" :disabled="uploading || (!input.trim() && !images.length)"
              aria-label="发送" @click="submit()"><AppIcon name="send" size="sm" /></button>
          </div>
          <p class="as-note">回答由 AI 生成，下单、付款等操作请在页面上自己完成。</p>
        </footer>
      </section>
    </Transition>
  </template>
</template>

<style scoped>
.assistant-fab { position: fixed; right: 24px; bottom: 24px; z-index: 60; display: flex; align-items: center; gap: 8px; height: 52px; padding: 0 20px 0 16px; border: 0; border-radius: 999px; background: var(--primary); color: #fff; font-weight: 600; box-shadow: var(--shadow-lg); cursor: pointer; transition: background .2s, box-shadow .2s; }
.assistant-fab:hover { background: var(--primary-hover); }

.assistant { position: fixed; right: 24px; bottom: 24px; z-index: 60; width: 400px; height: min(640px, calc(100vh - 48px)); display: flex; flex-direction: column; background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-card); box-shadow: var(--shadow-lg); overflow: hidden; }
.panel-enter-active, .panel-leave-active { transition: opacity .2s, transform .2s; }
.panel-enter-from, .panel-leave-to { opacity: 0; transform: translateY(12px); }

.as-head { display: flex; align-items: center; gap: 8px; padding: 12px 12px 12px 16px; background: #fff; border-bottom: 1px solid var(--border); }
.as-logo { width: 36px; height: 36px; border-radius: 10px; background: var(--primary); color: #fff; display: grid; place-items: center; flex: none; }
.as-title { display: flex; flex-direction: column; flex: 1; min-width: 0; line-height: 1.3; }
.as-title .subtle { font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.as-body { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 14px; }
.as-welcome { display: flex; flex-direction: column; gap: 12px; font-size: 14px; color: var(--text-2); }
.as-suggest { display: flex; flex-wrap: wrap; gap: 8px; }
.as-suggest .chip { height: auto; min-height: 32px; padding: 4px 12px; font-size: 13px; text-align: left; }

.as-user { align-self: flex-end; max-width: 85%; display: flex; flex-direction: column; align-items: flex-end; gap: 6px; }
.as-user-imgs { display: flex; gap: 6px; }
.as-user-imgs img { width: 72px; height: 72px; object-fit: cover; border-radius: 10px; }
.as-bubble { padding: 9px 13px; border-radius: 14px 14px 4px 14px; background: var(--primary); color: #fff; font-size: 14px; white-space: pre-wrap; word-break: break-word; }

.as-reply { display: flex; flex-direction: column; gap: 10px; max-width: 100%; }
.as-text { padding: 10px 14px; border-radius: 14px 14px 14px 4px; background: #fff; border: 1px solid var(--border); font-size: 14px; word-break: break-word; }
.as-text :deep(p) { margin: 0 0 6px; }
.as-text :deep(p:last-child) { margin-bottom: 0; }
.as-text :deep(ul), .as-text :deep(ol) { margin: 4px 0 8px; padding-left: 20px; }
.as-activity { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text-3); }
.dots { width: 8px; height: 8px; border-radius: 50%; background: var(--primary); animation: pulse 1s ease-in-out infinite; }
@keyframes pulse { 50% { opacity: .25; } }

.as-goods { display: flex; flex-direction: column; gap: 8px; }
.as-good { display: flex; gap: 10px; padding: 8px; background: #fff; border: 1px solid var(--border); border-radius: 12px; cursor: pointer; transition: border-color .15s, box-shadow .15s; }
.as-good:hover { border-color: var(--primary); box-shadow: var(--shadow-sm); }
.as-good-img { width: 64px; height: 64px; border-radius: 8px; object-fit: cover; flex: none; }
.as-good-info { min-width: 0; display: flex; flex-direction: column; gap: 2px; font-size: 13px; }
.as-good-title { font-size: 14px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.as-go { align-self: flex-start; }
.as-error { font-size: 13px; padding: 8px 12px; }

.as-foot { background: #fff; border-top: 1px solid var(--border); padding: 10px 12px 8px; display: flex; flex-direction: column; gap: 8px; }
.as-pending { display: flex; gap: 6px; }
.as-thumb { position: relative; }
.as-thumb img { width: 52px; height: 52px; object-fit: cover; border-radius: 8px; border: 1px solid var(--border); }
.as-thumb button { position: absolute; top: -6px; right: -6px; width: 20px; height: 20px; border-radius: 50%; border: 0; background: var(--text); color: #fff; display: grid; place-items: center; cursor: pointer; padding: 0; }
.as-input { display: flex; align-items: flex-end; gap: 6px; }
.as-input .textarea { height: auto; min-height: 40px; max-height: 120px; padding: 9px 12px; font-size: 14px; resize: none; line-height: 1.5; }
.as-input .btn { height: 40px; }
.as-note { font-size: 12px; color: var(--text-3); text-align: center; }

@media (max-width: 768px) {
  .assistant-fab { right: 16px; bottom: calc(80px + env(safe-area-inset-bottom)); height: 48px; padding: 0 14px; }
  .fab-label { display: none; }
  .assistant { inset: 0; width: auto; height: auto; border-radius: 0; border: 0; z-index: 70; }
}
</style>
