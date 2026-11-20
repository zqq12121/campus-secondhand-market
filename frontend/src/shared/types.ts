/**
 * 接口数据类型，对应 docs/接口文档.md。
 * 约定：ID 与金额都是字符串；时间为 "yyyy-MM-dd HH:mm:ss"；枚举为数据库数值（见 enums.ts）。
 */

export type Id = string
export type Money = string
export type DateTime = string

export interface ApiResult<T> {
  code: number
  message: string
  data: T
  traceId?: string
}

export interface PageQuery {
  page?: number
  size?: number
}

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface PageWithCounts<T> extends PageResult<T> {
  counts: Record<string, number>
}

export type CreditLevel = 'EXCELLENT' | 'GOOD' | 'FAIR' | 'POOR' | 'RESTRICTED'

/* ---------- 公共 ---------- */
export interface Campus { id: Id; name: string }
export interface Category { id: Id; name: string; iconUrl?: string | null; children?: Category[] }
export interface HomeData {
  banners: { id: Id; title: string; imageUrl: string; linkUrl: string | null }[]
  notices: { id: Id; title: string; createdAt: DateTime }[]
  categories: Category[]
}
export interface AnnouncementItem { id: Id; title: string; createdAt: DateTime }
export interface AnnouncementDetail extends AnnouncementItem { content: string }

/* ---------- 账号 ---------- */
export interface LoginUser {
  id: Id
  nickname: string
  avatarUrl: string | null
  campusId: Id
  creditScore: number
  creditLevel: CreditLevel
  status: number
}
export interface LoginResult { token: string; expiresIn: number; user: LoginUser }
export interface RegisterBody { email: string; code: string; password: string; nickname: string; campusId: Id }

export interface MeProfile {
  id: Id
  email: string
  verified: boolean
  nickname: string
  avatarUrl: string | null
  college: string | null
  campusId: Id
  campusName: string
  bio: string | null
  creditScore: number
  creditLevel: CreditLevel
  balance: Money
  status: number
  banUntil: DateTime | null
  stats: { onSaleCount: number; soldCount: number; boughtCount: number; favoriteCount: number; goodRate: string | null }
  createdAt: DateTime
}
export type MeUpdateBody = Partial<Pick<MeProfile, 'nickname' | 'avatarUrl' | 'college' | 'campusId' | 'bio'>>

export interface CreditLog {
  id: Id; delta: number; scoreAfter: number; reasonType: number; reasonText: string
  refId: Id | null; remark: string | null; createdAt: DateTime
}
export interface BalanceLog {
  id: Id; amount: string; balanceAfter: Money; type: number; orderId: Id | null; orderNo: string | null; createdAt: DateTime
}
export interface BlockedUser { userId: Id; nickname: string; avatarUrl: string | null; createdAt: DateTime }

export interface UserBrief { id: Id; nickname: string; avatarUrl: string | null; creditLevel?: CreditLevel }
export interface UserHome {
  id: Id; nickname: string; avatarUrl: string | null; college: string | null; campusName: string; bio: string | null
  creditScore: number; creditLevel: CreditLevel; badge: boolean; goodRate: string | null; reviewCount: number
  onSaleCount: number; soldCount: number; blocked: boolean; joinedAt: string; status?: number
}
export interface ReviewItem {
  id: Id; role: number; rating: number; content: string | null; tags: string[]; reply: string | null
  isDefault: boolean; reviewer: UserBrief; goodsTitle: string; createdAt: DateTime
}

/* ---------- 商品 ---------- */
export interface GoodsItem {
  id: Id
  title: string
  coverUrl: string
  price: Money
  originalPrice: Money | null
  conditionLevel: number
  tradeMode: number
  campusId: Id
  campusName: string
  favoriteCount: number
  seller: UserBrief
  publishedAt: DateTime | null
}
export interface MyGoodsItem extends GoodsItem {
  status: number; auditRemark: string | null; viewCount: number; stock: number; updatedAt: DateTime
}
export interface FavoriteItem extends GoodsItem { status: number; favoritedAt: DateTime }

export interface GoodsQuery extends PageQuery {
  keyword?: string
  categoryId?: Id
  campusId?: Id
  priceMin?: string
  priceMax?: string
  conditionLevel?: number[]
  tradeMode?: number
  free?: boolean
  sort?: 'DEFAULT' | 'NEWEST' | 'PRICE_ASC' | 'PRICE_DESC'
}

export type CannotBuyReason = 'SELF' | 'NOT_ON_SALE' | 'OUT_OF_STOCK' | 'BLOCKED' | 'NOT_LOGIN'
export interface GoodsDetail {
  id: Id
  title: string
  description: string
  images: string[]
  categoryId: Id
  categoryPath: string[]
  price: Money
  originalPrice: Money | null
  conditionLevel: number
  tradeMode: number
  campusId: Id
  campusName: string
  location: string | null
  stock: number
  status: number
  auditRemark: string | null
  viewCount: number
  favoriteCount: number
  publishedAt: DateTime | null
  seller: UserBrief & { creditScore: number; badge: boolean; goodRate: string | null; onSaleCount: number }
  favorited: boolean
  isMine: boolean
  canBuy: boolean
  cannotBuyReason: CannotBuyReason | null
}
export interface GoodsForm {
  title: string
  description: string
  images: string[]
  categoryId: Id
  price: Money
  originalPrice?: Money | null
  conditionLevel: number
  tradeMode: number
  campusId?: Id
  location?: string
  stock?: number
  draft?: boolean
}
export interface GoodsSaveResult { id: Id; status: number; hitWords: string[] }

