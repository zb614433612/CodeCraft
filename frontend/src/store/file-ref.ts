import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 跨页文件引用暂存（M4）
 *
 * 场景：文件管理页「📌 引用到聊天」→ 写入 pendingRefs → 跳转 /code-assistant
 * → 聊天页激活时 consumeRefs() 消费并入附件区（发送时引用同一 file_id，无需重传）。
 *
 * 设计：轻量、无持久化（跨页一次性传递）；按 id 去重。
 */
export interface PendingFileRef {
  /** 本地资产ID */
  id: number
  /** 远端 Files API 文件ID（本地存储文档为 null） */
  fileId?: string | null
  /** 原始文件名 */
  filename: string
  /** 显示名（可空） */
  displayName?: string
  mimeType?: string
  /** 存储类型：cloud=云端（图片）/ local=本地（文档） */
  storageType?: string
  size?: number
}

export const useFileRefStore = defineStore('file-ref', () => {
  /** 待消费的引用（跨页一次性传递） */
  const pendingRefs = ref<PendingFileRef[]>([])

  /** 追加引用（按 id 去重） */
  function addRefs(refs: PendingFileRef[]): void {
    for (const r of refs) {
      if (!r || r.id == null) continue
      if (!pendingRefs.value.some(x => x.id === r.id)) {
        pendingRefs.value.push(r)
      }
    }
  }

  /** 消费全部引用（返回并清空——聊天页挂载时调用） */
  function consumeRefs(): PendingFileRef[] {
    const out = pendingRefs.value
    pendingRefs.value = []
    return out
  }

  /** 清空 */
  function clear(): void {
    pendingRefs.value = []
  }

  return { pendingRefs, addRefs, consumeRefs, clear }
})
