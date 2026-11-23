import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from './stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** 需要登录 */
    auth?: boolean
    /** 二级页面：手机端隐藏底部 Tab 栏 */
    noTabbar?: boolean
    /** 不显示顶部导航（登录页） */
    bare?: boolean
    /** 当前高亮的导航项 */
    nav?: 'home' | 'want' | 'chat' | 'notice' | 'me'
  }
}

const routes: RouteRecordRaw[] = [
  { path: '/', name: 'home', component: () => import('./views/HomeView.vue'), meta: { title: '首页', nav: 'home' } },
  { path: '/login', name: 'login', component: () => import('./views/LoginView.vue'), meta: { title: '登录', bare: true } },
  { path: '/search', name: 'search', component: () => import('./views/SearchView.vue'), meta: { title: '搜索' } },
  { path: '/goods/:id', name: 'goods', component: () => import('./views/GoodsView.vue'), meta: { title: '商品详情', noTabbar: true } },
  { path: '/publish/:id?', name: 'publish', component: () => import('./views/PublishView.vue'), meta: { title: '发布闲置', auth: true } },
  { path: '/wants', name: 'wants', component: () => import('./views/WantsView.vue'), meta: { title: '求购广场', nav: 'want' } },
  { path: '/chat/:id?', name: 'chat', component: () => import('./views/ChatView.vue'), meta: { title: '消息', auth: true, nav: 'chat' } },
  { path: '/checkout/:goodsId', name: 'checkout', component: () => import('./views/CheckoutView.vue'), meta: { title: '确认下单', auth: true, noTabbar: true } },
  { path: '/orders', name: 'orders', component: () => import('./views/OrdersView.vue'), meta: { title: '我的订单', auth: true, nav: 'me' } },
  { path: '/order/:id', name: 'order', component: () => import('./views/OrderView.vue'), meta: { title: '订单详情', auth: true, nav: 'me', noTabbar: true } },
  { path: '/order/:id/review', name: 'review', component: () => import('./views/ReviewView.vue'), meta: { title: '评价交易', auth: true, nav: 'me', noTabbar: true } },
  { path: '/me', name: 'me', component: () => import('./views/MeView.vue'), meta: { title: '个人中心', auth: true, nav: 'me' } },
  { path: '/me/preferences', name: 'preferences', component: () => import('./views/PreferencesView.vue'), meta: { title: '助手偏好', auth: true, nav: 'me', noTabbar: true } },
  { path: '/users/:id', name: 'user', component: () => import('./views/UserView.vue'), meta: { title: '用户主页' } },
  { path: '/notifications', name: 'notifications', component: () => import('./views/NotificationsView.vue'), meta: { title: '消息通知', auth: true, nav: 'notice' } },
  { path: '/announcements/:id', name: 'announcement', component: () => import('./views/AnnouncementView.vue'), meta: { title: '平台公告', noTabbar: true } },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('./views/NotFoundView.vue'), meta: { title: '页面不存在' } },
]

const router = createRouter({
  history: createWebHistory('/'),
  routes,
  scrollBehavior: (_to, _from, saved) => saved ?? { top: 0 },
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.auth && !auth.loggedIn) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.name === 'login' && auth.loggedIn) return { name: 'home' }
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · WU 校园闲置` : 'WU 校园闲置'
})

export default router
