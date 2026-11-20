import { defineStore } from 'pinia'
import { ref } from 'vue'
import { notificationApi } from '../api'

/** 顶栏铃铛和消息角标的未读数 */
export const useUnreadStore = defineStore('unread', () => {
  const notification = ref(0)
  const chat = ref(0)

  async function refresh() {
    try {
      const r = await notificationApi.unreadCount()
      notification.value = r.notification
      chat.value = r.chat
    } catch {
      /* 未读数失败不打扰用户 */
    }
  }

  function set(v: { notification?: number; chat?: number }) {
    if (v.notification != null) notification.value = v.notification
    if (v.chat != null) chat.value = v.chat
  }

  function reset() {
    notification.value = 0
    chat.value = 0
  }

  return { notification, chat, refresh, set, reset }
})
