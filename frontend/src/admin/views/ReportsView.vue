<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import StatusTag from '@/shared/components/StatusTag.vue'
import { REPORT_REASON, REPORT_STATUS, REPORT_TARGET } from '@/shared/enums'
import { shortTime } from '@/shared/format'
import { reportApi } from '../api'
import type { ReportItem } from '../types'

/** 举报处理（接口文档 13.8）。诈骗成立时必须包含封禁（文档规则），前端自动勾选且不可取消。 */
const list = ref<ReportItem[]>([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ status: 0, targetType: '', page: 1 })
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
      <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }">
        <button v-if="row.status === 0" class="btn btn-primary btn-sm" @click="open(row as ReportItem)">处理</button><span v-else class="subtle">{{ row.result }}</span>
      </template></el-table-column>
    </el-table>
    <el-pagination v-if="total > 10" background layout="total, prev, pager, next" :total="total" :page-size="10" :current-page="q.page" @current-change="load" />
  </section>

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
