/**
 * Mock 内存数据库：仅供 `npm run dev`（VITE_USE_MOCK=true）本地自测，结构按 docs/接口文档.md 返回。
 * 重启 dev server 数据会重置。登录账号见 frontend/README.md。
 */

/* ---------------- 工具 ---------------- */
export class MockError extends Error {
  constructor(public code: number, message: string, public data: unknown = null, public http = 200) {
    super(message)
  }
}

const pad = (n: number) => String(n).padStart(2, '0')
export function fmt(d: Date = new Date()): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
export const ago = (min: number) => fmt(new Date(Date.now() - min * 60000))
export const later = (min: number) => fmt(new Date(Date.now() + min * 60000))
export const money = (n: number) => n.toFixed(2)

let seq = 10000
export const nextId = () => String(++seq)

/** 占位图：/mock-img/<文字>-<色号>.svg，由 plugin.ts 生成 SVG */
export const img = (text: string, tint = 1) => `/mock-img/${encodeURIComponent(text)}-${tint}.svg`

export function page<T>(arr: T[], q: Record<string, unknown>) {
  const p = Math.max(1, Number(q.page) || 1)
  const size = Math.min(50, Math.max(1, Number(q.size) || 20))
  return { list: arr.slice((p - 1) * size, p * size), total: arr.length, page: p, size }
}

/* ---------------- 基础数据 ---------------- */
export const school = { id: '1', name: 'WU', emailSuffix: '@wu.edu.cn' }
export const campuses = [{ id: '1', name: '贝克兰德' }, { id: '2', name: '廷根' }]

export interface CategoryRow { id: string; parentId: string; name: string; iconUrl: string | null; sort: number; enabled: boolean }
export const categories: CategoryRow[] = [
  ['1', '0', '教材书籍'], ['2', '0', '数码电子'], ['3', '0', '生活用品'], ['4', '0', '服饰鞋包'], ['5', '0', '运动出行'], ['6', '0', '其他'],
  ['101', '1', '专业教材'], ['102', '1', '考研考证'], ['103', '1', '课外读物'], ['201', '2', '手机平板'], ['202', '2', '电脑配件'],
  ['203', '2', '耳机音箱'], ['301', '3', '宿舍电器'], ['302', '3', '日用杂物'], ['401', '4', '衣服'], ['402', '4', '鞋包'],
  ['501', '5', '自行车电动车'], ['502', '5', '运动器材'], ['601', '6', '其他闲置'],
].map(([id, parentId, name], i) => ({ id, parentId, name, iconUrl: null, sort: i, enabled: true }))

export const creditLevel = (s: number) => (s >= 90 ? 'EXCELLENT' : s >= 80 ? 'GOOD' : s >= 60 ? 'FAIR' : s >= 40 ? 'POOR' : 'RESTRICTED')

/* ---------------- 用户 ---------------- */
export interface UserRow {
  id: string; email: string; password: string; nickname: string; avatarUrl: string | null; college: string | null
  campusId: string; bio: string | null; creditScore: number; balance: number; status: number; banUntil: string | null; createdAt: string
}
const u = (id: string, email: string, nickname: string, campusId: string, creditScore: number, college: string, bio: string | null = null): UserRow => ({
  id, email, password: 'abc12345', nickname, avatarUrl: null, college, campusId, bio, creditScore, balance: 0, status: 0, banUntil: null, createdAt: ago(60 * 24 * 300),
})
export const users: UserRow[] = [
  { ...u('1001', 'linxiao2022@wu.edu.cn', '林晓', '1', 86, '外国语学院', '大三，出一些用不上的书和小物件。'), balance: 995 },
  u('1002', 'chenxx21@wu.edu.cn', '数码小陈', '2', 96, '计算机学院', '只出自用数码，支持当面验机。'),
  u('1003', 'zhouyy23@wu.edu.cn', '周同学', '1', 82, '机械学院'),
  u('1004', 'ajie22@wu.edu.cn', '阿杰', '2', 88, '经管学院'),
  u('1005', 'xuxx24@wu.edu.cn', '许同学', '2', 79, '数学学院'),
  { ...u('1006', 'qiangw23@wu.edu.cn', '阿强', '1', 72, '机械学院'), status: 1, banUntil: later(60 * 24 * 7) },
]
export const userById = (id: string) => users.find((x) => x.id === id)
export const brief = (id: string) => {
  const x = userById(id)!
  return { id: x.id, nickname: x.nickname, avatarUrl: x.avatarUrl, creditLevel: creditLevel(x.creditScore) }
}
export const maskEmail = (e: string) => e.slice(0, 2) + '***' + e.slice(e.indexOf('@'))

