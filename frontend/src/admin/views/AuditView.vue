<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import CoverImage from '@/shared/components/CoverImage.vue'
import { shortTime } from '@/shared/format'
import { auditApi, categoryApi } from '../api'
import HighlightText from '../components/HighlightText.vue'
import type { AdminCategory, AuditGoods, AuditRecord } from '../types'

/** 商品审核（接口文档 13.3）：只列待审核，按提交时间升序 */
const list = ref<AuditGoods[]>([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ keyword: '', categoryId: '', page: 1 })
const cats = ref<AdminCategory[]>([])
const selected = ref<AuditGoods[]>([])
const reject = reactive({ open: false, ids: [] as string[], reason: '', saving: false })
const records = reactive({ open: false, list: [] as AuditRecord[] })
const preview = reactive({ open: false, item: null as AuditGoods | null })
const PRESETS = ['含站外联系方式', '禁售品', '图片与描述不符', '价格明显不合理']

async function load(page = q.page) {
  q.page = page
  loading.value = true
  try {
    const r = await auditApi.list({ keyword: q.keyword || undefined, categoryId: q.categoryId || undefined, page, size: 10 })
    list.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

async function approve(ids: string[]) {
  if (ids.length === 1) {
    await auditApi.approve(ids[0])
    ElMessage.success('已通过，商品上架')
  } else {
    const r = await auditApi.batch(ids, 'APPROVE')
    report(r)
  }
  load()
}

function openReject(ids: string[]) {
  Object.assign(reject, { open: true, ids, reason: '' })
}
async function doReject() {
  if (!reject.reason.trim()) return ElMessage.warning('请填写驳回原因')
  reject.saving = true
  try {
    if (reject.ids.length === 1) {
      await auditApi.reject(reject.ids[0], reject.reason.trim())
      ElMessage.success('已驳回并通知卖家')
    } else {
      report(await auditApi.batch(reject.ids, 'REJECT', reject.reason.trim()))
    }
    reject.open = false
    load()
  } finally {
    reject.saving = false
  }
}
function report(r: { success: string[]; failed: { id: string; message: string }[] }) {
  if (r.failed.length) ElMessage.warning(`成功 ${r.success.length} 条，失败 ${r.failed.length} 条：${r.failed.map((f) => f.message).join('；')}`)
  else ElMessage.success(`已处理 ${r.success.length} 条`)
}

async function showRecords(id: string) {
  records.list = await auditApi.records(id)
  records.open = true
}

onMounted(async () => {
  cats.value = await categoryApi.tree().catch(() => [])
  load(1)
})
</script>

<template>
  <section class="card">
    <div class="toolbar">
      <input v-model="q.keyword" class="input" placeholder="商品标题 / 卖家昵称" aria-label="搜索商品标题或卖家昵称" @keyup.enter="load(1)" />
      <select v-model="q.categoryId" class="select" aria-label="按分类筛选" @change="load(1)">
        <option value="">全部分类</option>
        <optgroup v-for="c in cats" :key="c.id" :label="c.name"><option :value="c.id">全部{{ c.name }}</option><option v-for="s in c.children" :key="s.id" :value="s.id">{{ s.name }}</option></optgroup>
      </select>
      <button class="btn btn-primary btn-sm" @click="load(1)">查询</button>
      <span class="row" style="margin-left: auto">
        <button class="btn btn-outline btn-sm" :disabled="!selected.length" @click="approve(selected.map((s) => s.id))">批量通过（{{ selected.length }}）</button>
        <button class="btn btn-danger-outline btn-sm" :disabled="!selected.length" @click="openReject(selected.map((s) => s.id))">批量驳回</button>
      </span>
    </div>
    <el-table v-loading="loading" :data="list" row-key="id" empty-text="没有待审核的商品" @selection-change="(v: AuditGoods[]) => (selected = v)">
      <el-table-column type="selection" width="44" />
      <el-table-column label="商品" min-width="300">
        <template #default="{ row }">
          <button class="goods-cell" type="button" @click="Object.assign(preview, { open: true, item: row })">
            <span class="thumb t52"><CoverImage :src="row.coverUrl" /></span>
            <span class="txt"><HighlightText :text="row.title" :words="row.hitWords" /><span class="subtle desc"><HighlightText :text="row.description" :words="row.hitWords" /></span></span>
          </button>
        </template>
      </el-table-column>
      <el-table-column label="卖家" width="120"><template #default="{ row }">{{ row.seller.nickname }}<div class="subtle">信用 {{ row.seller.creditScore }}</div></template></el-table-column>
      <el-table-column label="价格" width="90"><template #default="{ row }"><span class="price">¥{{ row.price }}</span></template></el-table-column>
      <el-table-column label="进入审核原因" width="170">
        <template #default="{ row }">
          <span v-if="row.hitWords.length" class="tag tag-red">命中：{{ row.hitWords.join('、') }}</span>
          <span v-else-if="row.seller.creditScore < 80" class="tag tag-yellow">信用分低于 80</span>
          <span v-else class="tag">修改后重审</span>
        </template>
      </el-table-column>
      <el-table-column label="提交时间" width="110"><template #default="{ row }"><span class="subtle">{{ shortTime(row.submittedAt) }}</span></template></el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <div class="ops">
            <button class="btn btn-primary btn-sm" @click="approve([row.id])">通过</button>
            <button class="btn btn-danger-outline btn-sm" @click="openReject([row.id])">驳回</button>
            <button class="btn btn-ghost btn-sm" @click="showRecords(row.id)">记录</button>
          </div>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="total > 10" background layout="total, prev, pager, next" :total="total" :page-size="10" :current-page="q.page" @current-change="load" />
  </section>

  <el-dialog v-model="reject.open" :title="reject.ids.length > 1 ? `批量驳回 ${reject.ids.length} 件商品` : '驳回商品'" width="min(520px, 92vw)">
    <div class="stack">
      <div class="field"><label>常用原因</label><div class="radio-group">
        <button v-for="p in PRESETS" :key="p" type="button" class="radio-card" :class="{ active: reject.reason === p }" @click="reject.reason = p">{{ p }}</button></div></div>
      <div class="field"><label for="rj">驳回说明<span class="req">*</span></label><el-input id="rj" v-model="reject.reason" type="textarea" :rows="3" maxlength="200" show-word-limit /><span class="hint">将写入商品驳回原因，并通知卖家</span></div>
    </div>
    <template #footer><el-button @click="reject.open = false">取消</el-button><el-button type="danger" :loading="reject.saving" @click="doReject">确认驳回</el-button></template>
  </el-dialog>

  <el-dialog v-model="records.open" title="审核记录" width="min(560px, 92vw)">
    <el-table :data="records.list" empty-text="暂无记录">
      <el-table-column label="时间" width="110"><template #default="{ row }">{{ shortTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="审核方" width="100"><template #default="{ row }">{{ row.auditorType === 1 ? '系统' : row.auditorName }}</template></el-table-column>
      <el-table-column label="结果" width="80"><template #default="{ row }">{{ ['', '通过', '驳回', '转人工'][row.result] }}</template></el-table-column>
      <el-table-column label="说明"><template #default="{ row }">{{ row.reason || row.hitWords || '—' }}</template></el-table-column>
    </el-table>
  </el-dialog>

  <el-dialog v-model="preview.open" :title="preview.item?.title" width="min(640px, 92vw)">
    <div v-if="preview.item" class="stack">
      <div class="imgs"><a v-for="u in preview.item.images" :key="u" :href="u" target="_blank" rel="noopener" class="thumb t96"><CoverImage :src="u" /></a></div>
      <p class="muted pre"><HighlightText :text="preview.item.description" :words="preview.item.hitWords" /></p>
      <p class="subtle">分类：{{ preview.item.categoryName }} · 价格 ¥{{ preview.item.price }}</p>
    </div>
  </el-dialog>
</template>

<style scoped>
.goods-cell { display: flex; gap: 12px; align-items: center; border: 0; background: none; cursor: pointer; text-align: left; padding: 0; font: inherit; color: inherit; }
.t52 { width: 52px; height: 52px; overflow: hidden; }
.t96 { width: 96px; height: 96px; overflow: hidden; }
.txt { display: flex; flex-direction: column; min-width: 0; }
.desc { display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden; }
.ops { display: flex; gap: 6px; }
.imgs { display: flex; gap: 8px; flex-wrap: wrap; }
.pre { white-space: pre-wrap; }
</style>
