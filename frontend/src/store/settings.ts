import { defineStore } from 'pinia'
import { ref } from 'vue'

// 执行模式
export type ExecutionMode = 'auto' | 'manual'

// 各助手的执行模式配置，手动为默认
export interface ExecutionModes {
  code_assistant: ExecutionMode
}

// 模型类型
export type Model = 'deepseek-v4-pro' | 'deepseek-v4-flash'

// 思考模式类型
// non-thinking -> thinking.type=disabled
// thinking    -> thinking.type=enabled + reasoning_effort=high
// thinking_max -> thinking.type=enabled + reasoning_effort=max
export type ThinkingMode = 'non-thinking' | 'thinking' | 'thinking_max'

// 各助手的模型配置
export interface Models {
  code_assistant: Model
}

// 各助手的思考模式配置
export interface ThinkingModes {
  code_assistant: ThinkingMode
}

// 主题模式
export type ThemeMode = 'light' | 'dark' | 'auto'

export const useSettingsStore = defineStore('settings', () => {
  // 各助手的执行模式，默认 manual
  const executionModes = ref<ExecutionModes>({
    code_assistant: 'manual'
  })

  // 各助手的模型，默认 deepseek-v4-flash
  const models = ref<Models>({
    code_assistant: 'deepseek-v4-flash'
  })

  // 各助手的思考模式，默认 non-thinking
  const thinkingModes = ref<ThinkingModes>({
    code_assistant: 'non-thinking'
  })

  // 主题模式（持久化，刷新不丢失）
  const theme = ref<ThemeMode>('auto')

  // 项目工作目录（持久化，刷新不丢失）
  const projectRoot = ref('')

  // 获取指定助手的执行模式
  function getMode(agentType: string): ExecutionMode {
    const key = agentType as keyof ExecutionModes
    return executionModes.value[key] || 'manual'
  }

  // 设置指定助手的执行模式
  function setMode(agentType: string, mode: ExecutionMode) {
    const key = agentType as keyof ExecutionModes
    if (key in executionModes.value) {
      executionModes.value[key] = mode
    }
  }

  // 切换指定助手的执行模式
  function toggleMode(agentType: string) {
    const key = agentType as keyof ExecutionModes
    if (key in executionModes.value) {
      executionModes.value[key] =
        executionModes.value[key] === 'auto' ? 'manual' : 'auto'
    }
  }

  // 获取指定助手的模型
  function getModel(agentType: string): Model {
    const key = agentType as keyof Models
    return models.value[key] || 'deepseek-v4-flash'
  }

  // 设置指定助手的模型
  function setModel(agentType: string, model: Model) {
    const key = agentType as keyof Models
    if (key in models.value) {
      models.value[key] = model
    }
  }

  // 获取指定助手的思考模式
  function getThinkingMode(agentType: string): ThinkingMode {
    const key = agentType as keyof ThinkingModes
    return thinkingModes.value[key] || 'non-thinking'
  }

  // 设置指定助手的思考模式
  function setThinkingMode(agentType: string, mode: ThinkingMode) {
    const key = agentType as keyof ThinkingModes
    if (key in thinkingModes.value) {
      thinkingModes.value[key] = mode
    }
  }

  // 获取主题模式
  function getTheme(): ThemeMode {
    return theme.value
  }

  // 设置主题模式
  function setTheme(mode: ThemeMode) {
    theme.value = mode
  }

  return {
    executionModes,
    models,
    thinkingModes,
    theme,
    projectRoot,
    getMode,
    setMode,
    toggleMode,
    getModel,
    setModel,
    getThinkingMode,
    setThinkingMode,
    getTheme,
    setTheme
  }
}, {
  persist: true
})
