<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppIcon from '@/shared/components/AppIcon.vue'
import { shortTime } from '@/shared/format'
import { systemApi } from '../api'
import { useAdminAuth } from '../stores/auth'
import type { AdminAccount, OperationLog, Permission, Role } from '../types'

/** 系统管理（接口文档 13.11）：管理员、角色权限、操作日志，各自按权限码显示 */
const auth = useAdminAuth()
const tab = ref(auth.can('system:admin') ? 'adm' : auth.can('system:role') ? 'role' : 'log')
const ignoreCancel = (e: unknown) => { if (e !== 'cancel' && e !== 'close') throw e }

/* 管理员 */
const admins = ref<AdminAccount[]>([])
const roles = ref<Role[]>([])
const admForm = reactive({ open: false, id: '', username: '', password: '', realName: '', roleIds: [] as string[] })
async function loadAdmins() { admins.value = (await systemApi.admins({ page: 1, size: 50 })).list }
function editAdmin(a?: AdminAccount) {
  Object.assign(admForm, { open: true, id: a?.id ?? '', username: a?.username ?? '', password: '', realName: a?.realName ?? '', roleIds: a?.roles.map((r) => r.id) ?? [] })
}
async function saveAdmin() {
  if (!admForm.realName.trim() || !admForm.roleIds.length) return ElMessage.warning('请填写姓名并选择角色')
  if (admForm.id) await systemApi.updateAdmin(admForm.id, { realName: admForm.realName.trim(), roleIds: admForm.roleIds })
  else {
    if (!admForm.username.trim() || admForm.password.length < 8) return ElMessage.warning('请填写账号，初始密码至少 8 位')
    await systemApi.createAdmin({ username: admForm.username.trim(), password: admForm.password, realName: admForm.realName.trim(), roleIds: admForm.roleIds })
  }
  admForm.open = false
  ElMessage.success('已保存')
  loadAdmins()
}
async function toggleAdmin(a: AdminAccount) {
  await systemApi.setAdminStatus(a.id, a.status ? 0 : 1)
  ElMessage.success(a.status ? '已停用，该账号已下线' : '已启用')
  loadAdmins()
}
async function resetPwd(a: AdminAccount) {
  try {
    const { value } = await ElMessageBox.prompt(`为「${a.username}」设置新密码（至少 8 位）`, '重置密码', { inputType: 'password', inputPattern: /^.{8,32}$/, inputErrorMessage: '8–32 位', confirmButtonText: '重置', cancelButtonText: '取消' })
    await systemApi.resetAdminPassword(a.id, value)
    ElMessage.success('密码已重置')
  } catch (e) { ignoreCancel(e) }
}

