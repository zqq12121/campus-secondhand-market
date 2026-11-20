<script setup lang="ts">
import { useRoute } from 'vue-router'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useUnreadStore } from '../stores/unread'

const route = useRoute()
const unread = useUnreadStore()
const act = (n: string) => (route.meta.nav === n ? 'active' : '')
</script>

<template>
  <nav class="tabbar" aria-label="底部导航">
    <RouterLink :class="act('home')" to="/"><AppIcon name="home" />首页</RouterLink>
    <RouterLink :class="act('want')" to="/wants"><AppIcon name="megaphone" />求购</RouterLink>
    <RouterLink class="publish" to="/publish"><AppIcon name="plus" />发布</RouterLink>
    <RouterLink :class="act('chat')" to="/chat"><AppIcon name="message" />消息<span v-if="unread.chat" class="dot-badge">{{ unread.chat }}</span></RouterLink>
    <RouterLink :class="act('me')" to="/me"><AppIcon name="user" />我的</RouterLink>
  </nav>
</template>
