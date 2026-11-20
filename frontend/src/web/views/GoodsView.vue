<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppIcon from '@/shared/components/AppIcon.vue'
import CoverImage from '@/shared/components/CoverImage.vue'
import UserAvatar from '@/shared/components/UserAvatar.vue'
import StatusTag from '@/shared/components/StatusTag.vue'
import { CONDITION, CREDIT_LEVEL, CREDIT_TONE, GOODS_STATUS, GOODS_STATUS_TONE } from '@/shared/enums'
import { priceText, shortTime } from '@/shared/format'
import { ApiError } from '@/shared/request'
import type { CannotBuyReason, GoodsDetail, GoodsItem } from '@/shared/types'
import GoodsCard from '../components/GoodsCard.vue'
import ReportDialog from '../components/ReportDialog.vue'
import { chatApi, goodsApi } from '../api'
import { useAuthStore } from '../stores/auth'

/** 商品详情（接口文档 5.3）。购买按钮是否可用完全按后端 canBuy / cannotBuyReason 决定。 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const id = route.params.id as string

const g = ref<GoodsDetail | null>(null)
const notFound = ref(false)
const current = ref(0)
const related = ref<GoodsItem[]>([])
const reportOpen = ref(false)

const REASON: Record<CannotBuyReason, string> = {
  SELF: '这是你发布的商品', NOT_ON_SALE: '商品已不在售', OUT_OF_STOCK: '已经卖完了', BLOCKED: '卖家已将你拉黑', NOT_LOGIN: '登录后购买',
}
const buyText = computed(() => (g.value?.canBuy ? '立即购买' : REASON[g.value?.cannotBuyReason ?? 'NOT_ON_SALE']))
const free = computed(() => Number(g.value?.price) === 0)

onMounted(async () => {
  try {
    g.value = await goodsApi.detail(id)
    goodsApi.list({ categoryId: g.value.categoryId, size: 5 }).then((r) => (related.value = r.list.filter((x) => x.id !== id).slice(0, 4)))
  } catch (e) {
    if ((e as ApiError).code === 2001) notFound.value = true
  }
})

function requireLogin(): boolean {
  if (auth.loggedIn) return true
  router.push({ name: 'login', query: { redirect: route.fullPath } })
  return false
}

async function toggleFav() {
  if (!g.value || !requireLogin()) return
  const r = g.value.favorited ? await goodsApi.unfavorite(id) : await goodsApi.favorite(id)
  g.value.favorited = r.favorited
  g.value.favoriteCount = r.favoriteCount
  ElMessage.success(r.favorited ? '已收藏' : '已取消收藏')
}

function buy() {
  if (!g.value) return
  if (g.value.cannotBuyReason === 'NOT_LOGIN' || !auth.loggedIn) return requireLogin()
  if (g.value.canBuy) router.push({ name: 'checkout', params: { goodsId: id } })
}

async function chat() {
  if (!g.value || !requireLogin()) return
  const c = await chatApi.open(g.value.seller.id, id)
  router.push({ name: 'chat', params: { id: c.id } })
}

async function offShelf() {
  await ElMessageBox.confirm('下架后买家将看不到这件商品，之后可以在个人中心重新上架。', '下架商品', { confirmButtonText: '下架', cancelButtonText: '取消' })
  await goodsApi.offShelf(id)
  ElMessage.success('已下架')
  g.value = await goodsApi.detail(id)
}
</script>

<template>
  <main class="page">
    <div v-if="notFound" class="container"><div class="empty card">商品不存在或已被删除。<RouterLink to="/" class="link">回到首页</RouterLink></div></div>
    <div v-else-if="g" class="container stack">
      <nav class="subtle row" aria-label="面包屑">
        <RouterLink to="/">首页</RouterLink>
        <template v-for="c in g.categoryPath" :key="c"><AppIcon name="right" size="sm" /><span>{{ c }}</span></template>
      </nav>

      <section class="card card-pad detail">
        <div>
          <div class="gallery-main"><CoverImage :src="g.images[current]" :alt="g.title" :tint="2" /></div>
          <div v-if="g.images.length > 1" class="thumbs">
            <button v-for="(img, i) in g.images" :key="img" type="button" :class="{ active: i === current }" :aria-label="`图片 ${i + 1}`" @click="current = i">
              <CoverImage :src="img" />
            </button>
          </div>
        </div>

        <div class="stack">
          <div class="row" style="flex-wrap: wrap">
            <span v-if="g.tradeMode !== 1" class="tag tag-green"><AppIcon name="shield" size="sm" />支持担保</span>
            <span v-if="g.tradeMode !== 2" class="tag tag-blue">支持面交</span>
            <span class="tag">{{ CONDITION[g.conditionLevel] }}</span>
            <StatusTag v-if="g.status !== 2" :text="GOODS_STATUS[g.status]" :tone="GOODS_STATUS_TONE[g.status]" />
          </div>
          <h1 class="title">{{ g.title }}</h1>
          <div class="row" style="align-items: baseline; gap: 12px">
            <span class="price big-price"><small v-if="!free">¥</small>{{ priceText(g.price) }}</span>
            <span v-if="g.originalPrice && Number(g.originalPrice) > 0" class="strike">原价 ¥{{ priceText(g.originalPrice) }}</span>
          </div>
          <div class="row subtle"><AppIcon name="eye" size="sm" />{{ g.viewCount }} 浏览<span>·</span><AppIcon name="heart" size="sm" />{{ g.favoriteCount }} 收藏<span v-if="g.publishedAt">· {{ shortTime(g.publishedAt) }} 发布</span></div>
          <div v-if="g.isMine && g.auditRemark" class="alert alert-danger"><AppIcon name="alert" /><span>{{ g.auditRemark }}</span></div>
          <div class="divider" style="margin: 4px 0" />
          <dl class="kv">
            <dt>交易方式</dt><dd>{{ g.tradeMode === 1 ? '仅面交' : g.tradeMode === 2 ? '仅担保交易' : '面交 / 担保交易' }}<span v-if="g.tradeMode !== 1" class="subtle">（担保：付款到平台，确认收货后打款给卖家）</span></dd>
            <dt>面交地点</dt><dd><span class="row"><AppIcon name="pin" size="sm" />{{ g.campusName }}{{ g.location ? ` · ${g.location}` : '' }}</span></dd>
            <dt>库存</dt><dd>{{ g.stock }} 件</dd>
          </dl>
          <div class="actions desk">
            <template v-if="g.isMine">
              <RouterLink v-if="![4, 5, 7].includes(g.status)" class="btn btn-primary btn-lg" :to="`/publish/${g.id}`"><AppIcon name="edit" />编辑</RouterLink>
              <button v-if="[1, 2].includes(g.status)" class="btn btn-outline btn-lg" @click="offShelf">下架</button>
            </template>
            <template v-else>
              <button class="btn btn-buy btn-lg" :disabled="!g.canBuy && g.cannotBuyReason !== 'NOT_LOGIN'" @click="buy">{{ buyText }}</button>
              <button class="btn btn-outline btn-lg" @click="chat"><AppIcon name="message" />聊一聊</button>
              <button class="btn btn-outline btn-lg fav" :class="{ active: g.favorited }" :aria-pressed="g.favorited" @click="toggleFav"><AppIcon name="heart" />{{ g.favorited ? '已收藏' : '收藏' }}</button>
              <button class="btn btn-ghost btn-lg" @click="requireLogin() && (reportOpen = true)"><AppIcon name="flag" />举报</button>
            </template>
          </div>
        </div>
      </section>

      <div class="layout-main-aside">
        <section class="card card-pad stack">
          <h2 class="section-title" style="font-size: 17px">商品描述</h2>
          <p class="muted desc">{{ g.description }}</p>
          <div class="alert alert-warn"><AppIcon name="alert" /><span>请在平台内沟通和交易。私下加微信、转账给对方的交易平台无法保障。</span></div>
        </section>
        <aside class="card card-pad stack">
          <div class="seller-card">
            <UserAvatar size="lg" :name="g.seller.nickname" :src="g.seller.avatarUrl" />
            <div><b>{{ g.seller.nickname }}</b>
              <div><StatusTag class="mt-8" :text="`${CREDIT_LEVEL[g.seller.creditLevel!]} ${g.seller.creditScore}`" :tone="CREDIT_TONE[g.seller.creditLevel!]" /></div></div>
          </div>
          <div class="grid-2" style="gap: 8px; text-align: center">
            <div class="stat"><span class="v" style="font-size: 22px">{{ g.seller.goodRate ? `${g.seller.goodRate}%` : '—' }}</span><span class="l">好评率</span></div>
            <div class="stat"><span class="v" style="font-size: 22px">{{ g.seller.onSaleCount }}</span><span class="l">在售</span></div>
          </div>
          <RouterLink class="btn btn-outline btn-block" :to="`/users/${g.seller.id}`">查看 Ta 的主页</RouterLink>
        </aside>
      </div>

      <section v-if="related.length" class="stack" style="gap: 12px">
        <h2 class="section-title" style="font-size: 17px">同类商品</h2>
        <div class="goods-grid"><GoodsCard v-for="x in related" :key="x.id" :goods="x" /></div>
      </section>

      <div v-if="!g.isMine" class="mobile-bar">
        <button class="btn btn-outline fav" :class="{ active: g.favorited }" :aria-label="g.favorited ? '取消收藏' : '收藏'" @click="toggleFav"><AppIcon name="heart" /></button>
        <button class="btn btn-outline" @click="chat">聊一聊</button>
        <button class="btn btn-buy" :disabled="!g.canBuy && g.cannotBuyReason !== 'NOT_LOGIN'" @click="buy">{{ buyText }}</button>
      </div>
      <ReportDialog v-model="reportOpen" :target-type="1" :target-id="g.id" />
    </div>
    <div v-else class="container"><div v-loading="true" style="height: 320px" /></div>
  </main>
</template>

<style scoped>
.detail { display: grid; grid-template-columns: 1fr 1fr; gap: 32px; }
.gallery-main { border-radius: 16px; aspect-ratio: 1; overflow: hidden; }
.thumbs { display: flex; gap: 10px; margin-top: 12px; overflow-x: auto; }
.thumbs button { width: 64px; height: 64px; flex: none; border-radius: 12px; border: 2px solid transparent; cursor: pointer; padding: 0; overflow: hidden; background: none; }
.thumbs button.active { border-color: var(--primary); }
.title { font-size: 22px; }
.big-price { font-size: 34px; }
.kv { display: grid; grid-template-columns: 88px 1fr; gap: 10px 12px; font-size: 14px; margin: 0; }
.kv dt { color: var(--text-3); }
.kv dd { margin: 0; }
.desc { white-space: pre-wrap; }
.seller-card { display: flex; align-items: center; gap: 14px; }
.actions { display: flex; gap: 10px; flex-wrap: wrap; }
.fav.active { color: var(--danger); border-color: #FCA5A5; }
.fav.active :deep(svg) { fill: currentColor; }
.link { color: var(--primary); }
.mobile-bar { display: none; }
@media (max-width: 768px) {
  .detail { grid-template-columns: 1fr; gap: 16px; }
  .actions.desk { display: none; }
  .mobile-bar { display: flex; gap: 8px; position: fixed; left: 0; right: 0; bottom: 0; padding: 10px 16px calc(10px + env(safe-area-inset-bottom)); background: #fff; border-top: 1px solid var(--border); z-index: 40; }
  .mobile-bar .btn { flex: 1; }
  .page { padding-bottom: calc(61px + 16px + env(safe-area-inset-bottom)); }
}
</style>
