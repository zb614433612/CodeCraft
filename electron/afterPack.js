/**
 * electron-builder afterPack 钩子（仅 macOS 生效）
 *
 * 背景：
 *   CI 上没有 Apple 开发者证书，electron-builder 会跳过代码签名；
 *   但 macOS（尤其 Apple Silicon）要求可执行文件必须有有效签名，
 *   否则内置 JRE 中的 java 等二进制无法运行（表现为应用卡在启动画面）。
 *
 * 本钩子在整个 .app 组装完成、但【尚未生成 dmg】之前执行 ad-hoc 签名，
 * 确保最终 dmg 内分发的应用带有效签名。
 *
 * 说明：ad-hoc 签名（codesign --sign -）无需证书；用户首次打开仍需
 * 「右键 → 打开」绕过 Gatekeeper 的"未验证开发者"提示。
 */
const { execFileSync } = require('child_process')
const path = require('path')
const fs = require('fs')

/**
 * 收集目录下所有需要签名的文件（可执行文件 / 动态库）
 * @param {string} dir - 要扫描的目录
 * @returns {string[]} 需要签名的文件绝对路径列表
 */
function collectSignableFiles (dir) {
  const result = []
  const walk = (current) => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const fullPath = path.join(current, entry.name)
      if (entry.isDirectory()) {
        walk(fullPath)
        continue
      }
      if (!entry.isFile()) continue
      let needSign = entry.name.endsWith('.dylib') || entry.name.endsWith('.jnilib')
      if (!needSign) {
        try {
          needSign = (fs.statSync(fullPath).mode & 0o111) !== 0
        } catch (_) {
          /* 权限读取失败时按无需签名处理 */
        }
      }
      if (needSign) result.push(fullPath)
    }
  }
  walk(dir)
  return result
}

/**
 * afterPack 钩子入口
 * @param {import('app-builder-lib').PackContext} context
 */
async function afterPack (context) {
  // 仅处理 macOS；Windows / Linux 构建直接跳过
  if (context.electronPlatformName !== 'darwin') return

  const appPath = path.join(
    context.appOutDir,
    `${context.packager.appInfo.productFilename}.app`
  )
  if (!fs.existsSync(appPath)) {
    throw new Error(`[afterPack] 未找到 .app 目录: ${appPath}`)
  }

  // ad-hoc 签名参数（有 entitlements 文件时一并带上，保持与正式签名行为一致）
  const signArgs = ['--force', '--sign', '-', '--timestamp=none']
  const entitlements = path.join(__dirname, 'entitlements.mac.plist')
  if (fs.existsSync(entitlements)) {
    signArgs.push('--entitlements', entitlements)
  }

  // ① 先对内置 JRE 中的可执行文件 / 动态库逐个签名
  const jreDir = path.join(appPath, 'Contents', 'Resources', 'jre')
  if (fs.existsSync(jreDir)) {
    const files = collectSignableFiles(jreDir)
    console.log(`[afterPack] 对内置 JRE 的 ${files.length} 个文件进行 ad-hoc 签名...`)
    for (const file of files) {
      try {
        execFileSync('codesign', [...signArgs, file], { stdio: 'pipe' })
      } catch (e) {
        const errText = e.stderr ? e.stderr.toString().trim() : e.message
        console.warn(`[afterPack] ⚠️ 签名失败（已跳过）: ${file}`)
        console.warn(`  ${errText}`)
      }
    }
    console.log('[afterPack] ✅ 内置 JRE 签名完成')
  } else {
    console.warn(`[afterPack] ⚠️ 未找到内置 JRE 目录，跳过: ${jreDir}`)
  }

  // ② 对整个 .app 做深度 ad-hoc 签名（覆盖 Electron 框架、Helper 等全部嵌套组件）
  console.log(`[afterPack] 对整个应用进行 ad-hoc 签名: ${appPath}`)
  execFileSync('codesign', [...signArgs, '--deep', appPath], { stdio: 'inherit' })
  console.log('[afterPack] ✅ 应用 ad-hoc 签名完成（dmg 将携带该签名分发）')
}

module.exports = afterPack
module.exports.default = afterPack
