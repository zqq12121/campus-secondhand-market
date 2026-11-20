/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** true：请求由 mock/ 下的本地 Mock 服务处理；false：代理到 VITE_API_TARGET */
  readonly VITE_USE_MOCK: string
  /** 真实后端地址，仅开发代理使用，如 http://localhost:8080 */
  readonly VITE_API_TARGET: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<object, object, unknown>
  export default component
}
