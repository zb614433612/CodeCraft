<!--
  数据管理 · 导入备份卡片（v2）
  ----------------------------------------------------------------------------
  结构：卡片头 → 拖放区（未选文件）/ 文件信息 + 内容预览（已选）→ 导入结果（统计 + 逐表明细）
  数据接缝：
    emit('import', file) -> POST /api/system/import-data（multipart，字段名 file）
                            后端返回 {inserted, updated, failed, details:[{category,label,tables:[...]}]}
    emit('clear')        -> 父组件清空上一次导入结果
  前端职责：
    1) 选择文件后本地解析（utils/data-transfer.ts 的 parseBackupJson），校验 app 标识并预览各类别条数；
    2) 解析失败只提示不阻断（可重新选择），真正的合法性由后端再次校验；
    3) 结果表格最多 190px 高，超出滚动。
-->
<template>
  <section class="cc-data-card">
    <div class="cc-data-card__head">
      <div class="cc-data-card__icon">
        <svg class="cc-i"><use href="#i-db" /></svg>
      </div>
      <div>
        <div class="cc-data-card__title">导入备份</div>
        <div class="cc-data-card__desc">合并策略：同 ID 覆盖更新、新 ID 追加，不清空现有数据</div>
      </div>
    </div>

    <input
      ref="fileInput"
      type="file"
      accept=".json,application/json"
      style="display: none"
      @change="onFileChange"
    />

    <!-- 未选文件：拖放区 -->
    <div
      v-if="!file"
      class="cc-data-drop"
      :class="{ 'is-over': dragOver }"
      @click="fileInput?.click()"
      @dragenter.prevent="dragOver = true"
      @dragover.prevent="dragOver = true"
      @dragleave.prevent="dragOver = false"
      @drop.prevent="onDrop"
    >
      <svg class="cc-i" style="width: 22px; height: 22px"><use href="#i-disk" /></svg>
      <div class="cc-data-drop__title">选择或拖入 JSON 备份文件</div>
      <div class="cc-data-drop__hint">仅支持 CodeCraft 导出的 .json，单文件 ≤500MB；单行失败不影响其余数据</div>
    </div>

    <!-- 已选文件 -->
    <div v-else class="cc-data-file">
      <div class="cc-data-file__row">
        <svg class="cc-i cc-i--sm"><use href="#i-file" /></svg>
        <span class="cc-data-file__name">{{ file.name }}</span>
        <span class="cc-data-file__meta">{{ formatDataSize(file.size) }}</span>
        <button class="cc-icon-btn cc-icon-btn--sm" style="margin-left: auto" title="移除" @click="clear">
          <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
        </button>
      </div>

      <template v-if="preview">
        <div class="cc-data-preview">
          <span v-for="item in preview.items" :key="item.key" class="cc-tag">
            {{ item.label }} {{ item.count }} 条
          </span>
          <span class="cc-tag cc-tag--accent">合计 {{ preview.total }} 条</span>
        </div>
        <div class="cc-data-card__foot" style="border-top: none; padding-top: 0">
          <button class="cc-btn cc-btn--primary" :disabled="importing" @click="startImport">
            <svg class="cc-i cc-i--sm"><use href="#i-disk" /></svg>{{ importing ? '导入中…' : '开始导入' }}
          </button>
          <span class="cc-data-hint">
            导出时间：{{ preview.exportedAt }} · 合并导入（同 ID 覆盖）
          </span>
        </div>
      </template>

      <template v-else>
        <div class="cc-data-error">{{ parseError }}</div>
        <div class="cc-data-card__foot" style="border-top: none; padding-top: 0">
          <span class="cc-data-hint">请重新选择 CodeCraft 导出的备份文件</span>
        </div>
      </template>
    </div>

    <!-- 导入结果：统计 + 逐表明细 -->
    <div v-if="result" class="cc-data-result">
      <div class="cc-data-result__stats">
        <div class="cc-data-result__item is-ok"><b>{{ result.inserted }}</b><span>新增</span></div>
        <div class="cc-data-result__item"><b>{{ result.updated }}</b><span>更新</span></div>
        <div class="cc-data-result__item is-danger"><b>{{ result.failed }}</b><span>失败</span></div>
      </div>
      <div style="max-height: 190px; overflow: auto">
        <table class="cc-data-result__table">
          <thead>
            <tr>
              <th>类别</th>
              <th>表</th>
              <th style="text-align: right">新增</th>
              <th style="text-align: right">更新</th>
              <th style="text-align: right">失败</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="cat in result.details" :key="cat.category">
              <tr v-for="detail in cat.tables" :key="`${cat.category}-${detail.table}`">
                <td>{{ cat.label }}</td>
                <td>{{ detail.table }}</td>
                <td style="text-align: right">{{ detail.inserted }}</td>
                <td style="text-align: right">{{ detail.updated }}</td>
                <td style="text-align: right">{{ detail.failed }}</td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import {
  formatDataSize,
  parseBackupJson,
  type BackupPreview,
  type DataImportResult
} from '../utils/data-transfer'

withDefaults(
  defineProps<{
    importing?: boolean
    result?: DataImportResult | null
  }>(),
  {
    importing: false,
    result: null
  }
)

const emit = defineEmits<{
  (e: 'import', file: File): void
  (e: 'clear'): void
}>()

const fileInput = ref<HTMLInputElement | null>(null)
const file = ref<File | null>(null)
const preview = ref<BackupPreview | null>(null)
const parseError = ref('')
const dragOver = ref(false)

/**
 * 单文件上限 500MB。
 * 与后端两处配置保持一致：application.yml 的 spring.servlet.multipart.max-file-size（500MB）
 * 与 server.tomcat.max-swallow-size（520MB）。
 * 前端先拦一次的意义：超限文件不必等上传完再被 Spring 拒绝（大文件白传几分钟）。
 */
const MAX_IMPORT_BYTES = 500 * 1024 * 1024

/** 读取并解析备份文件（校验体积 + app 标识 + 统计各类别条数） */
async function handleFile(next: File | null | undefined) {
  if (!next) return
  file.value = next
  preview.value = null
  parseError.value = ''
  // 体积超限：保留文件名与原因，让用户看到「哪个文件、多重、为什么不行」（不进入可导入状态）
  if (next.size > MAX_IMPORT_BYTES) {
    parseError.value =
      `文件体积 ${formatDataSize(next.size)} 超过 500MB 上限，请拆分或精简后再导入`
    return
  }
  try {
    const text = await next.text()
    preview.value = parseBackupJson(text)
  } catch (e) {
    parseError.value = (e as Error).message || '无法解析该文件'
  }
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  handleFile(input.files?.[0])
  input.value = ''
}

function onDrop(event: DragEvent) {
  dragOver.value = false
  handleFile(event.dataTransfer?.files?.[0])
}

function clear() {
  file.value = null
  preview.value = null
  parseError.value = ''
  emit('clear')
}

function startImport() {
  if (!file.value || !preview.value) return
  emit('import', file.value)
}
</script>