export const blocks: { userId: string; blockedUserId: string; createdAt: string }[] = []
export const isBlocked = (by: string, target: string) => blocks.some((b) => b.userId === by && b.blockedUserId === target)

export const creditLogs: { id: string; userId: string; delta: number; scoreAfter: number; reasonType: number; refId: string | null; remark: string | null; createdAt: string }[] = [
  { id: '1', userId: '1001', delta: -3, scoreAfter: 84, reasonType: 4, refId: null, remark: '约好面交未到', createdAt: ago(60 * 24 * 20) },
  { id: '2', userId: '1001', delta: 1, scoreAfter: 85, reasonType: 1, refId: null, remark: null, createdAt: ago(60 * 24 * 3) },
  { id: '3', userId: '1001', delta: 1, scoreAfter: 86, reasonType: 2, refId: null, remark: null, createdAt: ago(60 * 24) },
]
export const CREDIT_REASON: Record<number, string> = { 1: '完成交易', 2: '收到好评', 3: '收到差评', 4: '违约取消/爽约', 5: '纠纷败诉', 6: '举报成立', 7: '管理员调整' }
export function addCredit(userId: string, delta: number, reasonType: number, refId: string | null = null, remark: string | null = null) {
  const x = userById(userId)!
  x.creditScore = Math.max(0, Math.min(100, x.creditScore + delta))
  creditLogs.push({ id: nextId(), userId, delta, scoreAfter: x.creditScore, reasonType, refId, remark, createdAt: fmt() })
}

export const balanceLogs: { id: string; userId: string; amount: number; balanceAfter: number; type: number; orderId: string | null; createdAt: string }[] = [
  { id: '1', userId: '1001', amount: 15, balanceAfter: 15, type: 1, orderId: '7002', createdAt: ago(60 * 24 * 17) },
  { id: '2', userId: '1001', amount: 980, balanceAfter: 995, type: 1, orderId: '7003', createdAt: ago(60 * 24 * 4) },
]

