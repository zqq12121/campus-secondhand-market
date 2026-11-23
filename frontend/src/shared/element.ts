/**
 * Element Plus 按需注册：只引入实际用到的组件和对应样式，替代整包 app.use(ElementPlus)。
 * 这里是两端共用的部分；后台额外的组件在 src/admin/element.ts。
 * 新增组件时把组件和它的样式入口一起补上（样式入口会自动带上依赖的 base / popper 等）。
 */
import type { App, Component } from 'vue'
import { ElButton, ElConfigProvider, ElDialog, ElInput, ElInputNumber, ElLoading, ElPagination, ElSwitch } from 'element-plus'

import 'element-plus/es/components/config-provider/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/dialog/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/input-number/style/css'
import 'element-plus/es/components/pagination/style/css'
import 'element-plus/es/components/switch/style/css'
import 'element-plus/es/components/loading/style/css'
// ElMessage / ElMessageBox 是在脚本里调用的服务，只需要样式
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'

export function installElement(app: App, extra: Component[] = []) {
  ;[ElConfigProvider, ElButton, ElDialog, ElInput, ElInputNumber, ElPagination, ElSwitch, ...extra].forEach((c) => app.use(c as never))
  app.use(ElLoading) // v-loading 指令
}
