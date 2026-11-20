<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppIcon from '@/shared/components/AppIcon.vue'
import CoverImage from '@/shared/components/CoverImage.vue'
import { shortTime } from '@/shared/format'
import type { OrderDetail, ReviewTags } from '@/shared/types'
import { orderApi, reviewApi } from '../api'

/** 评价交易（接口文档 10.1 / 10.3）。标签按身份取：买家评卖家用 role=1，卖家评买家用 role=2。 */
const route = useRoute()
const router = useRouter()
const id = route.params.id as string
const o = ref<OrderDetail | null>(null)
const tags = ref<ReviewTags>({ '1': [], '2': [] })
const form = reactive({ rating: 1, tags: [] as string[], content: '' })
const saving = ref(false)

const myRoleKey = computed(() => (o.value?.myRole === 'BUYER' ? '1' : '2'))
const peerName = computed(() => (o.value ? (o.value.myRole === 'BUYER' ? o.value.seller.nickname : o.value.buyer.nickname) : ''))

onMounted(async () => {
  ;[o.value, tags.value] = await Promise.all([orderApi.detail(id), reviewApi.tags()])
  if (!o.value.actions.includes('REVIEW')) {
    ElMessage.info(o.value.review.mine ? '你已经评价过了' : '当前订单不能评价')
    router.replace(`/order/${id}`)
  }
})

function toggleTag(t: string) {
  const i = form.tags.indexOf(t)
  if (i >= 0) form.tags.splice(i, 1)
  else if (form.tags.length < 5) form.tags.push(t)
  else ElMessage.warning('最多选 5 个标签')
}

async function submit() {
  saving.value = true
  try {
    await reviewApi.submit(id, { rating: form.rating, tags: form.tags, content: form.content.trim() || undefined })
    ElMessage.success('评价已提交')
    router.replace(`/order/${id}`)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <main class="page">
    <div v-if="o" class="container wrap stack">
      <div class="alert alert-green"><AppIcon name="check" /><span>交易已完成。评价后可以帮助其他同学判断。</span></div>
      <section class="card">
        <div class="list-item">
          <span class="thumb item-thumb"><CoverImage :src="o.goods.cover" :alt="o.goods.title" /></span>
          <div style="flex: 1">{{ o.goods.title }}<div class="subtle">评价{{ o.myRole === 'BUYER' ? '卖家' : '买家' }}：{{ peerName }}<template v-if="o.review.deadline"> · {{ shortTime(o.review.deadline) }} 前可评价，到期未评默认好评</template></div></div>
        </div>
        <div class="card-pad stack" style="padding-top: 8px">
          <div class="field"><label>总体评价<span class="req">*</span></label>
            <div class="rate radio-group">
              <button type="button" class="radio-card" :class="{ active: form.rating === 1 }" @click="form.rating = 1"><AppIcon name="star" size="lg" />好评</button>
              <button type="button" class="radio-card mid" :class="{ active: form.rating === 2 }" @click="form.rating = 2"><AppIcon name="star" size="lg" />中评</button>
              <button type="button" class="radio-card bad" :class="{ active: form.rating === 3 }" @click="form.rating = 3"><AppIcon name="star" size="lg" />差评</button>
            </div></div>
          <div class="field"><label>印象标签（最多 5 个）</label>
            <div class="row" style="flex-wrap: wrap">
              <button v-for="t in tags[myRoleKey]" :key="t" type="button" class="chip" :class="{ active: form.tags.includes(t) }" :aria-pressed="form.tags.includes(t)" @click="toggleTag(t)">{{ t }}</button>
            </div></div>
          <div class="field"><label for="rv">评价内容</label>
            <el-input id="rv" v-model="form.content" type="textarea" :rows="4" maxlength="200" show-word-limit placeholder="说说这次交易的感受（选填）" /></div>
          <div class="alert alert-info"><AppIcon name="lock" /><span>双方都评价后或到期后评价才会公开，公开后不能修改。</span></div>
          <button class="btn btn-primary btn-lg btn-block" :disabled="saving" @click="submit">提交评价</button>
        </div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.wrap { max-width: 640px; margin: 0 auto; }
.item-thumb { overflow: hidden; }
.rate { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.rate .radio-card { flex-direction: column; justify-content: center; padding: 16px; gap: 6px; }
.rate .radio-card.bad.active { border-color: var(--danger); background: var(--danger-soft); color: var(--danger); }
.rate .radio-card.mid.active { border-color: var(--warning); background: var(--warning-soft); color: var(--warning); }
</style>
