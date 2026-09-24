import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue({
      template: {
        compilerOptions: {
          // 允许使用 Electron 的 <webview> 标签（「网页标签页」内嵌外部网页用）
          isCustomElement: (tag) => tag === 'webview'
        }
      }
    })
  ],
  base: '',
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  build: {
    outDir: resolve(__dirname, '..', 'src', 'main', 'resources', 'static'),
    emptyOutDir: true
  }
})
