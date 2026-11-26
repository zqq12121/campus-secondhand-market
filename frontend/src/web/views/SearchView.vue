<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CONDITION } from '@/shared/enums'
import type { GoodsItem, GoodsQuery } from '@/shared/types'
import GoodsCard from '../components/GoodsCard.vue'
import { goodsApi } from '../api'
import { useMetaStore } from '../stores/meta'

/** 搜索结果（接口文档 5.1 / 5.2）。筛选条件全部放在 URL 上，刷新和分享都能还原。 */
const HISTORY_KEY = 'ct_search_history'
const route = useRoute()
const router = useRouter()
const meta = useMetaStore()

const list = ref<GoodsItem[]>([])
const total = ref(0)
const loading = ref(false)
const hotWords = ref<string[]>([])
const history = ref<string[]>(readHistory())
const price = reactive({ min: '', max: '' })

const q = computed(() => route.query as Record<string, string | string[] | undefined>)
const str = (k: string) => (typeof q.value[k] === 'string' ? (q.value[k] as string) : '')
const conditions = computed(() => ([] as string[]).concat(q.value.conditionLevel ?? []).map(Number))
const page = computed(() => Number(str('page')) || 1)

/** 选中的一级分类（选了二级时找它的父级） */
const topCategory = computed(() => {
  const id = str('categoryId')
  return meta.categories.find((c) => c.id === id || c.children?.some((x) => x.id === id))
})

function readHistory(): string[] {
  try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]') } catch { return [] }
}
function saveHistory(k: string) {
  history.value = [k, ...history.value.filter((x) => x !== k)].slice(0, 10)
  try { localStorage.setItem(HISTORY_KEY, JSON.stringify(history.value)) } catch { /* 隐私模式下忽略 */ }
}
function clearHistory() {
  history.value = []
  try { localStorage.removeItem(HISTORY_KEY) } catch { /* 忽略 */ }
}

function setQuery(patch: Record<string, string | string[] | undefined>) {
  const next: Record<string, unknown> = { ...route.query, ...patch, page: undefined }
  Object.keys(next).forEach((k) => (next[k] === undefined || next[k] === '') && delete next[k])
  router.push({ query: next as Record<string, string | string[]> })
}
function toggleCondition(v: number) {
  const s = new Set(conditions.value)
  s.has(v) ? s.delete(v) : s.add(v)
  setQuery({ conditionLevel: [...s].map(String) })
}
function applyPrice() {
  setQuery({ priceMin: price.min || undefined, priceMax: price.max || undefined })
}

