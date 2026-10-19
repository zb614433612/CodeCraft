/**
 * Pinia 持久化插件类型增强
 *
 * <h3>为什么需要手写</h3>
 * {@code pinia-plugin-persistedstate@4.x} 的 {@code dist/index.d.ts} 里**没有**
 * {@code declare module 'pinia'} 类型增强，直接写 {@code persist: true} 会报
 * TS2769（{@code 'persist' does not exist in type DefineStoreOptions}），
 * 并连锁导致 store 的 actions 类型推断失败（{@code this.setXxx} 全部报错）。
 *
 * <p>这里补充插件实际支持的配置项子集，覆盖本项目用到的 {@code pick} / {@code key} / {@code storage}。
 * 若后续升级插件并自带增强，删除本文件即可。</p>
 */

import 'pinia'

declare module 'pinia' {
  /** 持久化配置项（{@code pinia-plugin-persistedstate} 的 PersistedStateOptions 子集） */
  interface PersistStateOptions {
    /** 存储键名，默认与 store id 相同 */
    key?: string
    /** 存储介质，默认 localStorage */
    storage?: Storage
    /** 只持久化这些字段 */
    pick?: string[]
    /** 排除这些字段 */
    omit?: string[]
    /** 自定义序列化 */
    serializer?: {
      serialize: (value: unknown) => string
      deserialize: (value: string) => unknown
    }
    /** 水合前回调 */
    beforeHydrate?: (context: unknown) => void
    /** 水合后回调 */
    afterHydrate?: (context: unknown) => void
    /** 开启调试日志 */
    debug?: boolean
  }

  export interface DefineStoreOptionsBase<S, Store> {
    /** 持久化配置：true 表示全量持久化到 localStorage */
    persist?: boolean | PersistStateOptions | PersistStateOptions[]
  }
}
