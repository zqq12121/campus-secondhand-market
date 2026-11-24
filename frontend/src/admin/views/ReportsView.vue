<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import CoverImage from '@/shared/components/CoverImage.vue'
import StatusTag from '@/shared/components/StatusTag.vue'
import { GOODS_STATUS, REPORT_REASON, REPORT_STATUS, REPORT_TARGET, USER_STATUS, WANT_STATUS } from '@/shared/enums'
import { shortTime } from '@/shared/format'
import { reportApi } from '../api'
import type { ReportDetail, ReportItem } from '../types'

/** 举报处理（接口文档 13.8）。诈骗成立时必须包含封禁（文档规则），前端自动勾选且不可取消。 */
const list = ref<ReportItem[]>([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ status: 0, targetType: '', page: 1 })
const detail = reactive({ open: false, data: null as ReportDetail | null })
const h = reactive({ open: false, item: null as ReportItem | null, established: true, actions: [] as string[], banDays: 7 as number | null, result: '', saving: false })

const ACTIONS = computed(() => {
  const t = h.item?.targetType
  return [
    t === 1 && { v: 'OFF_SHELF', l: '下架商品' },
    t === 3 && { v: 'CLOSE_WANT', l: '关闭求购帖' },
    { v: 'DEDUCT_CREDIT', l: h.item?.reasonType === 3 ? '扣 30 信用分' : '扣 5 信用分' },
    { v: 'BAN', l: '封禁账号' },
  ].filter(Boolean) as { v: string; l: string }[]
})
const fraud = computed(() => h.item?.reasonType === 3)

