/**
 * markdown-it-container 类型声明
 * ----------------------------------------------------------------------------
 * 该包 v4 只发 ESM 且不自带 d.ts，社区也没有 @types 版本，
 * 这里按实际使用面（md.use(container, name, { validate, render })）补一个最小声明。
 */
declare module 'markdown-it-container' {
  import type MarkdownIt from 'markdown-it'

  interface ContainerOptions {
    /** 命中该容器时的判定：params 是 `::: xxx` 后面的文本 */
    validate?: (params: string) => boolean
    /** 打开 / 关闭各调用一次，tokens[idx].nesting 为 1 表示打开 */
    render?: (tokens: any[], idx: number) => string
  }

  function container(md: MarkdownIt, name: string, options?: ContainerOptions): void
  export default container
}