/* ---------------- 商品 ---------------- */
export interface GoodsRow {
  id: string; sellerId: string; campusId: string; categoryId: string; title: string; description: string; images: string[]
  price: number; originalPrice: number | null; conditionLevel: number; tradeMode: number; location: string | null; stock: number
  status: number; auditRemark: string | null; viewCount: number; favoriteCount: number; publishedAt: string | null
  createdAt: string; updatedAt: string; deleted: boolean; hitWords: string[]
}
let gid = 5000
const g = (sellerId: string, categoryId: string, title: string, price: number, orig: number | null, cond: number, mode: number, campusId: string, tint: number, minAgo: number, extra: Partial<GoodsRow> = {}): GoodsRow => {
  const id = String(++gid)
  const short = title.split(' ')[0].slice(0, 6)
  return {
    id, sellerId, campusId, categoryId, title, description: `${title}。自用，成色如图，可当面验货。不讲价，谢谢。`,
    images: [img(short, tint), img('细节图', (tint % 6) + 1)], price, originalPrice: orig, conditionLevel: cond, tradeMode: mode,
    location: campusId === '1' ? '3 号食堂门口' : '图书馆东门', stock: 1, status: 2, auditRemark: null,
    viewCount: 20 + ((gid * 37) % 300), favoriteCount: (gid * 7) % 50, publishedAt: ago(minAgo), createdAt: ago(minAgo),
    updatedAt: ago(minAgo), deleted: false, hitWords: [], ...extra,
  }
}
export const goods: GoodsRow[] = [
  g('1001', '101', '高等数学 第七版 上下册 同济 笔记少', 25, 89.6, 3, 1, '1', 1, 10),
  g('1002', '201', 'iPad Air 5 64G 蓝色 带笔 电池 92%', 2650, 4399, 2, 3, '2', 2, 25),
  g('1003', '301', '宿舍小台灯 三档调光 毕业清仓', 15, 59, 3, 1, '1', 3, 60),
  g('1004', '203', '索尼 WH-1000XM4 降噪耳机 国行', 980, 1999, 3, 2, '2', 4, 120),
  g('1003', '501', '捷安特 ATX 山地车 送锁和打气筒', 450, 1680, 4, 1, '1', 6, 180),
  g('1005', '102', '考研数学 张宇 18 讲 + 1000 题 全新未拆', 60, 139, 1, 3, '2', 1, 300),
  g('1001', '401', '优衣库 摇粒绒外套 M 码 只穿过两次', 45, 199, 2, 1, '1', 5, 400),
  g('1002', '202', 'ThinkPad X1 Carbon 2021 i7 16G 512G', 3800, 9999, 3, 2, '2', 2, 1500),
  g('1004', '302', '宿舍收纳箱 三个一起 免费送 自提', 0, null, 4, 1, '1', 3, 1600),
  g('1005', '502', '羽毛球拍 尤尼克斯 弓箭 11 一支', 320, 899, 3, 3, '2', 6, 2900),
  g('1002', '103', 'Kindle Paperwhite 5 8G 送保护套', 520, 1068, 2, 3, '1', 4, 3000),
  g('1003', '301', '小米电饭煲 1.6L 宿舍可用 低功率', 70, 169, 3, 1, '2', 5, 4400),
  g('1001', '301', '宿舍小冰箱 45L 静音', 180, 499, 3, 3, '1', 2, 50, { status: 1, hitWords: [] }),
  g('1006', '201', '二手手机 9 成新 要的加微信详聊', 899, null, 2, 1, '1', 2, 30, { status: 1, hitWords: ['微信'] }),
  g('1001', '202', '二手键盘 青轴 有点吵', 60, 199, 4, 1, '1', 4, 9000, { status: 6 }),
  g('1001', '103', 'Kindle 保护套', 20, null, 2, 1, '1', 5, 100, { status: 0, publishedAt: null }),
]
export const goodsById = (id: string) => goods.find((x) => x.id === id && !x.deleted)
export function categoryPath(id: string): string[] {
  const c = categories.find((x) => x.id === id)
  const p = c && categories.find((x) => x.id === c.parentId)
  return [p?.name, c?.name].filter(Boolean) as string[]
}
export const campusName = (id: string) => campuses.find((c) => c.id === id)?.name ?? ''

export const favorites: { userId: string; goodsId: string; createdAt: string }[] = [
  { userId: '1001', goodsId: '5002', createdAt: ago(60) }, { userId: '1001', goodsId: '5008', createdAt: ago(600) },
]

export const hotWords = ['高等数学', 'iPad', '自行车', '考研资料', '台灯', '耳机', '电饭煲']
export const sensitiveWords: { id: string; word: string; createdAt: string }[] =
  ['微信', 'QQ', '代考', '答案', '定金', '刷单'].map((w, i) => ({ id: String(i + 1), word: w, createdAt: ago(60 * 24 * 30) }))
export const hitWordsIn = (text: string) => sensitiveWords.map((s) => s.word).filter((w) => text.toLowerCase().includes(w.toLowerCase()))
export const maskWords = (text: string) => sensitiveWords.reduce((t, s) => t.split(s.word).join('***'), text)