async function load(page = q.page) {
  q.page = page
  loading.value = true
  try {
    const r = await reportApi.list({ status: q.status, targetType: q.targetType || undefined, page, size: 10 })
    list.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

/** 被举报对象的当前状态：商品 / 求购帖看对象本身，用户看账号状态 */
const targetStatus = computed(() => {
  const d = detail.data
  if (!d) return ''
  if (d.report.targetType === 2) return d.target.ownerStatus === null ? '' : USER_STATUS[d.target.ownerStatus]
  if (d.target.status === null) return ''
  return (d.report.targetType === 1 ? GOODS_STATUS : WANT_STATUS)[d.target.status]
})

async function openDetail(item: ReportItem) {
  detail.data = await reportApi.detail(item.id)
  detail.open = true
}
function handleFromDetail() {
  if (!detail.data) return
  detail.open = false
  open(detail.data.report)
}

function open(item: ReportItem) {
  const def = item.targetType === 1 ? ['OFF_SHELF', 'DEDUCT_CREDIT'] : item.targetType === 3 ? ['CLOSE_WANT'] : ['DEDUCT_CREDIT']
  Object.assign(h, { open: true, item, established: true, actions: item.reasonType === 3 ? [...def, 'BAN'] : def, banDays: item.reasonType === 3 ? null : 7, result: '' })
}
function toggle(v: string) {
  if (v === 'BAN' && fraud.value) return
  const i = h.actions.indexOf(v)
  i >= 0 ? h.actions.splice(i, 1) : h.actions.push(v)
}

async function submit() {
  if (!h.item || !h.result.trim()) return ElMessage.warning('请填写处理说明')
  h.saving = true
  try {
    const r = await reportApi.handle(h.item.id, {
      established: h.established, actions: h.established ? h.actions : [], banDays: h.established && h.actions.includes('BAN') ? h.banDays : null, result: h.result.trim(),
    })
    ElMessage.success(r.affected > 1 ? `已处理，同一对象的 ${r.affected} 条举报一并结案` : '已处理并通知双方')
    h.open = false
    load()
  } finally {
    h.saving = false
  }
}

onMounted(() => load(1))
</script>

<template>
  <section class="card">
    <div class="toolbar">
      <select v-model.number="q.status" class="select" aria-label="按处理状态筛选" @change="load(1)"><option v-for="(t, k) in REPORT_STATUS" :key="k" :value="Number(k)">{{ t }}</option></select>
      <select v-model="q.targetType" class="select" aria-label="按举报对象筛选" @change="load(1)"><option value="">全部对象</option><option v-for="(t, k) in REPORT_TARGET" :key="k" :value="k">{{ t }}</option></select>
    </div>
    <el-table v-loading="loading" :data="list" row-key="id" empty-text="没有举报">
      <el-table-column label="举报对象" min-width="200"><template #default="{ row }"><span class="tag tag-blue">{{ REPORT_TARGET[row.targetType] }}</span> {{ row.targetTitle }}</template></el-table-column>
      <el-table-column label="原因" width="100"><template #default="{ row }"><StatusTag :text="REPORT_REASON[row.reasonType]" :tone="row.reasonType === 3 ? 'red' : ''" /></template></el-table-column>
      <el-table-column label="举报说明" min-width="160"><template #default="{ row }"><span class="muted">{{ row.description || '—' }}</span></template></el-table-column>
      <el-table-column label="举报人" width="100"><template #default="{ row }">{{ row.reporter.nickname }}</template></el-table-column>
      <el-table-column label="被举报次数" width="100"><template #default="{ row }"><span class="num">{{ row.targetReportCount }}</span></template></el-table-column>
      <el-table-column label="时间" width="110"><template #default="{ row }"><span class="subtle">{{ shortTime(row.createdAt) }}</span></template></el-table-column>
      <el-table-column label="操作" width="150" fixed="right"><template #default="{ row }">
        <div class="ops">
          <button class="btn btn-outline btn-sm" @click="openDetail(row as ReportItem)">详情</button>
          <button v-if="row.status === 0" class="btn btn-primary btn-sm" @click="open(row as ReportItem)">处理</button>
        </div>
        <span v-if="row.status !== 0" class="subtle">{{ row.result }}</span>
      </template></el-table-column>
    </el-table>
    <el-pagination v-if="total > 10" background layout="total, prev, pager, next" :total="total" :page-size="10" :current-page="q.page" @current-change="load" />
  </section>

  <el-dialog v-model="detail.open" title="举报详情" width="min(640px, 94vw)">
    <div v-if="detail.data" class="stack">
      <dl class="kv">
        <dt>举报对象</dt><dd><span class="tag tag-blue">{{ REPORT_TARGET[detail.data.report.targetType] }}</span> {{ detail.data.report.targetTitle }}</dd>
        <dt>原因</dt><dd><StatusTag :text="REPORT_REASON[detail.data.report.reasonType]" :tone="detail.data.report.reasonType === 3 ? 'red' : ''" /> · 该对象共被举报 {{ detail.data.report.targetReportCount }} 次</dd>
        <dt>举报人</dt><dd>{{ detail.data.report.reporter.nickname }} · {{ shortTime(detail.data.report.createdAt) }}</dd>
        <dt>举报说明</dt><dd>{{ detail.data.report.description || '—' }}</dd>
        <dt>处理状态</dt><dd>{{ REPORT_STATUS[detail.data.report.status] }}{{ detail.data.report.result ? `：${detail.data.report.result}` : '' }}</dd>
      </dl>
      <div v-if="detail.data.report.evidenceUrls.length" class="imgs">
        <a v-for="u in detail.data.report.evidenceUrls" :key="u" :href="u" target="_blank" rel="noopener" class="thumb t96"><CoverImage :src="u" /></a>
      </div>
      <div>
        <h4 class="h4">被举报对象（当前）</h4>
        <div v-if="!detail.data.target.exists" class="alert alert-warn">对象已删除</div>
        <div v-else class="row target">
          <span v-if="detail.data.target.coverUrl" class="thumb t72"><CoverImage :src="detail.data.target.coverUrl" /></span>
          <div class="stack" style="gap: 4px">
            <b>{{ detail.data.target.title }} <span v-if="targetStatus" class="tag">{{ targetStatus }}</span></b>
            <span v-if="detail.data.target.description" class="muted pre">{{ detail.data.target.description }}</span>
            <span class="subtle">{{ detail.data.report.targetType === 2 ? '用户' : '发布者' }}：{{ detail.data.target.ownerNickname }} · 信用分 {{ detail.data.target.ownerCreditScore }}<template v-if="detail.data.report.targetType !== 2 && detail.data.target.ownerStatus"> · {{ USER_STATUS[detail.data.target.ownerStatus] }}</template></span>
          </div>
        </div>
      </div>
    </div>
    <template v-if="detail.data?.report.status === 0" #footer><el-button type="primary" @click="handleFromDetail">处理</el-button></template>
  </el-dialog>

  <el-dialog v-model="h.open" title="处理举报" width="min(560px, 92vw)">
    <div v-if="h.item" class="stack">
      <div v-if="h.item.targetReportCount > 1" class="alert alert-danger">该对象共被举报 {{ h.item.targetReportCount }} 次，本次处理结果会同时用于其他待处理的举报。</div>
      <div class="field"><label>判定</label><div class="radio-group">
        <button type="button" class="radio-card" :class="{ active: h.established }" @click="h.established = true">举报成立</button>
        <button type="button" class="radio-card" :class="{ active: !h.established }" @click="h.established = false">不成立</button></div></div>
      <div v-if="h.established" class="field"><label>处理措施</label>
        <div class="row" style="flex-wrap: wrap">
          <button v-for="a in ACTIONS" :key="a.v" type="button" class="chip" :class="{ active: h.actions.includes(a.v) }" :aria-pressed="h.actions.includes(a.v)" :disabled="a.v === 'BAN' && fraud" @click="toggle(a.v)">{{ a.l }}</button>
        </div>
        <span v-if="fraud" class="hint">诈骗举报成立时必须封禁账号</span></div>
      <div v-if="h.established && h.actions.includes('BAN')" class="field"><label>封禁时长</label><div class="radio-group">
        <button v-for="d in [7, 30, null]" :key="String(d)" type="button" class="radio-card" :class="{ active: h.banDays === d }" @click="h.banDays = d">{{ d ? `${d} 天` : '永久' }}</button></div></div>
      <div class="field"><label for="hd-r">处理说明<span class="req">*</span></label><el-input id="hd-r" v-model="h.result" type="textarea" :rows="3" maxlength="200" show-word-limit /><span class="hint">结果会通知举报人和被举报人</span></div>
    </div>
    <template #footer><el-button @click="h.open = false">取消</el-button><el-button type="primary" :loading="h.saving" @click="submit">提交</el-button></template>
  </el-dialog>
</template>

<style scoped>
.ops { display: flex; gap: 6px; }
.kv { display: grid; grid-template-columns: 96px 1fr; gap: 10px; font-size: 14px; margin: 0; }
.kv dt { color: var(--text-3); }
.kv dd { margin: 0; }
.h4 { font-size: 14px; margin-bottom: 8px; }
.imgs { display: flex; gap: 8px; flex-wrap: wrap; }
.t96 { width: 96px; height: 96px; overflow: hidden; }
.t72 { overflow: hidden; }
.target { align-items: flex-start; }
.pre { white-space: pre-wrap; }
</style>
