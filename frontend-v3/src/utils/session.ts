/**
 * 身份会话（v3 · 登录页版）
 * ----------------------------------------------------------------------------
 * token 由 {@code http-client} 管（{@code codecraft.token}），但「我是谁」还需要一层身份快照：
 * 菜单过滤与路由守卫都需要知道当前用户角色（admin / user）。
 *
 * <pre>
 *   登录成功（LoginView）  →  token 落盘
 *   ensureIdentity()      →  GET /api/user/current  →  userStore.setUserInfo()
 * </pre>
 *
 * <h3>三条设计约束</h3>
 * <ol>
 *   <li><b>快照可持久化</b>：user store 已声明 {@code persist}，刷新页面无需重新请求；</li>
 *   <li><b>并发去重</b>：启动预热与路由守卫可能同时触发，只发一次请求；</li>
 *   <li><b>未登录 / 后端不可用不阻塞应用</b>：返回 {@code null} 交由调用方降级，
 *       并进入 30 秒冷却期 —— 否则用户在管理页之间来回点时，每次都要干等 15 秒超时。</li>
 * </ol>
 *
 * <p><b>与后端权限的一致性</b>：后端仅 6 个 Controller 真正校验 {@code userRole === 'admin'}
 * （LLMProvider / Menu / Role / ScheduleTask / System / User）。前端 meta.adminOnly 允许比后端
 * <b>更严格</b>（收口敏感页面入口），但绝不允许更宽松。</p>
 */

import { useUserStore, type UserInfo } from '@/store/user'
import { getCurrentUser, getProfile } from '@/api/user-admin'
import { clearAuthToken, hasToken } from './http-client'

/** 身份拉取失败后的冷却时长（毫秒） */
const FAILURE_COOLDOWN = 30_000

/** 进行中的身份拉取任务（并发去重） */
let inflight: Promise<UserInfo | null> | null = null

/** 冷却截止时间戳（失败后短时间不再重试） */
let cooldownUntil = 0

/** 读取已缓存的身份快照；无角色信息视为「未知」（不发请求） */
export function peekIdentity(): UserInfo | null {
  const info = useUserStore().userInfo
  return info && info.role ? info : null
}

/** 当前是否管理员（基于快照，同步；快照未就绪时返回 false） */
export function isAdminNow(): boolean {
  return useUserStore().isAdmin
}

/**
 * 是否可访问某类页面。
 *
 * @param adminOnly 页面是否仅管理员可访问（false / undefined 时任何人可访问）
 */
export function canAccess(adminOnly?: boolean): boolean {
  if (!adminOnly) return true
  return isAdminNow()
}

/**
 * 补齐展示字段（昵称 / 头像 / 邮箱 / 手机号）。
 *
 * <p>`GET /api/user/current` 只返回 `userId + username + role`（够做权限判定，不够渲染 UI），
 * 所以身份快照就绪后再补一次 `GET /api/user/profile`。这一步是**尽力而为**：
 * 失败直接返回原快照，绝不因为拿不到头像就把用户判成「身份未知」。</p>
 */
async function fillProfile(info: UserInfo): Promise<UserInfo> {
  try {
    const res = await getProfile()
    const profile = res.data
    if (!profile) return info
    return {
      ...info,
      nickname: profile.nickname || info.nickname,
      avatar: profile.avatar || info.avatar,
      email: profile.email || info.email,
      phone: profile.phone || info.phone
    }
  } catch {
    return info
  }
}

/**
 * 主动刷新身份快照中的展示字段（头像 / 昵称）。
 * 头像上传、个人资料修改后调用一次即可让侧栏与聊天页同步。
 */
export async function refreshProfile(): Promise<void> {
  const store = useUserStore()
  if (!store.userInfo) return
  const next = await fillProfile({ ...store.userInfo })
  store.setUserInfo(next)
}

/**
 * 确保身份快照可用（必要时请求 {@code GET /api/user/current}）。
 *
 * @param force true=忽略缓存强制刷新（切换账号、权限变更后）
 * @returns 身份信息；后端不可用 / 未返回角色时为 {@code null}
 */
export function ensureIdentity(force = false): Promise<UserInfo | null> {
  if (!force) {
    const cached = peekIdentity()
    if (cached) return Promise.resolve(cached)
    // 冷却期内直接返回 null：避免连续触发时反复等网络超时
    if (Date.now() < cooldownUntil) return Promise.resolve(null)
  }
  if (inflight) return inflight

  const task = (async (): Promise<UserInfo | null> => {
    try {
      // 未登录时不发请求：否则 /user/current 必然 401，还会触发全局「登录已过期」跳转
      if (!hasToken()) {
        cooldownUntil = Date.now() + FAILURE_COOLDOWN
        return null
      }
      const response = await getCurrentUser()
      const data = response.data
      if (!data || !data.role) {
        cooldownUntil = Date.now() + FAILURE_COOLDOWN
        console.warn('[session] /user/current 未返回角色信息，按「身份未知」处理')
        return null
      }
      let info: UserInfo = {
        userId: data.userId,
        id: data.userId,
        username: data.username,
        role: data.role
      }
      // 补昵称 / 头像：/user/current 不返回展示字段，侧栏与聊天气泡都靠它
      info = await fillProfile(info)
      useUserStore().setUserInfo(info)
      cooldownUntil = 0
      return info
    } catch (error) {
      cooldownUntil = Date.now() + FAILURE_COOLDOWN
      console.warn(
        '[session] 获取当前用户身份失败，管理页将按无权限处理：',
        error instanceof Error ? error.message : String(error)
      )
      return null
    }
  })()

  inflight = task
  void task.finally(() => {
    if (inflight === task) inflight = null
  })
  return task
}

/**
 * 清空身份快照。
 *
 * @param clearCache 是否同时清掉 store 里的 userInfo（切换账号时传 true）
 */
export function resetIdentity(clearCache = true): void {
  cooldownUntil = 0
  inflight = null
  if (clearCache) useUserStore().setUserInfo(null)
}

/** 是否处于「身份拉取失败」的冷却期（供 UI 展示「后端未连接」提示） */
export function inIdentityCooldown(): boolean {
  return Date.now() < cooldownUntil
}

/**
 * 彻底清空会话：token + 身份快照 + 冷却计时。
 *
 * <p>两个调用点：「退出登录」按钮，以及 http-client 的 401 回调。
 * 与 {@link resetIdentity} 的区别是它连 token 一起清 —— 清完之后
 * 路由守卫会立刻把用户挡回登录页，不会出现「身份没了但请求还带着旧 token」的中间态。</p>
 */
export function clearSession(): void {
  clearAuthToken()
  resetIdentity(true)
}
