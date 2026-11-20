import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { installElement } from '@/shared/element'
import '@/shared/styles/base.css'
import '@/shared/styles/element.css'
import App from './App.vue'
import router from './router'

const app = createApp(App).use(createPinia()).use(router)
installElement(app)
app.mount('#app')
