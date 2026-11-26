<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AppIcon from '@/shared/components/AppIcon.vue'
import ImageUploader from '@/shared/components/ImageUploader.vue'
import { CONDITION } from '@/shared/enums'
import type { GoodsForm } from '@/shared/types'
import { commonApi, goodsApi } from '../api'
import { LISTING_DRAFT_KEY, type ListingDraft } from '../assistant/api'
import { useAuthStore } from '../stores/auth'
import { useMetaStore } from '../stores/meta'

/** 发布 / 编辑商品（接口文档 5.4 / 5.5）。前端校验与接口约束一致，最终以后端为准。 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const meta = useMetaStore()
const editId = computed(() => (route.params.id as string) || '')

const form = reactive({
  images: [] as string[], title: '', description: '', parentCategoryId: '', categoryId: '',
  price: '', originalPrice: '', conditionLevel: 2, tradeMode: 3, campusId: '', location: '', stock: 1,
})
const saving = ref(false)
const errors = reactive<Record<string, string>>({})

const subCategories = computed(() => meta.categories.find((c) => c.id === form.parentCategoryId)?.children ?? [])
const isFree = computed(() => form.price !== '' && Number(form.price) === 0)
const credit = computed(() => auth.me?.creditScore ?? 80)
const escrowDisabled = computed(() => isFree.value || credit.value < 60)
const guaranteeEnabled = computed(() => meta.config.guaranteeEnabled)
const upload = (f: File) => commonApi.upload(f, 'GOODS')

watch(isFree, (free) => { if (free) form.tradeMode = 1 })
// 担保未开放：编辑旧商品或助手草稿时也统一改成仅面交
watch(() => [form.tradeMode, guaranteeEnabled.value], () => { if (!guaranteeEnabled.value) form.tradeMode = 1 })
watch(() => form.parentCategoryId, () => {
  if (!subCategories.value.some((c) => c.id === form.categoryId)) form.categoryId = subCategories.value[0]?.id ?? ''
})

onMounted(async () => {
  await Promise.all([meta.load(), auth.me ? null : auth.fetchMe()])
  form.campusId = auth.me?.campusId ?? meta.campuses[0]?.id ?? ''
  form.parentCategoryId = meta.categories[0]?.id ?? ''
  if (!editId.value && route.query.from === 'assistant') fillFromAssistant()
  if (editId.value) {
    const g = await goodsApi.detail(editId.value)
    if (!g.isMine) { ElMessage.error('只能编辑自己的商品'); return router.replace('/') }
    Object.assign(form, {
      images: g.images, title: g.title, description: g.description, categoryId: g.categoryId,
      price: g.price, originalPrice: g.originalPrice ?? '', conditionLevel: g.conditionLevel, tradeMode: g.tradeMode,
      campusId: g.campusId, location: g.location ?? '', stock: g.stock,
      parentCategoryId: meta.categories.find((c) => c.children?.some((x) => x.id === g.categoryId))?.id ?? '',
    })
  }
})

/** 校园二手助手生成并经用户确认的草稿：只预填，仍由用户检查后自己提交 */
function fillFromAssistant() {
  let draft: ListingDraft | null = null
  try {
    draft = JSON.parse(sessionStorage.getItem(LISTING_DRAFT_KEY) ?? 'null')
    sessionStorage.removeItem(LISTING_DRAFT_KEY)
  } catch { /* 草稿读不到就按空表单 */ }
  if (!draft) return
  Object.assign(form, {
    images: draft.images, title: draft.title, description: draft.description, categoryId: draft.categoryId,
    conditionLevel: draft.conditionLevel, price: String(draft.price),
    parentCategoryId: meta.categories.find((c) => c.children?.some((x) => x.id === draft!.categoryId))?.id ?? form.parentCategoryId,
  })
  ElMessage.info('已填入助手生成的草稿，请检查图片和信息后再发布')
}

