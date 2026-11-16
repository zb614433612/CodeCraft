'use strict'

/**
 * 自动更新的纯逻辑工具（不依赖 electron，便于单独测试）
 *
 * - normalizeVersion：去掉 v 前缀与 -beta/+build 后缀
 * - compareVersion：语义化版本比较（返回 1/0/-1）
 * - pickAsset：按平台/架构挑选安装包（与 .github/workflows/release.yml 的命名保持一致）
 */

/** 归一化版本号：v2.0.2 / 2.0.2-beta.1 → 2.0.2 */
function normalizeVersion (v) {
  return String(v || '').trim().replace(/^v/i, '').split(/[-+]/)[0]
}

/** 版本比较：a>b 返回 1，相等 0，a<b 返回 -1（缺失段按 0 处理） */
function compareVersion (a, b) {
  const pa = normalizeVersion(a).split('.').map(n => parseInt(n, 10) || 0)
  const pb = normalizeVersion(b).split('.').map(n => parseInt(n, 10) || 0)
  const len = Math.max(pa.length, pb.length)
  for (let i = 0; i < len; i++) {
    const x = pa[i] || 0
    const y = pb[i] || 0
    if (x > y) return 1
    if (x < y) return -1
  }
  return 0
}

/**
 * 按平台/架构挑选安装包资产
 *
 * 命名规则（与 release.yml 一致）：
 * - Windows: CodeCraft.Setup.<ver>.exe（若出现架构后缀则为 CodeCraft.Setup.<ver>-x64.exe / -arm64.exe）
 * - macOS:   CodeCraft-<ver>-x64.dmg / CodeCraft-<ver>-arm64.dmg
 * - Linux:   CodeCraft-<ver>.AppImage / CodeCraft-<ver>-arm64.AppImage
 *
 * ⚠️ Windows 分支必须感知架构（2026-11-16 修复）：
 * 此前 win32 分支完全不看 arch，只按「第一个名字像 setup 的 exe」取。
 * 一旦发布同时包含 x64 与 arm64 的 exe，ARM 设备会拿到 x64 安装包
 * （能装上但性能异常或直接跑不起来），且这是静默选错、用户无从判断。
 * 现在优先挑与 arch 匹配的包；没有架构后缀的通用包作为兜底，保持向后兼容。
 *
 * @param {{name:string,size:number,url:string}[]} assets
 * @param {string} platform process.platform（缺省取当前平台）
 * @param {string} arch process.arch（缺省取当前架构）
 * @returns {{name:string,size:number,url:string}|null}
 */
function pickAsset (assets, platform = process.platform, arch = process.arch) {
  if (!Array.isArray(assets) || assets.length === 0) return null

  if (platform === 'win32') {
    const exes = assets.filter(a => /\.exe$/i.test(a.name))
    if (exes.length === 0) return null
    const setupExes = exes.filter(a => /setup/i.test(a.name))
    const pool = setupExes.length > 0 ? setupExes : exes
    // 1) 带当前架构后缀的 setup 包（如 CodeCraft.Setup.1.0.0-arm64.exe）
    const archMatch = pool.find(a => new RegExp(`-${arch}\\.exe$`, 'i').test(a.name))
    if (archMatch) return archMatch
    // 2) 不带任何架构后缀的通用包：兼容旧命名（当前发布只有这一种）
    const generic = pool.find(a => !/-(x64|arm64|ia32)\\.exe$/i.test(a.name))
    if (generic) return generic
    // 3) 只有别的架构：仍返回第一个（总比「找不到更新」好），但这是最后的兜底
    return pool[0] || null
  }

  if (platform === 'darwin') {
    const dmgs = assets.filter(a => /\.dmg$/i.test(a.name))
    return dmgs.find(a => new RegExp(`-${arch}\\.dmg$`, 'i').test(a.name)) ||
      dmgs.find(a => !/-(x64|arm64)\\.dmg$/i.test(a.name)) ||
      dmgs[0] || null
  }

  // Linux：同样优先架构匹配，其次通用 AppImage
  const linuxPkgs = assets.filter(a => /\.appimage$/i.test(a.name) || /\.deb$/i.test(a.name))
  return linuxPkgs.find(a => new RegExp(`-${arch}\\.(appimage|deb)$`, 'i').test(a.name)) ||
    linuxPkgs.find(a => !/-(x64|arm64|ia32)\\.(appimage|deb)$/i.test(a.name)) ||
    linuxPkgs[0] || null
}

module.exports = { normalizeVersion, compareVersion, pickAsset }