/* ---------------- 求购 ---------------- */
export interface WantRow {
  id: string; userId: string; campusId: string; categoryId: string; title: string; description: string | null
  priceMin: number | null; priceMax: number | null; status: number; expireAt: string; createdAt: string; deleted: boolean
}
export const wants: WantRow[] = [
  { id: '701', userId: '1003', campusId: '1', categoryId: '202', title: '求一台 24 寸显示器，有 Type-C 口最好', description: '宿舍写代码用，要求无坏点，贝克兰德当面验货。', priceMin: 300, priceMax: 600, status: 0, expireAt: later(60 * 24 * 6), createdAt: ago(120), deleted: false },
  { id: '702', userId: '1005', campusId: '2', categoryId: '101', title: '收 线性代数 同济第六版 课本', description: '下周上课要用，笔记多一点也没关系。', priceMin: 10, priceMax: 20, status: 0, expireAt: later(60 * 24 * 2), createdAt: ago(300), deleted: false },
  { id: '703', userId: '1004', campusId: '2', categoryId: '501', title: '求购一辆二手电动车，能骑就行', description: '两个校区间通勤用，续航 30km 以上。', priceMin: 500, priceMax: 1200, status: 0, expireAt: later(60 * 24 * 13), createdAt: ago(800), deleted: false },
  { id: '704', userId: '1001', campusId: '1', categoryId: '301', title: '收一个宿舍用小冰箱', description: '50L 以内，静音。', priceMin: 100, priceMax: 250, status: 0, expireAt: later(60 * 24 * 4), createdAt: ago(1500), deleted: false },
]

/* ---------------- 聊天 ---------------- */
export interface ConvRow { id: string; small: string; big: string; goodsId: string | null; lastMessage: string | null; lastMessageAt: string | null; smallUnread: number; bigUnread: number }
export interface MsgRow { id: string; conversationId: string; senderId: string; type: number; content: string; createdAt: string }
export const convs: ConvRow[] = [
  { id: '301', small: '1001', big: '1002', goodsId: '5002', lastMessage: '可以的，明天下午图书馆东门见', lastMessageAt: ago(30), smallUnread: 0, bigUnread: 0 },
  { id: '302', small: '1001', big: '1003', goodsId: '5001', lastMessage: '书还在吗？', lastMessageAt: ago(200), smallUnread: 1, bigUnread: 0 },
]
let mid = 90000
const m = (conversationId: string, senderId: string, type: number, content: string, minAgo: number): MsgRow =>
  ({ id: String(++mid), conversationId, senderId, type, content, createdAt: ago(minAgo) + '.000' })
export const msgs: MsgRow[] = [
  m('301', '1001', 1, '你好，iPad 还在吗？电池健康能截图看下吗', 60),
  m('301', '1002', 1, '在的，稍等', 58),
  m('301', '1002', 2, img('电池截图', 2), 57),
  m('301', '1001', 1, '就在平台上聊吧。可以面交验机吗？', 50),
  m('301', '1002', 3, '5011', 40),
  m('301', '1001', 1, '可以的，明天下午图书馆东门见', 30),
  m('302', '1003', 1, '书还在吗？', 200),
]
export const nextMsgId = () => String(++mid)
export const convOf = (a: string, b: string) => convs.find((c) => c.small === (a < b ? a : b) && c.big === (a < b ? b : a))
export const peerOf = (c: ConvRow, me: string) => (c.small === me ? c.big : c.small)

/* ---------------- 订单 ---------------- */
export interface OrderRow {
  id: string; orderNo: string; buyerId: string; sellerId: string; goodsId: string; goodsTitle: string; goodsCover: string
  unitPrice: number; quantity: number; totalAmount: number; tradeMode: number; status: number
  cancelReason: string | null; cancelBy: number | null; sellerConfirmedAt: string | null; paidAt: string | null
  deliveredAt: string | null; finishedAt: string | null; canceledAt: string | null; reviewDeadline: string | null; createdAt: string
  /** 进入售后前的状态，撤销/拒绝后恢复 */
  beforeAfterSale?: number
}
export interface OrderLogRow { orderId: string; fromStatus: number | null; toStatus: number; action: string; operatorType: number; operatorId: string | null; remark: string | null; createdAt: string }
export interface PaymentRow { orderId: string; outTradeNo: string; amount: number; status: number; paidAt: string | null }
export interface AfterSaleRow {
  id: string; orderId: string; type: number; buyerId: string; sellerId: string; reason: string; evidenceUrls: string[]
  refundAmount: number; status: number; sellerReply: string | null; sellerDeadline: string; arbitratorId: string | null
  arbitrationReason: string | null; appealRemark: string | null; refundedAt: string | null; closedAt: string | null; createdAt: string
}
export interface ReviewRow { id: string; orderId: string; reviewerId: string; revieweeId: string; role: number; rating: number; content: string | null; tags: string[]; reply: string | null; isDefault: boolean; visible: boolean; createdAt: string }

