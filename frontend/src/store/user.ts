import { defineStore } from 'pinia'

// 通用API响应类型
export interface ApiResponse<T = any> {
  code: number
  message: string
  data: T
  timestamp: number
}

// 用户信息类型
export interface UserInfo {
  userId?: number // 后端返回的字段名是userId
  id?: number // 兼容字段，可通过userId映射
  username?: string
  nickname?: string
  avatar?: string
  email?: string
  phone?: string
  roles?: string[]
  permissions?: string[]
  token?: string // 登录令牌
  expire?: number // 令牌过期时间（秒）
  // 其他可能字段
}

// 登录请求参数
export interface LoginParams {
  username: string
  password: string // 二次加密后的密码
}

// 随机码请求参数
export interface RandomCodeParams {
  username: string
}

// 随机码响应数据（字符串格式）
export type RandomCodeData = string

// Store state 类型
export interface UserState {
  userInfo: UserInfo | null
  isLoggedIn: boolean
  token: string
  randomCode: string
  rememberedUsername: string | null // 记住的用户名
}

// Store actions 类型
export interface UserActions {
  setUserInfo(userInfo: UserInfo | null): void
  setToken(token: string): void
  setRandomCode(randomCode: string): void
  setRememberedUsername(username: string | null): void
  clearRememberedUsername(): void
  clearUserInfo(): void
  clearAllUserInfo(): void
  saveLoginResponse(response: ApiResponse<UserInfo>): void
}

export const useUserStore = defineStore<'user', UserState, {}, UserActions>('user', {
  state: (): UserState => ({
    // 用户信息
    userInfo: null,
    // 登录状态
    isLoggedIn: false,
    // token
    token: '',
    // 随机码
    randomCode: '',
    // 记住的用户名
    rememberedUsername: null
  }),

  getters: {
    // 获取用户信息
    getUserInfo: (state) => state.userInfo,
    // 是否已登录
    getIsLoggedIn: (state) => state.isLoggedIn,
    // 获取token
    getToken: (state) => state.token,
    // 获取随机码
    getRandomCode: (state) => state.randomCode,
    // 获取记住的用户名
    getRememberedUsername: (state) => state.rememberedUsername
  },

  actions: {
    // 设置用户信息
    setUserInfo(userInfo: UserInfo | null) {
      this.userInfo = userInfo
      this.isLoggedIn = !!userInfo
    },

    // 设置token
    setToken(token: string) {
      this.token = token
    },

    // 设置随机码
    setRandomCode(randomCode: string) {
      this.randomCode = randomCode
    },

    // 设置记住的用户名
    setRememberedUsername(username: string | null) {
      this.rememberedUsername = username
    },

    // 清除记住的用户名
    clearRememberedUsername() {
      this.rememberedUsername = null
    },

    // 清除用户信息（退出登录，但保留记住的用户名）
    clearUserInfo() {
      this.userInfo = null
      this.isLoggedIn = false
      this.token = ''
      this.randomCode = ''
      // 注意：不清除 rememberedUsername，以便用户下次还能看到记住的用户名
    },

    // 完全清除所有用户信息（包括记住的用户名）
    clearAllUserInfo() {
      this.userInfo = null
      this.isLoggedIn = false
      this.token = ''
      this.randomCode = ''
      this.rememberedUsername = null
    },

    // 保存登录响应数据
    saveLoginResponse(response: ApiResponse<UserInfo>) {
      if (response.code === 200 && response.data) {
        const userData = response.data
        // 字段映射：优先使用userId作为id，如果没有userId则使用原有的id
        const mappedUserInfo: UserInfo = {
          ...userData,
          id: userData.userId || userData.id, // 优先使用userId作为id
        }
        this.setUserInfo(mappedUserInfo)
        // 保存token到单独的store字段
        if (userData.token) {
          this.setToken(userData.token)
        }
        // 如果需要，也可以保存expire等信息
        if (import.meta.env.DEV) {
          console.log('登录响应数据已保存:', mappedUserInfo)
        }
      }
    }
  },

  // 持久化配置（如果需要）
  persist: true
})