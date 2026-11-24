/** 管理后台接口，前缀 /api/admin/v1（接口文档第 13 章） */
import { createHttp } from '@/shared/request'
import type { CreditLog, Id, PageQuery, PageResult } from '@/shared/types'
import type {
  AdminAccount, AdminCategory, CreditAppealItem, AdminGoods, AdminGoodsDetail, AdminLoginResult, AdminMe, AdminOrder, AdminOrderDetail, AdminUser,
  AdminUserDetail, Announcement, AuditGoods, AuditRecord, CategoryShare, DisputeDetail, DisputeItem, OperationLog, Overview, Permission,
  ReportDetail, ReportHandleBody, ReportItem, Role, SensitiveWord, Trend,
} from './types'

export const ADMIN_TOKEN_KEY = 'ct_admin_token'

export const http = createHttp({
  baseURL: '/api/admin/v1',
  tokenKey: ADMIN_TOKEN_KEY,
  onUnauthorized: () => {
    import('./router').then(({ default: router }) => {
      if (router.currentRoute.value.name !== 'login') router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
    })
  },
})

type Q = PageQuery & Record<string, unknown>

export const authApi = {
  login: (username: string, password: string) => http.post<AdminLoginResult>('/auth/login', { username, password }, { silent: true }),
  logout: () => http.post<null>('/auth/logout', undefined, { silent: true }),
  me: () => http.get<AdminMe>('/auth/me', undefined, { silent: true }),
  changePassword: (oldPassword: string, newPassword: string) => http.put<null>('/auth/password', { oldPassword, newPassword }),
}

export const dashboardApi = {
  overview: () => http.get<Overview>('/dashboard/overview'),
  trend: (days = 30) => http.get<Trend>('/dashboard/trend', { days }),
  categoryShare: () => http.get<CategoryShare[]>('/dashboard/category-share'),
}

export const auditApi = {
  list: (q: Q) => http.get<PageResult<AuditGoods>>('/audit/goods', q),
  approve: (id: Id) => http.post<null>(`/audit/goods/${id}/approve`),
  reject: (id: Id, reason: string) => http.post<null>(`/audit/goods/${id}/reject`, { reason }),
  batch: (ids: Id[], result: 'APPROVE' | 'REJECT', reason?: string) =>
    http.post<{ success: Id[]; failed: { id: Id; message: string }[] }>('/audit/goods/batch', { ids, result, reason }),
  records: (id: Id) => http.get<AuditRecord[]>(`/audit/goods/${id}/records`),
}

export const goodsApi = {
  list: (q: Q) => http.get<PageResult<AdminGoods>>('/goods', q),
  detail: (id: Id) => http.get<AdminGoodsDetail>(`/goods/${id}`),
  forceOff: (id: Id, reason: string) => http.post<null>(`/goods/${id}/force-off`, { reason }),
}

export const userApi = {
  list: (q: Q) => http.get<PageResult<AdminUser>>('/users', q),
  detail: (id: Id) => http.get<AdminUserDetail>(`/users/${id}`),
  creditLogs: (id: Id, q: PageQuery) => http.get<PageResult<CreditLog>>(`/users/${id}/credit-logs`, q),
  mute: (id: Id, days: number | null, reason: string) => http.post<null>(`/users/${id}/mute`, { days, reason }),
  ban: (id: Id, days: number | null, reason: string) => http.post<null>(`/users/${id}/ban`, { days, reason }),
  unban: (id: Id) => http.post<null>(`/users/${id}/unban`),
  credit: (id: Id, delta: number, reason: string) => http.post<null>(`/users/${id}/credit`, { delta, reason }),
}

export const orderApi = {
  list: (q: Q) => http.get<PageResult<AdminOrder>>('/orders', q),
  detail: (id: Id) => http.get<AdminOrderDetail>(`/orders/${id}`),
}

