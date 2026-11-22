/** 用户端 Mock 接口（/api/v1），行为按 docs/接口文档.md 第 2–12 章与附录 C 状态机实现。 */
import { files, route, type Auth, type Ctx } from './plugin'
import {
  addCredit, afterSales, ago, anns, balanceLogs, blocks, brief, campusName, campuses, categories, categoryPath, convOf, convs,
  CREDIT_REASON, creditLevel, creditLogs, favorites, fmt, goods, goodsById, hitWordsIn, hotWords, isBlocked, later, maskWords,
  MockError, money, msgs, nextId, nextMsgId, notify, notis, orderLogs, orders, page, payments, peerOf, REVIEW_TAGS, reports,
  reviews, school, userById, users, wantResponses, wants, type AfterSaleRow, type ConvRow, type GoodsRow, type MsgRow, type OrderRow,
} from './db'

const get = (p: string, a: Auth, h: (c: Ctx) => unknown) => route('U GET', p, a, h)
const post = (p: string, a: Auth, h: (c: Ctx) => unknown) => route('U POST', p, a, h)
const put = (p: string, a: Auth, h: (c: Ctx) => unknown) => route('U PUT', p, a, h)
const del = (p: string, a: Auth, h: (c: Ctx) => unknown) => route('U DELETE', p, a, h)

const err = (code: number, msg: string, data: unknown = null, http = 200) => { throw new MockError(code, msg, data, http) }
const PWD = /^(?=.*[A-Za-z])(?=.*\d).{8,20}$/
const codes = new Map<string, string>()

/* ---------------- 序列化 ---------------- */
function goodsItem(x: GoodsRow) {
  return {
    id: x.id, title: x.title, coverUrl: x.images[0], price: money(x.price), originalPrice: x.originalPrice == null ? null : money(x.originalPrice),
    conditionLevel: x.conditionLevel, tradeMode: x.tradeMode, campusId: x.campusId, campusName: campusName(x.campusId),
    favoriteCount: x.favoriteCount, seller: brief(x.sellerId), publishedAt: x.publishedAt,
  }
}
function goodRate(userId: string) {
  const list = reviews.filter((r) => r.revieweeId === userId && r.visible)
  return list.length ? ((list.filter((r) => r.rating === 1).length / list.length) * 100).toFixed(1) : null
}
const chatGoods = (id: string | null) => {
  const x = id ? goods.find((g) => g.id === id) : null
  return x ? { id: x.id, title: x.title, coverUrl: x.images[0], price: money(x.price), status: x.status, tradeMode: x.tradeMode } : null
}
function msgOut(m: MsgRow) {
  return m.type === 3 ? { ...m, goods: chatGoods(m.content) } : m
}
function convOut(c: ConvRow, me: string) {
  const p = userById(peerOf(c, me))!
  return {
    id: c.id, peer: { ...brief(p.id), online: p.id === '1002' }, goods: chatGoods(c.goodsId),
    blockedByMe: isBlocked(me, p.id), blockedByPeer: isBlocked(p.id, me),
  }
}

/* ---------------- 订单状态机（附录 C） ---------------- */
function role(o: OrderRow, uid: string): 'BUYER' | 'SELLER' {
  if (o.buyerId === uid) return 'BUYER'
  if (o.sellerId === uid) return 'SELLER'
  return err(403, '无权查看该订单', null, 403)
}
function actions(o: OrderRow, uid: string): string[] {
  const buyer = o.buyerId === uid
  const reviewed = reviews.some((r) => r.orderId === o.id && r.reviewerId === uid)
  const canReview = o.status === 70 && !reviewed && !!o.reviewDeadline && o.reviewDeadline > fmt()
  const map: Record<number, string[]> = buyer
    ? { 10: ['CANCEL'], 20: ['COMPLETE', 'CANCEL'], 30: ['PAY', 'CANCEL'], 40: ['APPLY_CANCEL'], 50: ['CONFIRM_RECEIPT', 'APPLY_REFUND'] }
    : { 10: ['SELLER_CONFIRM', 'CANCEL'], 20: ['CANCEL'], 30: ['CANCEL'], 40: ['DELIVER'] }
  return [...(map[o.status] ?? []), ...(canReview ? ['REVIEW'] : [])]
}
function asActions(a: AfterSaleRow, uid: string): string[] {
  if (a.sellerId === uid) return a.status === 0 ? ['AGREE', 'REJECT'] : []
  if (a.status === 0) return ['WITHDRAW']
  if (a.status === 2 && a.type === 2) return ['APPEAL', 'WITHDRAW']
  return []
}
function asOut(a: AfterSaleRow, uid: string) {
  const { buyerId: _b, sellerId: _s, arbitratorId: _a, appealRemark: _r, ...rest } = a
  return { ...rest, refundAmount: money(a.refundAmount), actions: asActions(a, uid) }
}
function sysMsg(o: OrderRow, text: string) {
  let c = convOf(o.buyerId, o.sellerId)
  if (!c) {
    c = { id: nextId(), small: o.buyerId < o.sellerId ? o.buyerId : o.sellerId, big: o.buyerId < o.sellerId ? o.sellerId : o.buyerId, goodsId: o.goodsId, lastMessage: null, lastMessageAt: null, smallUnread: 0, bigUnread: 0 }
    convs.push(c)
  }
  msgs.push({ id: nextMsgId(), conversationId: c.id, senderId: '0', type: 4, content: text, createdAt: fmt() + '.000' })
  c.lastMessage = text
  c.lastMessageAt = fmt()
  return c
}
const STATUS_TEXT: Record<number, string> = { 10: '待卖家确认', 20: '待面交', 30: '待支付', 40: '待交货', 50: '待收货', 60: '售后中', 70: '已完成', 80: '已取消', 90: '已退款' }
/** 状态流转：写日志、系统消息、给对方发通知 */
function transit(o: OrderRow, to: number, action: string, operatorType: number, operatorId: string | null, remark: string | null = null) {
  orderLogs.push({ orderId: o.id, fromStatus: o.status, toStatus: to, action, operatorType, operatorId, remark, createdAt: fmt() })
  o.status = to
  sysMsg(o, `订单 ${o.orderNo} ${STATUS_TEXT[to]}`)
  const target = operatorId === o.buyerId ? o.sellerId : o.buyerId
  notify(target, 1, `订单${STATUS_TEXT[to]}`, `「${o.goodsTitle}」订单状态变为：${STATUS_TEXT[to]}`, 1, o.id)
  if (operatorType === 3 || operatorType === 4) notify(o.sellerId === target ? o.buyerId : o.sellerId, 1, `订单${STATUS_TEXT[to]}`, `「${o.goodsTitle}」订单状态变为：${STATUS_TEXT[to]}`, 1, o.id)
}
function restock(o: OrderRow) {
  const g = goods.find((x) => x.id === o.goodsId)
  if (!g) return
  g.stock += o.quantity
  if (g.status === 4) g.status = 2
}
function finish(o: OrderRow) {
  o.finishedAt = fmt()
  o.reviewDeadline = later(60 * 24 * 15)
  const g = goods.find((x) => x.id === o.goodsId)
  if (g && g.stock === 0) g.status = 5
  addCredit(o.buyerId, 1, 1, o.id)
  addCredit(o.sellerId, 1, 1, o.id)
}
function release(o: OrderRow) {
  const s = userById(o.sellerId)!
  s.balance += o.totalAmount
  balanceLogs.push({ id: nextId(), userId: s.id, amount: o.totalAmount, balanceAfter: s.balance, type: 1, orderId: o.id, createdAt: fmt() })
}
function mustOrder(id: string, uid: string) {
  const o = orders.find((x) => x.id === id) ?? err(404, '订单不存在', null, 404)
  role(o, uid)
  return o
}
function need(o: OrderRow, uid: string, action: string) {
  if (!actions(o, uid).includes(action)) err(5007, '当前订单状态不允许此操作')
}

