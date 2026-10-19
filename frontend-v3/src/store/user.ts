/**
 * 用户状态 Store（v3 · 登录页版）
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li><b>token 归 http-client 管</b>：统一存 localStorage 键 {@code codecraft.token}，
 *       避免 store 与 http-client 各存一份导致不一致。此处 {@code token} 字段只是镜像；</li>
 *   <li><b>{@code isLoggedIn} = 「本地有 token」</b>：真正的权限判定看 {@code role}
 *       （路由守卫的 meta.adminOnly），这里只用于 UI 粗判；</li>
 *   <li><b>对外接口保持不变</b>：{@code userInfo / setToken / clearUserInfo} 等签名与 v2 一致，
 *       {@code src/api} 下 10 个文件的类型导入零修改。</li>
 * </ul>
 *
 * <p><b>注意</b>：{@code src/api} 里的模块只 `import type`，不会在运行时调用本 store；
 * 真正持有当前用户信息的是这里，供布局/用户管理页读取。</p>
 */

import { defineStore } from 'pinia'
import { clearAuthToken, getStoredToken, setAuthToken } from '@/utils/http-client'

/** 后端统一响应包装（复用 http-client 定义，避免两份类型漂移） */
export type { ApiResponse } from '@/utils/http-client'

/** 随机码响应（后端返回纯字符串） */
export type RandomCodeData = string

/** 登录请求参数（password 为二次加密后的值） */
export interface LoginParams {
  username: string
  password: string
}

/** 随机码请求参数 */
export interface RandomCodeParams {
  username: string
}

/** 用户信息 */
export interface UserInfo {
  /** 后端返回的字段名是 userId */
  userId?: number
  /** 兼容字段，由 userId 映射而来 */
  id?: number
  username?: string
  nickname?: string
  avatar?: string
  email?: string
  phone?: string
  /** 用户角色：admin=管理员，user=普通用户 */
  role?: string
  /** 兼容多角色模式 */
  roles?: string[]
  permissions?: string[]
  /** 登录令牌（镜像 http-client 中的值） */
  token?: string
  /** 令牌过期时间（秒） */
  expire?: number
}

/** Store state */
export interface UserState {
  userInfo: UserInfo | null
  isLoggedIn: boolean
  token: string
  randomCode: string
  rememberedUsername: string | null
}

export const useUserStore = defineStore('user', {
  state: (): UserState => ({
    userInfo: null,
    // 「本地有 token」即视为已登录；退出登录时由 clearUserInfo 置 false
    isLoggedIn: !!getStoredToken(),
    token: getStoredToken(),
    randomCode: '',
    rememberedUsername: null
  }),

  getters: {
    getUserInfo: (state) => state.userInfo,
    getIsLoggedIn: (state) => state.isLoggedIn,
    getToken: (state) => state.token,
    getRandomCode: (state) => state.randomCode,
    getRememberedUsername: (state) => state.rememberedUsername,
    /** 是否管理员（单角色 role 优先，其次 roles 数组） */
    isAdmin(): boolean {
      if (!this.userInfo) return false
      return this.userInfo.role === 'admin' || Boolean(this.userInfo.roles?.includes('admin'))
    }
  },

  actions: {
    setUserInfo(userInfo: UserInfo | null) {
      this.userInfo = userInfo
      this.isLoggedIn = !!userInfo
    },

    /** 设置 token：同时同步给 http-client（单一真相源） */
    setToken(token: string) {
      this.token = token
      setAuthToken(token)
    },

    setRandomCode(randomCode: string) {
      this.randomCode = randomCode
    },

    setRememberedUsername(username: string | null) {
      this.rememberedUsername = username
    },

    clearRememberedUsername() {
      this.rememberedUsername = null
    },

    /** 清除当前用户信息（保留记住的用户名）；同时清掉 token，下次请求会自动重新隐形登录 */
    clearUserInfo() {
      this.userInfo = null
      this.isLoggedIn = false
      this.token = ''
      this.randomCode = ''
      clearAuthToken()
    },

    /** 完全清除（含记住的用户名） */
    clearAllUserInfo() {
      this.userInfo = null
      this.isLoggedIn = false
      this.token = ''
      this.randomCode = ''
      this.rememberedUsername = null
      clearAuthToken()
    },

    /**
     * 保存登录响应
     *
     * <p>兼容 v2 的 {@code {code, data}} 结构；字段映射优先取 {@code userId}。</p>
     */
    saveLoginResponse(response: { code: number; data?: UserInfo | null }) {
      if (response.code === 200 && response.data) {
        const userData = response.data
        const mappedUserInfo: UserInfo = {
          ...userData,
          id: userData.userId || userData.id
        }
        this.setUserInfo(mappedUserInfo)
        if (userData.token) {
          this.setToken(userData.token)
        }
      }
    }
  },

  /**
   * 仅持久化用户资料；token 由 http-client 自己持久化，避免双份。
   *
   * <p><b>key 为什么要加 `v3.` 前缀</b>：经典版（v2）的 user store 同样用
   * `persist: true`，默认键名就是 `user`，其 state 里含 `token` / `isLoggedIn`。
   * 两版共用同一个 localStorage 键会互相覆盖——新版写入会抹掉经典版的 token，
   * 切回经典版就变成「掉登录」。加前缀后两份 state 各存各的，
   * 跨版本登录态靠 {@code http-client} 的单向 token 兜底实现（见 getStoredToken）。</p>
   */
  persist: {
    key: 'v3.user',
    pick: ['userInfo', 'rememberedUsername']
  }
})
