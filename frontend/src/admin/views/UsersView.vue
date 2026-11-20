<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/shared/components/StatusTag.vue'
import UserAvatar from '@/shared/components/UserAvatar.vue'
import { USER_STATUS } from '@/shared/enums'
import { shortTime } from '@/shared/format'
import { userApi } from '../api'
import { useAdminAuth } from '../stores/auth'
import type { AdminUser } from '../types'

/** 用户管理（接口文档 13.5）：禁言 / 封禁 / 解除 / 调整信用分，按权限码显示按钮 */
const auth = useAdminAuth()
const list = ref<AdminUser[]>([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ keyword: '', campusId: '', status: '', credit: '', page: 1 })
const punish = reactive({ open: false, user: null as AdminUser | null, kind: 'mute' as 'mute' | 'ban', days: 7 as number | null, reason: '', saving: false })
const credit = reactive({ open: false, user: null as AdminUser | null, delta: 0, reason: '', saving: false })

async function load(page = q.page) {
  q.page = page
  loading.value = true
  try {
    const [creditMin, creditMax] = q.credit === 'lt60' ? [undefined, 59] : q.credit === 'lt40' ? [undefined, 39] : q.credit === 'ge90' ? [90, undefined] : [undefined, undefined]
    const r = await userApi.list({ keyword: q.keyword || undefined, campusId: q.campusId || undefined, status: q.status === '' ? undefined : Number(q.status), creditMin, creditMax, page, size: 10 })
    list.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

function openPunish(u: AdminUser) {
  Object.assign(punish, { open: true, user: u, kind: 'mute', days: 7, reason: '' })
}
async function doPunish() {
  if (!punish.user || !punish.reason.trim()) return ElMessage.warning('请填写处罚原因')
  punish.saving = true
  try {
    await (punish.kind === 'mute' ? userApi.mute : userApi.ban)(punish.user.id, punish.days, punish.reason.trim())
    ElMessage.success('处罚已生效')
    punish.open = false
    load()
  } finally {
    punish.saving = false
  }
}

async function unban(u: AdminUser) {
  await ElMessageBox.confirm(`解除「${u.nickname}」的${USER_STATUS[u.status]}？`, '解除处罚', { confirmButtonText: '解除', cancelButtonText: '取消' })
  await userApi.unban(u.id)
  ElMessage.success('已解除')
  load()
}

function openCredit(u: AdminUser) {
  Object.assign(credit, { open: true, user: u, delta: 0, reason: '' })
}
async function doCredit() {
  if (!credit.user || !credit.delta || !credit.reason.trim()) return ElMessage.warning('请填写调整值和原因')
  credit.saving = true
  try {
    await userApi.credit(credit.user.id, credit.delta, credit.reason.trim())
    ElMessage.success('信用分已调整')
    credit.open = false
    load()
  } finally {
    credit.saving = false
  }
}

onMounted(() => load(1))
</script>

<template>
  <section class="card">
    <div class="toolbar">
      <input v-model="q.keyword" class="input" placeholder="邮箱 / 昵称" aria-label="搜索邮箱或昵称" @keyup.enter="load(1)" />
      <select v-model="q.campusId" class="select" aria-label="按校区筛选" @change="load(1)"><option value="">全部校区</option><option value="1">贝克兰德</option><option value="2">廷根</option></select>
      <select v-model="q.status" class="select" aria-label="按账号状态筛选" @change="load(1)"><option value="">全部状态</option><option v-for="(t, k) in USER_STATUS" :key="k" :value="k">{{ t }}</option></select>
      <select v-model="q.credit" class="select" aria-label="按信用分筛选" @change="load(1)"><option value="">信用分不限</option><option value="ge90">90 及以上</option><option value="lt60">低于 60</option><option value="lt40">低于 40</option></select>
      <button class="btn btn-primary btn-sm" @click="load(1)">查询</button>
    </div>
    <el-table v-loading="loading" :data="list" row-key="id" empty-text="没有符合条件的用户">
      <el-table-column label="用户" min-width="220"><template #default="{ row }"><div class="row"><UserAvatar size="sm" :name="row.nickname" /><span>{{ row.nickname }}<div class="subtle">{{ row.email }}</div></span></div></template></el-table-column>
      <el-table-column label="校区 / 学院" width="140"><template #default="{ row }">{{ row.campusName }}<div class="subtle">{{ row.college || '—' }}</div></template></el-table-column>
      <el-table-column label="信用分" width="90"><template #default="{ row }"><StatusTag :text="String(row.creditScore)" :tone="row.creditScore >= 80 ? 'green' : row.creditScore >= 60 ? 'yellow' : 'red'" /></template></el-table-column>
      <el-table-column label="在售 / 卖出 / 买入" width="140"><template #default="{ row }"><span class="num">{{ row.onSaleCount }} / {{ row.soldCount }} / {{ row.boughtCount }}</span></template></el-table-column>
      <el-table-column label="被举报" width="80"><template #default="{ row }"><span class="num">{{ row.reportedCount }}</span></template></el-table-column>
      <el-table-column label="状态" width="150"><template #default="{ row }">
        <StatusTag :text="row.status === 0 ? '正常' : `${USER_STATUS[row.status]}${row.banUntil ? ` 至 ${shortTime(row.banUntil)}` : '（永久）'}`" :tone="row.status === 0 ? 'green' : row.status === 1 ? 'yellow' : 'red'" />
      </template></el-table-column>
      <el-table-column label="注册时间" width="110"><template #default="{ row }"><span class="subtle">{{ row.createdAt.slice(0, 10) }}</span></template></el-table-column>
      <el-table-column label="操作" width="200" fixed="right"><template #default="{ row }">
        <div class="ops">
          <button v-if="auth.can('user:credit')" class="btn btn-outline btn-sm" @click="openCredit(row as AdminUser)">调整信用分</button>
          <template v-if="auth.can('user:punish')">
            <button v-if="row.status !== 0" class="btn btn-outline btn-sm" @click="unban(row as AdminUser)">解除</button>
            <button v-if="row.status !== 2" class="btn btn-danger-outline btn-sm" @click="openPunish(row as AdminUser)">处罚</button>
          </template>
        </div>
      </template></el-table-column>
    </el-table>
    <el-pagination v-if="total > 10" background layout="total, prev, pager, next" :total="total" :page-size="10" :current-page="q.page" @current-change="load" />
  </section>

  <el-dialog v-model="punish.open" :title="`处罚用户 · ${punish.user?.nickname ?? ''}`" width="min(520px, 92vw)">
    <div class="stack">
      <div class="field"><label>处罚类型</label><div class="radio-group">
        <button type="button" class="radio-card" :class="{ active: punish.kind === 'mute' }" @click="punish.kind = 'mute'">禁言</button>
        <button type="button" class="radio-card" :class="{ active: punish.kind === 'ban' }" @click="punish.kind = 'ban'">封禁</button></div>
        <span class="hint">{{ punish.kind === 'mute' ? '禁言：不能发消息、发布商品和求购，可浏览和购买' : '封禁：立即下线，在售商品全部下架，进行中的求购关闭' }}</span></div>
      <div class="field"><label>时长</label><div class="radio-group">
        <button v-for="d in [7, 30, null]" :key="String(d)" type="button" class="radio-card" :class="{ active: punish.days === d }" @click="punish.days = d">{{ d ? `${d} 天` : '永久' }}</button></div></div>
      <div class="field"><label for="pn-r">原因<span class="req">*</span></label><el-input id="pn-r" v-model="punish.reason" type="textarea" :rows="3" maxlength="200" /></div>
    </div>
    <template #footer><el-button @click="punish.open = false">取消</el-button><el-button type="danger" :loading="punish.saving" @click="doPunish">确认处罚</el-button></template>
  </el-dialog>

  <el-dialog v-model="credit.open" :title="`调整信用分 · ${credit.user?.nickname ?? ''}`" width="min(420px, 92vw)">
    <div class="stack">
      <p class="muted">当前信用分 <b class="num">{{ credit.user?.creditScore }}</b>，调整后限制在 0–100。</p>
      <div class="field"><label for="cr-v">调整值<span class="req">*</span></label><el-input-number id="cr-v" v-model="credit.delta" :min="-100" :max="100" /><span class="hint">正数加分，负数扣分</span></div>
      <div class="field"><label for="cr-r">原因<span class="req">*</span></label><el-input id="cr-r" v-model="credit.reason" maxlength="200" placeholder="将记入信用分流水并通知用户" /></div>
    </div>
    <template #footer><el-button @click="credit.open = false">取消</el-button><el-button type="primary" :loading="credit.saving" @click="doCredit">确认</el-button></template>
  </el-dialog>
</template>

<style scoped>
.ops { display: flex; gap: 6px; flex-wrap: wrap; }
</style>
