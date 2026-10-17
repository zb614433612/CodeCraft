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
 * - Windows: CodeCraft.Setup.<ver>.exe
 * - macOS:   CodeCraft-<ver>-x64.dmg / CodeCraft-<ver>-arm64.dmg
 * - Linux:   CodeCraft-<ver>.AppImage
 *
 * @param {{name:string,size:number,url:string}[]} assets
 * @param {string} platform process.platform
 * @param {string} arch process.arch
 * @returns {{name:string,size:number,url:string}|null}
 */
function pickAsset (assets, platform, arch) {
  if (!Array.isArray(assets) || assets.length === 0) return null

  if (platform === 'win32') {
    return assets.find(a => /\.exe$/i.test(a.name) && /setup/i.test(a.name)) ||
      assets.find(a => /\.exe$/i.test(a.name)) || null
  }

  if (platform === 'darwin') {
    const dmgs = assets.filter(a => /\.dmg$/i.test(a.name))
    return dmgs.find(a => new RegExp(`-${arch}\\.dmg$`, 'i').test(a.name)) ||
      dmgs.find(a => !/-(x64|arm64)\.dmg$/i.test(a.name)) ||
      dmgs[0] || null
  }

  return assets.find(a => /\.appimage$/i.test(a.name)) ||
    assets.find(a => /\.deb$/i.test(a.name)) || null
}

module.exports = { normalizeVersion, compareVersion, pickAsset }
