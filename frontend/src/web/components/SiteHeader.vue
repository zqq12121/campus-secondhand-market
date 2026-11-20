<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/shared/components/AppIcon.vue'
import { initial } from '@/shared/format'
import { useAuthStore } from '../stores/auth'
import { useUnreadStore } from '../stores/unread'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const unread = useUnreadStore()
const keyword = ref('')

watch(() => route.query.keyword, (v) => { keyword.value = typeof v === 'string' ? v : '' }, { immediate: true })

function search() {
  const k = keyword.value.trim().slice(0, 30)
  router.push({ name: 'search', query: k ? { keyword: k } : {} })
}
const act = (n: string) => (route.meta.nav === n ? 'active' : '')
</script>

<template>
  <header class="header">
    <div class="container">
      <RouterLink class="logo" to="/"><span class="logo-mark"><AppIcon name="package" /></span><span>WU 校园闲置</span></RouterLink>
      <form class="header-search" role="search" @submit.prevent="search">
        <AppIcon name="search" />
        <input v-model="keyword" maxlength="30" placeholder="搜索教材、数码、生活用品…" aria-label="搜索商品" />
        <button class="btn btn-primary" type="submit">搜索</button>
      </form>
      <nav class="header-nav" aria-label="主导航">
        <RouterLink class="nav-link" :class="act('home')" to="/">首页</RouterLink>
        <RouterLink class="nav-link" :class="act('want')" to="/wants">求购广场</RouterLink>
        <template v-if="auth.loggedIn">
          <RouterLink class="nav-link" :class="act('chat')" to="/chat" :aria-label="`消息，${unread.chat} 条未读`">
            <AppIcon name="message" /><span v-if="unread.chat" class="dot-badge">{{ unread.chat > 99 ? '99+' : unread.chat }}</span>
          </RouterLink>
          <RouterLink class="nav-link" :class="act('notice')" to="/notifications" :aria-label="`通知，${unread.notification} 条未读`">
            <AppIcon name="bell" /><span v-if="unread.notification" class="dot-badge">{{ unread.notification > 99 ? '99+' : unread.notification }}</span>
          </RouterLink>
          <RouterLink class="btn btn-primary" to="/publish"><AppIcon name="plus" />发布闲置</RouterLink>
          <RouterLink class="nav-link" :class="act('me')" to="/me" aria-label="个人中心">
            <span class="avatar">
              <img v-if="auth.me?.avatarUrl" :src="auth.me.avatarUrl" alt="" />
              <template v-else>{{ initial(auth.me?.nickname) }}</template>
            </span>
          </RouterLink>
        </template>
        <template v-else>
          <RouterLink class="btn btn-primary" :to="{ name: 'login', query: { redirect: route.fullPath } }">登录 / 注册</RouterLink>
        </template>
      </nav>
    </div>
  </header>
</template>

<style scoped>
.avatar img { width: 100%; height: 100%; object-fit: cover; }
</style>
