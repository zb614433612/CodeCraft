import { createApp } from 'vue'
import { createPinia } from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import './style.css'
import App from './App.vue'
import router from './router'

// 导入Markdown扩展库并使其在全局可用
import hljs from 'highlight.js'
import katex from 'katex'

// 将库挂载到window对象，供markdown-it插件使用
if (typeof window !== 'undefined') {
  ;(window as any).hljs = hljs
  ;(window as any).katex = katex
}

const app = createApp(App)
const pinia = createPinia()
pinia.use(piniaPluginPersistedstate)

app.use(router)
app.use(pinia)
app.use(Antd)
app.mount('#app')
