/** 管理后台 Mock 接口（/api/admin/v1），对应接口文档第 13 章 */
import { route, type Auth, type Ctx } from './plugin'
import {
  addCredit, admins, afterSales, anns, balanceLogs, campusName, categories, convOf, creditAppeals, creditLogs, CREDIT_REASON, fmt, goods, later, MockError, money,
  msgs, nextId, notify, opLog, opLogs, orderLogs, orders, page, payments, PERMISSIONS, reports, roles, sensitiveWords, userById,
  users, wants, type AfterSaleRow, type OrderRow,
} from './db'

const get = (p: string, a: Auth, h: (c: Ctx) => unknown) => route('A GET', p, a, h)
const post = (p: string, a: Auth, h: (c: Ctx) => unknown) => route('A POST', p, a, h)
const put = (p: string, a: Auth, h: (c: Ctx) => unknown) => route('A PUT', p, a, h)
const del = (p: string, a: Auth, h: (c: Ctx) => unknown) => route('A DELETE', p, a, h)
const err = (code: number, msg: string, http = 200): never => { throw new MockError(code, msg, null, http) }

function permsOf(aid: string): string[] {
  const a = admins.find((x) => x.id === aid)
  if (!a || a.status !== 1) return []
  const ids = new Set(roles.filter((r) => a.roleIds.includes(r.id)).flatMap((r) => r.permissionIds))
  return PERMISSIONS.filter((p) => ids.has(p.id)).map((p) => p.code)
}
/** 权限校验包装：没有权限码返回 403 */
const need = (perm: string, h: (c: Ctx) => unknown) => (c: Ctx) => (permsOf(c.aid).includes(perm) ? h(c) : err(403, '没有权限执行此操作', 403))
const catName = (id: string) => categories.find((c) => c.id === id)?.name ?? ''
const n = (s: string | string[] | undefined) => (s === undefined ? undefined : Number(s))

function adminOrderOut(o: OrderRow) {
  return {
    id: o.id, orderNo: o.orderNo, goodsTitle: o.goodsTitle, goodsCover: o.goodsCover, totalAmount: money(o.totalAmount), tradeMode: o.tradeMode,
    status: o.status, buyer: { id: o.buyerId, nickname: userById(o.buyerId)!.nickname }, seller: { id: o.sellerId, nickname: userById(o.sellerId)!.nickname }, createdAt: o.createdAt,
  }
}
function asOut(a: AfterSaleRow) {
  return { id: a.id, orderId: a.orderId, type: a.type, reason: a.reason, evidenceUrls: a.evidenceUrls, refundAmount: money(a.refundAmount), status: a.status,
    sellerReply: a.sellerReply, sellerDeadline: a.sellerDeadline, arbitrationReason: a.arbitrationReason, refundedAt: a.refundedAt, closedAt: a.closedAt, actions: [], createdAt: a.createdAt }
}
function orderDetailOut(o: OrderRow) {
  const p = [...payments].reverse().find((x) => x.orderId === o.id)
  return {
    ...adminOrderOut(o), quantity: o.quantity, unitPrice: money(o.unitPrice), cancelReason: o.cancelReason,
    payment: p ? { outTradeNo: p.outTradeNo, status: p.status, amount: money(p.amount), paidAt: p.paidAt } : null,
    logs: orderLogs.filter((l) => l.orderId === o.id).map(({ orderId: _o, operatorId: _i, ...l }) => l),
    afterSales: afterSales.filter((a) => a.orderId === o.id).map(asOut),
  }
}
function forceOff(goodsId: string, reason: string, aid: string) {
  const g = goods.find((x) => x.id === goodsId) ?? err(404, '商品不存在')
  if ([5, 7].includes(g.status)) return err(2003, '当前状态不可执行此操作')
  if (orders.some((o) => o.goodsId === g.id && o.status < 70)) return err(2010, '商品有进行中的订单')
  g.status = 7
  g.auditRemark = reason
  notify(g.sellerId, 2, '商品被强制下架', `「${g.title}」：${reason}`, 2, g.id)
  opLog(aid, '商品管理', `强制下架：${reason}`, g.id)
}
function punishUser(id: string, status: 1 | 2, days: number | null, reason: string, aid: string) {
  const u = userById(id) ?? err(404, '用户不存在')
  u.status = status
  u.banUntil = days ? later(days * 24 * 60) : null
  if (status === 2) {
    goods.forEach((g) => { if (g.sellerId === id && [1, 2].includes(g.status)) g.status = 6 })
    wants.forEach((w) => { if (w.userId === id && w.status === 0) w.status = 3 })
  }
  opLog(aid, '用户管理', `${status === 1 ? '禁言' : '封禁'} ${days ? `${days} 天` : '永久'}：${reason}`, id)
}

