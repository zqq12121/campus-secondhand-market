/** 用户端接口，按 docs/接口文档.md 第 2–12 章组织，前缀 /api/v1 */
import { http } from './http'
import type {
  AfterSale, AnnouncementDetail, AnnouncementItem, BalanceLog, BlockedUser, Campus, Category, ChatMessage,
  ConversationDetail, ConversationItem, CreditLog, FavoriteItem, GoodsDetail, GoodsForm, GoodsItem, GoodsQuery,
  GoodsSaveResult, HomeData, Id, LoginResult, MeProfile, MeUpdateBody, MyGoodsItem, NotificationItem,
  OrderCreateBody, OrderCreateResult, OrderDetail, OrderItem, PageQuery, PageResult, PageWithCounts, PayResult,
  PayStatus, RegisterBody, ReportBody, ReviewBody, ReviewItem, ReviewTags, SendMessageBody, SendMessageResult,
  UnreadCount, UserHome, WantForm, WantItem,
} from '@/shared/types'
import type { RequestOptions } from '@/shared/request'

/* 2. 公共 */
export const commonApi = {
  campuses: () => http.get<Campus[]>('/common/campuses'),
  categories: () => http.get<Category[]>('/common/categories'),
  home: () => http.get<HomeData>('/home'),
  announcements: (q: PageQuery) => http.get<PageResult<AnnouncementItem>>('/announcements', q),
  announcement: (id: Id) => http.get<AnnouncementDetail>(`/announcements/${id}`),
  /** biz：GOODS / AVATAR / CHAT / EVIDENCE */
  upload: (file: File, biz: 'GOODS' | 'AVATAR' | 'CHAT' | 'EVIDENCE') => {
    const form = new FormData()
    form.append('file', file)
    form.append('biz', biz)
    return http.upload<{ url: string }>('/files', form)
  },
}

/* 3. 账号 */
export const authApi = {
  sendCode: (email: string, scene: 'REGISTER' | 'RESET_PASSWORD') => http.post<null>('/auth/email-codes', { email, scene }),
  register: (body: RegisterBody) => http.post<LoginResult>('/auth/register', body),
  login: (email: string, password: string) => http.post<LoginResult>('/auth/login', { email, password }, { silent: true }),
  logout: () => http.post<null>('/auth/logout', undefined, { silent: true }),
  resetPassword: (email: string, code: string, newPassword: string) =>
    http.post<null>('/auth/password/reset', { email, code, newPassword }),
}

/* 4. 个人中心与用户主页 */
export const userApi = {
  me: (opts?: RequestOptions) => http.get<MeProfile>('/users/me', undefined, opts),
  updateMe: (body: MeUpdateBody) => http.put<MeProfile>('/users/me', body),
  changePassword: (oldPassword: string, newPassword: string) =>
    http.put<{ token: string; expiresIn: number }>('/users/me/password', { oldPassword, newPassword }),
  creditLogs: (q: PageQuery) => http.get<PageResult<CreditLog>>('/users/me/credit-logs', q),
  balanceLogs: (q: PageQuery) => http.get<PageResult<BalanceLog>>('/users/me/balance-logs', q),
  myGoods: (q: PageQuery & { status?: number }) => http.get<PageWithCounts<MyGoodsItem>>('/users/me/goods', q),
  favorites: (q: PageQuery) => http.get<PageResult<FavoriteItem>>('/users/me/favorites', q),
  myWants: (q: PageQuery & { status?: number }) => http.get<PageResult<WantItem>>('/users/me/wants', q),
  blocks: (q: PageQuery) => http.get<PageResult<BlockedUser>>('/users/me/blocks', q),
  block: (id: Id) => http.post<null>(`/users/${id}/block`),
  unblock: (id: Id) => http.del<null>(`/users/${id}/block`),
  home: (id: Id) => http.get<UserHome>(`/users/${id}`),
  goods: (id: Id, q: PageQuery) => http.get<PageResult<GoodsItem>>(`/users/${id}/goods`, q),
  reviews: (id: Id, q: PageQuery & { rating?: number }) => http.get<PageResult<ReviewItem>>(`/users/${id}/reviews`, q),
}

/* 5. 商品 */
export const goodsApi = {
  list: (q: GoodsQuery) => http.get<PageResult<GoodsItem>>('/goods', q),
  hotWords: () => http.get<string[]>('/search/hot-words'),
  detail: (id: Id) => http.get<GoodsDetail>(`/goods/${id}`),
  create: (body: GoodsForm) => http.post<GoodsSaveResult>('/goods', body),
  update: (id: Id, body: GoodsForm) => http.put<GoodsSaveResult>(`/goods/${id}`, body),
  offShelf: (id: Id) => http.post<null>(`/goods/${id}/off-shelf`),
  onShelf: (id: Id) => http.post<GoodsSaveResult>(`/goods/${id}/on-shelf`),
  remove: (id: Id) => http.del<null>(`/goods/${id}`),
  favorite: (id: Id) => http.post<{ favorited: boolean; favoriteCount: number }>(`/goods/${id}/favorite`),
  unfavorite: (id: Id) => http.del<{ favorited: boolean; favoriteCount: number }>(`/goods/${id}/favorite`),
}

