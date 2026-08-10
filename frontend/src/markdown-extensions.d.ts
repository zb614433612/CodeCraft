declare module 'markdown-it-texmath' {
  import { MarkdownIt } from 'markdown-it'
  import katex from 'katex'

  interface TexMathOptions {
    engine: typeof katex
    delimiters?: string | string[]
    katexOptions?: Record<string, unknown>
    blockOpen?: string
    blockClose?: string
    inlineOpen?: string
    inlineClose?: string
  }

  const texmath: (md: MarkdownIt, options: TexMathOptions) => void
  export default texmath
}

declare module 'markdown-it-container' {
  import { PluginWithOptions } from 'markdown-it'
  const markdownItContainer: PluginWithOptions
  export default markdownItContainer
}