const order = (id: string, buyerId: string, goodsId: string, tradeMode: number, status: number, minAgo: number, extra: Partial<OrderRow> = {}): OrderRow => {
  const gg = goodsById(goodsId)!
  return {
    id, orderNo: `2026111${id}`, buyerId, sellerId: gg.sellerId, goodsId, goodsTitle: gg.title, goodsCover: gg.images[0],
    unitPrice: gg.price, quantity: 1, totalAmount: gg.price, tradeMode, status, cancelReason: null, cancelBy: null,
    sellerConfirmedAt: null, paidAt: null, deliveredAt: null, finishedAt: null, canceledAt: null, reviewDeadline: null, createdAt: ago(minAgo), ...extra,
  }
}
export const orders: OrderRow[] = [
  order('7101', '1001', '5002', 2, 50, 60 * 20, { paidAt: ago(60 * 20 - 2), deliveredAt: ago(60 * 2) }),
  order('7102', '1001', '5004', 2, 30, 3),
  order('7103', '1003', '5001', 1, 10, 40),
  order('7104', '1001', '5009', 1, 70, 60 * 24 * 5, { sellerConfirmedAt: ago(60 * 24 * 5 - 10), finishedAt: ago(60 * 24 * 4), reviewDeadline: later(60 * 24 * 11) }),
  // 一笔已申请平台介入的担保订单，供后台「纠纷裁决」演示
  order('7105', '1005', '5011', 2, 60, 60 * 24 * 4, { paidAt: ago(60 * 24 * 4 - 3), deliveredAt: ago(60 * 24 * 3), beforeAfterSale: 50 }),
]
export const orderLogs: OrderLogRow[] = [
  { orderId: '7101', fromStatus: null, toStatus: 30, action: 'CREATE', operatorType: 1, operatorId: '1001', remark: null, createdAt: ago(60 * 20) },
  { orderId: '7101', fromStatus: 30, toStatus: 40, action: 'PAY', operatorType: 3, operatorId: null, remark: null, createdAt: ago(60 * 20 - 2) },
  { orderId: '7101', fromStatus: 40, toStatus: 50, action: 'DELIVER', operatorType: 2, operatorId: '1002', remark: null, createdAt: ago(60 * 2) },
  { orderId: '7102', fromStatus: null, toStatus: 30, action: 'CREATE', operatorType: 1, operatorId: '1001', remark: null, createdAt: ago(3) },
  { orderId: '7103', fromStatus: null, toStatus: 10, action: 'CREATE', operatorType: 1, operatorId: '1003', remark: null, createdAt: ago(40) },
  { orderId: '7104', fromStatus: null, toStatus: 10, action: 'CREATE', operatorType: 1, operatorId: '1001', remark: null, createdAt: ago(60 * 24 * 5) },
  { orderId: '7104', fromStatus: 10, toStatus: 20, action: 'SELLER_CONFIRM', operatorType: 2, operatorId: '1004', remark: null, createdAt: ago(60 * 24 * 5 - 10) },
  { orderId: '7104', fromStatus: 20, toStatus: 70, action: 'COMPLETE', operatorType: 1, operatorId: '1001', remark: null, createdAt: ago(60 * 24 * 4) },
  { orderId: '7105', fromStatus: null, toStatus: 30, action: 'CREATE', operatorType: 1, operatorId: '1005', remark: null, createdAt: ago(60 * 24 * 4) },
  { orderId: '7105', fromStatus: 30, toStatus: 40, action: 'PAY', operatorType: 3, operatorId: null, remark: null, createdAt: ago(60 * 24 * 4 - 3) },
  { orderId: '7105', fromStatus: 40, toStatus: 50, action: 'DELIVER', operatorType: 2, operatorId: '1002', remark: null, createdAt: ago(60 * 24 * 3) },
  { orderId: '7105', fromStatus: 50, toStatus: 60, action: 'APPLY_REFUND', operatorType: 1, operatorId: '1005', remark: '屏幕有一条亮线', createdAt: ago(60 * 24 * 3 - 60) },
  { orderId: '7105', fromStatus: 60, toStatus: 60, action: 'APPEAL', operatorType: 1, operatorId: '1005', remark: '交货当天晚上就发现了', createdAt: ago(60 * 24 * 2) },
]
export const payments: PaymentRow[] = [
  { orderId: '7101', outTradeNo: 'P7101A', amount: 2650, status: 1, paidAt: ago(60 * 20 - 2) },
  { orderId: '7105', outTradeNo: 'P7105A', amount: 520, status: 1, paidAt: ago(60 * 24 * 4 - 3) },
]
export const afterSales: AfterSaleRow[] = [
  { id: '801', orderId: '7105', type: 2, buyerId: '1005', sellerId: '1002', reason: '屏幕有一条亮线，描述里没写', evidenceUrls: [img('亮线照片', 2)],
    refundAmount: 520, status: 3, sellerReply: '交货时当面看过是好的', sellerDeadline: ago(60 * 24), arbitratorId: null, arbitrationReason: null,
    appealRemark: '交货当天晚上就发现了', refundedAt: null, closedAt: null, createdAt: ago(60 * 24 * 3 - 60) },
]
export const reviews: ReviewRow[] = [
  { id: '1', orderId: '0', reviewerId: '1005', revieweeId: '1002', role: 1, rating: 1, content: '耳机成色很好，面交很准时。', tags: ['描述相符', '准时赴约'], reply: null, isDefault: false, visible: true, createdAt: ago(60 * 24 * 7) },
  { id: '2', orderId: '0', reviewerId: '1003', revieweeId: '1002', role: 1, rating: 2, content: '东西没问题，回复有点慢。', tags: [], reply: '那几天在考试，抱歉。', isDefault: false, visible: true, createdAt: ago(60 * 24 * 20) },
]
export const REVIEW_TAGS = { '1': ['描述相符', '准时赴约', '沟通愉快', '价格实在'], '2': ['准时赴约', '付款爽快', '沟通愉快'] }

