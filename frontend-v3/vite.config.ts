import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * Vite 配置（v3）
 * ----------------------------------------------------------------------------
 * 与旧项目（v2）保持一致的几个关键点：
 *  1) `base: './'` —— Electron 打包后以 file:// 加载，绝对路径 `/assets/x.js` 会 404；
 *  2) `@` 别名 —— 旧项目通篇 `@/api/xxx`、`@/store/xxx`，迁移时不必改 import 路径；
 *  3) `isCustomElement: webview` —— 「网页标签页」内嵌外部页面用的是 Electron 的 <webview>；
 *  4) 样式真相源是根目录 `assets/`，由 `src/main.ts` 直接 import，不复制副本，
 *     这样 `preview.html`（静态预览）与 Vue 应用共用同一份 CSS。
 * ----------------------------------------------------------------------------
 * 注意：`index.html` 是 Vite 入口；静态预览页已改名 `preview.html`（用浏览器直接打开即可）。
 */
export default defineConfig({
  plugins: [
    vue({
      template: {
        compilerOptions: {
          isCustomElement: (tag) => tag === 'webview'
        }
      }
    })
  ],
  base: './',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    // 显式监听所有地址：Vite 8 在部分 Windows 环境下只绑 [::1]，
    // 导致 127.0.0.1 / 局域网 / Electron 调试连不上（IPv4 回环不监听）。
    host: true,
    port: 5173,
    strictPort: false,
    open: false,
    /**
     * 后端联调代理（L1 数据层收口时补上）
     * --------------------------------------------------------------------------
     * `utils/http-client.ts` 里所有请求都以 `/api` 开头（相对地址），
     * 开发期由 Vite 转发到本机 Spring Boot 服务；否则请求会打到 5173 自己身上，
     * 返回 index.html 而非 JSON，表现为「接口返回空/解析失败」。
     *
     * 端口注意：后端 `application.yml` 的 `server.port` 是 **8084**（不是默认 8080），
     * 可用环境变量覆盖：`CC_BACKEND=http://192.168.1.9:8084 npm run dev`。
     *
     * 生产/Electron（file://）下没有代理，需改为直连后端地址（见 README「已知项」）。
     */
    proxy: {
      '/api': {
        target: process.env.CC_BACKEND || 'http://127.0.0.1:8084',
        changeOrigin: true
      }
    }
  },
  build: {
    /**
     * 产物落到后端独立资源目录 `src/main/resources/v3-static/`。
     *
     * <p><b>为什么不放在 `static/v3/`</b>（最初就是这么做的，踩了坑）：
     * 经典版（v2）的构建把 `outDir` 指向 `src/main/resources/static`，且带
     * `emptyOutDir: true` —— 每次 `mvn package`（pom 里有 frontend-maven-plugin）
     * 或手动 `npm run build` 都会**清空整个 static 目录**，把 `static/v3/` 一起删掉。
     * 结果就是「刚构建完 v3，打个包又没了」。
     *
     * <p>改成独立目录后两套产物彻底解耦：旧版随便清 static，v3 不受影响；
     * 由后端 `SpaConfig` 用 ResourceHandler 把 `/v3/**` 映射到 `classpath:/v3-static/`。
     *
     * <p>配合 `base: './'`，产物里的资源引用是 `./assets/x.js`，在 `/v3/` 下解析为
     * `/v3/assets/x.js`；因此**不要**改成绝对 base `/v3/`，否则 Electron 下以
     * `file://` 打开会全 404。</p>
     */
    outDir: fileURLToPath(new URL('../src/main/resources/v3-static', import.meta.url)),
    emptyOutDir: true,
    // Electron 内核固定，无需兼容老浏览器
    target: 'chrome120',
    sourcemap: false,
    chunkSizeWarningLimit: 1500
  },
  css: {
    devSourcemap: true
  }
})
