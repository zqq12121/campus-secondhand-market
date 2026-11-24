<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import CoverImage from '@/shared/components/CoverImage.vue'
import StatusTag from '@/shared/components/StatusTag.vue'
import { CONDITION, GOODS_STATUS, GOODS_STATUS_TONE, GOODS_TRADE_MODE } from '@/shared/enums'
import { shortTime } from '@/shared/format'
import { ApiError } from '@/shared/request'
import { goodsApi } from '../api'
import type { AdminGoods, AdminGoodsDetail } from '../types'

/** 商品管理（接口文档 13.4）：全量搜索 + 详情 + 强制下架 */
const list = ref<AdminGoods[]>([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ keyword: '', status: '', page: 1 })
const detail = reactive({ open: false, data: null as AdminGoodsDetail | null })

async function load(page = q.page) {
  q.page = page
  loading.value = true
  try {
    const r = await goodsApi.list({ keyword: q.keyword || undefined, status: q.status === '' ? undefined : Number(q.status), page, size: 10 })
    list.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

async function openDetail(g: AdminGoods) {
  detail.data = await goodsApi.detail(g.id)
  detail.open = true
}

async function forceOff(g: AdminGoods) {
  try {
    const { value } = await ElMessageBox.prompt(`强制下架「${g.title}」，原因会写入商品并通知卖家。`, '强制下架', {
      confirmButtonText: '下架', cancelButtonText: '取消', inputPattern: /^.{1,200}$/s, inputErrorMessage: '请填写 1–200 字的原因', confirmButtonClass: 'el-button--danger',
    })
    await goodsApi.forceOff(g.id, value.trim())
    ElMessage.success('已强制下架')
    load()
  } catch (e) {
    // 接口错误（如 2010 有进行中的订单）已由请求封装弹出提示
    if (e !== 'cancel' && e !== 'close' && !(e instanceof ApiError)) throw e
  }
}

onMounted(() => load(1))
</script>

<template>
  <section class="card">
    <div class="toolbar">
      <input v-model="q.keyword" class="input" placeholder="商品标题 / 卖家" aria-label="搜索商品标题或卖家" @keyup.enter="load(1)" />
      <select v-model="q.status" class="select" aria-label="按商品状态筛选" @change="load(1)">
        <option value="">全部状态</option><option v-for="(t, k) in GOODS_STATUS" :key="k" :value="k">{{ t }}</option>
      </select>
      <button class="btn btn-primary btn-sm" @click="load(1)">查询</button>
    </div>
    <el-table v-loading="loading" :data="list" row-key="id" empty-text="没有符合条件的商品">
      <el-table-column label="商品" min-width="260">
        <template #default="{ row }"><div class="row"><span class="thumb t48"><CoverImage :src="row.coverUrl" /></span><span><button class="link" @click="openDetail(row as AdminGoods)">{{ row.title }}</button><div class="subtle">{{ row.categoryName }} · {{ row.campusName }}</div></span></div></template>
      </el-table-column>
      <el-table-column label="卖家" width="110"><template #default="{ row }">{{ row.seller.nickname }}</template></el-table-column>
      <el-table-column label="价格" width="90"><template #default="{ row }"><span class="price">¥{{ row.price }}</span></template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="{ row }"><StatusTag :text="GOODS_STATUS[row.status]" :tone="GOODS_STATUS_TONE[row.status]" /><div v-if="row.auditRemark" class="subtle">{{ row.auditRemark }}</div></template></el-table-column>
      <el-table-column label="被举报" width="80"><template #default="{ row }"><span class="num" :class="{ down: row.reportCount }">{{ row.reportCount }}</span></template></el-table-column>
      <el-table-column label="发布时间" width="110"><template #default="{ row }"><span class="subtle">{{ shortTime(row.createdAt) }}</span></template></el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }"><button v-if="![5, 7].includes(row.status)" class="btn btn-danger-outline btn-sm" @click="forceOff(row as AdminGoods)">强制下架</button></template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="total > 10" background layout="total, prev, pager, next" :total="total" :page-size="10" :current-page="q.page" @current-change="load" />
  </section>

  <el-dialog v-model="detail.open" title="商品详情" width="min(720px, 94vw)">
    <div v-if="detail.data" class="stack">
      <div class="imgs"><a v-for="u in detail.data.goods.images" :key="u" :href="u" target="_blank" rel="noopener" class="thumb t96"><CoverImage :src="u" /></a></div>
      <dl class="kv">
        <dt>标题</dt><dd>{{ detail.data.goods.title }} <StatusTag :text="GOODS_STATUS[detail.data.goods.status]" :tone="GOODS_STATUS_TONE[detail.data.goods.status]" /></dd>
        <dt>价格 / 库存</dt><dd><span class="price">¥{{ detail.data.goods.price }}</span> · 库存 {{ detail.data.goods.stock }}</dd>
        <dt>分类</dt><dd>{{ detail.data.goods.categoryPath.join(' / ') }}</dd>
        <dt>成色 / 方式</dt><dd>{{ CONDITION[detail.data.goods.conditionLevel] }} · {{ GOODS_TRADE_MODE[detail.data.goods.tradeMode] }}</dd>
        <dt>地点</dt><dd>{{ detail.data.goods.campusName }}{{ detail.data.goods.location ? ` · ${detail.data.goods.location}` : '' }}</dd>
        <dt>卖家</dt><dd>{{ detail.data.goods.seller.nickname }} · 信用分 {{ detail.data.goods.seller.creditScore }}</dd>
        <dt>数据</dt><dd>浏览 {{ detail.data.goods.viewCount }} · 收藏 {{ detail.data.goods.favoriteCount }} · 关联订单 {{ detail.data.orderCount }} · 被举报 {{ detail.data.goods.reportCount }} 次</dd>
        <dt>时间</dt><dd>创建 {{ shortTime(detail.data.goods.createdAt) }}{{ detail.data.goods.publishedAt ? ` · 上架 ${shortTime(detail.data.goods.publishedAt)}` : '' }}</dd>
        <template v-if="detail.data.goods.auditRemark"><dt>审核备注</dt><dd>{{ detail.data.goods.auditRemark }}</dd></template>
      </dl>
      <p class="muted pre">{{ detail.data.goods.description }}</p>
      <div>
        <h4 class="h4">审核记录</h4>
        <el-table :data="detail.data.auditRecords" size="small" empty-text="暂无记录">
          <el-table-column label="时间" width="110"><template #default="{ row }">{{ shortTime(row.createdAt) }}</template></el-table-column>
          <el-table-column label="审核方" width="90"><template #default="{ row }">{{ row.auditorType === 1 ? '系统' : row.auditorName ?? '管理员' }}</template></el-table-column>
          <el-table-column label="结果" width="80"><template #default="{ row }">{{ ['', '通过', '驳回', '转人工'][row.result] }}</template></el-table-column>
          <el-table-column label="说明"><template #default="{ row }">{{ row.reason || row.hitWords || '—' }}</template></el-table-column>
        </el-table>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.t48 { width: 48px; height: 48px; overflow: hidden; }
.t96 { width: 96px; height: 96px; overflow: hidden; }
.imgs { display: flex; gap: 8px; flex-wrap: wrap; }
.pre { white-space: pre-wrap; margin: 0; }
.link { border: 0; padding: 0; background: none; font: inherit; color: inherit; text-align: left; cursor: pointer; }
.link:hover { color: var(--primary); text-decoration: underline; }
.kv { display: grid; grid-template-columns: 96px 1fr; gap: 10px; font-size: 14px; margin: 0; }
.kv dt { color: var(--text-3); }
.kv dd { margin: 0; }
.h4 { font-size: 14px; margin-bottom: 8px; }
</style>
