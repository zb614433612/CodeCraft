/**
 * UI 版本偏好（新版 v3 ↔ 经典版 v2）
 * ----------------------------------------------------------------------------
 * 两套前端产物同时部署在同一个后端下：
 *   · 经典版（v2）：`/`，产物在 `src/main/resources/static/`（根）
 *   · 新版（v3）  ：`/v3/`，产物在 `src/main/resources/static/v3/`
 *
 * 谁来决定打开哪个？靠 localStorage 里的 **`cc.uiVersion`**：
 *   · `'v3'` 或 **未设置** → 新版（默认启动新版，用户要求）
 *   · `'v2'`              → 经典版（用户在账号菜单里主动切过去的）
 *
 * 读取方有两个，键名与取值必须严格一致，否则会出现「点了切换但下次还进旧版」：
 *   1) 本文件（新版自己的账号菜单「切换到经典版」写 `'v2'` 后整页跳 `/`）
 *   2) 经典版 `index.html` 里注入的一段内联脚本（读完决定是否 `location.replace('/v3/')`）
 *
 * 因此**不要**给这里的键名加 `v3.` 前缀 —— 它天生是跨版本的共享偏好，
 * 与 `v3.user` / `v3.workspace` 那类「新版独占状态」的隔离策略刚好相反。
 */

/** localStorage 键：UI 版本偏好（跨版本共享，勿加前缀） */
export const UI_VERSION_KEY = 'cc.uiVersion'

export type UiVersion = 'v2' | 'v3'

/** 读取当前偏好；未设置或读取失败一律按新版处理（默认启动新版） */
export function getUiVersion(): UiVersion {
  try {
    return localStorage.getItem(UI_VERSION_KEY) === 'v2' ? 'v2' : 'v3'
  } catch {
    return 'v3'
  }
}

/** 写入偏好（localStorage 不可用时静默失败，跳转照常进行） */
export function setUiVersion(version: UiVersion): void {
  try {
    localStorage.setItem(UI_VERSION_KEY, version)
  } catch {
    // 隐私模式 / 存储被禁用：偏好丢失只会导致下次仍进新版，不影响本次跳转
  }
}

/** 经典版入口（后端静态根，由旧版 `index.html` 提供服务） */
export const LEGACY_ENTRY = '/'

/**
 * 切换到经典版。
 *
 * <p>用整页 `location.assign` 而不是前端路由：两套产物是独立的 SPA，
 * 各自维护 router / store 实例，只能靠浏览器重新加载切换。</p>
 *
 * <p>先写偏好再跳转 —— 顺序反了（先跳后写）在极端情况下会丢写入，
 * 表现为「切回经典版又被弹回新版」，像死循环。</p>
 */
export function switchToLegacy(): void {
  setUiVersion('v2')
  window.location.assign(LEGACY_ENTRY)
}