function orderDetail(o: OrderRow, uid: string) {
  const myRole = role(o, uid)
  const g = goods.find((x) => x.id === o.goodsId)
  const a = [...afterSales].reverse().find((x) => x.orderId === o.id)
  const mine = reviews.find((r) => r.orderId === o.id && r.reviewerId === uid)
  const theirs = reviews.find((r) => r.orderId === o.id && r.reviewerId !== uid)
  const pay = payments.find((p) => p.orderId === o.id)
  const deadline =
    o.status === 30 ? { action: 'AUTO_CANCEL_UNPAID', at: fmt(new Date(new Date(o.createdAt.replace(' ', 'T')).getTime() + 15 * 60000)) }
    : o.status === 10 ? { action: 'AUTO_CANCEL_UNCONFIRMED', at: fmt(new Date(new Date(o.createdAt.replace(' ', 'T')).getTime() + 24 * 3600000)) }
    : o.status === 20 && o.sellerConfirmedAt ? { action: 'AUTO_COMPLETE', at: fmt(new Date(new Date(o.sellerConfirmedAt.replace(' ', 'T')).getTime() + 7 * 86400000)) }
    : o.status === 50 && o.deliveredAt ? { action: 'AUTO_CONFIRM', at: fmt(new Date(new Date(o.deliveredAt.replace(' ', 'T')).getTime() + 7 * 86400000)) }
    : o.status === 60 && a?.status === 0 ? { action: 'AUTO_AGREE_AFTER_SALE', at: a.sellerDeadline }
    : null
  const rb = (r?: typeof mine) => (r ? { rating: r.rating, content: r.content, tags: r.tags, createdAt: r.createdAt } : null)
  return {
    id: o.id, orderNo: o.orderNo, myRole,
    goods: { id: o.goodsId, title: o.goodsTitle, cover: o.goodsCover, unitPrice: money(o.unitPrice), location: g?.location ?? null, campusName: campusName(g?.campusId ?? '1') },
    quantity: o.quantity, totalAmount: money(o.totalAmount), tradeMode: o.tradeMode, status: o.status,
    buyer: brief(o.buyerId), seller: brief(o.sellerId), conversationId: convOf(o.buyerId, o.sellerId)?.id ?? null,
    timeline: { createdAt: o.createdAt, sellerConfirmedAt: o.sellerConfirmedAt, paidAt: o.paidAt, deliveredAt: o.deliveredAt, finishedAt: o.finishedAt, canceledAt: o.canceledAt },
    deadline, cancelReason: o.cancelReason, cancelBy: o.cancelBy,
    payment: pay ? { status: pay.status, amount: money(pay.amount), paidAt: pay.paidAt } : null,
    afterSale: a ? asOut(a, uid) : null,
    review: { deadline: o.reviewDeadline, mine: rb(mine), theirs: theirs ? (theirs.visible ? rb(theirs) : { submitted: true }) : null },
    actions: actions(o, uid),
    logs: orderLogs.filter((l) => l.orderId === o.id).map(({ orderId: _o, operatorId: _i, ...l }) => l),
  }
}