/** 审核记录：只在内存里记录后台操作，系统初审从商品 hitWords 推出 */
const auditRecords: { goodsId: string; auditorType: number; auditorName: string | null; result: number; reason: string | null; hitWords: string | null; createdAt: string }[] = []

export function registerAdminRoutes() {
  /* 13.1 认证 */
  post('/auth/login', 'public', ({ body }) => {
    const a = admins.find((x) => x.username === body.username)
    if (!a || a.password !== body.password) return err(1005, '账号或密码错误')
    if (a.status !== 1) return err(403, '账号已停用', 403)
    a.lastLoginAt = fmt()
    return { token: `mock-admin.${a.id}`, expiresIn: 604800, admin: { id: a.id, username: a.username, realName: a.realName } }
  })
  post('/auth/logout', 'admin', () => null)
  get('/auth/me', 'admin', ({ aid }) => {
    const a = admins.find((x) => x.id === aid) ?? err(401, '未登录', 401)
    if (a.status !== 1) return err(401, '账号已停用', 401)
    const perms = permsOf(aid)
    const groups = [...new Set(PERMISSIONS.map((p) => p.group))]
    return {
      id: a.id, username: a.username, realName: a.realName, roles: roles.filter((r) => a.roleIds.includes(r.id)).map((r) => r.code), permissions: perms,
      menus: groups.map((g) => ({ code: g, name: g, children: PERMISSIONS.filter((p) => p.group === g && perms.includes(p.code)).map((p) => ({ code: p.code, name: p.name })) })).filter((m) => m.children.length),
    }
  })
  put('/auth/password', 'admin', ({ aid, body }) => {
    const a = admins.find((x) => x.id === aid)!
    if (a.password !== body.oldPassword) return err(1005, '当前密码错误')
    a.password = body.newPassword
    return null
  })

  /* 13.2 看板 */
  get('/dashboard/overview', 'admin', need('dashboard:view', () => {
    const today = fmt().slice(0, 10)
    const todayOrders = orders.filter((o) => o.createdAt.startsWith(today))
    return {
      today: { newUsers: users.filter((u) => u.createdAt.startsWith(today)).length + 12, newGoods: goods.filter((g) => g.createdAt.startsWith(today)).length + 30, newOrders: todayOrders.length + 15,
        gmv: money(orders.filter((o) => o.status === 70 && o.finishedAt?.startsWith(today)).reduce((s, o) => s + o.totalAmount, 0) + 1260) },
      total: { users: users.length + 3200, goods: goods.length + 8800, orders: orders.length + 5100, gmv: money(356000) },
      pending: { goodsAudit: goods.filter((g) => g.status === 1 && !g.deleted).length, reports: reports.filter((r) => r.status === 0).length, disputes: afterSales.filter((a) => a.status === 3).length },
      statUpdatedAt: fmt().slice(0, 14) + '00:00',
    }
  }))
  get('/dashboard/trend', 'admin', need('dashboard:view', ({ query }) => {
    const days = Math.min(90, Number(query.days) || 30)
    const dates: string[] = []
    const series = { newUsers: [] as number[], newGoods: [] as number[], newOrders: [] as number[], finishedOrders: [] as number[], gmv: [] as string[] }
    for (let i = days - 1; i >= 0; i--) {
      const d = new Date(Date.now() - i * 86400000)
      dates.push(fmt(d).slice(0, 10))
      const wave = Math.sin(i / 3) * 8 + (d.getDay() === 0 || d.getDay() === 6 ? 12 : 0)
      const o = Math.round(40 + wave + (i % 5))
      series.newUsers.push(Math.round(10 + wave / 2))
      series.newGoods.push(Math.round(60 + wave))
      series.newOrders.push(o)
      series.finishedOrders.push(Math.round(o * 0.8))
      series.gmv.push(money(o * 150 + wave * 120))
    }
    return { dates, ...series }
  }))
  get('/dashboard/category-share', 'admin', need('dashboard:view', () => {
    const top = categories.filter((c) => c.parentId === '0')
    const onSale = goods.filter((g) => g.status === 2 && !g.deleted)
    const count = (id: string) => onSale.filter((g) => g.categoryId === id || categories.find((c) => c.id === g.categoryId)?.parentId === id).length
    return top.map((c) => ({ categoryId: c.id, name: c.name, count: count(c.id), percent: ((count(c.id) / Math.max(onSale.length, 1)) * 100).toFixed(1) }))
  }))

  /* 13.3 审核 */
  get('/audit/goods', 'admin', need('goods:audit', ({ query }) => {
    const kw = String(query.keyword ?? '')
    const cat = String(query.categoryId ?? '')
    return page(goods.filter((g) => g.status === 1 && !g.deleted)
      .filter((g) => !kw || g.title.includes(kw) || userById(g.sellerId)!.nickname.includes(kw))
      .filter((g) => !cat || g.categoryId === cat || categories.find((c) => c.id === g.categoryId)?.parentId === cat)
      .sort((a, b) => a.updatedAt.localeCompare(b.updatedAt))
      .map((g) => ({ id: g.id, title: g.title, description: g.description, coverUrl: g.images[0], images: g.images, price: money(g.price), categoryName: catName(g.categoryId),
        seller: { id: g.sellerId, nickname: userById(g.sellerId)!.nickname, creditScore: userById(g.sellerId)!.creditScore }, hitWords: g.hitWords, submittedAt: g.updatedAt })), query)
  }))
  const audit = (id: string, ok: boolean, reason: string | null, aid: string) => {
    const g = goods.find((x) => x.id === id && x.status === 1 && !x.deleted) ?? err(409, '状态已变更，请刷新')
    g.status = ok ? 2 : 3
    if (ok) g.publishedAt = fmt()
    else g.auditRemark = reason
    auditRecords.push({ goodsId: id, auditorType: 2, auditorName: admins.find((a) => a.id === aid)!.realName, result: ok ? 1 : 2, reason, hitWords: null, createdAt: fmt() })
    notify(g.sellerId, 2, ok ? '商品审核通过' : '商品审核未通过', ok ? `「${g.title}」已上架` : `「${g.title}」：${reason}`, 2, g.id)
    opLog(aid, '商品审核', ok ? '通过' : `驳回：${reason}`, id)
  }
  post('/audit/goods/:id/approve', 'admin', need('goods:audit', ({ aid, params }) => { audit(params.id, true, null, aid); return null }))
  post('/audit/goods/:id/reject', 'admin', need('goods:audit', ({ aid, params, body }) => {
    if (!body.reason) return err(400, '请填写驳回原因')
    audit(params.id, false, body.reason, aid)
    return null
  }))
  post('/audit/goods/batch', 'admin', need('goods:audit', ({ aid, body }) => {
    const success: string[] = []
    const failed: { id: string; message: string }[] = []
    for (const id of body.ids as string[]) {
      try { audit(id, body.result === 'APPROVE', body.reason ?? null, aid); success.push(id) } catch (e) { failed.push({ id, message: (e as Error).message }) }
    }
    return { success, failed }
  }))
  get('/audit/goods/:id/records', 'admin', need('goods:audit', ({ params }) => {
    const g = goods.find((x) => x.id === params.id)
    const sys = g ? [{ id: '0', auditorType: 1, auditorName: null, result: g.hitWords.length ? 3 : 1, reason: null, hitWords: g.hitWords.join('、') || null, createdAt: g.createdAt }] : []
    return [...sys, ...auditRecords.filter((r) => r.goodsId === params.id).map((r, i) => ({ id: String(i + 1), ...r }))]
  }))

  /* 13.4 商品管理 */
  get('/goods', 'admin', need('goods:manage', ({ query }) => {
    const kw = String(query.keyword ?? '')
    return page(goods.filter((g) => !g.deleted && (query.status === undefined || g.status === n(query.status)))
      .filter((g) => !kw || g.title.includes(kw) || userById(g.sellerId)!.nickname.includes(kw))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .map((g) => ({ id: g.id, title: g.title, coverUrl: g.images[0], price: money(g.price), status: g.status, categoryName: catName(g.categoryId), campusName: campusName(g.campusId),
        seller: { id: g.sellerId, nickname: userById(g.sellerId)!.nickname }, auditRemark: g.auditRemark, reportCount: reports.filter((r) => r.targetType === 1 && r.targetId === g.id).length, createdAt: g.createdAt })), query)
  }))
  post('/goods/:id/force-off', 'admin', need('goods:manage', ({ aid, params, body }) => {
    if (!body.reason) return err(400, '请填写原因')
    forceOff(params.id, body.reason, aid)
    return null
  }))

  /* 13.5 用户 */
  get('/users', 'admin', need('user:view', ({ query }) => {
    const kw = String(query.keyword ?? '')
    return page(users.filter((u) => !kw || u.nickname.includes(kw) || u.email.includes(kw))
      .filter((u) => !query.campusId || u.campusId === query.campusId)
      .filter((u) => query.status === undefined || u.status === n(query.status))
      .filter((u) => query.creditMin === undefined || u.creditScore >= n(query.creditMin)!)
      .filter((u) => query.creditMax === undefined || u.creditScore <= n(query.creditMax)!)
      .map((u) => ({ id: u.id, email: u.email, nickname: u.nickname, campusName: campusName(u.campusId), college: u.college, creditScore: u.creditScore, status: u.status, banUntil: u.banUntil,
        onSaleCount: goods.filter((g) => g.sellerId === u.id && g.status === 2).length, soldCount: orders.filter((o) => o.sellerId === u.id && o.status === 70).length,
        boughtCount: orders.filter((o) => o.buyerId === u.id && o.status === 70).length, reportedCount: reports.filter((r) => r.targetType === 2 && r.targetId === u.id).length, createdAt: u.createdAt })), query)
  }))
  post('/users/:id/mute', 'admin', need('user:punish', ({ aid, params, body }) => { punishUser(params.id, 1, body.days ?? null, body.reason, aid); return null }))
  post('/users/:id/ban', 'admin', need('user:punish', ({ aid, params, body }) => { punishUser(params.id, 2, body.days ?? null, body.reason, aid); return null }))
  post('/users/:id/unban', 'admin', need('user:punish', ({ aid, params }) => {
    const u = userById(params.id) ?? err(404, '用户不存在')
    u.status = 0
    u.banUntil = null
    opLog(aid, '用户管理', '解除处罚', params.id)
    return null
  }))
  post('/users/:id/credit', 'admin', need('user:credit', ({ aid, params, body }) => {
    if (!body.reason || !body.delta) return err(400, '请填写调整值和原因')
    addCredit(params.id, Number(body.delta), 7, null, body.reason)
    notify(params.id, 5, `信用分 ${body.delta > 0 ? '+' : ''}${body.delta}`, `管理员调整：${body.reason}`)
    opLog(aid, '用户管理', `调整信用分 ${body.delta}：${body.reason}`, params.id)
    return null
  }))

  /* 13.6 订单 */
  get('/orders', 'admin', need('order:view', ({ query }) => page(orders
    .filter((o) => !query.orderNo || o.orderNo.includes(String(query.orderNo)))
    .filter((o) => !query.userId || o.buyerId === query.userId || o.sellerId === query.userId)
    .filter((o) => query.status === undefined || o.status === n(query.status))
    .filter((o) => query.tradeMode === undefined || o.tradeMode === n(query.tradeMode))
    .filter((o) => !query.startTime || o.createdAt >= String(query.startTime))
    .filter((o) => !query.endTime || o.createdAt <= String(query.endTime))
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt)).map(adminOrderOut), query)))
  get('/orders/:id', 'admin', need('order:view', ({ params }) => orderDetailOut(orders.find((o) => o.id === params.id) ?? err(404, '订单不存在'))))

  /* 13.7 纠纷 */
  get('/disputes', 'admin', need('dispute:handle', ({ query }) => {
    const st = query.status === undefined ? 3 : n(query.status)
    return page(afterSales.filter((a) => a.status === st).map((a) => {
      const o = orders.find((x) => x.id === a.orderId)!
      const appeal = orderLogs.find((l) => l.orderId === o.id && l.action === 'APPEAL')
      return { id: a.id, orderId: o.id, orderNo: o.orderNo, goodsTitle: o.goodsTitle, refundAmount: money(a.refundAmount), reason: a.reason, status: a.status,
        buyer: { id: o.buyerId, nickname: userById(o.buyerId)!.nickname }, seller: { id: o.sellerId, nickname: userById(o.sellerId)!.nickname }, appealedAt: appeal?.createdAt ?? a.createdAt }
    }), query)
  }))
  const disputeMsgs = (a: AfterSaleRow, beforeId?: string, size = 50) => {
    const o = orders.find((x) => x.id === a.orderId)!
    const c = convOf(o.buyerId, o.sellerId)
    let list = c ? msgs.filter((m) => m.conversationId === c.id && m.createdAt >= o.createdAt) : []
    if (beforeId) list = list.filter((m) => Number(m.id) < Number(beforeId))
    return { list: list.slice(-size), hasMore: list.length > size }
  }
  get('/disputes/:id', 'admin', need('dispute:handle', ({ params }) => {
    const a = afterSales.find((x) => x.id === params.id) ?? err(404, '纠纷不存在')
    const m = disputeMsgs(a)
    return { afterSale: asOut(a), order: orderDetailOut(orders.find((o) => o.id === a.orderId)!), buyerAppealRemark: a.appealRemark, messages: m.list, messagesHasMore: m.hasMore }
  }))
  get('/disputes/:id/messages', 'admin', need('dispute:handle', ({ params, query }) => {
    const a = afterSales.find((x) => x.id === params.id) ?? err(404, '纠纷不存在')
    return disputeMsgs(a, query.beforeId as string | undefined, Number(query.size) || 50)
  }))
  post('/disputes/:id/arbitrate', 'admin', need('dispute:handle', ({ aid, params, body }) => {
    const a = afterSales.find((x) => x.id === params.id) ?? err(404, '纠纷不存在')
    if (a.status !== 3) return err(9002, '售后状态不允许此操作')
    if (!body.reason) return err(400, '请填写裁决理由')
    const o = orders.find((x) => x.id === a.orderId)!
    const refund = body.result === 'REFUND'
    a.status = refund ? 4 : 5
    a.arbitratorId = aid
    a.arbitrationReason = body.reason
    a.closedAt = fmt()
    orderLogs.push({ orderId: o.id, fromStatus: 60, toStatus: refund ? 90 : 70, action: refund ? 'ARBITRATE_REFUND' : 'ARBITRATE_RELEASE', operatorType: 4, operatorId: aid, remark: body.reason, createdAt: fmt() })
    o.status = refund ? 90 : 70
    if (refund) {
      a.refundedAt = fmt()
      const p = payments.find((x) => x.orderId === o.id && x.status === 1)
      if (p) p.status = 3
      const g = goods.find((x) => x.id === o.goodsId)
      if (g) { g.stock += o.quantity; if (g.status === 4) g.status = 2 }
      addCredit(o.sellerId, -5, 5, o.id)
    } else {
      const s = userById(o.sellerId)!
      s.balance += o.totalAmount
      balanceLogs.push({ id: nextId(), userId: s.id, amount: o.totalAmount, balanceAfter: s.balance, type: 1, orderId: o.id, createdAt: fmt() })
      o.finishedAt = fmt()
      o.reviewDeadline = later(60 * 24 * 15)
      addCredit(o.buyerId, -5, 5, o.id)
    }
    const text = refund ? '平台裁决：全额退款给买家' : '平台裁决：货款放给卖家'
    notify(o.buyerId, 1, text, body.reason, 1, o.id)
    notify(o.sellerId, 1, text, body.reason, 1, o.id)
    opLog(aid, '纠纷裁决', refund ? '全额退款给买家' : '放款给卖家', a.id)
    return null
  }))

  /* 13.8 举报 */
  const targetTitle = (t: number, id: string) => (t === 1 ? goods.find((g) => g.id === id)?.title : t === 2 ? userById(id)?.nickname : wants.find((w) => w.id === id)?.title) ?? id
  /* 信用分申诉（P2） */
  get('/credit-appeals', 'admin', need('user:credit-appeal', ({ query }) => page([...creditAppeals].reverse()
    .filter((a) => query.status === undefined || a.status === n(query.status))
    .map((a) => {
      const u = userById(a.userId)
      const l = creditLogs.find((c) => c.id === a.creditLogId)
      return { id: a.id, userId: a.userId, nickname: u?.nickname ?? null, creditScore: u?.creditScore ?? null, creditLogId: a.creditLogId, delta: a.delta,
        reasonText: l ? CREDIT_REASON[l.reasonType] : null, logRemark: l?.remark ?? null, logCreatedAt: l?.createdAt ?? null, reason: a.reason,
        status: a.status, reply: a.reply, handlerId: null, handledAt: a.handledAt, createdAt: a.createdAt }
    }), query)))
  post('/credit-appeals/:id/handle', 'admin', need('user:credit-appeal', ({ params, body }) => {
    const a = creditAppeals.find((x) => x.id === params.id && x.status === 0) ?? err(409, '申诉已处理')
    const reply = String(body.reply ?? '').trim()
    if (!reply) return err(400, '请填写处理说明')
    a.status = body.approve ? 1 : 2; a.reply = reply; a.handledAt = fmt()
    if (body.approve) addCredit(a.userId, -a.delta, 8, a.id, `信用分申诉通过：${reply}`)
    else notify(a.userId, 5, '信用分申诉未通过', reply)
    return null
  }))

  get('/reports', 'admin', need('report:handle', ({ query }) => page(reports
    .filter((r) => r.status === (query.status === undefined ? 0 : n(query.status)))
    .filter((r) => !query.targetType || r.targetType === n(query.targetType))
    .filter((r) => !query.reasonType || r.reasonType === n(query.reasonType))
    .map((r) => ({ id: r.id, reporter: { id: r.reporterId, nickname: userById(r.reporterId)!.nickname }, targetType: r.targetType, targetId: r.targetId,
      targetTitle: targetTitle(r.targetType, r.targetId), reasonType: r.reasonType, description: r.description, evidenceUrls: r.evidenceUrls, status: r.status, result: r.result,
      targetReportCount: reports.filter((x) => x.targetType === r.targetType && x.targetId === r.targetId).length, createdAt: r.createdAt })), query)))
  post('/reports/:id/handle', 'admin', need('report:handle', ({ aid, params, body }) => {
    const r = reports.find((x) => x.id === params.id && x.status === 0) ?? err(409, '状态已变更，请刷新')
    if (!body.result) return err(400, '请填写处理说明')
    const actions: string[] = body.established ? body.actions ?? [] : []
    if (body.established && r.reasonType === 3 && !actions.includes('BAN')) return err(400, '诈骗举报成立时必须封禁')
    const owner = r.targetType === 1 ? goods.find((g) => g.id === r.targetId)?.sellerId : r.targetType === 2 ? r.targetId : wants.find((w) => w.id === r.targetId)?.userId
    if (actions.includes('OFF_SHELF')) { try { forceOff(r.targetId, body.result, aid) } catch { /* 已下架等情况忽略 */ } }
    if (actions.includes('CLOSE_WANT')) { const w = wants.find((x) => x.id === r.targetId); if (w) w.status = 3 }
    if (actions.includes('DEDUCT_CREDIT') && owner) addCredit(owner, r.reasonType === 3 ? -30 : -5, 6, r.id, body.result)
    if (actions.includes('BAN') && owner) punishUser(owner, 2, body.banDays ?? null, body.result, aid)
    const same = reports.filter((x) => x.status === 0 && x.targetType === r.targetType && x.targetId === r.targetId)
    same.forEach((x) => { x.status = body.established ? 1 : 2; x.result = body.result; x.handlerId = aid; x.handledAt = fmt(); notify(x.reporterId, 4, '你的举报已处理', body.result) })
    if (owner && body.established) notify(owner, 4, '你被举报的内容已处理', body.result)
    opLog(aid, '举报处理', `${body.established ? '成立' : '不成立'}：${body.result}`, r.id)
    return { affected: same.length }
  }))

  /* 13.9 分类 */
  const catNode = (c: (typeof categories)[number]) => ({
    ...c, goodsCount: goods.filter((g) => !g.deleted && (g.categoryId === c.id || categories.find((x) => x.id === g.categoryId)?.parentId === c.id)).length,
  })
  get('/categories', 'admin', need('category:manage', () => categories.filter((c) => c.parentId === '0').sort((a, b) => a.sort - b.sort)
    .map((c) => ({ ...catNode(c), children: categories.filter((x) => x.parentId === c.id).sort((a, b) => a.sort - b.sort).map(catNode) }))))
  post('/categories', 'admin', need('category:manage', ({ aid, body }) => {
    if (body.parentId !== '0' && categories.find((c) => c.id === body.parentId)?.parentId !== '0') return err(400, '只支持两级分类')
    const id = nextId()
    categories.push({ id, parentId: body.parentId, name: body.name, iconUrl: body.iconUrl ?? null, sort: body.sort ?? 0, enabled: true })
    opLog(aid, '分类管理', `新增分类：${body.name}`, id)
    return { id }
  }))
  put('/categories/:id', 'admin', need('category:manage', ({ aid, params, body }) => {
    const c = categories.find((x) => x.id === params.id) ?? err(404, '分类不存在')
    Object.assign(c, { name: body.name ?? c.name, iconUrl: body.iconUrl ?? c.iconUrl, sort: body.sort ?? c.sort })
    opLog(aid, '分类管理', `修改分类：${c.name}`, c.id)
    return null
  }))
  put('/categories/:id/enabled', 'admin', need('category:manage', ({ aid, params, body }) => {
    const c = categories.find((x) => x.id === params.id) ?? err(404, '分类不存在')
    c.enabled = !!body.enabled
    if (!c.enabled && c.parentId === '0') categories.forEach((x) => { if (x.parentId === c.id) x.enabled = false })
    opLog(aid, '分类管理', `${c.enabled ? '启用' : '停用'}分类：${c.name}`, c.id)
    return null
  }))
  del('/categories/:id', 'admin', need('category:manage', ({ aid, params }) => {
    const i = categories.findIndex((x) => x.id === params.id)
    if (i < 0) return err(404, '分类不存在')
    const c = categories[i]
    if (categories.some((x) => x.parentId === c.id) || goods.some((g) => g.categoryId === c.id && !g.deleted)) return err(8001, '分类下有商品或子分类，只能停用')
    categories.splice(i, 1)
    opLog(aid, '分类管理', `删除分类：${c.name}`, c.id)
    return null
  }))

  /* 13.10 内容 */
  get('/announcements', 'admin', need('content:manage', ({ query }) => page(anns
    .filter((a) => !query.type || a.type === n(query.type)).filter((a) => query.status === undefined || a.status === n(query.status))
    .sort((a, b) => a.type - b.type || a.sort - b.sort), query)))
  post('/announcements', 'admin', need('content:manage', ({ aid, body }) => {
    if (body.type === 2 && !body.imageUrl) return err(400, '轮播图必须上传图片')
    const id = nextId()
    anns.push({ id, type: body.type, title: body.title, content: body.content ?? null, imageUrl: body.imageUrl || null, linkUrl: body.linkUrl || null, sort: body.sort ?? 0, status: body.status ?? 1, createdBy: aid, createdAt: fmt(), updatedAt: fmt() })
    if (body.type === 1 && body.notifyUsers) users.forEach((u) => notify(u.id, 6, '平台公告', body.title))
    opLog(aid, '内容运营', `新增${body.type === 1 ? '公告' : '轮播图'}：${body.title}`, id)
    return { id }
  }))
  put('/announcements/:id', 'admin', need('content:manage', ({ aid, params, body }) => {
    const a = anns.find((x) => x.id === params.id) ?? err(404, '不存在')
    Object.assign(a, { title: body.title ?? a.title, content: body.content ?? a.content, imageUrl: body.imageUrl ?? a.imageUrl, linkUrl: body.linkUrl ?? a.linkUrl, sort: body.sort ?? a.sort, status: body.status ?? a.status, updatedAt: fmt() })
    opLog(aid, '内容运营', `修改：${a.title}`, a.id)
    return null
  }))
  put('/announcements/:id/status', 'admin', need('content:manage', ({ aid, params, body }) => {
    const a = anns.find((x) => x.id === params.id) ?? err(404, '不存在')
    a.status = Number(body.status)
    a.updatedAt = fmt()
    opLog(aid, '内容运营', `${a.status ? '上线' : '下线'}：${a.title}`, a.id)
    return null
  }))
  del('/announcements/:id', 'admin', need('content:manage', ({ aid, params }) => {
    const i = anns.findIndex((x) => x.id === params.id)
    if (i >= 0) { opLog(aid, '内容运营', `删除：${anns[i].title}`, params.id); anns.splice(i, 1) }
    return null
  }))
  get('/sensitive-words', 'admin', need('content:manage', ({ query }) => page(sensitiveWords.filter((w) => !query.keyword || w.word.includes(String(query.keyword))), query)))
  post('/sensitive-words', 'admin', need('content:manage', ({ aid, body }) => {
    let added = 0
    let skipped = 0
    for (const w of body.words as string[]) {
      if (sensitiveWords.some((x) => x.word === w)) skipped++
      else { sensitiveWords.push({ id: nextId(), word: w, createdAt: fmt() }); added++ }
    }
    opLog(aid, '内容运营', `新增敏感词 ${added} 个`)
    return { added, skipped }
  }))
  del('/sensitive-words/:id', 'admin', need('content:manage', ({ aid, params }) => {
    const i = sensitiveWords.findIndex((x) => x.id === params.id)
    if (i >= 0) { opLog(aid, '内容运营', `删除敏感词：${sensitiveWords[i].word}`); sensitiveWords.splice(i, 1) }
    return null
  }))

  /* 13.11 系统 */
  const adminOut = (a: (typeof admins)[number]) => ({ id: a.id, username: a.username, realName: a.realName, status: a.status, roles: roles.filter((r) => a.roleIds.includes(r.id)).map((r) => ({ id: r.id, name: r.name })), lastLoginAt: a.lastLoginAt, createdAt: a.createdAt })
  get('/admins', 'admin', need('system:admin', ({ query }) => page(admins.map(adminOut), query)))
  post('/admins', 'admin', need('system:admin', ({ aid, body }) => {
    if (admins.some((a) => a.username === body.username)) return err(400, '账号已存在')
    const id = nextId()
    admins.push({ id, username: body.username, password: body.password, realName: body.realName, status: 1, roleIds: body.roleIds, lastLoginAt: null, createdAt: fmt() })
    opLog(aid, '系统管理', `新增管理员：${body.username}`, id)
    return { id }
  }))
  put('/admins/:id', 'admin', need('system:admin', ({ aid, params, body }) => {
    const a = admins.find((x) => x.id === params.id) ?? err(404, '不存在')
    a.realName = body.realName
    a.roleIds = body.roleIds
    opLog(aid, '系统管理', `修改管理员：${a.username}`, a.id)
    return null
  }))
  put('/admins/:id/status', 'admin', need('system:admin', ({ aid, params, body }) => {
    if (params.id === aid) return err(9101, '不能停用自己或删除最后一个超级管理员')
    const a = admins.find((x) => x.id === params.id) ?? err(404, '不存在')
    a.status = Number(body.status)
    opLog(aid, '系统管理', `${a.status ? '启用' : '停用'}管理员：${a.username}`, a.id)
    return null
  }))
  put('/admins/:id/password', 'admin', need('system:admin', ({ aid, params, body }) => {
    const a = admins.find((x) => x.id === params.id) ?? err(404, '不存在')
    a.password = body.password
    opLog(aid, '系统管理', `重置密码：${a.username}`, a.id)
    return null
  }))
  get('/roles', 'admin', (c) => (permsOf(c.aid).some((p) => p === 'system:role' || p === 'system:admin') ? roles.map((r) => ({ id: r.id, code: r.code, name: r.name, builtIn: r.builtIn, adminCount: admins.filter((a) => a.roleIds.includes(r.id)).length })) : err(403, '没有权限执行此操作', 403)))
  post('/roles', 'admin', need('system:role', ({ aid, body }) => {
    if (roles.some((r) => r.code === body.code)) return err(400, '角色编码已存在')
    const id = nextId()
    roles.push({ id, code: body.code, name: body.name, builtIn: false, permissionIds: [] })
    opLog(aid, '系统管理', `新增角色：${body.name}`, id)
    return { id }
  }))
  put('/roles/:id', 'admin', need('system:role', ({ params, body }) => { const r = roles.find((x) => x.id === params.id) ?? err(404, '不存在'); r.name = body.name; return null }))
  del('/roles/:id', 'admin', need('system:role', ({ aid, params }) => {
    const i = roles.findIndex((x) => x.id === params.id)
    if (i < 0) return err(404, '不存在')
    if (roles[i].builtIn) return err(400, '内置角色不可删除')
    if (admins.some((a) => a.roleIds.includes(params.id))) return err(400, '有管理员绑定该角色，不可删除')
    opLog(aid, '系统管理', `删除角色：${roles[i].name}`, params.id)
    roles.splice(i, 1)
    return null
  }))
  get('/permissions', 'admin', need('system:role', () => [...new Set(PERMISSIONS.map((p) => p.group))].map((g, i) => ({
    id: `g${i}`, code: g, name: g, children: PERMISSIONS.filter((p) => p.group === g).map(({ group: _g, ...p }) => p),
  }))))
  get('/roles/:id/permissions', 'admin', need('system:role', ({ params }) => (roles.find((r) => r.id === params.id) ?? err(404, '不存在')).permissionIds))
  put('/roles/:id/permissions', 'admin', need('system:role', ({ aid, params, body }) => {
    const r = roles.find((x) => x.id === params.id) ?? err(404, '不存在')
    if (r.code === 'SUPER_ADMIN') return err(400, '超级管理员权限不可修改')
    const roleOnly = PERMISSIONS.find((p) => p.code === 'system:role')!.id
    r.permissionIds = (body.permissionIds as string[]).filter((id) => id !== roleOnly)
    opLog(aid, '系统管理', `修改角色权限：${r.name}`, r.id)
    return null
  }))
  get('/operation-logs', 'admin', need('system:log', ({ query }) => page(opLogs
    .filter((l) => !query.adminId || l.adminId === query.adminId).filter((l) => !query.module || l.module === query.module)
    .map((l) => ({ id: l.id, adminName: admins.find((a) => a.id === l.adminId)?.username ?? l.adminId, module: l.module, action: l.action, targetId: l.targetId, detail: l.detail, ip: l.ip, createdAt: l.createdAt })), query)))
}
