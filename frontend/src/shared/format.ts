/** 展示用的格式化工具 */

/** 金额字符串 "12.50" → "12.5"；0 显示为"免费送" */
export function priceText(price: string | null | undefined): string {
  if (price == null) return ''
  const n = Number(price)
  if (n === 0) return '免费送'
  return Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/0$/, '')
}

/** "2026-11-19 21:30:00" → "11-19 21:30"；同一天只显示时间 */
export function shortTime(t: string | null | undefined): string {
  if (!t) return ''
  const [date, time = ''] = t.split(' ')
  const today = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  const todayStr = `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`
  const hm = time.slice(0, 5)
  return date === todayStr ? hm : `${date.slice(5)} ${hm}`.trim()
}

/** 距离截止时间还剩多久，用于订单倒计时 */
export function remaining(target: string | null | undefined, now = Date.now()): string {
  if (!target) return ''
  const ms = parseTime(target) - now
  if (ms <= 0) return '已到期'
  const m = Math.floor(ms / 60000)
  const d = Math.floor(m / 1440)
  const h = Math.floor((m % 1440) / 60)
  const mm = m % 60
  if (d > 0) return `${d} 天 ${h} 小时`
  if (h > 0) return `${h} 小时 ${mm} 分`
  const s = Math.floor((ms % 60000) / 1000)
  return `${mm}:${String(s).padStart(2, '0')}`
}

export function parseTime(t: string): number {
  return new Date(t.replace(' ', 'T') + '+08:00').getTime()
}

export function uuid(): string {
  return crypto.randomUUID()
}

/** 头像占位：取昵称首字 */
export function initial(name: string | null | undefined): string {
  return (name || '?').slice(0, 1)
}
