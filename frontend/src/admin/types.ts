/** 管理后台接口数据类型（接口文档第 13 章） */
import type { AfterSale, ChatMessage, DateTime, Id, Money, OrderLog } from '@/shared/types'

export interface AdminMe {
  id: Id; username: string; realName: string; roles: string[]; permissions: string[]
  menus: { code: string; name: string; children: { code: string; name: string }[] }[]
}
export interface AdminLoginResult { token: string; expiresIn: number; admin: { id: Id; username: string; realName: string } }

export interface Overview {
  today: { newUsers: number; newGoods: number; newOrders: number; gmv: Money }
  total: { users: number; goods: number; orders: number; gmv: Money }
  pending: { goodsAudit: number; reports: number; disputes: number }
  statUpdatedAt: DateTime
}
export interface Trend { dates: string[]; newUsers: number[]; newGoods: number[]; newOrders: number[]; finishedOrders: number[]; gmv: Money[] }
export interface CategoryShare { categoryId: Id; name: string; count: number; percent: string }

export interface AuditGoods {
  id: Id; title: string; description: string; coverUrl: string; images: string[]; price: Money; categoryName: string
  seller: { id: Id; nickname: string; creditScore: number }; hitWords: string[]; submittedAt: DateTime
}
export interface AdminGoods {
  id: Id; title: string; coverUrl: string; price: Money; status: number; categoryName: string; campusName: string
  seller: { id: Id; nickname: string }; auditRemark: string | null; reportCount: number; createdAt: DateTime
}
export interface AuditRecord { id: Id; auditorType: number; auditorName: string | null; result: number; reason: string | null; hitWords: string | null; createdAt: DateTime }

export interface AdminUser {
  id: Id; email: string; nickname: string; campusName: string; college: string | null; creditScore: number
  status: number; banUntil: DateTime | null; onSaleCount: number; soldCount: number; boughtCount: number; reportedCount: number; createdAt: DateTime
}

export interface AdminOrder {
  id: Id; orderNo: string; goodsTitle: string; goodsCover: string; totalAmount: Money; tradeMode: number; status: number
  buyer: { id: Id; nickname: string }; seller: { id: Id; nickname: string }; createdAt: DateTime
}
export interface AdminOrderDetail extends AdminOrder {
  quantity: number; unitPrice: Money; cancelReason: string | null
  payment: { outTradeNo: string; status: number; amount: Money; paidAt: DateTime | null } | null
  logs: OrderLog[]; afterSales: AfterSale[]
}

export interface DisputeItem {
  id: Id; orderId: Id; orderNo: string; goodsTitle: string; refundAmount: Money; reason: string; status: number
  buyer: { id: Id; nickname: string }; seller: { id: Id; nickname: string }; appealedAt: DateTime
}
export interface DisputeDetail {
  afterSale: AfterSale
  order: AdminOrderDetail
  buyerAppealRemark: string | null
  messages: ChatMessage[]
  messagesHasMore: boolean
}

export interface ReportItem {
  id: Id; reporter: { id: Id; nickname: string }; targetType: number; targetId: Id; targetTitle: string; reasonType: number
  description: string | null; evidenceUrls: string[]; status: number; result: string | null; targetReportCount: number; createdAt: DateTime
}
export interface ReportHandleBody { established: boolean; actions: string[]; banDays: number | null; result: string }

export interface AdminCategory { id: Id; parentId: Id; name: string; iconUrl: string | null; sort: number; enabled: boolean; goodsCount: number; children?: AdminCategory[] }
export interface Announcement {
  id: Id; type: number; title: string; content: string | null; imageUrl: string | null; linkUrl: string | null; sort: number; status: number; createdAt: DateTime; updatedAt: DateTime
}
export interface SensitiveWord { id: Id; word: string; createdAt: DateTime }

export interface AdminAccount { id: Id; username: string; realName: string; status: number; roles: { id: Id; name: string }[]; lastLoginAt: DateTime | null; createdAt: DateTime }
export interface Role { id: Id; code: string; name: string; builtIn: boolean; adminCount: number }
export interface Permission { id: Id; code: string; name: string; children?: Permission[] }
export interface OperationLog { id: Id; adminName: string; module: string; action: string; targetId: Id | null; detail: string | null; ip: string; createdAt: DateTime }

export interface CreditAppealItem {
  id: Id; userId: Id; nickname: string | null; creditScore: number | null; creditLogId: Id; delta: number
  reasonText: string | null; logRemark: string | null; logCreatedAt: DateTime | null; reason: string; status: number
  reply: string | null; handlerId: Id | null; handledAt: DateTime | null; createdAt: DateTime
}
