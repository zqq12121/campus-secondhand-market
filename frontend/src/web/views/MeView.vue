<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppIcon from '@/shared/components/AppIcon.vue'
import CoverImage from '@/shared/components/CoverImage.vue'
import UserAvatar from '@/shared/components/UserAvatar.vue'
import StatusTag from '@/shared/components/StatusTag.vue'
import { CREDIT_LEVEL, CREDIT_TONE, GOODS_STATUS, GOODS_STATUS_TONE, WANT_STATUS } from '@/shared/enums'
import { priceText, shortTime } from '@/shared/format'
import type { BalanceLog, BlockedUser, CreditLog, FavoriteItem, MyGoodsItem, WantItem } from '@/shared/types'
import GoodsCard from '../components/GoodsCard.vue'
import { USER_TOKEN_KEY } from '../http'
import { commonApi, goodsApi, userApi, wantApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { useMetaStore } from '../stores/meta'

/** 个人中心（接口文档第 4 章 + 5.9 / 5.11 / 6.6） */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const meta = useMetaStore()

const GOODS_TABS = [
  { key: '2', label: '在售' }, { key: '1', label: '审核中' }, { key: '3', label: '已驳回' }, { key: '4', label: '交易中' },
  { key: '5', label: '已售出' }, { key: '6', label: '已下架' }, { key: '0', label: '草稿' },
]
const tab = computed(() => (typeof route.query.tab === 'string' ? route.query.tab : '2'))
const goods = ref<MyGoodsItem[]>([])
const counts = ref<Record<string, number>>({})
const favs = ref<FavoriteItem[]>([])
const wants = ref<WantItem[]>([])
const blocks = ref<BlockedUser[]>([])
const credits = ref<CreditLog[]>([])
const balances = ref<BalanceLog[]>([])
const loading = ref(false)

const profileOpen = ref(false)
const pwdOpen = ref(false)
const profile = reactive({ nickname: '', college: '', campusId: '', bio: '', avatarUrl: '' as string | null })
const pwd = reactive({ old: '', new: '' })

const me = computed(() => auth.me)
const nextLevel = computed(() => {
  const s = me.value?.creditScore ?? 0
  return s >= 90 ? '' : s >= 80 ? `再加 ${90 - s} 分升级为「信用极好」` : s >= 60 ? `再加 ${80 - s} 分升级为「信用良好」` : '信用分低于 60 不能使用担保交易'
})

async function loadTab() {
  loading.value = true
  try {
    if (tab.value === 'fav') favs.value = (await userApi.favorites({ page: 1, size: 50 })).list
    else if (tab.value === 'want') wants.value = (await userApi.myWants({ page: 1, size: 50 })).list
    else if (tab.value === 'block') blocks.value = (await userApi.blocks({ page: 1, size: 50 })).list
    else {
      const r = await userApi.myGoods({ status: Number(tab.value), page: 1, size: 50 })
      goods.value = r.list
      counts.value = r.counts
    }
  } finally {
    loading.value = false
  }
}
const setTab = (k: string) => router.replace({ query: { tab: k } })

async function goodsAction(g: MyGoodsItem, a: 'off' | 'on' | 'del') {
  if (a === 'off') { await goodsApi.offShelf(g.id); ElMessage.success('已下架') }
  if (a === 'on') {
    const r = await goodsApi.onShelf(g.id)
    ElMessage.success(r.status === 2 ? '已重新上架' : '已提交审核')
  }
  if (a === 'del') {
    await ElMessageBox.confirm('删除后不可恢复。', '删除商品', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    await goodsApi.remove(g.id)
  }
  loadTab()
}

async function wantAction(w: WantItem, a: 'close' | 'del') {
  if (a === 'close') await wantApi.close(w.id)
  else {
    await ElMessageBox.confirm('删除后不可恢复。', '删除求购', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    await wantApi.remove(w.id)
  }
  loadTab()
}

async function unblock(u: BlockedUser) {
  await userApi.unblock(u.userId)
  ElMessage.success('已取消拉黑')
  loadTab()
}

function openProfile() {
  if (!me.value) return
  Object.assign(profile, { nickname: me.value.nickname, college: me.value.college ?? '', campusId: me.value.campusId, bio: me.value.bio ?? '', avatarUrl: me.value.avatarUrl })
  profileOpen.value = true
}

async function pickAvatar(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (!f) return
  profile.avatarUrl = (await commonApi.upload(f, 'AVATAR')).url
}

async function saveProfile() {
  if (profile.nickname.trim().length < 2) return ElMessage.warning('昵称需要 2–16 个字')
  auth.me = await userApi.updateMe({ nickname: profile.nickname.trim(), college: profile.college.trim(), campusId: profile.campusId, bio: profile.bio.trim(), avatarUrl: profile.avatarUrl })
  profileOpen.value = false
  ElMessage.success('资料已保存')
}

async function savePwd() {
  if (!/^(?=.*[A-Za-z])(?=.*\d).{8,20}$/.test(pwd.new)) return ElMessage.warning('新密码需 8–20 位，且同时包含字母和数字')
  const r = await userApi.changePassword(pwd.old, pwd.new)
  localStorage.setItem(USER_TOKEN_KEY, r.token)
  auth.token = r.token
  pwdOpen.value = false
  Object.assign(pwd, { old: '', new: '' })
  ElMessage.success('密码已修改，其他设备需要重新登录')
}

async function logout() {
  await auth.logout()
  router.replace('/')
}

watch(tab, loadTab)
onMounted(async () => {
  meta.load()
  await auth.fetchMe()
  loadTab()
  userApi.creditLogs({ page: 1, size: 3 }).then((r) => (credits.value = r.list))
  userApi.balanceLogs({ page: 1, size: 3 }).then((r) => (balances.value = r.list))
})
</script>

<template>
  <main class="page">
    <div v-if="me" class="container">
      <div class="layout-side">
        <aside class="card side-menu hide-sm">
          <RouterLink class="active" to="/me"><AppIcon name="user" />我的主页</RouterLink>
          <RouterLink to="/orders"><AppIcon name="file" />我的订单</RouterLink>
          <RouterLink to="/notifications"><AppIcon name="bell" />消息通知</RouterLink>
          <RouterLink to="/me/preferences"><AppIcon name="sliders" />助手偏好</RouterLink>
          <button type="button" @click="openProfile"><AppIcon name="edit" />编辑资料</button>
          <button type="button" @click="pwdOpen = true"><AppIcon name="lock" />修改密码</button>
          <button type="button" @click="logout"><AppIcon name="logout" />退出登录</button>
        </aside>

        <div class="stack">
          <section class="card card-pad stack">
            <div class="profile">
              <UserAvatar size="lg" :name="me.nickname" :src="me.avatarUrl" />
              <div class="info">
                <div class="row"><h1 style="font-size: 20px">{{ me.nickname }}</h1><span v-if="me.verified" class="tag tag-green"><AppIcon name="shield" size="sm" />已认证</span>
                  <StatusTag v-if="me.status !== 0" :text="me.status === 1 ? '禁言中' : '已封禁'" tone="red" /></div>
                <p class="subtle">{{ me.email }} · {{ me.college || '未填写学院' }} · {{ me.campusName }}</p>
                <p v-if="me.bio" class="muted mt-8">{{ me.bio }}</p>
              </div>
              <div class="row">
                <button class="btn btn-outline" @click="openProfile"><AppIcon name="edit" />编辑资料</button>
                <button class="btn btn-ghost show-sm" @click="logout">退出</button>
              </div>
            </div>
            <div class="quick-stats">
              <RouterLink to="/orders"><div class="qv num">{{ me.stats.boughtCount }}</div><div class="subtle">买到</div></RouterLink>
              <RouterLink :to="{ name: 'orders', query: { role: 'SELLER' } }"><div class="qv num">{{ me.stats.soldCount }}</div><div class="subtle">卖出</div></RouterLink>
              <button type="button" @click="setTab('fav')"><div class="qv num">{{ me.stats.favoriteCount }}</div><div class="subtle">收藏</div></button>
              <a href="#wallet"><div class="qv num">¥{{ priceText(me.balance) || '0' }}</div><div class="subtle">平台余额</div></a>
            </div>
          </section>

          <div class="grid-2">
            <section class="card card-pad stack">
              <div class="between"><h3 class="h3">信用分</h3><StatusTag :text="CREDIT_LEVEL[me.creditLevel]" :tone="CREDIT_TONE[me.creditLevel]" /></div>
              <div class="row" style="align-items: baseline"><span class="num big">{{ me.creditScore }}</span><span class="subtle">/ 100 · {{ nextLevel }}</span></div>
              <div class="credit-bar"><span :style="{ width: `${me.creditScore}%` }" /></div>
              <ul class="logs subtle">
                <li v-for="c in credits" :key="c.id">{{ shortTime(c.createdAt) }} {{ c.reasonText }} <span :class="c.delta > 0 ? 'up' : 'down'">{{ c.delta > 0 ? '+' : '−' }}{{ Math.abs(c.delta) }}</span></li>
                <li v-if="!credits.length">暂无变动</li>
              </ul>
            </section>
            <section id="wallet" class="card card-pad stack">
              <div class="between"><h3 class="h3">平台余额</h3><span class="subtle">担保交易放款，演示用不支持提现</span></div>
              <span class="num big">¥{{ me.balance }}</span>
              <ul class="logs subtle">
                <li v-for="b in balances" :key="b.id">{{ shortTime(b.createdAt) }} 担保订单放款 {{ b.orderNo }} <span class="up">{{ b.amount }}</span></li>
                <li v-if="!balances.length">暂无流水</li>
              </ul>
            </section>
          </div>

          <section class="card">
            <div class="tabs" style="padding: 0 16px" role="tablist">
              <button v-for="t in GOODS_TABS" :key="t.key" class="tab" :class="{ active: tab === t.key }" role="tab" @click="setTab(t.key)">{{ t.label }}<span v-if="counts[t.key]" class="count">{{ counts[t.key] }}</span></button>
              <button class="tab" :class="{ active: tab === 'fav' }" role="tab" @click="setTab('fav')">我的收藏</button>
              <button class="tab" :class="{ active: tab === 'want' }" role="tab" @click="setTab('want')">我的求购</button>
              <button class="tab" :class="{ active: tab === 'block' }" role="tab" @click="setTab('block')">黑名单</button>
            </div>
            <div v-loading="loading" style="min-height: 120px">
              <template v-if="tab === 'fav'">
                <div v-if="favs.length" class="goods-grid" style="padding: 16px"><GoodsCard v-for="g in favs" :key="g.id" :goods="g" /></div>
                <div v-else class="empty">还没有收藏</div>
              </template>
              <template v-else-if="tab === 'want'">
                <div v-for="w in wants" :key="w.id" class="list-item">
                  <div style="flex: 1; min-width: 0">{{ w.title }}<div class="subtle">{{ w.categoryName }} · {{ shortTime(w.createdAt) }} 发布 · {{ shortTime(w.expireAt) }} 到期</div></div>
                  <span class="tag" :class="w.status === 0 ? 'tag-green' : ''">{{ WANT_STATUS[w.status] }}</span>
                  <div class="ops">
                    <button v-if="w.status === 0" class="btn btn-primary btn-sm" @click="wantAction(w, 'close')">已解决</button>
                    <button class="btn btn-outline btn-sm" @click="wantAction(w, 'del')">删除</button>
                  </div>
                </div>
                <div v-if="!wants.length" class="empty">还没有发布求购。<RouterLink to="/wants" class="link">去求购广场</RouterLink></div>
              </template>
              <template v-else-if="tab === 'block'">
                <div v-for="u in blocks" :key="u.userId" class="list-item">
                  <UserAvatar :name="u.nickname" :src="u.avatarUrl" />
                  <div style="flex: 1">{{ u.nickname }}<div class="subtle">{{ shortTime(u.createdAt) }} 拉黑</div></div>
                  <button class="btn btn-outline btn-sm" @click="unblock(u)">取消拉黑</button>
                </div>
                <div v-if="!blocks.length" class="empty">黑名单是空的</div>
              </template>
              <template v-else>
                <div v-for="g in goods" :key="g.id" class="list-item">
                  <RouterLink class="thumb item-thumb" :to="`/goods/${g.id}`"><CoverImage :src="g.coverUrl" :alt="g.title" /></RouterLink>
                  <div style="flex: 1; min-width: 0">
                    <RouterLink :to="`/goods/${g.id}`">{{ g.title }}</RouterLink>
                    <div class="subtle">¥{{ priceText(g.price) }} · {{ g.viewCount }} 浏览 · {{ g.favoriteCount }} 收藏 · 库存 {{ g.stock }}</div>
                    <div v-if="g.auditRemark" class="error">{{ g.auditRemark }}</div>
                  </div>
                  <StatusTag :text="GOODS_STATUS[g.status]" :tone="GOODS_STATUS_TONE[g.status]" />
                  <div class="ops">
                    <RouterLink v-if="[0, 1, 2, 3, 6].includes(g.status)" class="btn btn-outline btn-sm" :to="`/publish/${g.id}`"><AppIcon name="edit" size="sm" />编辑</RouterLink>
                    <button v-if="[1, 2].includes(g.status)" class="btn btn-outline btn-sm" @click="goodsAction(g, 'off')">下架</button>
                    <button v-if="g.status === 6" class="btn btn-primary btn-sm" @click="goodsAction(g, 'on')">重新上架</button>
                    <button v-if="[0, 3, 6].includes(g.status)" class="btn btn-danger-outline btn-sm" @click="goodsAction(g, 'del')">删除</button>
                  </div>
                </div>
                <div v-if="!goods.length" class="empty">这里还没有商品。<RouterLink to="/publish" class="link">发布闲置</RouterLink></div>
              </template>
            </div>
          </section>
        </div>
      </div>
    </div>

    <el-dialog v-model="profileOpen" title="编辑资料" width="min(520px, 92vw)">
      <div class="stack">
        <div class="row"><UserAvatar size="lg" :name="profile.nickname" :src="profile.avatarUrl" />
          <label class="btn btn-outline btn-sm">更换头像<input type="file" accept="image/jpeg,image/png,image/webp" hidden @change="pickAvatar" /></label></div>
        <div class="field"><label for="pf-n">昵称</label><el-input id="pf-n" v-model="profile.nickname" maxlength="16" show-word-limit /></div>
        <div class="field"><label for="pf-c">学院</label><el-input id="pf-c" v-model="profile.college" maxlength="64" /></div>
        <div class="field"><label>所在校区</label><div class="radio-group">
          <button v-for="c in meta.campuses" :key="c.id" type="button" class="radio-card" :class="{ active: profile.campusId === c.id }" @click="profile.campusId = c.id">{{ c.name }}</button></div></div>
        <div class="field"><label for="pf-b">个人简介</label><el-input id="pf-b" v-model="profile.bio" type="textarea" :rows="3" maxlength="200" show-word-limit /></div>
        <div class="field"><label for="pf-e">校园邮箱</label><el-input id="pf-e" :model-value="me?.email" disabled /><span class="hint">邮箱即账号，不可修改</span></div>
      </div>
      <template #footer><el-button @click="profileOpen = false">取消</el-button><el-button type="primary" @click="saveProfile">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="pwdOpen" title="修改密码" width="min(420px, 92vw)">
      <div class="stack">
        <div class="field"><label for="pw-o">当前密码</label><el-input id="pw-o" v-model="pwd.old" type="password" show-password autocomplete="current-password" /></div>
        <div class="field"><label for="pw-n">新密码</label><el-input id="pw-n" v-model="pwd.new" type="password" show-password autocomplete="new-password" placeholder="8–20 位，含字母和数字" /></div>
      </div>
      <template #footer><el-button @click="pwdOpen = false">取消</el-button><el-button type="primary" @click="savePwd">确认修改</el-button></template>
    </el-dialog>
  </main>
</template>

<style scoped>
.profile { display: flex; align-items: center; gap: 20px; flex-wrap: wrap; }
.profile .info { flex: 1; min-width: 200px; }
.quick-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.quick-stats > * { padding: 16px; border-radius: 12px; background: #F8FAFC; text-align: center; transition: background .15s; border: 0; cursor: pointer; font: inherit; }
.quick-stats > *:hover { background: var(--primary-soft); }
.qv { font-size: 22px; font-weight: 600; }
.h3 { font-size: 16px; }
.big { font-size: 36px; font-weight: 600; }
.credit-bar { height: 10px; border-radius: 999px; background: #E2E8F0; overflow: hidden; }
.credit-bar span { display: block; height: 100%; background: linear-gradient(90deg, #10B981, #047857); }
.logs { margin: 0; padding-left: 18px; }
.item-thumb { overflow: hidden; }
.ops { display: flex; gap: 6px; flex-wrap: wrap; justify-content: flex-end; }
.link { color: var(--primary); }
@media (max-width: 768px) { .quick-stats { grid-template-columns: repeat(2, 1fr); } .list-item { flex-wrap: wrap; } }
</style>
