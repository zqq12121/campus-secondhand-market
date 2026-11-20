/**
 * 实时消息（接口文档 7.7）。
 * - 真实后端：STOMP over WebSocket，订阅 /user/queue/messages、/notifications、/errors。
 * - Mock 模式：没有 WebSocket 服务，改为每 5 秒轮询未读数，聊天页自己轮询消息。
 */
import { Client, type IMessage } from '@stomp/stompjs'
import { USER_TOKEN_KEY } from './http'
import type { ChatMessage, NotificationItem } from '@/shared/types'

export interface MessageEvent {
  event: 'MESSAGE'
  conversationId: string
  clientMsgId: string | null
  message: ChatMessage
  riskTip: string | null
  conversationUnread: number
  totalUnread: number
}
export interface NotificationEvent {
  event: 'NOTIFICATION'
  notification: NotificationItem
  unread: { notification: number; chat: number }
}
export interface ErrorEvent { event: 'ERROR'; clientMsgId: string; code: number; message: string }

type Handlers = {
  message: (e: MessageEvent) => void
  notification: (e: NotificationEvent) => void
  error: (e: ErrorEvent) => void
}

export const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

const listeners: { [K in keyof Handlers]: Set<Handlers[K]> } = {
  message: new Set(), notification: new Set(), error: new Set(),
}
let client: Client | null = null

export function on<K extends keyof Handlers>(type: K, fn: Handlers[K]): () => void {
  listeners[type].add(fn)
  return () => listeners[type].delete(fn)
}

function emit<K extends keyof Handlers>(type: K, payload: Parameters<Handlers[K]>[0]) {
  listeners[type].forEach((fn) => (fn as (p: typeof payload) => void)(payload))
}

export function connect() {
  if (USE_MOCK || client?.active) return
  const token = localStorage.getItem(USER_TOKEN_KEY)
  if (!token) return
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  client = new Client({
    brokerURL: `${proto}://${location.host}/ws`,
    connectHeaders: { Authorization: `Bearer ${token}` },
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    reconnectDelay: 5000,
    onConnect: () => {
      const parse = (m: IMessage) => JSON.parse(m.body)
      client?.subscribe('/user/queue/messages', (m) => emit('message', parse(m)))
      client?.subscribe('/user/queue/notifications', (m) => emit('notification', parse(m)))
      client?.subscribe('/user/queue/errors', (m) => emit('error', parse(m)))
    },
  })
  client.activate()
}

export function disconnect() {
  client?.deactivate()
  client = null
}

/** 已连接时走 WebSocket 发送，返回 false 表示需要走 HTTP 兜底（接口文档 7.5） */
export function sendViaSocket(conversationId: string, body: { type: number; content: string; clientMsgId: string }): boolean {
  if (!client?.connected) return false
  client.publish({ destination: '/app/chat.send', body: JSON.stringify({ ...body, conversationId }) })
  return true
}

export function markReadViaSocket(conversationId: string): boolean {
  if (!client?.connected) return false
  client.publish({ destination: '/app/chat.read', body: JSON.stringify({ conversationId }) })
  return true
}
