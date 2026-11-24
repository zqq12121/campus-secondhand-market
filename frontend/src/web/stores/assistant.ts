import { defineStore } from 'pinia'
import { ref } from 'vue'
import { ApiError } from '@/shared/request'
import { assistantApi, type AssistantGoods, type ConfirmEvent, type ListingDraft, type StreamEvent } from '../assistant/api'
import { useAuthStore } from './auth'

export type CardState = 'pending' | 'confirmed' | 'cancelled' | 'expired'
export type ConfirmCard = ConfirmEvent & { state: CardState }

export interface AssistantMessage {
  id: number
  role: 'user' | 'assistant'
  text: string
  images: string[]
  /** 正在执行的工具（中文说明），流结束后清空 */
  activity: string
  goods: AssistantGoods[]
  cards: ConfirmCard[]
  listing: ListingDraft | null
  error: string
  streaming: boolean
}

const TOOL_LABELS: Record<string, string> = {
  search_goods: '正在搜索商品',
  get_goods_detail: '正在核实商品状态',
  search_rules: '正在查平台规则',
  list_my_orders: '正在查你的订单',
  get_order: '正在查订单详情',
  draft_want_post: '正在起草求购帖',
  suggest_listing: '正在生成发布草稿',
}
const STORAGE_KEY = 'ct_assistant'
const MAX_KEPT = 60

interface Saved { conversationId: string; messages: AssistantMessage[] }

function load(): Saved {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (raw) return JSON.parse(raw) as Saved
  } catch { /* 隐私模式等情况下读不到，当作新会话 */ }
  return { conversationId: '', messages: [] }
}

/** 校园二手助手：会话与消息只存本标签页的 sessionStorage，服务端 checkpoint 才是对话的真实来源 */
export const useAssistantStore = defineStore('assistant', () => {
  const saved = load()
  const open = ref(false)
  const conversationId = ref(saved.conversationId)
  const messages = ref<AssistantMessage[]>(saved.messages.map((m) => ({ ...m, streaming: false, activity: '' })))
  const busy = ref(false)
  let seq = messages.value.reduce((n, m) => Math.max(n, m.id), 0)
  let controller: AbortController | null = null

  function persist() {
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify({
        conversationId: conversationId.value, messages: messages.value.slice(-MAX_KEPT),
      }))
    } catch { /* 存不下不影响使用 */ }
  }

  function push(role: AssistantMessage['role'], text = '', images: string[] = []): AssistantMessage {
    messages.value.push({ id: ++seq, role, text, images, activity: '', goods: [], cards: [], listing: null,
      error: '', streaming: role === 'assistant' })
    return messages.value[messages.value.length - 1]
  }

  /** 用户没确认就发了新消息时，后端会把待确认的操作视为取消 */
  function expirePendingCards() {
    for (const m of messages.value) for (const c of m.cards) if (c.state === 'pending') c.state = 'expired'
  }

  async function run(call: (onEvent: (e: StreamEvent) => void, signal: AbortSignal) => Promise<void>) {
    busy.value = true
    controller = new AbortController()
    push('assistant')
    // 通过数组取回响应式代理，后续修改才会触发视图更新
    const reply = messages.value[messages.value.length - 1]
    try {
      await call((e) => {
        switch (e.event) {
          case 'token': reply.text += e.data; reply.activity = ''; break
          case 'revise': reply.text = e.data; break
          case 'tool': reply.activity = e.data.status === 'start' ? TOOL_LABELS[e.data.name] ?? '正在处理' : ''; break
          case 'goods': reply.goods.push(...e.data.filter((g) => !reply.goods.some((x) => x.id === g.id))); break
          case 'confirm': reply.cards.push({ ...e.data, state: 'pending' }); break
          case 'listing': reply.listing = e.data; break
          case 'done': conversationId.value = e.data.conversationId; break
          case 'error': reply.error = e.data.message; break
        }
      }, controller.signal)
    } catch (e) {
      if ((e as Error).name === 'AbortError') reply.error = '已停止'
      else if (e instanceof ApiError && e.code === 401) {
        reply.error = '登录已过期，请重新登录'
        useAuthStore().clear()
      } else reply.error = (e as Error).message || '网络异常，请稍后再试'
    } finally {
      reply.streaming = false
      reply.activity = ''
      busy.value = false
      controller = null
      persist()
    }
  }

  async function send(text: string, images: string[] = []) {
    if (busy.value || (!text.trim() && !images.length)) return
    expirePendingCards()
    push('user', text.trim(), images)
    const body = { message: text.trim() || '请看图片', images, conversationId: conversationId.value || undefined }
    await run((onEvent, signal) => assistantApi.chat(body, onEvent, signal))
  }

  async function decide(card: ConfirmCard, decision: 'confirm' | 'cancel', edits?: object) {
    if (busy.value || card.state !== 'pending' || !conversationId.value) return
    card.state = decision === 'confirm' ? 'confirmed' : 'cancelled'
    const id = conversationId.value
    await run((onEvent, signal) => assistantApi.resume(id, { interruptId: card.interruptId, decision, edits }, onEvent, signal))
  }

  function stop() { controller?.abort() }

  function newConversation() {
    stop()
    conversationId.value = ''
    messages.value = []
    persist()
  }

  return { open, conversationId, messages, busy, send, decide, stop, newConversation }
})
