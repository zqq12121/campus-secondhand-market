<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppIcon from '@/shared/components/AppIcon.vue'
import UserAvatar from '@/shared/components/UserAvatar.vue'
import StatusTag from '@/shared/components/StatusTag.vue'
import { CREDIT_LEVEL, CREDIT_TONE, RATING } from '@/shared/enums'
import { shortTime } from '@/shared/format'
import type { GoodsItem, ReviewItem, UserHome } from '@/shared/types'
import GoodsCard from '../components/GoodsCard.vue'
import ReportDialog from '../components/ReportDialog.vue'
import { chatApi, userApi } from '../api'
import { useAuthStore } from '../stores/auth'

/** 他人主页（接口文档 4.8–4.10） */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const id = route.params.id as string

const u = ref<UserHome | null>(null)
const tab = ref<'goods' | 'reviews'>('goods')
const goods = ref<GoodsItem[]>([])
const reviews = ref<ReviewItem[]>([])
const reviewTotal = ref(0)
const rating = ref<number | undefined>()
const reportOpen = ref(false)

async function loadReviews(r?: number) {
  rating.value = r
  const res = await userApi.reviews(id, { rating: r, page: 1, size: 50 })
  reviews.value = res.list
  reviewTotal.value = res.total
}

function needLogin(): boolean {
  if (auth.loggedIn) return false
  router.push({ name: 'login', query: { redirect: route.fullPath } })
  return true
}

async function chat() {
  if (needLogin()) return
  const c = await chatApi.open(id)
  router.push(`/chat/${c.id}`)
}

async function toggleBlock() {
  if (!u.value || needLogin()) return
  if (u.value.blocked) {
    await userApi.unblock(id)
  } else {
    await ElMessageBox.confirm('拉黑后对方不能给你发消息，也不能购买你的商品。', '拉黑', { type: 'warning', confirmButtonText: '拉黑', cancelButtonText: '取消' })
    await userApi.block(id)
  }
  u.value.blocked = !u.value.blocked
  ElMessage.success(u.value.blocked ? '已拉黑' : '已取消拉黑')
}

onMounted(async () => {
  if (auth.loggedIn && !auth.me) await auth.fetchMe()
  if (auth.me?.id === id) return router.replace('/me')
  u.value = await userApi.home(id)
  goods.value = (await userApi.goods(id, { page: 1, size: 50 })).list
  loadReviews()
})
</script>

<template>
  <main class="page">
    <div v-if="u" class="container stack">
      <section class="card" :class="{ inactive: u.status === 2 }">
        <div class="cover" />
        <div class="head">
          <UserAvatar size="lg" class="big-avatar" :name="u.nickname" :src="u.avatarUrl" />
          <div class="info">
            <div class="row"><h1 style="font-size: 20px">{{ u.nickname }}</h1>
              <StatusTag :text="`${CREDIT_LEVEL[u.creditLevel]} ${u.creditScore}`" :tone="CREDIT_TONE[u.creditLevel]" />
              <span v-if="u.badge" class="tag tag-green"><AppIcon name="star" size="sm" />信用徽章</span>
              <span v-if="u.status === 2" class="tag tag-red">账号已封禁</span></div>
            <p class="subtle">{{ u.college || '未填写学院' }} · {{ u.campusName }} · {{ u.joinedAt }} 加入</p>
            <p v-if="u.bio" class="muted mt-8">{{ u.bio }}</p>
          </div>
          <div class="nums">
            <div class="stat"><span class="v" style="font-size: 22px">{{ u.goodRate ? `${u.goodRate}%` : '—' }}</span><span class="l">好评率</span></div>
            <div class="stat"><span class="v" style="font-size: 22px">{{ u.soldCount }}</span><span class="l">成交</span></div>
            <div class="stat"><span class="v" style="font-size: 22px">{{ u.onSaleCount }}</span><span class="l">在售</span></div>
          </div>
          <div class="row">
            <button class="btn btn-primary" @click="chat"><AppIcon name="message" />私信</button>
            <button class="btn btn-outline" @click="toggleBlock">{{ u.blocked ? '取消拉黑' : '拉黑' }}</button>
            <button class="btn btn-ghost" aria-label="举报该用户" @click="needLogin() || (reportOpen = true)"><AppIcon name="flag" /></button>
          </div>
        </div>
      </section>

      <section class="card">
        <div class="tabs" style="padding: 0 16px" role="tablist">
          <button class="tab" :class="{ active: tab === 'goods' }" role="tab" @click="tab = 'goods'">在售商品<span class="count">{{ u.onSaleCount }}</span></button>
          <button class="tab" :class="{ active: tab === 'reviews' }" role="tab" @click="tab = 'reviews'">收到的评价<span class="count">{{ u.reviewCount }}</span></button>
        </div>
        <div v-if="tab === 'goods'">
          <div v-if="goods.length" class="goods-grid" style="padding: 16px"><GoodsCard v-for="g in goods" :key="g.id" :goods="g" /></div>
          <div v-else class="empty">暂无在售商品</div>
        </div>
        <div v-else>
          <div class="row" style="padding: 16px 24px 0; flex-wrap: wrap">
            <button class="chip" :class="{ active: rating == null }" @click="loadReviews()">全部</button>
            <button v-for="(t, k) in RATING" :key="k" class="chip" :class="{ active: rating === Number(k) }" @click="loadReviews(Number(k))">{{ t }}</button>
          </div>
          <div v-for="r in reviews" :key="r.id" class="review">
            <UserAvatar size="sm" :name="r.reviewer.nickname" :src="r.reviewer.avatarUrl" />
            <div class="stack" style="gap: 6px; flex: 1">
              <div class="between"><b class="rv-name">{{ r.reviewer.nickname }}
                <span class="tag" :class="r.rating === 1 ? 'tag-green' : r.rating === 2 ? 'tag-yellow' : 'tag-red'">{{ RATING[r.rating] }}</span>
                <span v-if="r.isDefault" class="subtle">（默认好评）</span></b><span class="subtle">{{ shortTime(r.createdAt) }}</span></div>
              <p>{{ r.content || '用户没有填写评价内容' }}</p>
              <p class="subtle">商品：{{ r.goodsTitle }}</p>
              <div v-if="r.tags.length" class="row" style="flex-wrap: wrap"><span v-for="t in r.tags" :key="t" class="tag">{{ t }}</span></div>
              <p v-if="r.reply" class="reply subtle">Ta 的解释：{{ r.reply }}</p>
            </div>
          </div>
          <div v-if="!reviews.length" class="empty">暂无评价</div>
        </div>
      </section>
      <ReportDialog v-model="reportOpen" :target-type="2" :target-id="id" />
    </div>
  </main>
</template>

<style scoped>
.inactive { opacity: .7; }
.cover { height: 120px; border-radius: 16px 16px 0 0; background: linear-gradient(120deg, #047857, #10B981); }
.head { padding: 0 24px 24px; display: flex; gap: 20px; align-items: flex-end; margin-top: -36px; flex-wrap: wrap; }
.big-avatar { border: 4px solid #fff; width: 88px; height: 88px; font-size: 32px; }
.info { flex: 1; min-width: 200px; }
.nums { display: flex; gap: 28px; }
.review { padding: 16px 24px; border-bottom: 1px solid var(--border); display: flex; gap: 12px; }
.review:last-child { border-bottom: 0; }
.rv-name { font-size: 14px; }
.reply { background: #F8FAFC; padding: 8px 12px; border-radius: 8px; }
@media (max-width: 768px) { .head { padding: 0 16px 16px; } .review { padding: 14px 16px; } }
</style>