/* ---------------- 通知 / 举报 / 公告 ---------------- */
export interface NotiRow { id: string; userId: string; type: number; title: string; content: string; refType: number | null; refId: string | null; isRead: boolean; createdAt: string }
export const notis: NotiRow[] = [
  { id: '1201', userId: '1001', type: 1, title: '卖家已交货', content: '「iPad Air 5」卖家已交货，请检查后确认收货。7 天后将自动确认。', refType: 1, refId: '7101', isRead: false, createdAt: ago(120) },
  { id: '1202', userId: '1001', type: 3, title: '有新商品匹配你的求购', content: '你求购的「宿舍用小冰箱」有同学发布了新商品，去看看？', refType: 3, refId: '704', isRead: false, createdAt: ago(300) },
  { id: '1203', userId: '1001', type: 2, title: '商品审核未通过', content: '「二手键盘」描述中含站外联系方式，请修改后重新提交。', refType: 2, refId: '5015', isRead: true, createdAt: ago(60 * 24 * 2) },
  { id: '1204', userId: '1001', type: 5, title: '信用分 +1', content: '你在交易中收到好评，当前信用分 86。', refType: null, refId: null, isRead: true, createdAt: ago(60 * 24 * 3) },
  { id: '1205', userId: '1001', type: 6, title: '平台公告', content: '寒假清仓活动 1 月 5 日开启，免费送商品会优先展示在首页。', refType: null, refId: null, isRead: false, createdAt: ago(60 * 24 * 9) },
]
export function notify(userId: string, type: number, title: string, content: string, refType: number | null = null, refId: string | null = null) {
  notis.unshift({ id: nextId(), userId, type, title, content, refType, refId, isRead: false, createdAt: fmt() })
}

