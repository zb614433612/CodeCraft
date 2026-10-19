<!--
  云端文件抽屉（v2）
  ----------------------------------------------------------------------------
  拉取 Provider 账号下的云端 Files API 清单，与本地 file_asset 记录比对后标注：
    「已登记」= 本地存在同 provider + fileId 记录；
    「仅云端」= 本地无记录（软件重装 / 数据清理后的残留），可直接清理。
  与后端对应（FileAssetController）：
    GET    /api/files/cloud?providerCode=          -> CloudFileListVO
    DELETE /api/files/cloud/{fileId}?providerCode= -> 幂等删除（404 视为已删除），
                                                      本地有同 provider+fileId 记录时联动清理
  注意：total 为本次返回条数，truncated=true 表示云端文件数超过服务端拉取上限
       （10 页 × 1000 条），此时仅展示前 N 条。
  数据接缝：
    v-model:open        -> 抽屉开关
    cloud               -> GET /api/files/cloud 的返回
    emit('delete-cloud')-> DELETE /api/files/cloud/{fileId}
    emit('purge')       -> 批量删除全部「仅云端」
    emit('refresh')     -> 重新拉取
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 720px">
    <DrawerResizer storage-key="cloud-files" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">云端文件</div>
      <span v-if="providerText" class="cc-tag">{{ providerText }}</span>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <div class="cc-banner" style="background: var(--info-lt); border-color: rgba(46,144,250,.22); margin-bottom: var(--sp-4)">
        <svg class="cc-i cc-i--sm" style="color: var(--info)"><use href="#i-disk" /></svg>
        <span class="cc-banner__text">
          账号下的云端文件清单。「仅云端」= 本地无登记记录（重装 / 清理后残留），可直接清理。
        </span>
      </div>

      <!-- 统计：已登记 / 仅云端 / 仅云端占用 -->
      <div class="cc-cloud-stats">
        <div class="cc-cloud-stats__item"><b>{{ cloud?.total ?? 0 }}</b><span>云端文件</span></div>
        <div class="cc-cloud-stats__item"><b>{{ cloud?.linkedCount ?? 0 }}</b><span>已登记</span></div>
        <div class="cc-cloud-stats__item is-warn"><b>{{ cloud?.cloudOnlyCount ?? 0 }}</b><span>仅云端（可清理）</span></div>
        <div class="cc-cloud-stats__item"><b>{{ cloudOnlySizeText }}</b><span>仅云端占用</span></div>
      </div>

      <!-- 清单 -->
      <div v-if="files.length" class="cc-cloud-list">
        <div v-for="item in files" :key="item.fileId" class="cc-cloud-row" :class="{ 'is-off': !item.linked }">
          <div class="cc-cloud-row__main">
            <span class="cc-cloud-name">{{ item.filename || '（无文件名）' }}</span>
            <span class="cc-cloud-code">
              {{ item.fileId }} · {{ formatSize(item.bytes) }} · {{ formatEpoch(item.createdAtEpoch) }}
              <template v-if="item.expiresAtEpoch">· 过期 {{ formatEpoch(item.expiresAtEpoch) }}</template>
              <template v-else>· 永久保存</template>
            </span>
          </div>
          <div class="cc-cloud-row__side">
            <span v-if="item.linked" class="cc-tag cc-tag--ok">已登记</span>
            <span v-else class="cc-tag cc-tag--warn">仅云端</span>
            <button v-if="item.linked" class="cc-icon-btn cc-icon-btn--sm" title="已登记（本地可查看）" disabled>
              <svg class="cc-i cc-i--sm"><use href="#i-file" /></svg>
            </button>
            <button v-else class="cc-icon-btn cc-icon-btn--sm" title="删除云端文件" @click="$emit('delete-cloud', item.fileId)">
              <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>
            </button>
          </div>
        </div>
      </div>

      <div v-else class="cc-sql-empty">{{ loading ? '正在拉取云端清单…' : '云端没有文件' }}</div>

      <div v-if="cloud?.truncated" style="margin-top: var(--sp-3); font-size: var(--fs-xs); color: var(--text-4)">
        云端文件数超过服务端单次拉取上限（10 页 × 1000 条），以上仅为前 {{ files.length }} 条。
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button class="cc-btn" :disabled="loading" @click="$emit('refresh')">
        <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>重新拉取
      </button>
      <button class="cc-btn" @click="close">关闭</button>
      <button class="cc-btn cc-btn--danger" :disabled="!cloudOnlyCount" @click="$emit('purge')">
        <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>清理仅云端
      </button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import DrawerResizer from './DrawerResizer.vue'
import { computed } from 'vue'

/** 云端文件条目（对应后端 CloudFileVO） */
export interface CloudFileItem {
  fileId?: string
  filename?: string
  bytes?: number
  createdAtEpoch?: number
  /** null = 永久保存 */
  expiresAtEpoch?: number | null
  /** 是否已在本地登记（同 provider + fileId） */
  linked?: boolean
  linkedAssetId?: number | string
  linkedDisplayName?: string
  linkedStatus?: string
}

/** 云端文件清单（对应后端 CloudFileListVO） */
export interface CloudFileListModel {
  providerCode?: string
  /** 本次返回条数（truncated=true 时为拉取上限值） */
  total?: number
  linkedCount?: number
  cloudOnlyCount?: number
  cloudOnlySize?: number
  truncated?: boolean
  files?: CloudFileItem[]
}

const props = withDefaults(
  defineProps<{
    open: boolean
    cloud?: CloudFileListModel | null
    loading?: boolean
  }>(),
  {
    cloud: null,
    loading: false
  }
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'delete-cloud', fileId?: string): void
  (e: 'purge'): void
  (e: 'refresh'): void
}>()

const files = computed(() => props.cloud?.files || [])
const providerText = computed(() => props.cloud?.providerCode || '')
const cloudOnlyCount = computed(() => Number(props.cloud?.cloudOnlyCount) || 0)
const cloudOnlySizeText = computed(() => formatSize(props.cloud?.cloudOnlySize))

function formatSize(bytes?: number): string {
  const n = Number(bytes) || 0
  if (n <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let value = n
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024
    i++
  }
  return `${i === 0 ? value : value.toFixed(1)} ${units[i]}`
}

/** 远端时间为 Unix 秒 */
function formatEpoch(epoch?: number | null): string {
  if (!epoch) return '-'
  const d = new Date(epoch * 1000)
  if (Number.isNaN(d.getTime())) return '-'
  const pad = (v: number) => String(v).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function close() {
  emit('update:open', false)
}
</script>