/* 6. 求购 */
export const wantApi = {
  list: (q: PageQuery & { keyword?: string; categoryId?: Id; campusId?: Id }) => http.get<PageResult<WantItem>>('/wants', q),
  detail: (id: Id) => http.get<WantItem>(`/wants/${id}`),
  create: (body: WantForm) => http.post<{ id: Id }>('/wants', body),
  close: (id: Id) => http.post<null>(`/wants/${id}/close`),
  remove: (id: Id) => http.del<null>(`/wants/${id}`),
}

/* 7. 聊天 */
export const chatApi = {
  open: (peerUserId: Id, goodsId?: Id | null, wantId?: Id | null) =>
    http.post<ConversationDetail>('/conversations', { peerUserId, goodsId: goodsId ?? null, wantId: wantId ?? null }),
  list: (q: PageQuery) => http.get<PageResult<ConversationItem>>('/conversations', q),
  detail: (id: Id) => http.get<ConversationDetail>(`/conversations/${id}`),
  messages: (id: Id, q: { beforeId?: Id; size?: number }) =>
    http.get<{ list: ChatMessage[]; hasMore: boolean }>(`/conversations/${id}/messages`, q, { silent: true }),
  send: (id: Id, body: SendMessageBody) => http.post<SendMessageResult>(`/conversations/${id}/messages`, body),
  read: (id: Id) => http.post<{ totalUnread: number }>(`/conversations/${id}/read`, undefined, { silent: true }),
}

/* 8. 订单与支付 */
export const orderApi = {
  create: (body: OrderCreateBody) => http.post<OrderCreateResult>('/orders', body),
  list: (q: PageQuery & { role: 'BUYER' | 'SELLER'; status?: number[] }) => http.get<PageWithCounts<OrderItem>>('/orders', q),
  detail: (id: Id) => http.get<OrderDetail>(`/orders/${id}`),
  sellerConfirm: (id: Id) => http.post<null>(`/orders/${id}/seller-confirm`),
  complete: (id: Id) => http.post<null>(`/orders/${id}/complete`),
  cancel: (id: Id, reason: string) => http.post<null>(`/orders/${id}/cancel`, { reason }),
  pay: (id: Id) => http.post<PayResult>(`/orders/${id}/pay`),
  payStatus: (id: Id) => http.get<PayStatus>(`/orders/${id}/pay-status`, undefined, { silent: true }),
  deliver: (id: Id) => http.post<null>(`/orders/${id}/deliver`),
  confirmReceipt: (id: Id) => http.post<null>(`/orders/${id}/confirm-receipt`),
}

/* 9. 售后 */
export const afterSaleApi = {
  apply: (orderId: Id, body: { type: 1 | 2; reason: string; evidenceUrls: string[] }) =>
    http.post<AfterSale>(`/orders/${orderId}/after-sales`, body),
  detail: (id: Id) => http.get<AfterSale>(`/after-sales/${id}`),
  agree: (id: Id) => http.post<null>(`/after-sales/${id}/agree`),
  reject: (id: Id, reply: string) => http.post<null>(`/after-sales/${id}/reject`, { reply }),
  appeal: (id: Id, remark?: string) => http.post<null>(`/after-sales/${id}/appeal`, { remark }),
  withdraw: (id: Id) => http.post<null>(`/after-sales/${id}/withdraw`),
}

/* 10. 评价 */
export const reviewApi = {
  submit: (orderId: Id, body: ReviewBody) => http.post<null>(`/orders/${orderId}/reviews`, body),
  reply: (id: Id, reply: string) => http.post<null>(`/reviews/${id}/reply`, { reply }),
  tags: () => http.get<ReviewTags>('/review-tags'),
}

/* 11. 通知 */
export const notificationApi = {
  list: (q: PageQuery & { type?: number; isRead?: boolean }) => http.get<PageResult<NotificationItem>>('/notifications', q),
  unreadCount: () => http.get<UnreadCount>('/notifications/unread-count', undefined, { silent: true }),
  read: (id: Id) => http.post<null>(`/notifications/${id}/read`, undefined, { silent: true }),
  readAll: (type?: number) => http.post<{ updated: number }>('/notifications/read-all', type ? { type } : {}),
}

/* 12. 举报 */
export const reportApi = {
  submit: (body: ReportBody) => http.post<{ id: Id }>('/reports', body),
}
