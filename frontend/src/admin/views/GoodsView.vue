<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import CoverImage from '@/shared/components/CoverImage.vue'
import StatusTag from '@/shared/components/StatusTag.vue'
import { GOODS_STATUS, GOODS_STATUS_TONE } from '@/shared/enums'
import { shortTime } from '@/shared/format'
import { ApiError } from '@/shared/request'
import { goodsApi } from '../api'
import type { AdminGoods } from '../types'

/** 商品管理（接口文档 13.4）：全量搜索 + 强制下架 */
const list = ref<AdminGoods[]>([])
const total = ref(0)
const loading = ref(false)
const q = reactive({ keyword: '', status: '', page: 1 })

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
        <template #default="{ row }"><div class="row"><span class="thumb t48"><CoverImage :src="row.coverUrl" /></span><span>{{ row.title }}<div class="subtle">{{ row.categoryName }} · {{ row.campusName }}</div></span></div></template>
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
</template>

<style scoped>
.t48 { width: 48px; height: 48px; overflow: hidden; }
</style>
