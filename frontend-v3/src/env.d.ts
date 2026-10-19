/// <reference types="vite/client" />

/**
 * 刻意不写 `declare module '*.vue'`：
 * vue-tsc 已能解析 SFC 的真实类型，加上通配声明会把所有组件的 props/emits
 * 退化成 any，反而让 `npm run typecheck` 失去意义。
 */