/* ---------------- 路由 ---------------- */
export function registerUserRoutes() {
  /* 2. 公共 */
  get('/common/campuses', 'public', () => campuses)
  get('/common/categories', 'public', () => categories.filter((c) => c.parentId === '0' && c.enabled).map((c) => ({
    id: c.id, name: c.name, iconUrl: c.iconUrl, children: categories.filter((x) => x.parentId === c.id && x.enabled).map((x) => ({ id: x.id, name: x.name })),
  })))
  post('/files', 'user', ({ file, body }) => {
    if (!file) return err(400, '缺少文件')
    if (!/^image\/(jpeg|png|webp)$/.test(file.type)) return err(1101, '文件类型不允许')
    if (file.buf.length > 5 * 1024 * 1024) return err(1102, '文件超过 5MB')
    const id = `${String(body.biz ?? 'GOODS').toLowerCase()}-${nextId()}`
    files.set(id, file)
    return { url: `/mock-files/${id}` }
  })
  get('/home', 'public', () => ({
    banners: anns.filter((a) => a.type === 2 && a.status === 1).map((a) => ({ id: a.id, title: a.title, imageUrl: a.imageUrl ?? '', linkUrl: a.linkUrl })),
    notices: anns.filter((a) => a.type === 1 && a.status === 1).map((a) => ({ id: a.id, title: a.title, createdAt: a.createdAt })),
    categories: categories.filter((c) => c.parentId === '0' && c.enabled).map((c) => ({ id: c.id, name: c.name, iconUrl: c.iconUrl })),
  }))
  get('/announcements', 'public', ({ query }) => page(anns.filter((a) => a.type === 1 && a.status === 1).map((a) => ({ id: a.id, title: a.title, createdAt: a.createdAt })), query))
  get('/announcements/:id', 'public', ({ params }) => {
    const a = anns.find((x) => x.id === params.id && x.type === 1 && x.status === 1) ?? err(404, '公告不存在')
    return { id: a.id, title: a.title, content: a.content ?? '', createdAt: a.createdAt }
  })

  /* 3. 账号 */
  post('/auth/email-codes', 'public', ({ body }) => {
    const email = String(body.email ?? '')
    if (!email.endsWith(school.emailSuffix)) return err(1001, '仅支持 @wu.edu.cn 校园邮箱')
    const exists = users.some((x) => x.email === email)
    if (body.scene === 'REGISTER' && exists) return err(1002, '邮箱已注册')
    if (body.scene === 'RESET_PASSWORD' && !exists) return err(1008, '邮箱未注册')
    codes.set(email, '123456')
    console.log(`[mock] 邮箱验证码 ${email} → 123456`)
    return null
  })
  const loginResult = (id: string) => {
    const x = userById(id)!
    return { token: `mock-user.${id}`, expiresIn: 604800, user: { id, nickname: x.nickname, avatarUrl: x.avatarUrl, campusId: x.campusId, creditScore: x.creditScore, creditLevel: creditLevel(x.creditScore), status: x.status } }
  }
  post('/auth/register', 'public', ({ body }) => {
    if (!String(body.email).endsWith(school.emailSuffix)) return err(1001, '仅支持 @wu.edu.cn 校园邮箱')
    if (users.some((x) => x.email === body.email)) return err(1002, '邮箱已注册')
    if (codes.get(body.email) !== body.code) return err(1003, '验证码错误或已过期')
    if (!PWD.test(body.password ?? '')) return err(1007, '密码须为 8–20 位且包含字母和数字')
    const id = nextId()
    users.push({ id, email: body.email, password: body.password, nickname: body.nickname, avatarUrl: null, college: null, campusId: body.campusId, bio: null, creditScore: 80, balance: 0, status: 0, banUntil: null, createdAt: fmt() })
    codes.delete(body.email)
    return loginResult(id)
  })
  post('/auth/login', 'public', ({ body }) => {
    const x = users.find((y) => y.email === body.email)
    if (!x || x.password !== body.password) return err(1005, '邮箱或密码错误', { remainingAttempts: 4 })
    if (x.status === 2) return err(1009, '账号已封禁', { banUntil: x.banUntil })
    return loginResult(x.id)
  })
  post('/auth/logout', 'user', () => null)
  post('/auth/password/reset', 'public', ({ body }) => {
    const x = users.find((y) => y.email === body.email) ?? err(1008, '邮箱未注册')
    if (codes.get(body.email) !== body.code) return err(1003, '验证码错误或已过期')
    if (!PWD.test(body.newPassword ?? '')) return err(1007, '密码须为 8–20 位且包含字母和数字')
    x.password = body.newPassword
    return null
  })

  /* 4. 个人中心 */
  const meOut = (id: string) => {
    const x = userById(id)!
    return {
      id: x.id, email: x.email, verified: true, nickname: x.nickname, avatarUrl: x.avatarUrl, college: x.college, campusId: x.campusId,
      campusName: campusName(x.campusId), bio: x.bio, creditScore: x.creditScore, creditLevel: creditLevel(x.creditScore), balance: money(x.balance),
      status: x.status, banUntil: x.banUntil,
      stats: {
        onSaleCount: goods.filter((g) => g.sellerId === id && g.status === 2 && !g.deleted).length,
        soldCount: orders.filter((o) => o.sellerId === id && o.status === 70).length,
        boughtCount: orders.filter((o) => o.buyerId === id && o.status === 70).length,
        favoriteCount: favorites.filter((f) => f.userId === id).length, goodRate: goodRate(id),
      },
      createdAt: x.createdAt,
    }
  }
  get('/users/me', 'user', ({ uid }) => meOut(uid))
  put('/users/me', 'user', ({ uid, body }) => {
    const x = userById(uid)!
    for (const k of ['nickname', 'avatarUrl', 'college', 'campusId'] as const) if (body[k] !== undefined) (x as any)[k] = body[k]
    if (body.bio !== undefined) x.bio = maskWords(String(body.bio))
    return meOut(uid)
  })
  put('/users/me/password', 'user', ({ uid, body }) => {
    const x = userById(uid)!
    if (x.password !== body.oldPassword) return err(1005, '当前密码错误')
    if (!PWD.test(body.newPassword ?? '')) return err(1007, '密码须为 8–20 位且包含字母和数字')
    x.password = body.newPassword
    return { token: `mock-user.${uid}`, expiresIn: 604800 }
  })
  get('/users/me/credit-logs', 'user', ({ uid, query }) => page(creditLogs.filter((c) => c.userId === uid).reverse().map(({ userId: _u, ...c }) => ({ ...c, reasonText: CREDIT_REASON[c.reasonType] })), query))
  get('/users/me/balance-logs', 'user', ({ uid, query }) => page(balanceLogs.filter((b) => b.userId === uid).reverse().map((b) => ({
    id: b.id, amount: `+${money(b.amount)}`, balanceAfter: money(b.balanceAfter), type: b.type, orderId: b.orderId,
    orderNo: orders.find((o) => o.id === b.orderId)?.orderNo ?? b.orderId, createdAt: b.createdAt,
  })), query))
  get('/users/me/goods', 'user', ({ uid, query }) => {
    const mine = goods.filter((g) => g.sellerId === uid && !g.deleted)
    const counts: Record<string, number> = {}
    for (let s = 0; s <= 7; s++) counts[s] = mine.filter((g) => g.status === s).length
    const list = (query.status !== undefined ? mine.filter((g) => g.status === Number(query.status)) : mine).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
      .map((g) => ({ ...goodsItem(g), status: g.status, auditRemark: g.auditRemark, viewCount: g.viewCount, stock: g.stock, updatedAt: g.updatedAt }))
    return { ...page(list, query), counts }
  })
  get('/users/me/favorites', 'user', ({ uid, query }) => page(favorites.filter((f) => f.userId === uid).reverse()
    .map((f) => ({ g: goods.find((x) => x.id === f.goodsId), f })).filter(({ g }) => g && !g.deleted)
    .map(({ g, f }) => ({ ...goodsItem(g!), status: g!.status, favoritedAt: f.createdAt })), query))
  get('/users/me/wants', 'user', ({ uid, query }) => page(wants.filter((w) => w.userId === uid && !w.deleted && (query.status === undefined || w.status === Number(query.status)))
    .map((w) => ({ ...wantOut(w), isMine: true, responseCount: wantResponses.filter((r) => r.wantId === w.id).length })), query))
  get('/users/me/blocks', 'user', ({ uid, query }) => page(blocks.filter((b) => b.userId === uid).map((b) => ({ userId: b.blockedUserId, nickname: userById(b.blockedUserId)!.nickname, avatarUrl: null, createdAt: b.createdAt })), query))
  post('/users/:id/block', 'user', ({ uid, params }) => {
    if (uid === params.id) return err(400, '不能拉黑自己')
    if (!isBlocked(uid, params.id)) blocks.push({ userId: uid, blockedUserId: params.id, createdAt: fmt() })
    return null
  })
  del('/users/:id/block', 'user', ({ uid, params }) => {
    const i = blocks.findIndex((b) => b.userId === uid && b.blockedUserId === params.id)
    if (i >= 0) blocks.splice(i, 1)
    return null
  })
  get('/users/:id', 'optional', ({ uid, params }) => {
    const x = userById(params.id) ?? err(404, '用户不存在')
    const s = x.creditScore
    return {
      id: x.id, nickname: x.nickname, avatarUrl: x.avatarUrl, college: x.college, campusName: campusName(x.campusId), bio: x.bio,
      creditScore: s, creditLevel: creditLevel(s), badge: s >= 90, goodRate: goodRate(x.id),
      reviewCount: reviews.filter((r) => r.revieweeId === x.id && r.visible).length,
      onSaleCount: goods.filter((g) => g.sellerId === x.id && g.status === 2 && !g.deleted).length,
      soldCount: orders.filter((o) => o.sellerId === x.id && o.status === 70).length + 30,
      blocked: uid ? isBlocked(uid, x.id) : false, joinedAt: x.createdAt.slice(0, 10), status: x.status,
    }
  })
  get('/users/:id/goods', 'public', ({ params, query }) => page(goods.filter((g) => g.sellerId === params.id && g.status === 2 && !g.deleted).map(goodsItem), query))
  get('/users/:id/reviews', 'public', ({ params, query }) => page(reviews.filter((r) => r.revieweeId === params.id && r.visible && (!query.rating || r.rating === Number(query.rating))).reverse().map((r) => ({
    id: r.id, role: r.role, rating: r.rating, content: r.content, tags: r.tags, reply: r.reply, isDefault: r.isDefault,
    reviewer: brief(r.reviewerId), goodsTitle: orders.find((o) => o.id === r.orderId)?.goodsTitle ?? '数码配件', createdAt: r.createdAt,
  })), query))

  /* 5. 商品 */
  get('/goods', 'optional', ({ uid, query }) => {
    const kw = String(query.keyword ?? '').trim().toLowerCase()
    if (kw && !hotWords.includes(kw)) hotWords.push(kw)
    const cat = String(query.categoryId ?? '')
    const conds = ([] as string[]).concat(query.conditionLevel ?? []).map(Number)
    let list = goods.filter((g) => g.status === 2 && !g.deleted && !(uid && isBlocked(g.sellerId, uid)))
      .filter((g) => !kw || (g.title + g.description).toLowerCase().includes(kw))
      .filter((g) => !cat || g.categoryId === cat || categories.find((c) => c.id === g.categoryId)?.parentId === cat)
      .filter((g) => !query.campusId || g.campusId === query.campusId)
      .filter((g) => !query.priceMin || g.price >= Number(query.priceMin))
      .filter((g) => !query.priceMax || g.price <= Number(query.priceMax))
      .filter((g) => !conds.length || conds.includes(g.conditionLevel))
      .filter((g) => !query.tradeMode || g.tradeMode === 3 || g.tradeMode === Number(query.tradeMode))
      .filter((g) => query.free !== 'true' || g.price === 0)
    const sort = String(query.sort ?? 'DEFAULT')
    list = [...list].sort((a, b) => (sort === 'PRICE_ASC' ? a.price - b.price : sort === 'PRICE_DESC' ? b.price - a.price : (b.publishedAt ?? '').localeCompare(a.publishedAt ?? '')))
    return page(list.map(goodsItem), query)
  })
  get('/search/hot-words', 'public', () => hotWords.slice(0, 10))
  get('/goods/:id', 'optional', ({ uid, params }) => {
    const g = goodsById(params.id) ?? err(2001, '商品不存在')
    const isMine = g.sellerId === uid
    if (![2, 4, 5].includes(g.status) && !isMine) return err(2001, '商品不存在')
    g.viewCount++
    const s = userById(g.sellerId)!
    const reason = !uid ? 'NOT_LOGIN' : isMine ? 'SELF' : g.status !== 2 ? 'NOT_ON_SALE' : g.stock < 1 ? 'OUT_OF_STOCK' : isBlocked(g.sellerId, uid) ? 'BLOCKED' : null
    return {
      ...goodsItem(g), description: g.description, images: g.images, categoryId: g.categoryId, categoryPath: categoryPath(g.categoryId),
      location: g.location, stock: g.stock, status: g.status, auditRemark: isMine ? g.auditRemark : null, viewCount: g.viewCount,
      seller: { ...brief(s.id), creditScore: s.creditScore, badge: s.creditScore >= 90, goodRate: goodRate(s.id), onSaleCount: goods.filter((x) => x.sellerId === s.id && x.status === 2 && !x.deleted).length },
      favorited: !!uid && favorites.some((f) => f.userId === uid && f.goodsId === g.id), isMine, canBuy: reason === null, cannotBuyReason: reason,
    }
  })
  function saveGoods(uid: string, body: Record<string, any>, g?: GoodsRow) {
    const u = userById(uid)!
    const draft = !!body.draft
    if (!draft) {
      if (u.status !== 0) return err(1010, '账号已禁言，不能发布')
      if (u.creditScore < 40) return err(1011, '信用分不足，功能受限')
      if (!body.images?.length || body.images.length > 9) return err(400, '图片为 1–9 张', { images: '1–9 张' })
      const p = Number(body.price)
      if (!(p === 0 || (p >= 0.01 && p <= 99999))) return err(2005, '价格不合法')
      if (p === 0 && body.tradeMode !== 1) return err(2006, '免费送商品只能选择面交')
      if (u.creditScore < 60 && body.tradeMode !== 1) return err(2008, '信用分低于 60 不能使用担保交易')
      const c = categories.find((x) => x.id === body.categoryId)
      if (!c || c.parentId === '0' || !c.enabled) return err(2007, '分类不存在或已停用')
      if (!g && goods.filter((x) => x.sellerId === uid && x.status === 2).length >= 50) return err(2002, '同时在售商品已达 50 件')
    }
    const row: GoodsRow = g ?? {
      id: nextId(), sellerId: uid, campusId: u.campusId, categoryId: '', title: '', description: '', images: [], price: 0, originalPrice: null,
      conditionLevel: 2, tradeMode: 1, location: null, stock: 1, status: 0, auditRemark: null, viewCount: 0, favoriteCount: 0,
      publishedAt: null, createdAt: fmt(), updatedAt: fmt(), deleted: false, hitWords: [],
    }
    const onlyMinor = g && g.status === 2 && g.title === body.title && g.description === body.description && JSON.stringify(g.images) === JSON.stringify(body.images) && g.categoryId === body.categoryId
    Object.assign(row, {
      title: body.title ?? '', description: body.description ?? '', images: body.images ?? [], categoryId: body.categoryId ?? '',
      price: Number(body.price ?? 0), originalPrice: body.originalPrice ? Number(body.originalPrice) : null, conditionLevel: body.conditionLevel ?? 2,
      tradeMode: body.tradeMode ?? 1, campusId: body.campusId ?? u.campusId, location: body.location ?? null, stock: body.stock ?? 1, updatedAt: fmt(),
    })
    if (!g) goods.push(row)
    if (draft) { if (row.status !== 2) row.status = 0; return { id: row.id, status: row.status, hitWords: [] } }
    if (onlyMinor) return { id: row.id, status: 2, hitWords: [] }
    const hit = hitWordsIn(row.title + row.description)
    row.hitWords = hit
    row.auditRemark = null
    if (!hit.length && u.creditScore >= 80) { row.status = 2; row.publishedAt = fmt() } else row.status = 1
    return { id: row.id, status: row.status, hitWords: hit }
  }
  post('/goods', 'user', ({ uid, body }) => saveGoods(uid, body))
  put('/goods/:id', 'user', ({ uid, params, body }) => {
    const g = goodsById(params.id) ?? err(2001, '商品不存在')
    if (g.sellerId !== uid) return err(403, '无权操作', null, 403)
    if ([4, 5, 7].includes(g.status)) return err(2003, '当前状态不可执行此操作')
    return saveGoods(uid, body, g)
  })
  post('/goods/:id/off-shelf', 'user', ({ uid, params }) => {
    const g = goodsById(params.id) ?? err(2001, '商品不存在')
    if (g.sellerId !== uid) return err(403, '无权操作', null, 403)
    if (![1, 2].includes(g.status)) return err(2003, '当前状态不可执行此操作')
    g.status = 6
    return null
  })
  post('/goods/:id/on-shelf', 'user', ({ uid, params }) => {
    const g = goodsById(params.id) ?? err(2001, '商品不存在')
    if (g.sellerId !== uid || g.status !== 6) return err(2003, '当前状态不可执行此操作')
    const u = userById(uid)!
    if (u.status !== 0) return err(1010, '账号已禁言，不能发布')
    const hit = hitWordsIn(g.title + g.description)
    g.status = !hit.length && u.creditScore >= 80 ? 2 : 1
    g.hitWords = hit
    g.updatedAt = fmt()
    if (g.status === 2) g.publishedAt = fmt()
    return { id: g.id, status: g.status, hitWords: hit }
  })
  del('/goods/:id', 'user', ({ uid, params }) => {
    const g = goodsById(params.id) ?? err(2001, '商品不存在')
    if (g.sellerId !== uid || ![0, 3, 6].includes(g.status)) return err(2003, '当前状态不可执行此操作')
    g.deleted = true
    return null
  })
  const fav = (uid: string, id: string, on: boolean) => {
    const g = goodsById(id) ?? err(2001, '商品不存在')
    if (g.sellerId === uid) return err(2009, '不能收藏自己的商品')
    const i = favorites.findIndex((f) => f.userId === uid && f.goodsId === id)
    if (on && i < 0) { favorites.push({ userId: uid, goodsId: id, createdAt: fmt() }); g.favoriteCount++ }
    if (!on && i >= 0) { favorites.splice(i, 1); g.favoriteCount-- }
    return { favorited: on, favoriteCount: g.favoriteCount }
  }
  post('/goods/:id/favorite', 'user', ({ uid, params }) => fav(uid, params.id, true))
  del('/goods/:id/favorite', 'user', ({ uid, params }) => fav(uid, params.id, false))

  /* 6. 求购 */
  function wantOut(w: (typeof wants)[number]) {
    const c = categories.find((x) => x.id === w.categoryId)
    return {
      id: w.id, title: w.title, description: w.description, categoryId: w.categoryId, categoryName: c?.name ?? '', priceMin: w.priceMin == null ? null : money(w.priceMin),
      priceMax: w.priceMax == null ? null : money(w.priceMax), campusName: campusName(w.campusId), status: w.status, expireAt: w.expireAt, user: brief(w.userId), createdAt: w.createdAt,
    }
  }
  get('/wants', 'public', ({ query }) => {
    const cat = String(query.categoryId ?? '')
    const kw = String(query.keyword ?? '')
    return page(wants.filter((w) => !w.deleted && w.status === 0 && w.expireAt > fmt())
      .filter((w) => !cat || w.categoryId === cat || categories.find((c) => c.id === w.categoryId)?.parentId === cat)
      .filter((w) => !query.campusId || w.campusId === query.campusId).filter((w) => !kw || w.title.includes(kw))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map(wantOut), query)
  })
  get('/wants/:id', 'optional', ({ uid, params }) => {
    const w = wants.find((x) => x.id === params.id && !x.deleted) ?? err(3002, '求购帖不存在或已结束')
    if (w.status !== 0 && w.userId !== uid) return err(3002, '求购帖不存在或已结束')
    return { ...wantOut(w), isMine: w.userId === uid }
  })
  post('/wants', 'user', ({ uid, body }) => {
    const u = userById(uid)!
    if (u.status !== 0) return err(1010, '账号已禁言')
    if (wants.filter((w) => w.userId === uid && w.status === 0 && !w.deleted).length >= 10) return err(3001, '进行中的求购已达 10 条')
    if (![3, 7, 15, 30].includes(body.validDays)) return err(400, '有效期只能是 3/7/15/30 天')
    const id = nextId()
    wants.push({ id, userId: uid, campusId: body.campusId ?? u.campusId, categoryId: body.categoryId, title: maskWords(body.title), description: body.description ?? null,
      priceMin: body.priceMin ? Number(body.priceMin) : null, priceMax: body.priceMax ? Number(body.priceMax) : null, status: 0, expireAt: later(60 * 24 * body.validDays), createdAt: fmt(), deleted: false })
    return { id }
  })
  post('/wants/:id/close', 'user', ({ uid, params }) => {
    const w = wants.find((x) => x.id === params.id && x.userId === uid) ?? err(3002, '求购帖不存在或已结束')
    w.status = 1
    return null
  })
  del('/wants/:id', 'user', ({ uid, params }) => {
    const w = wants.find((x) => x.id === params.id && x.userId === uid) ?? err(3002, '求购帖不存在')
    w.deleted = true
    return null
  })

  /* 7. 聊天 */
  const mustConv = (id: string, uid: string) => {
    const c = convs.find((x) => x.id === id) ?? err(404, '会话不存在')
    if (c.small !== uid && c.big !== uid) return err(4001, '不是会话成员', null, 403)
    return c
  }
  post('/conversations', 'user', ({ uid, body }) => {
    const peer = String(body.peerUserId)
    if (peer === uid) return err(400, '不能和自己聊天')
    if (!userById(peer)) return err(404, '用户不存在')
    if (body.wantId) {
      const w = wants.find((x) => x.id === String(body.wantId) && !x.deleted && x.status === 0 && x.expireAt > fmt()) ?? err(3002, '求购帖不存在或已结束')
      if (w.userId !== peer) return err(400, '只能联系求购发布者')
      if (!wantResponses.some((r) => r.wantId === w.id && r.responderId === uid)) wantResponses.push({ wantId: w.id, responderId: uid })
    }
    let c = convOf(uid, peer)
    if (!c) {
      c = { id: nextId(), small: uid < peer ? uid : peer, big: uid < peer ? peer : uid, goodsId: null, lastMessage: null, lastMessageAt: null, smallUnread: 0, bigUnread: 0 }
      convs.push(c)
    }
    if (body.goodsId) c.goodsId = body.goodsId
    return convOut(c, uid)
  })
  get('/conversations', 'user', ({ uid, query }) => page(convs.filter((c) => c.small === uid || c.big === uid)
    .sort((a, b) => (b.lastMessageAt ?? '').localeCompare(a.lastMessageAt ?? '')).map((c) => {
      const o = convOut(c, uid)
      return { id: c.id, peer: o.peer, goods: o.goods, lastMessage: c.lastMessage, lastMessageAt: c.lastMessageAt, unread: c.small === uid ? c.smallUnread : c.bigUnread }
    }), query))
  get('/conversations/:id', 'user', ({ uid, params }) => convOut(mustConv(params.id, uid), uid))
  get('/conversations/:id/messages', 'user', ({ uid, params, query }) => {
    mustConv(params.id, uid)
    const size = Math.min(50, Number(query.size) || 30)
    let list = msgs.filter((m) => m.conversationId === params.id)
    if (query.beforeId) list = list.filter((m) => Number(m.id) < Number(query.beforeId))
    const slice = list.slice(-size)
    return { list: slice.map(msgOut), hasMore: list.length > size }
  })
  post('/conversations/:id/messages', 'user', ({ uid, params, body }) => {
    const c = mustConv(params.id, uid)
    const peer = peerOf(c, uid)
    if (isBlocked(peer, uid)) return err(4002, '对方已将你拉黑')
    if (userById(uid)!.status === 1) return err(4003, '你已被禁言')
    const type = Number(body.type)
    if (![1, 2, 3].includes(type)) return err(400, '消息类型不合法')
    let content = String(body.content ?? '')
    if (type === 1) { if (!content || content.length > 500) return err(400, '消息为 1–500 字'); content = maskWords(content) }
    const m: MsgRow = { id: nextMsgId(), conversationId: c.id, senderId: uid, type, content, createdAt: fmt() + '.000' }
    msgs.push(m)
    c.lastMessage = type === 1 ? content : type === 2 ? '[图片]' : '[商品]'
    c.lastMessageAt = fmt()
    if (c.small === peer) c.smallUnread++
    else c.bigUnread++
    const risk = /(微信|vx|wx|qq|\d{9,})/i.test(String(body.content)) ? '站外交易无保障，建议使用平台担保交易' : null
    // 模拟对方 3 秒后回复，便于观察轮询
    setTimeout(() => {
      msgs.push({ id: nextMsgId(), conversationId: c.id, senderId: peer, type: 1, content: '收到，我看一下～', createdAt: fmt() + '.000' })
      c.lastMessage = '收到，我看一下～'
      c.lastMessageAt = fmt()
      if (c.small === uid) c.smallUnread++
      else c.bigUnread++
    }, 3000)
    return { message: msgOut(m), clientMsgId: body.clientMsgId ?? null, riskTip: risk }
  })
  post('/conversations/:id/read', 'user', ({ uid, params }) => {
    const c = mustConv(params.id, uid)
    if (c.small === uid) c.smallUnread = 0
    else c.bigUnread = 0
    return { totalUnread: chatUnread(uid) }
  })
  const chatUnread = (uid: string) => convs.reduce((s, c) => s + (c.small === uid ? c.smallUnread : c.big === uid ? c.bigUnread : 0), 0)

  /* 8. 订单 */
  const idem = new Map<string, string>()
  post('/orders', 'user', ({ uid, body }) => {
    const prev = idem.get(body.requestId)
    if (prev) { const o = orders.find((x) => x.id === prev)!; return { id: o.id, orderNo: o.orderNo, status: o.status } }
    const g = goodsById(body.goodsId) ?? err(5001, '商品当前不可购买')
    if (g.sellerId === uid) return err(5003, '不能购买自己的商品')
    if (g.status !== 2) return err(5001, '商品当前不可购买')
    if (isBlocked(g.sellerId, uid)) return err(5004, '卖家已将你拉黑')
    const q = Number(body.quantity) || 1
    if (q > g.stock) return err(5002, '库存不足')
    const mode = Number(body.tradeMode)
    if (!(g.tradeMode === 3 || g.tradeMode === mode)) return err(5006, '商品不支持该交易方式')
    if (mode === 2 && userById(g.sellerId)!.creditScore < 60) return err(2008, '信用分低于 60 不能使用担保交易')
    g.stock -= q
    if (g.stock === 0) g.status = 4
    const id = nextId()
    const o: OrderRow = {
      id, orderNo: `${fmt().replace(/\D/g, '').slice(0, 12)}${id.slice(-3)}`, buyerId: uid, sellerId: g.sellerId, goodsId: g.id, goodsTitle: g.title, goodsCover: g.images[0],
      unitPrice: g.price, quantity: q, totalAmount: g.price * q, tradeMode: mode, status: mode === 1 ? 10 : 30, cancelReason: null, cancelBy: null,
      sellerConfirmedAt: null, paidAt: null, deliveredAt: null, finishedAt: null, canceledAt: null, reviewDeadline: null, createdAt: fmt(),
    }
    orders.push(o)
    idem.set(body.requestId, id)
    orderLogs.push({ orderId: id, fromStatus: null, toStatus: o.status, action: 'CREATE', operatorType: 1, operatorId: uid, remark: null, createdAt: fmt() })
    sysMsg(o, `对方已下单，订单号 ${o.orderNo}`)
    notify(o.sellerId, 1, '你有新订单', `「${g.title}」有人下单了`, 1, id)
    return { id, orderNo: o.orderNo, status: o.status, payExpireAt: mode === 2 ? later(15) : undefined }
  })
  get('/orders', 'user', ({ uid, query }) => {
    const r = query.role === 'SELLER' ? 'SELLER' : 'BUYER'
    const st = ([] as string[]).concat(query.status ?? []).map(Number)
    const mine = orders.filter((o) => (r === 'BUYER' ? o.buyerId : o.sellerId) === uid)
    const counts: Record<string, number> = {}
    for (const s of [10, 20, 30, 40, 50, 60, 70, 80, 90]) counts[s] = mine.filter((o) => o.status === s).length
    const list = mine.filter((o) => !st.length || st.includes(o.status)).sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map((o) => ({
      id: o.id, orderNo: o.orderNo, goodsId: o.goodsId, goodsTitle: o.goodsTitle, goodsCover: o.goodsCover, unitPrice: money(o.unitPrice), quantity: o.quantity,
      totalAmount: money(o.totalAmount), tradeMode: o.tradeMode, status: o.status, counterpart: brief(r === 'BUYER' ? o.sellerId : o.buyerId),
      actions: actions(o, uid), reviewed: reviews.some((x) => x.orderId === o.id && x.reviewerId === uid), createdAt: o.createdAt,
    }))
    return { ...page(list, query), counts }
  })
  get('/orders/:id', 'user', ({ uid, params }) => orderDetail(mustOrder(params.id, uid), uid))
  post('/orders/:id/seller-confirm', 'user', ({ uid, params }) => {
    const o = mustOrder(params.id, uid); need(o, uid, 'SELLER_CONFIRM')
    o.sellerConfirmedAt = fmt(); transit(o, 20, 'SELLER_CONFIRM', 2, uid); return null
  })
  post('/orders/:id/complete', 'user', ({ uid, params }) => {
    const o = mustOrder(params.id, uid); need(o, uid, 'COMPLETE')
    transit(o, 70, 'COMPLETE', 1, uid); finish(o); return null
  })
  post('/orders/:id/cancel', 'user', ({ uid, params, body }) => {
    const o = mustOrder(params.id, uid); need(o, uid, 'CANCEL')
    const reason = String(body.reason ?? '').trim()
    if (!reason || reason.length > 200) return err(400, '取消原因为 1–200 字')
    if (o.status === 20) addCredit(uid, -3, 4, o.id, '待面交状态取消订单')
    o.cancelReason = reason; o.cancelBy = o.buyerId === uid ? 1 : 2; o.canceledAt = fmt()
    const p = payments.find((x) => x.orderId === o.id && x.status === 0)
    if (p) p.status = 2
    transit(o, 80, 'CANCEL', o.cancelBy, uid, reason); restock(o); return null
  })
  post('/orders/:id/pay', 'user', ({ uid, params }) => {
    const o = mustOrder(params.id, uid)
    if (o.status !== 30 || o.buyerId !== uid) return err(5102, '订单状态不允许支付')
    let p = payments.find((x) => x.orderId === o.id && x.status === 0)
    if (!p) { p = { orderId: o.id, outTradeNo: `P${o.orderNo}${nextId().slice(-2)}`, amount: o.totalAmount, status: 0, paidAt: null }; payments.push(p) }
    // Mock 收银台：真实环境这里是支付宝返回的自动提交表单
    const payForm = `<form method="get" action="/api/v1/mock-alipay"><input type="hidden" name="orderId" value="${o.id}"></form><script>document.forms[0].submit();</script>`
    return { outTradeNo: p.outTradeNo, payForm, expireAt: later(15) }
  })
  get('/mock-alipay', 'public', ({ query }) => {
    const o = orders.find((x) => x.id === query.orderId)
    return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>支付宝沙箱（Mock）</title>
<body style="font-family:sans-serif;max-width:420px;margin:60px auto;padding:0 16px;text-align:center;color:#0f172a">
<p style="color:#64748b">支付宝沙箱收银台（本地 Mock）</p><h2>${o ? o.goodsTitle.replace(/[<>&]/g, '') : '订单不存在'}</h2>
<p style="font-size:32px;color:#c2410c;font-weight:700">¥${o ? money(o.totalAmount) : '0.00'}</p>
${o && o.status === 30 ? `<a href="/api/v1/mock-alipay/confirm?orderId=${o.id}" style="display:block;background:#1677ff;color:#fff;padding:14px;border-radius:10px;text-decoration:none;font-weight:600">确认付款</a>` : '<p>订单当前不需要支付</p>'}
<p><a href="/order/${o?.id ?? ''}?from=alipay" style="color:#64748b">返回商户</a></p></body></html>`
  })
  get('/mock-alipay/confirm', 'public', ({ query }) => {
    const o = orders.find((x) => x.id === query.orderId)
    const p = o && payments.find((x) => x.orderId === o.id && x.status === 0)
    if (o && p && o.status === 30) {
      p.status = 1; p.paidAt = fmt(); o.paidAt = fmt()
      transit(o, 40, 'PAY', 3, null)
    }
    return `<!doctype html><meta http-equiv="refresh" content="0;url=/order/${query.orderId}?from=alipay">`
  })
  get('/orders/:id/pay-status', 'user', ({ uid, params }) => {
    const o = mustOrder(params.id, uid)
    const p = [...payments].reverse().find((x) => x.orderId === o.id)
    return { orderStatus: o.status, paymentStatus: p?.status ?? 0, paidAt: p?.paidAt ?? null }
  })
  post('/orders/:id/deliver', 'user', ({ uid, params }) => {
    const o = mustOrder(params.id, uid); need(o, uid, 'DELIVER')
    o.deliveredAt = fmt(); transit(o, 50, 'DELIVER', 2, uid); return null
  })
  post('/orders/:id/confirm-receipt', 'user', ({ uid, params }) => {
    const o = mustOrder(params.id, uid); need(o, uid, 'CONFIRM_RECEIPT')
    transit(o, 70, 'CONFIRM_RECEIPT', 1, uid); release(o); finish(o); return null
  })

  /* 9. 售后 */
  post('/orders/:id/after-sales', 'user', ({ uid, params, body }) => {
    const o = mustOrder(params.id, uid)
    const type = Number(body.type)
    need(o, uid, type === 1 ? 'APPLY_CANCEL' : 'APPLY_REFUND')
    if (afterSales.some((a) => a.orderId === o.id && [0, 1, 2, 3].includes(a.status))) return err(9001, '该订单已有处理中的售后')
    if (type === 2 && !(body.evidenceUrls?.length >= 1)) return err(400, '申请退款需要上传凭证')
    const a: AfterSaleRow = {
      id: nextId(), orderId: o.id, type, buyerId: o.buyerId, sellerId: o.sellerId, reason: body.reason, evidenceUrls: body.evidenceUrls ?? [],
      refundAmount: o.totalAmount, status: 0, sellerReply: null, sellerDeadline: later(type === 1 ? 24 * 60 : 48 * 60), arbitratorId: null,
      arbitrationReason: null, appealRemark: null, refundedAt: null, closedAt: null, createdAt: fmt(),
    }
    afterSales.push(a)
    o.beforeAfterSale = o.status
    transit(o, 60, type === 1 ? 'APPLY_CANCEL' : 'APPLY_REFUND', 1, uid, body.reason)
    return asOut(a, uid)
  })
  const mustAs = (id: string, uid: string) => {
    const a = afterSales.find((x) => x.id === id) ?? err(404, '售后不存在')
    if (a.buyerId !== uid && a.sellerId !== uid) return err(403, '无权操作', null, 403)
    return a
  }
  get('/after-sales/:id', 'user', ({ uid, params }) => asOut(mustAs(params.id, uid), uid))
  post('/after-sales/:id/agree', 'user', ({ uid, params }) => {
    const a = mustAs(params.id, uid)
    if (a.sellerId !== uid || a.status !== 0) return err(9002, '售后状态不允许此操作')
    const o = orders.find((x) => x.id === a.orderId)!
    a.status = 1; a.refundedAt = fmt(); a.closedAt = fmt()
    const p = payments.find((x) => x.orderId === o.id && x.status === 1)
    if (p) p.status = 3
    transit(o, 90, 'AGREE_AFTER_SALE', 2, uid); restock(o); return null
  })
  post('/after-sales/:id/reject', 'user', ({ uid, params, body }) => {
    const a = mustAs(params.id, uid)
    if (a.sellerId !== uid || a.status !== 0) return err(9002, '售后状态不允许此操作')
    a.status = 2; a.sellerReply = body.reply
    const o = orders.find((x) => x.id === a.orderId)!
    if (a.type === 1) { a.closedAt = fmt(); transit(o, 40, 'REJECT_AFTER_SALE', 2, uid, body.reply) }
    else orderLogs.push({ orderId: o.id, fromStatus: 60, toStatus: 60, action: 'REJECT_AFTER_SALE', operatorType: 2, operatorId: uid, remark: body.reply, createdAt: fmt() })
    notify(a.buyerId, 1, '卖家拒绝了你的售后申请', body.reply, 1, o.id)
    return null
  })
  post('/after-sales/:id/appeal', 'user', ({ uid, params, body }) => {
    const a = mustAs(params.id, uid)
    if (a.buyerId !== uid || a.status !== 2 || a.type !== 2) return err(9002, '售后状态不允许此操作')
    a.status = 3; a.appealRemark = body.remark ?? null
    orderLogs.push({ orderId: a.orderId, fromStatus: 60, toStatus: 60, action: 'APPEAL', operatorType: 1, operatorId: uid, remark: body.remark ?? null, createdAt: fmt() })
    return null
  })
  post('/after-sales/:id/withdraw', 'user', ({ uid, params }) => {
    const a = mustAs(params.id, uid)
    if (a.buyerId !== uid || ![0, 2].includes(a.status)) return err(9002, '售后状态不允许此操作')
    a.status = 6; a.closedAt = fmt()
    const o = orders.find((x) => x.id === a.orderId)!
    transit(o, o.beforeAfterSale ?? 50, 'WITHDRAW_AFTER_SALE', 1, uid); return null
  })

  /* 10. 评价 */
  post('/orders/:id/reviews', 'user', ({ uid, params, body }) => {
    const o = mustOrder(params.id, uid)
    if (reviews.some((r) => r.orderId === o.id && r.reviewerId === uid)) return err(6002, '已评价过')
    if (!actions(o, uid).includes('REVIEW')) return err(6001, '当前不可评价')
    const r = role(o, uid)
    reviews.push({ id: nextId(), orderId: o.id, reviewerId: uid, revieweeId: r === 'BUYER' ? o.sellerId : o.buyerId, role: r === 'BUYER' ? 1 : 2,
      rating: body.rating, content: body.content ? maskWords(body.content) : null, tags: body.tags ?? [], reply: null, isDefault: false, visible: false, createdAt: fmt() })
    const both = reviews.filter((x) => x.orderId === o.id)
    if (both.length === 2) both.forEach((x) => { x.visible = true; if (x.rating === 1) addCredit(x.revieweeId, 1, 2, o.id); if (x.rating === 3) addCredit(x.revieweeId, -2, 3, o.id) })
    return null
  })
  post('/reviews/:id/reply', 'user', ({ uid, params, body }) => {
    const r = reviews.find((x) => x.id === params.id) ?? err(404, '评价不存在')
    if (r.revieweeId !== uid || !r.visible) return err(403, '无权操作', null, 403)
    if (r.reply) return err(6003, '已追加过解释')
    r.reply = body.reply
    return null
  })
  get('/review-tags', 'public', () => REVIEW_TAGS)

  /* 11. 通知 */
  get('/notifications', 'user', ({ uid, query }) => page(notis.filter((n) => n.userId === uid && (!query.type || n.type === Number(query.type)) && (query.isRead === undefined || String(n.isRead) === query.isRead))
    .map(({ userId: _u, ...n }) => n), query))
  get('/notifications/unread-count', 'user', ({ uid }) => {
    const mine = notis.filter((n) => n.userId === uid && !n.isRead)
    const byType: Record<string, number> = {}
    mine.forEach((n) => (byType[n.type] = (byType[n.type] ?? 0) + 1))
    return { notification: mine.length, chat: chatUnread(uid), byType }
  })
  post('/notifications/:id/read', 'user', ({ uid, params }) => {
    const n = notis.find((x) => x.id === params.id && x.userId === uid)
    if (n) n.isRead = true
    return null
  })
  post('/notifications/read-all', 'user', ({ uid, body }) => {
    let updated = 0
    notis.forEach((n) => { if (n.userId === uid && !n.isRead && (!body.type || n.type === body.type)) { n.isRead = true; updated++ } })
    return { updated }
  })

  /* 12. 举报 */
  post('/reports', 'user', ({ uid, body }) => {
    const owner = body.targetType === 1 ? goodsById(body.targetId)?.sellerId : body.targetType === 2 ? body.targetId : wants.find((w) => w.id === body.targetId)?.userId
    if (!owner) return err(404, '举报对象不存在')
    if (owner === uid) return err(7002, '不能举报自己')
    if (reports.some((r) => r.reporterId === uid && r.targetType === body.targetType && r.targetId === body.targetId && r.createdAt > ago(60 * 24))) return err(7001, '24 小时内已举报过该对象')
    if (body.reasonType === 5 && !body.description) return err(400, '选择「其他」时请填写说明')
    const id = nextId()
    reports.push({ id, reporterId: uid, targetType: body.targetType, targetId: body.targetId, reasonType: body.reasonType, description: body.description ?? null,
      evidenceUrls: body.evidenceUrls ?? [], status: 0, result: null, handlerId: null, handledAt: null, createdAt: fmt() })
    return { id }
  })
}