async function load() {
  price.min = str('priceMin')
  price.max = str('priceMax')
  const keyword = str('keyword')
  if (keyword) saveHistory(keyword)
  const query: GoodsQuery = {
    keyword: keyword || undefined,
    categoryId: str('categoryId') || undefined,
    campusId: str('campusId') || undefined,
    priceMin: str('priceMin') || undefined,
    priceMax: str('priceMax') || undefined,
    conditionLevel: conditions.value.length ? conditions.value : undefined,
    tradeMode: str('tradeMode') ? Number(str('tradeMode')) : undefined,
    free: str('free') === '1' || undefined,
    sort: (str('sort') as GoodsQuery['sort']) || 'DEFAULT',
    page: page.value,
    size: 20,
  }
  loading.value = true
  try {
    const r = await goodsApi.list(query)
    list.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

watch(() => route.fullPath, () => route.name === 'search' && load())
onMounted(() => {
  meta.load()
  goodsApi.hotWords().then((w) => (hotWords.value = w))
  load()
})

const SORTS = [['DEFAULT', '综合'], ['NEWEST', '最新'], ['PRICE_ASC', '价格 ↑'], ['PRICE_DESC', '价格 ↓']] as const
</script>

<template>
  <main class="page">
    <div class="container stack">
      <div class="hot subtle">
        热搜：<RouterLink v-for="w in hotWords" :key="w" class="tag" :to="{ name: 'search', query: { keyword: w } }">{{ w }}</RouterLink>
        <template v-if="history.length">
          <span class="sep">最近搜索：</span>
          <RouterLink v-for="w in history" :key="`h-${w}`" class="tag" :to="{ name: 'search', query: { keyword: w } }">{{ w }}</RouterLink>
          <button class="btn btn-ghost btn-sm" type="button" @click="clearHistory">清空</button>
        </template>
      </div>

      <section class="card filter-box" aria-label="筛选条件">
        <div class="filter-row"><b>分类</b><div class="opts">
          <button class="chip" :class="{ active: !str('categoryId') }" @click="setQuery({ categoryId: undefined })">全部</button>
          <button v-for="c in meta.categories" :key="c.id" class="chip" :class="{ active: topCategory?.id === c.id }" @click="setQuery({ categoryId: c.id })">{{ c.name }}</button>
        </div></div>
        <div v-if="topCategory?.children?.length" class="filter-row"><b>子分类</b><div class="opts">
          <button class="chip" :class="{ active: str('categoryId') === topCategory.id }" @click="setQuery({ categoryId: topCategory.id })">不限</button>
          <button v-for="c in topCategory.children" :key="c.id" class="chip" :class="{ active: str('categoryId') === c.id }" @click="setQuery({ categoryId: c.id })">{{ c.name }}</button>
        </div></div>
        <div class="filter-row"><b>校区</b><div class="opts">
          <button class="chip" :class="{ active: !str('campusId') }" @click="setQuery({ campusId: undefined })">不限</button>
          <button v-for="c in meta.campuses" :key="c.id" class="chip" :class="{ active: str('campusId') === c.id }" @click="setQuery({ campusId: c.id })">{{ c.name }}</button>
        </div></div>
        <div class="filter-row"><b>成色</b><div class="opts">
          <button class="chip" :class="{ active: !conditions.length }" @click="setQuery({ conditionLevel: undefined })">不限</button>
          <button v-for="(t, k) in CONDITION" :key="k" class="chip" :class="{ active: conditions.includes(Number(k)) }" :aria-pressed="conditions.includes(Number(k))" @click="toggleCondition(Number(k))">{{ t }}</button>
        </div></div>
        <div class="filter-row"><b>交易方式</b><div class="opts">
          <button class="chip" :class="{ active: !str('tradeMode') && !str('free') }" @click="setQuery({ tradeMode: undefined, free: undefined })">不限</button>
          <button class="chip" :class="{ active: str('tradeMode') === '1' }" @click="setQuery({ tradeMode: '1', free: undefined })">支持面交</button>
          <button v-if="meta.config.guaranteeEnabled" class="chip" :class="{ active: str('tradeMode') === '2' }" @click="setQuery({ tradeMode: '2', free: undefined })">支持担保</button>
          <button class="chip" :class="{ active: str('free') === '1' }" @click="setQuery({ free: '1', tradeMode: undefined })">免费送</button>
        </div></div>
        <div class="filter-row"><b>价格</b>
          <form class="price-range" @submit.prevent="applyPrice">
            <input v-model="price.min" class="input" inputmode="decimal" placeholder="¥ 最低" aria-label="最低价" /> —
            <input v-model="price.max" class="input" inputmode="decimal" placeholder="¥ 最高" aria-label="最高价" />
            <button class="btn btn-outline btn-sm" type="submit">确定</button>
          </form>
        </div>
      </section>

      <div class="between">
        <p class="muted">
          <template v-if="str('keyword')">「<b class="ink">{{ str('keyword') }}</b>」</template>共找到 <b class="num ink">{{ total }}</b> 件商品
        </p>
        <div class="row">
          <button v-for="[v, t] in SORTS" :key="v" class="chip" :class="{ active: (str('sort') || 'DEFAULT') === v }" @click="setQuery({ sort: v === 'DEFAULT' ? undefined : v })">{{ t }}</button>
        </div>
      </div>

      <div v-loading="loading">
        <div v-if="list.length" class="goods-grid"><GoodsCard v-for="g in list" :key="g.id" :goods="g" /></div>
        <div v-else-if="!loading" class="empty card">没有找到相关商品，换个关键词试试，或者<RouterLink to="/wants" class="ink-link">发个求购</RouterLink></div>
      </div>
      <el-pagination v-if="total > 20" background layout="prev, pager, next" :total="total" :page-size="20" :current-page="page"
        @current-change="(p: number) => router.push({ query: { ...route.query, page: String(p) } })" />
    </div>
  </main>
</template>

<style scoped>
.hot { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.sep { margin-left: 12px; }
.filter-box { padding: 8px 24px; }
.filter-row { display: flex; gap: 16px; padding: 12px 0; border-bottom: 1px dashed var(--border); align-items: flex-start; }
.filter-row:last-child { border-bottom: 0; }
.filter-row > b { width: 72px; flex: none; font-size: 14px; color: var(--text-2); padding-top: 6px; }
.opts { display: flex; flex-wrap: wrap; gap: 8px; }
.opts .chip { height: 30px; font-size: 13px; }
.price-range { display: flex; align-items: center; gap: 6px; }
.price-range .input { width: 90px; height: 30px; font-size: 13px; }
.ink { color: var(--text); }
.ink-link { color: var(--primary); }
@media (max-width: 768px) {
  .filter-box { padding: 4px 16px; }
  .filter-row { flex-direction: column; gap: 8px; }
  .opts { flex-wrap: nowrap; overflow-x: auto; width: 100%; }
}
</style>
