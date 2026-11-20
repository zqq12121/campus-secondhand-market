<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppIcon from '@/shared/components/AppIcon.vue'
import UserAvatar from '@/shared/components/UserAvatar.vue'
import { remaining } from '@/shared/format'
import type { WantForm, WantItem } from '@/shared/types'
import ReportDialog from '../components/ReportDialog.vue'
import { chatApi, wantApi } from '../api'
import { useAuthStore } from '../stores/auth'
import { useMetaStore } from '../stores/meta'

/** 求购广场（接口文档第 6 章）。「我有」= 发起会话（带 wantId），到聊天里发自己的商品卡片。 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const meta = useMetaStore()

const list = ref<WantItem[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const filter = reactive({ categoryId: '', campusId: '', keyword: '' })
const dialog = ref(false)
const saving = ref(false)
const form = reactive({ title: '', description: '', parentId: '', categoryId: '', campusId: '', priceMin: '', priceMax: '', validDays: 7 as WantForm['validDays'] })
const report = reactive({ open: false, id: '' })

const subCats = computed(() => meta.categories.find((c) => c.id === form.parentId)?.children ?? [])

async function load(p = 1) {
  page.value = p
  loading.value = true
  try {
    const r = await wantApi.list({ ...filter, categoryId: filter.categoryId || undefined, campusId: filter.campusId || undefined, keyword: filter.keyword || undefined, page: p, size: 20 })
    list.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

function needLogin(): boolean {
  if (auth.loggedIn) return false
  router.push({ name: 'login', query: { redirect: route.fullPath } })
  return true
}

function openCreate() {
  if (needLogin()) return
  form.parentId ||= meta.categories[0]?.id ?? ''
  form.categoryId ||= subCats.value[0]?.id ?? ''
  form.campusId ||= auth.me?.campusId ?? meta.campuses[0]?.id ?? ''
  dialog.value = true
}

async function submit() {
  if (!form.title.trim() || !form.categoryId) return ElMessage.warning('请填写想要什么并选择分类')
  if (form.priceMin && form.priceMax && Number(form.priceMin) > Number(form.priceMax)) return ElMessage.warning('最低预算不能高于最高预算')
  saving.value = true
  try {
    await wantApi.create({
      title: form.title.trim(), description: form.description.trim() || undefined, categoryId: form.categoryId, campusId: form.campusId,
      priceMin: form.priceMin ? Number(form.priceMin).toFixed(2) : null, priceMax: form.priceMax ? Number(form.priceMax).toFixed(2) : null,
      validDays: form.validDays,
    })
    ElMessage.success('求购已发布，有匹配的商品会通知你')
    dialog.value = false
    Object.assign(form, { title: '', description: '', priceMin: '', priceMax: '' })
    load()
  } finally {
    saving.value = false
  }
}

async function respond(w: WantItem) {
  if (needLogin()) return
  const c = await chatApi.open(w.user.id, null, w.id)
  router.push({ name: 'chat', params: { id: c.id }, query: { want: w.title } })
}

async function close(w: WantItem) {
  await wantApi.close(w.id)
  ElMessage.success('已标记为已解决')
  load(page.value)
}

async function remove(w: WantItem) {
  await ElMessageBox.confirm('删除后无法恢复。', '删除求购', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' })
  await wantApi.remove(w.id)
  load(page.value)
}

const budget = (w: WantItem) => (w.priceMin || w.priceMax ? `¥${w.priceMin ? Number(w.priceMin) : 0} – ${w.priceMax ? Number(w.priceMax) : '不限'}` : '面议')

onMounted(async () => {
  await meta.load()
  load()
})
</script>

<template>
  <main class="page">
    <div class="container stack">
      <div class="between">
        <div><h1 class="section-title">求购广场</h1><p class="subtle">找不到想要的？发个求购，有货的同学会来找你</p></div>
        <button class="btn btn-primary" @click="openCreate"><AppIcon name="plus" />发布求购</button>
      </div>

      <div class="row" style="flex-wrap: wrap">
        <button class="chip" :class="{ active: !filter.categoryId }" @click="filter.categoryId = ''; load()">全部</button>
        <button v-for="c in meta.categories" :key="c.id" class="chip" :class="{ active: filter.categoryId === c.id }" @click="filter.categoryId = c.id; load()">{{ c.name }}</button>
        <select v-model="filter.campusId" class="select campus" aria-label="按校区筛选" @change="load()">
          <option value="">全部校区</option><option v-for="c in meta.campuses" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
      </div>

      <div v-loading="loading" class="want-grid">
        <article v-for="w in list" :key="w.id" class="card want">
          <div class="between">
            <span class="tag" :class="w.isMine || w.user.id === auth.me?.id ? 'tag-blue' : 'tag-green'">{{ w.isMine || w.user.id === auth.me?.id ? '我发布的' : '进行中' }}</span>
            <span class="subtle">剩 {{ remaining(w.expireAt) }}</span>
          </div>
          <h3>{{ w.title }}</h3>
          <p v-if="w.description" class="desc">{{ w.description }}</p>
          <div class="row"><span class="subtle">预算</span><span class="budget">{{ budget(w) }}</span><span class="tag">{{ w.categoryName }}</span></div>
          <div class="foot">
            <RouterLink class="row subtle" :to="`/users/${w.user.id}`"><UserAvatar size="sm" :name="w.user.nickname" :src="w.user.avatarUrl" />{{ w.user.nickname }} · {{ w.campusName }}</RouterLink>
            <div v-if="w.user.id === auth.me?.id" class="row">
              <button class="btn btn-outline btn-sm" @click="remove(w)">删除</button>
              <button class="btn btn-primary btn-sm" @click="close(w)">已解决</button>
            </div>
            <div v-else class="row">
              <button class="btn btn-ghost btn-sm" :aria-label="`举报求购：${w.title}`" @click="needLogin() || Object.assign(report, { open: true, id: w.id })"><AppIcon name="flag" size="sm" /></button>
              <button class="btn btn-primary btn-sm" @click="respond(w)">我有</button>
            </div>
          </div>
        </article>
      </div>
      <div v-if="!loading && !list.length" class="empty card">还没有求购，来发第一条吧</div>
      <el-pagination v-if="total > 20" background layout="prev, pager, next" :total="total" :page-size="20" :current-page="page" @current-change="load" />
    </div>

    <el-dialog v-model="dialog" title="发布求购" width="min(520px, 92vw)">
      <div class="stack">
        <div class="field"><label for="w-t">想要什么<span class="req">*</span></label><el-input id="w-t" v-model="form.title" maxlength="30" show-word-limit placeholder="例如：求一台 24 寸显示器" /></div>
        <div class="field"><label for="w-d">具体要求</label><el-input id="w-d" v-model="form.description" type="textarea" :rows="3" maxlength="500" placeholder="型号、成色、面交地点等" /></div>
        <div class="grid-2" style="gap: 12px">
          <div class="field"><label for="w-c1">分类<span class="req">*</span></label>
            <select id="w-c1" v-model="form.parentId" class="select" @change="form.categoryId = subCats[0]?.id ?? ''"><option v-for="c in meta.categories" :key="c.id" :value="c.id">{{ c.name }}</option></select></div>
          <div class="field"><label for="w-c2">子分类<span class="req">*</span></label>
            <select id="w-c2" v-model="form.categoryId" class="select"><option v-for="c in subCats" :key="c.id" :value="c.id">{{ c.name }}</option></select></div>
        </div>
        <div class="grid-2" style="gap: 12px">
          <div class="field"><label for="w-min">预算（元）</label><div class="input-group"><input id="w-min" v-model="form.priceMin" class="input" inputmode="decimal" placeholder="最低" /><input v-model="form.priceMax" class="input" inputmode="decimal" placeholder="最高" aria-label="最高预算" /></div></div>
          <div class="field"><label for="w-cp">校区</label><select id="w-cp" v-model="form.campusId" class="select"><option v-for="c in meta.campuses" :key="c.id" :value="c.id">{{ c.name }}</option></select></div>
        </div>
        <div class="field"><label>有效期</label><div class="radio-group">
          <button v-for="d in [3, 7, 15, 30] as const" :key="d" type="button" class="radio-card" :class="{ active: form.validDays === d }" @click="form.validDays = d">{{ d }} 天</button></div></div>
        <p class="subtle">每人最多同时 10 条进行中的求购。</p>
      </div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">发布</el-button>
      </template>
    </el-dialog>
    <ReportDialog v-model="report.open" :target-type="3" :target-id="report.id" />
  </main>
</template>

<style scoped>
.campus { width: auto; height: 34px; margin-left: auto; font-size: 14px; }
.want-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 16px; min-height: 80px; }
.want { display: flex; flex-direction: column; gap: 10px; padding: 20px; }
.want h3 { font-size: 16px; }
.budget { font-family: var(--font-num); color: var(--price); font-weight: 600; }
.desc { color: var(--text-2); font-size: 14px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.foot { display: flex; align-items: center; justify-content: space-between; margin-top: auto; padding-top: 10px; border-top: 1px solid var(--border); gap: 8px; }
@media (max-width: 768px) { .want-grid { grid-template-columns: 1fr; } .campus { margin-left: 0; } }
</style>