export const disputeApi = {
  list: (q: Q) => http.get<PageResult<DisputeItem>>('/disputes', q),
  detail: (id: Id) => http.get<DisputeDetail>(`/disputes/${id}`),
  messages: (id: Id, q: { beforeId?: Id; size?: number }) => http.get<{ list: DisputeDetail['messages']; hasMore: boolean }>(`/disputes/${id}/messages`, q),
  arbitrate: (id: Id, result: 'REFUND' | 'RELEASE', reason: string) => http.post<null>(`/disputes/${id}/arbitrate`, { result, reason }),
}

export const reportApi = {
  list: (q: Q) => http.get<PageResult<ReportItem>>('/reports', q),
  detail: (id: Id) => http.get<ReportDetail>(`/reports/${id}`),
  handle: (id: Id, body: ReportHandleBody) => http.post<{ affected: number }>(`/reports/${id}/handle`, body),
}

export const categoryApi = {
  tree: () => http.get<AdminCategory[]>('/categories'),
  create: (body: { parentId: Id; name: string; iconUrl?: string | null; sort: number }) => http.post<{ id: Id }>('/categories', body),
  update: (id: Id, body: { name: string; iconUrl?: string | null; sort: number }) => http.put<null>(`/categories/${id}`, body),
  setEnabled: (id: Id, enabled: boolean) => http.put<null>(`/categories/${id}/enabled`, { enabled }),
  remove: (id: Id) => http.del<null>(`/categories/${id}`),
  sort: (items: { id: Id; sort: number }[]) => http.put<null>('/categories/sort', items),
}

export const contentApi = {
  announcements: (q: Q) => http.get<PageResult<Announcement>>('/announcements', q),
  createAnnouncement: (body: Partial<Announcement> & { notifyUsers?: boolean }) => http.post<{ id: Id }>('/announcements', body),
  updateAnnouncement: (id: Id, body: Partial<Announcement>) => http.put<null>(`/announcements/${id}`, body),
  setAnnouncementStatus: (id: Id, status: number) => http.put<null>(`/announcements/${id}/status`, { status }),
  removeAnnouncement: (id: Id) => http.del<null>(`/announcements/${id}`),
  words: (q: Q) => http.get<PageResult<SensitiveWord>>('/sensitive-words', q),
  addWords: (words: string[]) => http.post<{ added: number; skipped: number }>('/sensitive-words', { words }),
  removeWord: (id: Id) => http.del<null>(`/sensitive-words/${id}`),
}

export const systemApi = {
  admins: (q: Q) => http.get<PageResult<AdminAccount>>('/admins', q),
  createAdmin: (body: { username: string; password: string; realName: string; roleIds: Id[] }) => http.post<{ id: Id }>('/admins', body),
  updateAdmin: (id: Id, body: { realName: string; roleIds: Id[] }) => http.put<null>(`/admins/${id}`, body),
  setAdminStatus: (id: Id, status: number) => http.put<null>(`/admins/${id}/status`, { status }),
  resetAdminPassword: (id: Id, password: string) => http.put<null>(`/admins/${id}/password`, { password }),
  roles: () => http.get<Role[]>('/roles'),
  createRole: (code: string, name: string) => http.post<{ id: Id }>('/roles', { code, name }),
  renameRole: (id: Id, name: string) => http.put<null>(`/roles/${id}`, { name }),
  removeRole: (id: Id) => http.del<null>(`/roles/${id}`),
  permissions: () => http.get<Permission[]>('/permissions'),
  rolePermissions: (id: Id) => http.get<Id[]>(`/roles/${id}/permissions`),
  setRolePermissions: (id: Id, permissionIds: Id[]) => http.put<null>(`/roles/${id}/permissions`, { permissionIds }),
  logs: (q: Q) => http.get<PageResult<OperationLog>>('/operation-logs', q),
}

/* 信用分申诉（P2） */
export const creditAppealApi = {
  list: (q: Q) => http.get<PageResult<CreditAppealItem>>('/credit-appeals', q),
  handle: (id: Id, approve: boolean, reply: string) => http.post<null>(`/credit-appeals/${id}/handle`, { approve, reply }),
}
