<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppIcon from '@/shared/components/AppIcon.vue'
import { ANNOUNCEMENT_TYPE } from '@/shared/enums'
import { shortTime } from '@/shared/format'
import { categoryApi, contentApi } from '../api'
import { useAdminAuth } from '../stores/auth'
import type { AdminCategory, Announcement, SensitiveWord } from '../types'

/** 内容运营（接口文档 13.9 / 13.10）：分类、公告与轮播、敏感词 */
const auth = useAdminAuth()
const tab = ref(auth.can('category:manage') ? 'cat' : 'ann')
const confirmDel = (what: string) => ElMessageBox.confirm(`确定删除${what}？`, '删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
const ignoreCancel = (e: unknown) => { if (e !== 'cancel' && e !== 'close') throw e }

/* 分类 */
const cats = ref<AdminCategory[]>([])
const expanded = ref<Set<string>>(new Set())
const catForm = reactive({ open: false, id: '', parentId: '0', name: '', sort: 0 })
async function loadCats() { cats.value = await categoryApi.tree() }
function editCat(c?: AdminCategory, parentId = '0') {
  Object.assign(catForm, { open: true, id: c?.id ?? '', parentId: c?.parentId ?? parentId, name: c?.name ?? '', sort: c?.sort ?? 0 })
}
async function saveCat() {
  if (!catForm.name.trim()) return ElMessage.warning('请填写分类名称')
  if (catForm.id) await categoryApi.update(catForm.id, { name: catForm.name.trim(), sort: catForm.sort })
  else await categoryApi.create({ parentId: catForm.parentId, name: catForm.name.trim(), sort: catForm.sort })
  catForm.open = false
  ElMessage.success('已保存')
  loadCats()
}
async function toggleCat(c: AdminCategory) {
  await categoryApi.setEnabled(c.id, !c.enabled)
  ElMessage.success(c.enabled ? (c.parentId === '0' ? '已停用，子分类一并停用' : '已停用') : '已启用')
  loadCats()
}
async function delCat(c: AdminCategory) {
  try { await confirmDel(`分类「${c.name}」`); await categoryApi.remove(c.id); loadCats() } catch (e) { ignoreCancel(e) }
}
/** 与相邻的同级分类交换位置，同级分类按新顺序重新编号后批量保存 */
async function moveCat(list: AdminCategory[], i: number, dir: -1 | 1) {
  const next = [...list]
  ;[next[i], next[i + dir]] = [next[i + dir], next[i]]
  await categoryApi.sort(next.map((c, k) => ({ id: c.id, sort: k + 1 })))
  loadCats()
}
function toggleExpand(id: string) {
  expanded.value.has(id) ? expanded.value.delete(id) : expanded.value.add(id)
  expanded.value = new Set(expanded.value)
}

/* 公告与轮播 */
const anns = ref<Announcement[]>([])
const annForm = reactive({ open: false, id: '', type: 1, title: '', content: '', imageUrl: '', linkUrl: '', sort: 0, status: 1, notifyUsers: false })
async function loadAnns() { anns.value = (await contentApi.announcements({ page: 1, size: 50 })).list }
function editAnn(a?: Announcement) {
  Object.assign(annForm, { open: true, id: a?.id ?? '', type: a?.type ?? 1, title: a?.title ?? '', content: a?.content ?? '', imageUrl: a?.imageUrl ?? '', linkUrl: a?.linkUrl ?? '', sort: a?.sort ?? 0, status: a?.status ?? 1, notifyUsers: false })
}
async function saveAnn() {
  if (!annForm.title.trim()) return ElMessage.warning('请填写标题')
  if (annForm.type === 2 && !annForm.imageUrl.trim()) return ElMessage.warning('轮播图必须填写图片地址')
  const { open: _o, id, ...body } = annForm
  if (id) await contentApi.updateAnnouncement(id, body)
  else await contentApi.createAnnouncement(body)
  annForm.open = false
  ElMessage.success(annForm.type === 1 && annForm.notifyUsers && !id ? '已发布，正在通知全体用户' : '已保存')
  loadAnns()
}
async function toggleAnn(a: Announcement) { await contentApi.setAnnouncementStatus(a.id, a.status ? 0 : 1); loadAnns() }
async function delAnn(a: Announcement) {
  try { await confirmDel(`「${a.title}」`); await contentApi.removeAnnouncement(a.id); loadAnns() } catch (e) { ignoreCancel(e) }
}

/* 敏感词 */
const words = ref<SensitiveWord[]>([])
const wordTotal = ref(0)
const wordInput = ref('')
const wordKeyword = ref('')
async function loadWords() {
  const r = await contentApi.words({ keyword: wordKeyword.value || undefined, page: 1, size: 50 })
  words.value = r.list
  wordTotal.value = r.total
}
async function addWords() {
  const list = wordInput.value.split(/[\s,，、]+/).map((w) => w.trim()).filter(Boolean)
  if (!list.length) return
  const r = await contentApi.addWords(list)
  ElMessage.success(`新增 ${r.added} 个${r.skipped ? `，跳过已存在的 ${r.skipped} 个` : ''}，已即时生效`)
  wordInput.value = ''
  loadWords()
}
async function delWord(w: SensitiveWord) { await contentApi.removeWord(w.id); loadWords() }

onMounted(() => {
  if (auth.can('category:manage')) loadCats()
  if (auth.can('content:manage')) { loadAnns(); loadWords() }
})
</script>

<template>
  <section class="card">
    <div class="tabs" style="padding: 0 16px" role="tablist">
      <button v-if="auth.can('category:manage')" class="tab" :class="{ active: tab === 'cat' }" role="tab" @click="tab = 'cat'">分类管理</button>
      <template v-if="auth.can('content:manage')">
        <button class="tab" :class="{ active: tab === 'ann' }" role="tab" @click="tab = 'ann'">公告与轮播</button>
        <button class="tab" :class="{ active: tab === 'word' }" role="tab" @click="tab = 'word'">敏感词库</button>
      </template>
    </div>

    <div v-if="tab === 'cat'">
      <div class="toolbar"><span class="subtle">只支持两级；分类下有商品或子分类时只能停用，不能删除</span>
        <button class="btn btn-primary btn-sm" style="margin-left: auto" @click="editCat()"><AppIcon name="plus" size="sm" />新增一级分类</button></div>
      <div class="cat-tree">
        <template v-for="(c, i) in cats" :key="c.id">
          <div class="cat-row">
            <button class="icon-btn sm" :aria-label="expanded.has(c.id) ? '收起' : '展开'" @click="toggleExpand(c.id)"><AppIcon :name="expanded.has(c.id) ? 'down' : 'right'" size="sm" /></button>
            <span class="name"><b>{{ c.name }}</b> <span class="subtle">· {{ c.goodsCount }} 件 · 排序 {{ c.sort }}</span></span>
            <span class="tag" :class="c.enabled ? 'tag-green' : ''">{{ c.enabled ? '启用' : '停用' }}</span>
            <button class="btn btn-ghost btn-sm" :disabled="i === 0" @click="moveCat(cats, i, -1)">上移</button>
            <button class="btn btn-ghost btn-sm" :disabled="i === cats.length - 1" @click="moveCat(cats, i, 1)">下移</button>
            <button class="btn btn-ghost btn-sm" @click="editCat(undefined, c.id)">新增子类</button>
            <button class="btn btn-ghost btn-sm" @click="editCat(c)">编辑</button>
            <button class="btn btn-ghost btn-sm" @click="toggleCat(c)">{{ c.enabled ? '停用' : '启用' }}</button>
            <button class="btn btn-ghost btn-sm" @click="delCat(c)">删除</button>
          </div>
          <template v-if="expanded.has(c.id)">
            <div v-for="(s, j) in c.children" :key="s.id" class="cat-row child">
              <span class="name">{{ s.name }} <span class="subtle">· {{ s.goodsCount }} 件 · 排序 {{ s.sort }}</span></span>
              <span class="tag" :class="s.enabled ? 'tag-green' : ''">{{ s.enabled ? '启用' : '停用' }}</span>
              <button class="btn btn-ghost btn-sm" :disabled="j === 0" @click="moveCat(c.children!, j, -1)">上移</button>
              <button class="btn btn-ghost btn-sm" :disabled="j === c.children!.length - 1" @click="moveCat(c.children!, j, 1)">下移</button>
              <button class="btn btn-ghost btn-sm" @click="editCat(s)">编辑</button>
              <button class="btn btn-ghost btn-sm" @click="toggleCat(s)">{{ s.enabled ? '停用' : '启用' }}</button>
              <button class="btn btn-ghost btn-sm" @click="delCat(s)">删除</button>
            </div>
          </template>
        </template>
      </div>
    </div>

    <div v-else-if="tab === 'ann'">
      <div class="toolbar"><button class="btn btn-primary btn-sm" style="margin-left: auto" @click="editAnn()"><AppIcon name="plus" size="sm" />新增</button></div>
      <el-table :data="anns" row-key="id" empty-text="暂无公告">
        <el-table-column label="类型" width="90"><template #default="{ row }"><span class="tag" :class="row.type === 2 ? 'tag-blue' : ''">{{ ANNOUNCEMENT_TYPE[row.type] }}</span></template></el-table-column>
        <el-table-column label="标题" prop="title" min-width="220" />
        <el-table-column label="排序" prop="sort" width="70" />
        <el-table-column label="状态" width="80"><template #default="{ row }"><span class="tag" :class="row.status ? 'tag-green' : ''">{{ row.status ? '上线' : '下线' }}</span></template></el-table-column>
        <el-table-column label="更新时间" width="110"><template #default="{ row }"><span class="subtle">{{ shortTime(row.updatedAt) }}</span></template></el-table-column>
        <el-table-column label="操作" width="200"><template #default="{ row }">
          <div class="ops"><button class="btn btn-outline btn-sm" @click="editAnn(row as Announcement)">编辑</button><button class="btn btn-outline btn-sm" @click="toggleAnn(row as Announcement)">{{ row.status ? '下线' : '上线' }}</button><button class="btn btn-ghost btn-sm" @click="delAnn(row as Announcement)">删除</button></div>
        </template></el-table-column>
      </el-table>
    </div>

    <div v-else>
      <form class="toolbar" @submit.prevent="addWords">
        <input v-model="wordInput" class="input" placeholder="输入敏感词，多个用空格或逗号分隔" aria-label="新增敏感词" style="min-width: 280px" />
        <button class="btn btn-primary btn-sm" type="submit">添加</button>
        <input v-model="wordKeyword" class="input" placeholder="搜索" aria-label="搜索敏感词" @keyup.enter="loadWords" />
        <span class="subtle">共 {{ wordTotal }} 个 · 增删后立即生效</span>
      </form>
      <div class="words">
        <span v-for="w in words" :key="w.id" class="tag tag-red">{{ w.word }}<button :aria-label="`删除 ${w.word}`" @click="delWord(w)"><AppIcon name="x" size="sm" /></button></span>
      </div>
    </div>
  </section>

  <el-dialog v-model="catForm.open" :title="catForm.id ? '编辑分类' : catForm.parentId === '0' ? '新增一级分类' : '新增子分类'" width="min(420px, 92vw)">
    <div class="stack">
      <div class="field"><label for="cf-n">名称<span class="req">*</span></label><el-input id="cf-n" v-model="catForm.name" maxlength="32" /></div>
      <div class="field"><label for="cf-s">排序（越小越靠前）</label><el-input-number id="cf-s" v-model="catForm.sort" :min="0" /></div>
    </div>
    <template #footer><el-button @click="catForm.open = false">取消</el-button><el-button type="primary" @click="saveCat">保存</el-button></template>
  </el-dialog>

  <el-dialog v-model="annForm.open" :title="annForm.id ? '编辑' : '新增公告 / 轮播图'" width="min(560px, 92vw)">
    <div class="stack">
      <div class="field"><label>类型</label><div class="radio-group">
        <button v-for="(t, k) in ANNOUNCEMENT_TYPE" :key="k" type="button" class="radio-card" :class="{ active: annForm.type === Number(k) }" :disabled="!!annForm.id" @click="annForm.type = Number(k)">{{ t }}</button></div></div>
      <div class="field"><label for="af-t">标题<span class="req">*</span></label><el-input id="af-t" v-model="annForm.title" maxlength="64" show-word-limit /></div>
      <div v-if="annForm.type === 1" class="field"><label for="af-c">正文</label><el-input id="af-c" v-model="annForm.content" type="textarea" :rows="6" /></div>
      <template v-else>
        <div class="field"><label for="af-i">图片地址<span class="req">*</span></label><el-input id="af-i" v-model="annForm.imageUrl" placeholder="https://…" /><span class="hint">接口文档未提供后台图片上传接口，暂填图片 URL</span></div>
        <div class="field"><label for="af-l">跳转链接</label><el-input id="af-l" v-model="annForm.linkUrl" placeholder="/search?keyword=教材" /></div>
      </template>
      <div class="grid-2" style="gap: 12px">
        <div class="field"><label for="af-s">排序</label><el-input-number id="af-s" v-model="annForm.sort" :min="0" /></div>
        <div class="field"><label>状态</label><el-switch v-model="annForm.status" :active-value="1" :inactive-value="0" active-text="上线" inactive-text="下线" /></div>
      </div>
      <el-checkbox v-if="annForm.type === 1 && !annForm.id" v-model="annForm.notifyUsers">同时给全体用户发系统通知</el-checkbox>
    </div>
    <template #footer><el-button @click="annForm.open = false">取消</el-button><el-button type="primary" @click="saveAnn">保存</el-button></template>
  </el-dialog>
</template>

<style scoped>
.cat-tree { padding: 8px 24px 16px; }
.cat-row { display: flex; align-items: center; gap: 8px; padding: 10px 0; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
.cat-row.child { padding-left: 40px; }
.cat-row .name { flex: 1; min-width: 160px; }
.icon-btn.sm { width: 32px; height: 32px; }
.ops { display: flex; gap: 6px; }
.words { display: flex; flex-wrap: wrap; gap: 8px; padding: 16px 24px; }
.words .tag { height: 30px; padding: 0 6px 0 12px; font-size: 13px; }
.words .tag button { border: 0; background: none; cursor: pointer; color: inherit; display: grid; place-items: center; width: 20px; height: 20px; border-radius: 50%; }
</style>
