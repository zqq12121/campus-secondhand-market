/**
 * 订单操作：按接口返回的 actions（附录 C 动作码）执行，前端不自己推断状态。
 * 列表页和详情页共用。
 */
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import type { OrderAction } from '@/shared/types'
import { orderApi } from '../api'

export const ACTION_TEXT: Record<OrderAction, string> = {
  SELLER_CONFIRM: '确认订单', COMPLETE: '确认完成', CANCEL: '取消订单', PAY: '去支付', DELIVER: '已交货',
  CONFIRM_RECEIPT: '确认收货', APPLY_CANCEL: '申请取消退款', APPLY_REFUND: '申请退款', REVIEW: '去评价',
}
/** 主按钮（绿色 / 橙色），其他为描边按钮 */
export const PRIMARY_ACTIONS: OrderAction[] = ['SELLER_CONFIRM', 'COMPLETE', 'DELIVER', 'CONFIRM_RECEIPT', 'REVIEW']

/** 把支付宝返回的表单写进新窗口并提交，跳转到沙箱收银台（接口文档 8.7） */
export function openPayForm(html: string) {
  const w = window.open('', '_blank')
  if (!w) {
    ElMessage.error('浏览器拦截了支付窗口，请允许弹出窗口后重试')
    return false
  }
  w.document.open()
  w.document.write(html)
  w.document.close()
  return true
}

export function useOrderActions(onDone: () => void, openAfterSale?: (type: 1 | 2) => void) {
  const router = useRouter()

  async function confirm(title: string, message: string) {
    await ElMessageBox.confirm(message, title, { confirmButtonText: '确定', cancelButtonText: '再想想' })
  }

  async function run(action: OrderAction, id: string) {
    switch (action) {
      case 'SELLER_CONFIRM':
        await orderApi.sellerConfirm(id)
        ElMessage.success('已确认，记得和买家约好面交时间')
        break
      case 'COMPLETE':
        await confirm('确认完成交易', '请确认已当面验货并完成付款。确认后订单完成，不能再取消。')
        await orderApi.complete(id)
        ElMessage.success('交易完成，记得去评价')
        break
      case 'CANCEL': {
        const { value } = await ElMessageBox.prompt('取消原因（必填，最多 200 字）。待面交状态下取消会扣 3 信用分。', '取消订单', {
          confirmButtonText: '取消订单', cancelButtonText: '再想想', inputPattern: /^.{1,200}$/s, inputErrorMessage: '请填写 1–200 字的原因',
        })
        await orderApi.cancel(id, value.trim())
        ElMessage.success('订单已取消')
        break
      }
      case 'PAY': {
        const r = await orderApi.pay(id)
        if (openPayForm(r.payForm)) {
          ElMessage.info('已打开支付宝收银台，支付完成后回到本页查看结果')
          router.push({ name: 'order', params: { id }, query: { from: 'alipay' } })
        }
        return
      }
      case 'DELIVER':
        await confirm('确认已交货', '请确认已把商品交给买家。买家 7 天内未确认收货将自动确认。')
        await orderApi.deliver(id)
        ElMessage.success('已标记交货')
        break
      case 'CONFIRM_RECEIPT':
        await confirm('确认收货', '确认后货款将打给卖家，无法再申请退款。请确认商品没有问题。')
        await orderApi.confirmReceipt(id)
        ElMessage.success('已确认收货')
        break
      case 'APPLY_CANCEL':
        return openAfterSale ? openAfterSale(1) : router.push({ name: 'order', params: { id } })
      case 'APPLY_REFUND':
        return openAfterSale ? openAfterSale(2) : router.push({ name: 'order', params: { id } })
      case 'REVIEW':
        return router.push({ name: 'review', params: { id } })
    }
    onDone()
  }

  /** 用户在确认框点「再想想」时 Element Plus 会 reject，这里吞掉 */
  return (action: OrderAction, id: string) => run(action, id).catch((e) => { if (e !== 'cancel' && e !== 'close') throw e })
}
