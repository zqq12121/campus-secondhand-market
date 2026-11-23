<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppIcon from '@/shared/components/AppIcon.vue'
import { authApi, dashboardApi } from './api'
import { useAdminAuth } from './stores/auth'

/** 后台框架：侧栏菜单按权限码过滤（≤1024px 收窄为图标栏，样式见 base.css） */
const route = useRoute()
const router = useRouter()
const auth = useAdminAuth()
const pending = reactive({ goodsAudit: 0, disputes: 0, reports: 0 })
const pwdOpen = ref(false)
const pwd = reactive({ old: '', new: '' })

const MENU = [
  { group: '总览', items: [{ to: 'dashboard', icon: 'dashboard', name: '数据看板', perm: 'dashboard:view' }] },
  { group: '商品', items: [
    { to: 'audit', icon: 'audit', name: '商品审核', perm: 'goods:audit', count: 'goodsAudit' },
    { to: 'goods', icon: 'package', name: '商品管理', perm: 'goods:manage' },
  ] },
  { group: '管理', items: [
    { to: 'users', icon: 'users', name: '用户管理', perm: 'user:view' },
    { to: 'orders', icon: 'file', name: '订单查询', perm: 'order:view' },
    { to: 'disputes', icon: 'scale', name: '纠纷裁决', perm: 'dispute:handle', count: 'disputes' },
    { to: 'reports', icon: 'flag', name: '举报处理', perm: 'report:handle', count: 'reports' },
    { to: 'credit-appeals', icon: 'shield', name: '信用分申诉', perm: 'user:credit-appeal' },
  ] },
  { group: '运营', items: [{ to: 'content', icon: 'sliders', name: '内容运营', perm: ['category:manage', 'content:manage'] }] },
  { group: '系统', items: [{ to: 'system', icon: 'lock', name: '权限与日志', perm: ['system:admin', 'system:role', 'system:log'] }] },
] as const

const menu = computed(() => MENU.map((g) => ({
  group: g.group,
  items: g.items.filter((i) => ([] as string[]).concat(i.perm).some((p) => auth.can(p))),
})).filter((g) => g.items.length))

async function refreshPending() {
  if (!auth.can('dashboard:view')) return
  try { Object.assign(pending, (await dashboardApi.overview()).pending) } catch { /* 忽略 */ }
}

async function logout() {
  await auth.logout()
  router.replace({ name: 'login' })
}

async function changePwd() {
  if (pwd.new.length < 8) return ElMessage.warning('新密码至少 8 位')
  await authApi.changePassword(pwd.old, pwd.new)
  ElMessage.success('密码已修改')
  pwdOpen.value = false
  Object.assign(pwd, { old: '', new: '' })
}

onMounted(refreshPending)
watch(() => route.fullPath, refreshPending)
</script>

<template>
  <div class="admin">
    <aside class="admin-side">
      <div class="logo" title="管理后台"><span class="logo-mark"><AppIcon name="package" /></span><span class="label">管理后台</span></div>
      <template v-for="g in menu" :key="g.group">
        <div class="group">{{ g.group }}</div>
        <RouterLink v-for="i in g.items" :key="i.to" :to="`/${i.to}`" :class="{ active: route.name === i.to }" :title="i.name"
          :aria-label="'count' in i && pending[i.count] ? `${i.name}，${pending[i.count]} 条待处理` : i.name">
          <AppIcon :name="i.icon" /><span class="label">{{ i.name }}</span>
          <span v-if="'count' in i && pending[i.count]" class="count" aria-hidden="true">{{ pending[i.count] }}</span>
        </RouterLink>
      </template>
    </aside>
    <div class="admin-main">
      <header class="admin-top">
        <h1 style="font-size: 18px">{{ route.meta.title }}</h1>
        <el-dropdown trigger="click">
          <button class="row user-btn" type="button">
            <span class="avatar avatar-sm">{{ auth.me?.realName?.[0] ?? '管' }}</span>
            <span class="hide-sm">{{ auth.me?.username }} · {{ auth.me?.roles.includes('SUPER_ADMIN') ? '超级管理员' : '管理员' }}</span>
            <AppIcon name="down" size="sm" />
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="pwdOpen = true">修改密码</el-dropdown-item>
              <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>
      <main class="admin-content"><RouterView :key="route.path" /></main>
    </div>

    <el-dialog v-model="pwdOpen" title="修改密码" width="min(420px, 92vw)">
      <div class="stack">
        <div class="field"><label for="ap-o">当前密码</label><el-input id="ap-o" v-model="pwd.old" type="password" show-password /></div>
        <div class="field"><label for="ap-n">新密码</label><el-input id="ap-n" v-model="pwd.new" type="password" show-password /></div>
      </div>
      <template #footer><el-button @click="pwdOpen = false">取消</el-button><el-button type="primary" @click="changePwd">确认修改</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.user-btn { border: 0; background: none; cursor: pointer; font: inherit; color: inherit; padding: 4px 8px; border-radius: 10px; }
.user-btn:hover { background: #F1F5F9; }
</style>
