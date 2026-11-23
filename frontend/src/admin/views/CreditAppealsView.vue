<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import StatusTag from '@/shared/components/StatusTag.vue'
import type { TagTone } from '@/shared/enums'
import { shortTime } from '@/shared/format'
import { creditAppealApi } from '../api'
import type { CreditAppealItem } from '../types'

/** 信用分申诉（P2）：通过后按被扣分值加回，并通知用户；驳回也会通知用户处理说明 */
const STATUS: Record<number, string> = { 0: '待处理', 1: '已通过', 2: '已驳回' }
const TONE: Record<number, TagTone> = { 0: 'yellow', 1: 'green', 2: '' }

const list = ref<CreditAppealItem[]>([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ status: 0 as number | '', page: 1 })
const h = reactive({ open: false, item: null as CreditAppealItem | null, approve: true, reply: '', saving: false })

async function load(page = q.page) {
  q.page = page
  loading.value = true
  try {
    const r = await creditAppealApi.list({ status: q.status === '' ? undefined : q.status, page, size: 10 })
    list.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

function open(item: CreditAppealItem) {
  Object.assign(h, { open: true, item, approve: true, reply: '' })
}

async function submit() {
  if (!h.item || !h.reply.trim()) return ElMessage.warning('请填写处理说明')
  h.saving = true
  try {
    await creditAppealApi.handle(h.item.id, h.approve, h.reply.trim())
    ElMessage.success(h.approve ? `已通过，已为用户加回 ${Math.abs(h.item.delta)} 分` : '已驳回并通知用户')
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
      <select v-model="q.status" class="select" aria-label="按处理状态筛选" @change="load(1)">
        <option value="">全部状态</option>
        <option v-for="(t, k) in STATUS" :key="k" :value="Number(k)">{{ t }}</option>
      </select>
    </div>
    <el-table v-loading="loading" :data="list" row-key="id" empty-text="没有申诉">
      <el-table-column label="用户" width="140"><template #default="{ row }">{{ row.nickname ?? row.userId }}<div class="subtle">当前 {{ row.creditScore ?? '—' }} 分</div></template></el-table-column>
      <el-table-column label="被申诉的扣分" min-width="200"><template #default="{ row }">
        <span class="num down">−{{ Math.abs(row.delta) }}</span> {{ row.reasonText }}
        <div class="subtle">{{ row.logCreatedAt ? shortTime(row.logCreatedAt) : '' }}<template v-if="row.logRemark"> · {{ row.logRemark }}</template></div>
      </template></el-table-column>
      <el-table-column label="申诉理由" min-width="220"><template #default="{ row }"><span class="muted">{{ row.reason }}</span></template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{ row }"><StatusTag :text="STATUS[row.status]" :tone="TONE[row.status]" /></template></el-table-column>
      <el-table-column label="提交时间" width="110"><template #default="{ row }"><span class="subtle">{{ shortTime(row.createdAt) }}</span></template></el-table-column>
      <el-table-column label="操作" width="120" fixed="right"><template #default="{ row }">
        <button v-if="row.status === 0" class="btn btn-primary btn-sm" @click="open(row as CreditAppealItem)">处理</button>
        <span v-else class="subtle">{{ row.reply }}</span>
      </template></el-table-column>
    </el-table>
    <el-pagination v-if="total > 10" background layout="total, prev, pager, next" :total="total" :page-size="10" :current-page="q.page" @current-change="load" />
  </section>

  <el-dialog v-model="h.open" title="处理信用分申诉" width="min(520px, 92vw)">
    <div v-if="h.item" class="stack">
      <p class="muted">{{ h.item.nickname }}：{{ h.item.reasonText }} −{{ Math.abs(h.item.delta) }}<template v-if="h.item.logRemark">（{{ h.item.logRemark }}）</template></p>
      <div class="field"><label>判定</label><div class="radio-group">
        <button type="button" class="radio-card" :class="{ active: h.approve }" @click="h.approve = true">通过，加回 {{ Math.abs(h.item.delta) }} 分</button>
        <button type="button" class="radio-card" :class="{ active: !h.approve }" @click="h.approve = false">驳回</button></div></div>
      <div class="field"><label for="ca-r">处理说明<span class="req">*</span></label>
        <el-input id="ca-r" v-model="h.reply" type="textarea" :rows="3" maxlength="200" show-word-limit /><span class="hint">会通知给用户</span></div>
    </div>
    <template #footer><el-button @click="h.open = false">取消</el-button><el-button type="primary" :loading="h.saving" @click="submit">提交</el-button></template>
  </el-dialog>
</template>

<style scoped>
.down { color: var(--danger, #DC2626); }
</style>
