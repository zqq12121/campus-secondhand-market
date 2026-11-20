<script setup lang="ts">
import { onBeforeUnmount, onMounted, watch, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import SiteHeader from './components/SiteHeader.vue'
import TabBar from './components/TabBar.vue'
import { useAuthStore } from './stores/auth'
import { useUnreadStore } from './stores/unread'
import { connect, disconnect, on, USE_MOCK } from './realtime'

const route = useRoute()
const auth = useAuthStore()
const unread = useUnreadStore()
let timer: number | undefined

// 手机端二级页面隐藏 Tab 栏：与原型一致，用 body.no-tabbar 控制。
// 「消息」是一级 Tab：会话列表保留 Tab 栏，打开具体会话后才隐藏。
watchEffect(() => {
  const inConversation = route.name === 'chat' && !!route.params.id
  document.body.classList.toggle('no-tabbar', !!route.meta.noTabbar || inConversation)
})

watch(() => auth.loggedIn, (v) => {
  clearInterval(timer)
  if (!v) { unread.reset(); disconnect(); return }
  auth.fetchMe()
  unread.refresh()
  connect()
  // Mock 模式没有 WebSocket 推送，定时刷新未读数
  if (USE_MOCK) timer = window.setInterval(unread.refresh, 5000)
}, { immediate: true })

const offs: (() => void)[] = []
onMounted(() => {
  offs.push(on('notification', (e) => unread.set(e.unread)))
  offs.push(on('message', (e) => unread.set({ chat: e.totalUnread })))
})
onBeforeUnmount(() => { offs.forEach((f) => f()); clearInterval(timer) })
</script>

<template>
  <el-config-provider :locale="zhCn">
    <SiteHeader v-if="!route.meta.bare" />
    <RouterView :key="route.path" />
    <TabBar v-if="!route.meta.bare" />
  </el-config-provider>
</template>
