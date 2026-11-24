/** 校园二手助手（Python ai-service，前缀 /api/v1/ai）：SSE 对话、确认卡片回调、我的偏好 */
import { ApiError } from '@/shared/request'
import type { Id } from '@/shared/types'
import { http, USER_TOKEN_KEY } from '../http'

export interface AssistantGoods {
  id: Id
  title: string
  price: string
  conditionLevel: number
  campusName: string
  coverUrl: string | null
}

export interface WantDraft {
  title: string
  description: string
  categoryId: Id
  categoryName: string
  campusId: Id
  campusName: string
  priceMin: number | null
  priceMax: number | null
  validDays: number
}

export interface ListingDraft {
  title: string
  description: string
  categoryId: Id
  categoryName: string
  conditionLevel: number
  price: number
  priceRange: [number, number] | null
  priceBasis: string
  images: string[]
}

export type ConfirmEvent =
  | { interruptId: string; kind: 'want_post'; draft: WantDraft }
  | { interruptId: string; kind: 'listing'; draft: ListingDraft }

export interface DoneEvent {
  conversationId: string
  pending: boolean
}

export type StreamEvent =
  | { event: 'token'; data: string }
  /** 输出防护改写了回复：用 data 整体替换本轮已收到的文本 */
  | { event: 'revise'; data: string }
  | { event: 'tool'; data: { name: string; status: 'start' | 'end' } }
  | { event: 'goods'; data: AssistantGoods[] }
  | { event: 'confirm'; data: ConfirmEvent }
  | { event: 'listing'; data: ListingDraft }
  | { event: 'done'; data: DoneEvent }
  | { event: 'error'; data: { code: number; message: string } }

export interface MemoryItem {
  key: string
  label: string
  value: string
  updatedAt: string
}

const BASE = '/api/v1/ai'
/** 助手生成的发布草稿经 sessionStorage 交给发布页预填 */
export const LISTING_DRAFT_KEY = 'ct_assistant_listing'

/** POST 一个 SSE 接口，逐个事件回调。非 200 时抛 ApiError（含 401 / 429）。 */
async function stream(path: string, body: unknown, onEvent: (e: StreamEvent) => void, signal?: AbortSignal) {
  const token = localStorage.getItem(USER_TOKEN_KEY)
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body),
    signal,
  })
  if (!res.ok || !res.body) {
    const err = await res.json().catch(() => null) as { message?: string } | null
    throw new ApiError(res.status, err?.message || `助手暂时不可用（${res.status}）`)
  }
  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += value
    let idx: number
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      let event = ''
      let data = ''
      for (const line of block.split('\n')) {
        if (line.startsWith('event: ')) event = line.slice(7)
        else if (line.startsWith('data: ')) data += line.slice(6)
      }
      if (event && data) onEvent({ event, data: JSON.parse(data) } as StreamEvent)
    }
  }
}

export const assistantApi = {
  chat: (body: { message: string; conversationId?: string; images?: string[] },
    onEvent: (e: StreamEvent) => void, signal?: AbortSignal) => stream('/chat', body, onEvent, signal),
  resume: (conversationId: string, body: { interruptId: string; decision: 'confirm' | 'cancel'; edits?: object },
    onEvent: (e: StreamEvent) => void, signal?: AbortSignal) =>
    stream(`/chat/${conversationId}/resume`, body, onEvent, signal),
}

export const memoryApi = {
  list: () => http.get<MemoryItem[]>('/ai/memory'),
  remove: (key: string) => http.del<null>(`/ai/memory/${key}`),
  clear: () => http.del<null>('/ai/memory'),
}