/* 角色权限 */
const perms = ref<Permission[]>([])
const currentRole = ref<Role | null>(null)
const checked = ref<string[]>([])
async function loadRoles() {
  roles.value = await systemApi.roles()
  if (auth.can('system:role')) {
    perms.value = await systemApi.permissions()
    if (!currentRole.value && roles.value.length) pickRole(roles.value[roles.value.length - 1])
  }
}
async function pickRole(r: Role) {
  currentRole.value = r
  checked.value = await systemApi.rolePermissions(r.id)
}
function togglePerm(id: string) {
  const i = checked.value.indexOf(id)
  i >= 0 ? checked.value.splice(i, 1) : checked.value.push(id)
}
async function savePerms() {
  if (!currentRole.value) return
  await systemApi.setRolePermissions(currentRole.value.id, checked.value)
  ElMessage.success('权限已保存')
}
async function addRole() {
  try {
    const { value } = await ElMessageBox.prompt('格式：角色编码,角色名称，例如 AUDITOR,审核员', '新增角色', { inputPattern: /^[A-Z_]+,.+$/, inputErrorMessage: '编码用大写字母和下划线，用英文逗号分隔', confirmButtonText: '新增', cancelButtonText: '取消' })
    const [code, name] = value.split(',')
    await systemApi.createRole(code.trim(), name.trim())
    loadRoles()
  } catch (e) { ignoreCancel(e) }
}
async function renameRole(r: Role) {
  try {
    const { value } = await ElMessageBox.prompt('新的角色名称', '修改角色', { inputValue: r.name, inputPattern: /^\S.{0,31}$/, inputErrorMessage: '名称 1～32 字', confirmButtonText: '保存', cancelButtonText: '取消' })
    await systemApi.renameRole(r.id, value.trim())
    if (currentRole.value?.id === r.id) currentRole.value.name = value.trim()
    loadRoles()
  } catch (e) { ignoreCancel(e) }
}
async function delRole(r: Role) {
  try {
    await ElMessageBox.confirm(`删除角色「${r.name}」？`, '删除角色', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    await systemApi.removeRole(r.id)
    if (currentRole.value?.id === r.id) currentRole.value = null
    loadRoles()
  } catch (e) { ignoreCancel(e) }
}

/* 操作日志 */
const logs = ref<OperationLog[]>([])
const logTotal = ref(0)
const logQ = reactive({ module: '', page: 1 })
async function loadLogs(page = logQ.page) {
  logQ.page = page
  const r = await systemApi.logs({ module: logQ.module || undefined, page, size: 20 })
  logs.value = r.list
  logTotal.value = r.total
}

onMounted(() => {
  if (auth.can('system:admin') || auth.can('system:role')) loadRoles()
  if (auth.can('system:admin')) loadAdmins()
  if (auth.can('system:log')) loadLogs(1)
})
</script>

<template>
  <section class="card">
    <div class="tabs" style="padding: 0 16px" role="tablist">
      <button v-if="auth.can('system:admin')" class="tab" :class="{ active: tab === 'adm' }" role="tab" @click="tab = 'adm'">管理员</button>
      <button v-if="auth.can('system:role')" class="tab" :class="{ active: tab === 'role' }" role="tab" @click="tab = 'role'">角色权限</button>
      <button v-if="auth.can('system:log')" class="tab" :class="{ active: tab === 'log' }" role="tab" @click="tab = 'log'; loadLogs(1)">操作日志</button>
    </div>

    <div v-if="tab === 'adm'">
      <div class="toolbar"><span class="subtle">不能停用自己</span><button class="btn btn-primary btn-sm" style="margin-left: auto" @click="editAdmin()"><AppIcon name="plus" size="sm" />新增管理员</button></div>
      <el-table :data="admins" row-key="id">
        <el-table-column label="账号" prop="username" width="140" />
        <el-table-column label="姓名" prop="realName" width="140" />
        <el-table-column label="角色"><template #default="{ row }"><span v-for="r in row.roles" :key="r.id" class="tag tag-green" style="margin-right: 4px">{{ r.name }}</span></template></el-table-column>
        <el-table-column label="状态" width="80"><template #default="{ row }"><span class="tag" :class="row.status ? 'tag-green' : ''">{{ row.status ? '启用' : '停用' }}</span></template></el-table-column>
        <el-table-column label="最近登录" width="120"><template #default="{ row }"><span class="subtle">{{ shortTime(row.lastLoginAt) || '—' }}</span></template></el-table-column>
        <el-table-column label="操作" width="240"><template #default="{ row }">
          <div class="ops">
            <button class="btn btn-outline btn-sm" @click="editAdmin(row as AdminAccount)">编辑</button>
            <button class="btn btn-outline btn-sm" @click="resetPwd(row as AdminAccount)">重置密码</button>
            <button v-if="row.id !== auth.me?.id" class="btn btn-outline btn-sm" @click="toggleAdmin(row as AdminAccount)">{{ row.status ? '停用' : '启用' }}</button>
          </div>
        </template></el-table-column>
      </el-table>
    </div>

    <div v-else-if="tab === 'role'" class="role-wrap">
      <aside class="role-list">
        <button v-for="r in roles" :key="r.id" type="button" class="role-item" :class="{ active: currentRole?.id === r.id }" @click="pickRole(r)">
          <span>{{ r.name }}<br /><span class="subtle">{{ r.code }} · {{ r.adminCount }} 人</span></span>
          <span class="ops">
            <span class="del" role="button" tabindex="0" :aria-label="`修改角色 ${r.name}`" @click.stop="renameRole(r)" @keydown.enter.stop="renameRole(r)"><AppIcon name="edit" size="sm" /></span>
            <span v-if="!r.builtIn" class="del" role="button" tabindex="0" :aria-label="`删除角色 ${r.name}`" @click.stop="delRole(r)" @keydown.enter.stop="delRole(r)"><AppIcon name="trash" size="sm" /></span>
          </span>
        </button>
        <button class="btn btn-ghost btn-sm" @click="addRole"><AppIcon name="plus" size="sm" />新增角色</button>
      </aside>
      <div v-if="currentRole" class="perm-box">
        <p v-if="currentRole.code === 'SUPER_ADMIN'" class="alert alert-info">超级管理员拥有全部权限，不可修改。</p>
        <div v-for="grp in perms" :key="grp.id" class="perm-group"><b>{{ grp.name }}</b><br />
          <label v-for="p in grp.children" :key="p.id">
            <input type="checkbox" :checked="checked.includes(p.id)" :disabled="currentRole.code === 'SUPER_ADMIN' || p.code === 'system:role'" @change="togglePerm(p.id)" />{{ p.name }}
            <span class="subtle">{{ p.code }}</span>
          </label>
        </div>
        <p class="subtle">「角色与权限」固定只授予超级管理员。</p>
        <button v-if="currentRole.code !== 'SUPER_ADMIN'" class="btn btn-primary mt-16" @click="savePerms">保存</button>
      </div>
    </div>

    <div v-else>
      <div class="toolbar">
        <select v-model="logQ.module" class="select" aria-label="按模块筛选" @change="loadLogs(1)">
          <option value="">全部模块</option><option v-for="m in ['商品审核', '商品管理', '用户管理', '纠纷裁决', '举报处理', '分类管理', '内容运营', '系统管理']" :key="m">{{ m }}</option>
        </select>
      </div>
      <el-table :data="logs" row-key="id" empty-text="暂无操作日志">
        <el-table-column label="时间" width="120"><template #default="{ row }"><span class="subtle">{{ shortTime(row.createdAt) }}</span></template></el-table-column>
        <el-table-column label="管理员" prop="adminName" width="110" />
        <el-table-column label="模块" prop="module" width="110" />
        <el-table-column label="操作" prop="action" min-width="200" />
        <el-table-column label="对象 ID" width="100"><template #default="{ row }"><span class="num">{{ row.targetId ?? '—' }}</span></template></el-table-column>
        <el-table-column label="IP" width="120"><template #default="{ row }"><span class="num">{{ row.ip }}</span></template></el-table-column>
      </el-table>
      <el-pagination v-if="logTotal > 20" background layout="total, prev, pager, next" :total="logTotal" :page-size="20" :current-page="logQ.page" @current-change="loadLogs" />
    </div>
  </section>

  <el-dialog v-model="admForm.open" :title="admForm.id ? '编辑管理员' : '新增管理员'" width="min(460px, 92vw)">
    <div class="stack">
      <div class="field"><label for="ad-u">账号<span class="req">*</span></label><el-input id="ad-u" v-model="admForm.username" :disabled="!!admForm.id" maxlength="32" /></div>
      <div v-if="!admForm.id" class="field"><label for="ad-p">初始密码<span class="req">*</span></label><el-input id="ad-p" v-model="admForm.password" type="password" show-password autocomplete="new-password" /></div>
      <div class="field"><label for="ad-n">姓名<span class="req">*</span></label><el-input id="ad-n" v-model="admForm.realName" maxlength="32" /></div>
      <div class="field"><label>角色<span class="req">*</span></label>
        <el-checkbox-group v-model="admForm.roleIds"><el-checkbox v-for="r in roles" :key="r.id" :value="r.id">{{ r.name }}</el-checkbox></el-checkbox-group></div>
    </div>
    <template #footer><el-button @click="admForm.open = false">取消</el-button><el-button type="primary" @click="saveAdmin">保存</el-button></template>
  </el-dialog>
</template>

<style scoped>
.ops { display: flex; gap: 6px; flex-wrap: wrap; }
.role-wrap { display: grid; grid-template-columns: 220px 1fr; min-height: 300px; }
.role-list { border-right: 1px solid var(--border); padding: 8px; display: flex; flex-direction: column; gap: 4px; }
.role-item { display: flex; justify-content: space-between; align-items: center; padding: 10px 12px; border-radius: 10px; border: 0; background: none; text-align: left; cursor: pointer; font: inherit; color: inherit; }
.role-item:hover { background: #F1F5F9; }
.role-item.active { background: var(--primary-soft); color: var(--primary); font-weight: 600; }
.ops { display: flex; gap: 2px; }
.del { color: var(--text-3); padding: 4px; border-radius: 6px; }
.del:hover { color: var(--danger); }
.perm-box { padding: 16px 24px; }
.perm-group { padding: 12px 0; border-bottom: 1px solid var(--border); }
.perm-group label { display: inline-flex; align-items: center; gap: 6px; margin: 8px 20px 0 0; font-size: 14px; }
@media (max-width: 768px) { .role-wrap { grid-template-columns: 1fr; } .role-list { border-right: 0; border-bottom: 1px solid var(--border); } }
</style>
