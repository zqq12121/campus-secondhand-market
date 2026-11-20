<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { shortTime } from '@/shared/format'
import type { GoodsItem, HomeData } from '@/shared/types'
import GoodsCard from '../components/GoodsCard.vue'
import { commonApi, goodsApi } from '../api'
import { useMetaStore } from '../stores/meta'

const meta = useMetaStore()
const home = ref<HomeData | null>(null)
const goods = ref<GoodsItem[]>([])
const campusId = ref<string>('')
const page = ref(1)
const total = ref(0)
const loading = ref(false)

/** 一级分类图标：后端 iconUrl 为空时按名称取本地图标 */
const CAT_ICON: Record<string, [string, number]> = {
  教材书籍: ['book', 1], 数码电子: ['phone', 2], 生活用品: ['cup', 3], 服饰鞋包: ['shirt', 5], 运动出行: ['bike', 6],
}

async function loadGoods(reset = false) {
  if (reset) { page.value = 1; goods.value = [] }
  loading.value = true
  try {
    const r = await goodsApi.list({ sort: 'NEWEST', campusId: campusId.value || undefined, page: page.value, size: 20 })
    goods.value.push(...r.list)
    total.value = r.total
    page.value++
  } finally {
    loading.value = false
  }
}

function pickCampus(id: string) {
  campusId.value = id
  loadGoods(true)
}

onMounted(() => {
  meta.load()
  commonApi.home().then((h) => (home.value = h))
  loadGoods(true)
})
</script>

<template>
  <main class="page">
    <div class="container stack">
      <section class="hero">
        <RouterLink v-if="home?.banners[0]" class="banner" :to="home.banners[0].linkUrl || '/'"
          :style="home.banners[0].imageUrl ? { backgroundImage: `linear-gradient(120deg, rgba(4,120,87,.85), rgba(16,185,129,.6)), url(${home.banners[0].imageUrl})` } : undefined">
          <h1>{{ home.banners[0].title }}</h1>
          <p>只有 @wu.edu.cn 认证同学才能交易，面交或担保，放心买卖。</p>
          <span class="btn banner-btn">去看看</span>
        </RouterLink>
        <div v-else class="banner">
          <h1>学长学姐的好物，在校园里传下去</h1>
          <p>只有 @wu.edu.cn 认证同学才能交易，面交或担保，放心买卖。</p>
          <RouterLink class="btn banner-btn" to="/publish">发布我的闲置</RouterLink>
        </div>
        <div class="quick">
          <RouterLink to="/wants"><span class="ic tint-3"><AppIcon name="megaphone" /></span><span><b>求购广场</b><br /><span class="subtle">发布需求，等人来卖</span></span></RouterLink>
          <RouterLink :to="{ name: 'search', query: { tradeMode: '2' } }"><span class="ic tint-1"><AppIcon name="shield" /></span><span><b>担保交易</b><br /><span class="subtle">确认收货后才打款</span></span></RouterLink>
          <RouterLink :to="{ name: 'search', query: { free: '1' } }"><span class="ic tint-5"><AppIcon name="gift" /></span><span><b>免费送</b><br /><span class="subtle">自提即可带走</span></span></RouterLink>
        </div>
      </section>

      <div v-if="home?.notices.length" class="notice-bar card">
        <AppIcon name="bell" size="sm" />
        <RouterLink v-for="n in home.notices.slice(0, 2)" :key="n.id" :to="`/announcements/${n.id}`" class="notice-link">
          {{ n.title }}<span class="subtle"> · {{ shortTime(n.createdAt) }}</span>
        </RouterLink>
      </div>

      <section class="cats" aria-label="商品分类">
        <RouterLink v-for="c in home?.categories ?? []" :key="c.id" class="cat" :to="{ name: 'search', query: { categoryId: c.id } }">
          <span class="ic" :class="`tint-${CAT_ICON[c.name]?.[1] ?? 4}`">
            <img v-if="c.iconUrl" :src="c.iconUrl" alt="" class="cat-img" />
            <AppIcon v-else :name="CAT_ICON[c.name]?.[0] ?? 'grid'" size="lg" />
          </span>{{ c.name }}
        </RouterLink>
      </section>

      <section class="stack" style="gap: 12px">
        <div class="between">
          <h2 class="section-title">最新发布</h2>
          <div class="filters">
            <button class="chip" :class="{ active: !campusId }" @click="pickCampus('')">全部校区</button>
            <button v-for="c in meta.campuses" :key="c.id" class="chip" :class="{ active: campusId === c.id }" @click="pickCampus(c.id)">{{ c.name }}</button>
          </div>
        </div>
        <div v-if="goods.length" class="goods-grid"><GoodsCard v-for="g in goods" :key="g.id" :goods="g" /></div>
        <div v-else-if="!loading" class="empty card">还没有商品，<RouterLink to="/publish" style="color: var(--primary)">发布第一件闲置</RouterLink></div>
        <div style="text-align: center">
          <button v-if="goods.length < total" class="btn btn-outline" :disabled="loading" @click="loadGoods()">{{ loading ? '加载中…' : '加载更多' }}</button>
          <span v-else-if="goods.length" class="subtle">没有更多了</span>
        </div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.hero { display: grid; grid-template-columns: 1fr 300px; gap: 16px; }
.banner { border-radius: 20px; padding: 32px; background: linear-gradient(120deg, #047857, #10B981); background-size: cover; background-position: center; color: #fff; display: flex; flex-direction: column; justify-content: center; gap: 12px; min-height: 200px; }
.banner h1 { font-size: 30px; text-wrap: balance; }
.banner p { opacity: .92; }
.banner-btn { background: #fff; color: var(--primary); align-self: flex-start; }
.quick { display: flex; flex-direction: column; gap: 12px; }
.quick a { flex: 1; display: flex; align-items: center; gap: 12px; padding: 16px; border-radius: 16px; background: #fff; border: 1px solid var(--border); transition: border-color .15s; }
.quick a:hover { border-color: var(--primary); }
.quick .ic { width: 44px; height: 44px; border-radius: 12px; display: grid; place-items: center; flex: none; }
.notice-bar { display: flex; align-items: center; gap: 16px; padding: 10px 16px; color: var(--text-2); flex-wrap: wrap; }
.notice-link:hover { color: var(--primary); }
.cats { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.cat { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 16px 8px; border-radius: 16px; background: #fff; border: 1px solid var(--border); font-size: 14px; transition: border-color .15s, color .15s; }
.cat:hover { border-color: var(--primary); color: var(--primary); }
.cat .ic { width: 48px; height: 48px; border-radius: 14px; display: grid; place-items: center; }
.cat-img { width: 28px; height: 28px; object-fit: contain; }
.filters { display: flex; gap: 8px; flex-wrap: wrap; }
@media (max-width: 768px) {
  .hero { grid-template-columns: 1fr; }
  .banner { padding: 24px; min-height: 0; }
  .banner h1 { font-size: 22px; }
  .quick { flex-direction: row; }
  .quick a { padding: 12px 8px; font-size: 13px; flex-direction: column; text-align: center; gap: 6px; }
  .quick .ic { width: 36px; height: 36px; }
  .quick .subtle { display: none; }
  .cats { grid-template-columns: repeat(3, 1fr); gap: 8px; }
}
</style>