export interface ReportRow {
  id: string; reporterId: string; targetType: number; targetId: string; reasonType: number; description: string | null
  evidenceUrls: string[]; status: number; result: string | null; handlerId: string | null; handledAt: string | null; createdAt: string
}
export const reports: ReportRow[] = [
  { id: '901', reporterId: '1003', targetType: 1, targetId: '5014', reasonType: 3, description: '让我先转定金到支付宝', evidenceUrls: [], status: 0, result: null, handlerId: null, handledAt: null, createdAt: ago(200) },
  { id: '902', reporterId: '1005', targetType: 2, targetId: '1006', reasonType: 4, description: '砍价不成就骂人', evidenceUrls: [], status: 0, result: null, handlerId: null, handledAt: null, createdAt: ago(900) },
]

export interface AnnRow { id: string; type: number; title: string; content: string | null; imageUrl: string | null; linkUrl: string | null; sort: number; status: number; createdBy: string; createdAt: string; updatedAt: string }
export const anns: AnnRow[] = [
  { id: '3', type: 2, title: '寒假清仓 · 学长学姐的好物传下去', content: null, imageUrl: null, linkUrl: '/search?keyword=教材', sort: 1, status: 1, createdBy: '1', createdAt: ago(60 * 24 * 9), updatedAt: ago(60 * 24 * 9) },
  { id: '5', type: 1, title: '寒假清仓活动 1 月 5 日开启', content: '寒假前很多同学会清理宿舍。平台将在 1 月 5 日至 1 月 20 日举办寒假清仓活动。\n\n活动期间发布的「免费送」商品会优先展示在首页。\n\n请勿发布代考、答案、证件等禁售品，请在平台内沟通，不要私下转账或支付定金。', imageUrl: null, linkUrl: null, sort: 1, status: 1, createdBy: '1', createdAt: ago(60 * 24 * 9), updatedAt: ago(60 * 24 * 9) },
  { id: '6', type: 1, title: '平台交易安全须知', content: '1. 只在平台内沟通。\n2. 高价物品建议使用担保交易。\n3. 面交时当面验货。', imageUrl: null, linkUrl: null, sort: 2, status: 1, createdBy: '1', createdAt: ago(60 * 24 * 40), updatedAt: ago(60 * 24 * 40) },
]

/* ---------------- 后台 ---------------- */
export const PERMISSIONS = [
  ['dashboard:view', '数据看板', '总览'], ['goods:audit', '商品审核', '商品'], ['goods:manage', '商品管理', '商品'],
  ['user:view', '用户查看', '用户'], ['user:punish', '禁言/封禁', '用户'], ['user:credit', '调整信用分', '用户'],
  ['order:view', '订单查询', '交易'], ['dispute:handle', '纠纷裁决', '交易'], ['report:handle', '举报处理', '交易'],
  ['category:manage', '分类管理', '运营'], ['content:manage', '公告/轮播/敏感词', '运营'],
  ['system:admin', '管理员账号', '系统'], ['system:role', '角色与权限', '系统'], ['system:log', '操作日志', '系统'],
].map(([code, name, group], i) => ({ id: String(i + 1), code, name, group }))
export const roles = [
  { id: '1', code: 'SUPER_ADMIN', name: '超级管理员', builtIn: true, permissionIds: PERMISSIONS.map((p) => p.id) },
  { id: '2', code: 'ADMIN', name: '普通管理员', builtIn: true, permissionIds: PERMISSIONS.filter((p) => !p.code.startsWith('system:')).map((p) => p.id) },
]
export const admins = [
  { id: '1', username: 'admin', password: 'admin123', realName: '系统管理员', status: 1, roleIds: ['1'], lastLoginAt: ago(60) as string | null, createdAt: ago(60 * 24 * 100) },
  { id: '2', username: 'audit01', password: 'admin123', realName: '王老师', status: 1, roleIds: ['2'], lastLoginAt: ago(600) as string | null, createdAt: ago(60 * 24 * 50) },
]
export const opLogs: { id: string; adminId: string; module: string; action: string; targetId: string | null; detail: string | null; ip: string; createdAt: string }[] = []
export function opLog(adminId: string, module: string, action: string, targetId: string | null = null, detail: string | null = null) {
  opLogs.unshift({ id: nextId(), adminId, module, action, targetId, detail, ip: '127.0.0.1', createdAt: fmt() })
}
