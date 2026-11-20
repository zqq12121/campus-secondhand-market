import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi, userApi } from '../api'
import { USER_TOKEN_KEY } from '../http'
import type { LoginResult, MeProfile } from '@/shared/types'

/** 登录态：token 存 localStorage，资料按需拉取 /users/me */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem(USER_TOKEN_KEY))
  const me = ref<MeProfile | null>(null)
  const loggedIn = computed(() => !!token.value)

  function applyLogin(res: LoginResult) {
    token.value = res.token
    localStorage.setItem(USER_TOKEN_KEY, res.token)
    me.value = null
  }

  /** 同一时刻只发一个 /users/me：App 和页面同时调用时共用同一个请求 */
  let pending: Promise<MeProfile | null> | null = null
  function fetchMe(): Promise<MeProfile | null> {
    if (!token.value) return Promise.resolve(null)
    pending ??= userApi.me({ silent: true })
      .then((r) => (me.value = r))
      .catch(() => { clear(); return null })
      .finally(() => { pending = null })
    return pending
  }

  async function logout() {
    try { await authApi.logout() } finally { clear() }
  }

  function clear() {
    token.value = null
    me.value = null
    localStorage.removeItem(USER_TOKEN_KEY)
  }

  return { token, me, loggedIn, applyLogin, fetchMe, logout, clear }
})
