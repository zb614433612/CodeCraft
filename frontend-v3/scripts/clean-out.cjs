/*
 * 构建前清理产物目录
 * ----------------------------------------------------------------------------
 * 为什么需要这个脚本：`vite.config.ts` 里已经写了 `emptyOutDir: true`，但当
 * outDir 指向**项目根之外**（`../src/main/resources/v3-static`）时，实测 Vite 8
 * 并不会真的清空目录 —— 旧 hash 文件会一直累积：
 *
 *   v3-static/assets/index-Dsi2DrmP.js   ← 上一版
 *   v3-static/assets/index-CuJ6VGFu.js   ← 这一版
 *
 * 后果：jar 里塞进一堆永远加载不到的死文件，且排查「为什么改了没生效」时
 * 极容易被旧 chunk 误导。所以构建前显式删一次，不依赖打包器的实现细节。
 *
 * 只删本目录，不碰经典版的 src/main/resources/static。
 */
const fs = require('fs')
const path = require('path')

const target = path.resolve(__dirname, '..', '..', 'src', 'main', 'resources', 'v3-static')

if (fs.existsSync(target)) {
  fs.rmSync(target, { recursive: true, force: true })
  console.log('[clean-out] removed ' + target)
} else {
  console.log('[clean-out] nothing to remove: ' + target)
}
