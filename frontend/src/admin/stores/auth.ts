import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { ADMIN_TOKEN_KEY, authApi } from '../api'
import type { AdminMe } from '../types'

/** 后台登录态 + 权限码（GET /auth/me），菜单和按钮按权限码显示 */
export const useAdminAuth = defineStore('adminAuth', () => {
  const token = ref<string | null>(localStorage.getItem(ADMIN_TOKEN_KEY))
  const me = ref<AdminMe | null>(null)
  const loggedIn = computed(() => !!token.value)

  async function login(username: string, password: string) {
    const r = await authApi.login(username, password)
    token.value = r.token
    localStorage.setItem(ADMIN_TOKEN_KEY, r.token)
    await fetchMe()
  }

  async function fetchMe() {
    if (!token.value) return null
    try {
      me.value = await authApi.me()
    } catch {
      clear()
    }
    return me.value
  }

  async function logout() {
    try { await authApi.logout() } finally { clear() }
  }

  function clear() {
    token.value = null
    me.value = null
    localStorage.removeItem(ADMIN_TOKEN_KEY)
  }

  const can = (perm: string) => !!me.value?.permissions.includes(perm)

  return { token, me, loggedIn, login, fetchMe, logout, clear, can }
})
