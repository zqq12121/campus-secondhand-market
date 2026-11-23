/** 枚举取值与中文名，对应接口文档附录 A。key 为数据库数值。 */
import type { CreditLevel } from './types'

type Label = Record<number, string>

export const CONDITION: Label = { 1: '全新', 2: '几乎全新', 3: '轻微使用', 4: '明显使用' }
export const GOODS_TRADE_MODE: Label = { 1: '仅面交', 2: '仅担保', 3: '面交/担保' }
export const ORDER_TRADE_MODE: Label = { 1: '面交', 2: '担保交易' }
export const GOODS_STATUS: Label = {
  0: '草稿', 1: '待审核', 2: '在售', 3: '审核驳回', 4: '交易中', 5: '已售出', 6: '已下架', 7: '强制下架',
}
export const WANT_STATUS: Label = { 0: '进行中', 1: '已解决', 2: '已过期', 3: '已关闭' }
export const ORDER_STATUS: Label = {
  10: '待卖家确认', 20: '待面交', 30: '待支付', 40: '待交货', 50: '待收货',
  60: '售后中', 70: '已完成', 80: '已取消', 90: '已退款',
}
export const AFTER_SALE_TYPE: Label = { 1: '交货前取消退款', 2: '收货前申请退款' }
export const AFTER_SALE_STATUS: Label = {
  0: '待卖家处理', 1: '卖家已同意', 2: '卖家已拒绝', 3: '平台介入中', 4: '裁决退款', 5: '裁决放款', 6: '买家已撤销',
}
export const OPERATOR_TYPE: Label = { 1: '买家', 2: '卖家', 3: '系统', 4: '管理员' }
export const RATING: Label = { 1: '好评', 2: '中评', 3: '差评' }
export const REPORT_TARGET: Label = { 1: '商品', 2: '用户', 3: '求购帖' }
export const REPORT_REASON: Label = { 1: '禁售品', 2: '虚假信息', 3: '诈骗', 4: '辱骂骚扰', 5: '其他' }
export const REPORT_STATUS: Label = { 0: '待处理', 1: '成立', 2: '不成立' }
export const NOTIFICATION_TYPE: Label = { 1: '订单', 2: '审核', 3: '求购匹配', 4: '举报结果', 5: '信用分', 6: '系统公告', 7: '关注上新', 8: '降价提醒' }
export const CREDIT_APPEAL_STATUS: Label = { 0: '申诉处理中', 1: '申诉已通过', 2: '申诉被驳回' }
export const USER_STATUS: Label = { 0: '正常', 1: '禁言', 2: '封禁' }
export const ANNOUNCEMENT_TYPE: Label = { 1: '公告', 2: '轮播图' }

export const CREDIT_LEVEL: Record<CreditLevel, string> = {
  EXCELLENT: '信用极好', GOOD: '信用良好', FAIR: '信用一般', POOR: '信用较差', RESTRICTED: '受限',
}

/** 标签配色：对应 style.css 的 .tag-* 类 */
export type TagTone = '' | 'green' | 'orange' | 'red' | 'blue' | 'yellow'
export const ORDER_STATUS_TONE: Record<number, TagTone> = {
  10: 'yellow', 20: 'yellow', 30: 'orange', 40: 'blue', 50: 'blue', 60: 'red', 70: 'green', 80: '', 90: '',
}
export const GOODS_STATUS_TONE: Record<number, TagTone> = {
  0: '', 1: 'yellow', 2: 'green', 3: 'red', 4: 'blue', 5: '', 6: '', 7: 'red',
}
export const CREDIT_TONE: Record<CreditLevel, TagTone> = {
  EXCELLENT: 'green', GOOD: 'green', FAIR: 'yellow', POOR: 'orange', RESTRICTED: 'red',
}

export function label(map: Label, v: number | null | undefined): string {
  return v == null ? '' : map[v] ?? String(v)
}
