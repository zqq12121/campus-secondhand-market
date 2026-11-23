import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAdminAuth } from './stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    /** 进入页面所需的权限码（接口文档 13.0） */
    perm?: string
  }
}

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('./views/LoginView.vue'), meta: { title: '登录' } },
  {
    path: '/',
    component: () => import('./AdminLayout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('./views/DashboardView.vue'), meta: { title: '数据看板', perm: 'dashboard:view' } },
      { path: 'audit', name: 'audit', component: () => import('./views/AuditView.vue'), meta: { title: '商品审核', perm: 'goods:audit' } },
      { path: 'goods', name: 'goods', component: () => import('./views/GoodsView.vue'), meta: { title: '商品管理', perm: 'goods:manage' } },
      { path: 'users', name: 'users', component: () => import('./views/UsersView.vue'), meta: { title: '用户管理', perm: 'user:view' } },
      { path: 'orders', name: 'orders', component: () => import('./views/OrdersView.vue'), meta: { title: '订单查询', perm: 'order:view' } },
      { path: 'disputes', name: 'disputes', component: () => import('./views/DisputesView.vue'), meta: { title: '纠纷裁决', perm: 'dispute:handle' } },
      { path: 'credit-appeals', name: 'credit-appeals', component: () => import('./views/CreditAppealsView.vue'), meta: { title: '信用分申诉', perm: 'user:credit-appeal' } },
      { path: 'reports', name: 'reports', component: () => import('./views/ReportsView.vue'), meta: { title: '举报处理', perm: 'report:handle' } },
      { path: 'content', name: 'content', component: () => import('./views/ContentView.vue'), meta: { title: '内容运营' } },
      { path: 'system', name: 'system', component: () => import('./views/SystemView.vue'), meta: { title: '权限与日志' } },
      { path: 'forbidden', name: 'forbidden', component: () => import('./views/ForbiddenView.vue'), meta: { title: '无权限' } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

const router = createRouter({ history: createWebHistory('/admin/'), routes })

router.beforeEach(async (to) => {
  const auth = useAdminAuth()
  if (to.name === 'login') return auth.loggedIn ? { name: 'dashboard' } : true
  if (!auth.loggedIn) return { name: 'login', query: { redirect: to.fullPath } }
  if (!auth.me) await auth.fetchMe()
  if (!auth.me) return { name: 'login' }
  if (to.meta.perm && !auth.can(to.meta.perm)) return { name: 'forbidden' }
})

router.afterEach((to) => {
  document.title = `${to.meta.title ?? '管理后台'} · WU 校园闲置后台`
})

export default router