function validate(): boolean {
  Object.keys(errors).forEach((k) => delete errors[k])
  if (!form.images.length) errors.images = '至少上传 1 张图片'
  if (!form.title.trim()) errors.title = '请填写标题'
  if (!form.description.trim()) errors.description = '请填写描述'
  if (!form.categoryId) errors.categoryId = '请选择分类'
  const p = Number(form.price)
  if (form.price === '' || Number.isNaN(p) || !/^\d+(\.\d{1,2})?$/.test(form.price) || (p !== 0 && (p < 0.01 || p > 99999))) {
    errors.price = '价格为 0（免费送）或 0.01–99999，最多两位小数'
  }
  if (form.originalPrice && Number(form.originalPrice) < p) errors.originalPrice = '原价不能低于售价'
  if (form.stock < 1 || form.stock > 99) errors.stock = '数量为 1–99'
  return Object.keys(errors).length === 0
}

async function save(draft: boolean) {
  if (!draft && !validate()) return ElMessage.warning('请检查标红的字段')
  if (draft && !form.title.trim()) return ElMessage.warning('存草稿至少要填写标题')
  const body: GoodsForm = {
    title: form.title.trim(), description: form.description.trim(), images: form.images, categoryId: form.categoryId,
    price: form.price === '' ? '0' : Number(form.price).toFixed(2),
    originalPrice: form.originalPrice ? Number(form.originalPrice).toFixed(2) : null,
    conditionLevel: form.conditionLevel, tradeMode: form.tradeMode, campusId: form.campusId,
    location: form.location.trim() || undefined, stock: form.stock, draft,
  }
  saving.value = true
  try {
    const r = editId.value ? await goodsApi.update(editId.value, body) : await goodsApi.create(body)
    if (draft) ElMessage.success('已存入草稿箱')
    else if (r.status === 2) ElMessage.success('发布成功，已上架')
    else ElMessage.warning(r.hitWords.length ? `内容含「${r.hitWords.join('、')}」，已转人工审核` : '已提交，审核通过后上架')
    router.push(r.status === 2 && !draft ? `/goods/${r.id}` : { name: 'me', query: { tab: draft ? '0' : '1' } })
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <main class="page">
    <div class="container form-wrap stack">
      <div class="between">
        <h1 class="section-title">{{ editId ? '编辑商品' : '发布闲置' }}</h1>
        <span class="subtle">在售上限 50 件 · 每天最多发布 {{ credit < 60 ? 3 : 20 }} 件</span>
      </div>

      <form class="card" @submit.prevent="save(false)">
        <div class="form-section">
          <h3>商品图片 <span class="subtle fw4">1–9 张，单张不超过 5MB，第一张为封面</span></h3>
          <ImageUploader v-model="form.images" :uploader="upload" :max="9" cover-label />
          <span v-if="errors.images" class="error">{{ errors.images }}</span>
        </div>

        <div class="form-section">
          <h3>基本信息</h3>
          <div class="field"><label for="p-title">标题<span class="req">*</span></label>
            <el-input id="p-title" v-model="form.title" maxlength="30" show-word-limit placeholder="品牌 + 型号 + 卖点" />
            <span v-if="errors.title" class="error">{{ errors.title }}</span></div>
          <div class="field"><label for="p-desc">描述<span class="req">*</span></label>
            <el-input id="p-desc" v-model="form.description" type="textarea" :rows="5" maxlength="1000" show-word-limit placeholder="说说购买时间、使用情况、有无瑕疵、配件、转手原因…" />
            <span v-if="errors.description" class="error">{{ errors.description }}</span></div>
          <div class="grid-2">
            <div class="field"><label for="p-cat1">分类<span class="req">*</span></label>
              <select id="p-cat1" v-model="form.parentCategoryId" class="select"><option v-for="c in meta.categories" :key="c.id" :value="c.id">{{ c.name }}</option></select></div>
            <div class="field"><label for="p-cat2">子分类<span class="req">*</span></label>
              <select id="p-cat2" v-model="form.categoryId" class="select"><option v-for="c in subCategories" :key="c.id" :value="c.id">{{ c.name }}</option></select>
              <span v-if="errors.categoryId" class="error">{{ errors.categoryId }}</span></div>
          </div>
          <div class="field"><label>成色<span class="req">*</span></label>
            <div class="radio-group">
              <button v-for="(t, k) in CONDITION" :key="k" type="button" class="radio-card" :class="{ active: form.conditionLevel === Number(k) }" @click="form.conditionLevel = Number(k)">{{ t }}</button>
            </div></div>
        </div>

        <div class="form-section">
          <h3>价格与库存</h3>
          <div class="grid-2">
            <div class="field"><label for="p-price">售价（元）<span class="req">*</span></label>
              <div class="price-input"><span>¥</span><input id="p-price" v-model.trim="form.price" class="input" inputmode="decimal" :class="{ 'is-error': errors.price }" /></div>
              <span v-if="errors.price" class="error">{{ errors.price }}</span>
              <span v-else class="hint">填 0 即为「免费送」，只能面交</span></div>
            <div class="field"><label for="p-orig">原价（元）</label>
              <div class="price-input"><span>¥</span><input id="p-orig" v-model.trim="form.originalPrice" class="input" inputmode="decimal" /></div>
              <span v-if="errors.originalPrice" class="error">{{ errors.originalPrice }}</span></div>
          </div>
          <div class="field" style="max-width: 200px"><label for="p-stock">数量</label>
            <el-input-number id="p-stock" v-model="form.stock" :min="1" :max="99" />
            <span v-if="errors.stock" class="error">{{ errors.stock }}</span></div>
        </div>

        <div class="form-section">
          <h3>交易方式</h3>
          <div class="radio-group">
            <button type="button" class="radio-card" :class="{ active: form.tradeMode === 1 }" @click="form.tradeMode = 1"><AppIcon name="pin" />仅面交</button>
            <button v-if="guaranteeEnabled" type="button" class="radio-card" :class="{ active: form.tradeMode === 2 }" :disabled="escrowDisabled" @click="form.tradeMode = 2"><AppIcon name="shield" />仅担保</button>
            <button v-if="guaranteeEnabled" type="button" class="radio-card" :class="{ active: form.tradeMode === 3 }" :disabled="escrowDisabled" @click="form.tradeMode = 3"><AppIcon name="check" />面交和担保都支持</button>
          </div>
          <div class="grid-2">
            <div class="field"><label for="p-campus">面交校区</label>
              <select id="p-campus" v-model="form.campusId" class="select"><option v-for="c in meta.campuses" :key="c.id" :value="c.id">{{ c.name }}</option></select></div>
            <div class="field"><label for="p-loc">面交地点</label><el-input id="p-loc" v-model="form.location" maxlength="64" placeholder="如：图书馆东门" /></div>
          </div>
          <div v-if="!guaranteeEnabled" class="alert alert-info"><AppIcon name="pin" /><span>平台目前只支持当面交易，担保交易暂未开放。</span></div>
          <div v-else-if="credit < 60" class="alert alert-warn"><AppIcon name="alert" /><span>你的信用分为 {{ credit }}，低于 60 不能使用担保交易。</span></div>
          <div v-else class="alert alert-info"><AppIcon name="shield" /><span>担保交易：买家付款到平台，你交货、买家确认收货后，钱进入你的平台余额。</span></div>
        </div>

        <div class="form-foot">
          <button type="button" class="btn btn-outline" :disabled="saving" @click="save(true)">存草稿</button>
          <button type="submit" class="btn btn-primary" :disabled="saving">{{ saving ? '提交中…' : editId ? '保存修改' : '提交发布' }}</button>
        </div>
      </form>
    </div>
  </main>
</template>

<style scoped>
.form-wrap { max-width: 820px; margin: 0 auto; }
.form-section { padding: 24px; border-bottom: 1px solid var(--border); display: flex; flex-direction: column; gap: 18px; }
.form-section h3 { font-size: 16px; }
.fw4 { font-weight: 400; }
.price-input { position: relative; }
.price-input span { position: absolute; left: 14px; top: 10px; color: var(--text-2); font-family: var(--font-num); }
.price-input .input { padding-left: 32px; font-family: var(--font-num); }
.radio-card[disabled] { opacity: .45; cursor: not-allowed; }
.form-foot { position: sticky; bottom: 0; background: #fff; border-top: 1px solid var(--border); padding: 16px 24px; display: flex; justify-content: flex-end; gap: 10px; border-radius: 0 0 16px 16px; }
@media (max-width: 768px) { .form-section { padding: 16px; } .form-foot { bottom: 64px; padding: 12px 16px; } .form-foot .btn { flex: 1; } }
</style>