/* ---------- 求购 ---------- */
export interface WantItem {
  id: Id; title: string; description: string | null; categoryId: Id; categoryName: string
  priceMin: Money | null; priceMax: Money | null; campusName: string; status: number
  expireAt: DateTime; user: UserBrief; createdAt: DateTime; isMine?: boolean
}
export interface WantForm {
  title: string; description?: string; categoryId: Id; campusId?: Id
  priceMin?: Money | null; priceMax?: Money | null; validDays: 3 | 7 | 15 | 30
}

/* ---------- 聊天 ---------- */
export interface ChatGoods { id: Id; title: string; coverUrl: string; price: Money; status: number; tradeMode?: number }
export interface ConversationItem {
  id: Id
  peer: UserBrief & { online: boolean }
  goods: ChatGoods | null
  lastMessage: string | null
  lastMessageAt: DateTime | null
  unread: number
}
export interface ConversationDetail {
  id: Id
  peer: UserBrief & { online: boolean }
  goods: ChatGoods | null
  blockedByMe: boolean
  blockedByPeer: boolean
}
export interface ChatMessage {
  id: Id; senderId: Id; type: number; content: string; goods?: ChatGoods; createdAt: DateTime
}
export interface SendMessageBody { type: 1 | 2 | 3; content: string; clientMsgId: string }
export interface SendMessageResult { message: ChatMessage; clientMsgId: string; riskTip: string | null }

/* ---------- 订单 ---------- */
export type OrderAction =
  | 'SELLER_CONFIRM' | 'COMPLETE' | 'CANCEL' | 'PAY' | 'DELIVER' | 'CONFIRM_RECEIPT'
  | 'APPLY_CANCEL' | 'APPLY_REFUND' | 'REVIEW'

export interface OrderItem {
  id: Id; orderNo: string; goodsId: Id; goodsTitle: string; goodsCover: string; unitPrice: Money
  quantity: number; totalAmount: Money; tradeMode: number; status: number
  counterpart: UserBrief; actions: OrderAction[]; reviewed: boolean; createdAt: DateTime
}
export interface OrderCreateBody { goodsId: Id; quantity: number; tradeMode: number; requestId: string }
export interface OrderCreateResult { id: Id; orderNo: string; status: number; payExpireAt?: DateTime }

export interface AfterSale {
  id: Id; orderId: Id; type: number; reason: string; evidenceUrls: string[]; refundAmount: Money
  status: number; sellerReply: string | null; sellerDeadline: DateTime; arbitrationReason: string | null
  refundedAt: DateTime | null; closedAt: DateTime | null
  actions: ('AGREE' | 'REJECT' | 'APPEAL' | 'WITHDRAW')[]; createdAt: DateTime
}

export interface ReviewBrief { rating: number; content: string | null; tags: string[]; createdAt: DateTime }
export interface OrderLog {
  fromStatus: number | null; toStatus: number; action: string; operatorType: number; remark: string | null; createdAt: DateTime
}
export interface OrderDetail {
  id: Id
  orderNo: string
  myRole: 'BUYER' | 'SELLER'
  goods: { id: Id; title: string; cover: string; unitPrice: Money; location: string | null; campusName: string }
  quantity: number
  totalAmount: Money
  tradeMode: number
  status: number
  buyer: UserBrief
  seller: UserBrief
  conversationId: Id | null
  timeline: {
    createdAt: DateTime; sellerConfirmedAt: DateTime | null; paidAt: DateTime | null
    deliveredAt: DateTime | null; finishedAt: DateTime | null; canceledAt: DateTime | null
  }
  deadline: { action: string; at: DateTime } | null
  cancelReason: string | null
  cancelBy: number | null
  payment: { status: number; amount: Money; paidAt: DateTime | null } | null
  afterSale: AfterSale | null
  review: { deadline: DateTime | null; mine: ReviewBrief | null; theirs: ReviewBrief | { submitted: true } | null }
  actions: OrderAction[]
  logs: OrderLog[]
}
export interface PayResult { outTradeNo: string; payForm: string; expireAt: DateTime }
export interface PayStatus { orderStatus: number; paymentStatus: number; paidAt: DateTime | null }

/* ---------- 评价 / 通知 / 举报 ---------- */
export interface ReviewBody { rating: number; content?: string; tags: string[] }
export type ReviewTags = Record<'1' | '2', string[]>

export interface NotificationItem {
  id: Id; type: number; title: string; content: string; refType: number | null; refId: Id | null
  isRead: boolean; createdAt: DateTime
}
export interface UnreadCount { notification: number; chat: number; byType: Record<string, number> }

export interface ReportBody {
  targetType: 1 | 2 | 3; targetId: Id; reasonType: number; description?: string; evidenceUrls?: string[]
}
