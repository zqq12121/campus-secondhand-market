import { createApp } from 'vue'
import { createPinia } from 'pinia'
// 组件样式要先于 base.css / element.css 引入，主题覆盖才能生效
import { installElement } from '@/shared/element'
import { ADMIN_ELEMENTS } from './element'
import '@/shared/styles/base.css'
import '@/shared/styles/element.css'
import App from './App.vue'
import router from './router'

const app = createApp(App).use(createPinia()).use(router)
installElement(app, ADMIN_ELEMENTS)
app.mount('#app')
