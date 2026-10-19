<!--
  头像上传器（v3 / F07 + F17）
  ----------------------------------------------------------------------------
  一个圆形头像位，点一下即可选图上传；hover 才显示遮罩与「移除」按钮。
  上传链路：客户端压缩（utils/image-compress）→ POST /api/avatar → emit 新 URL。

  数据接缝：
    v-model            -> 头像值（图片 URL / emoji / 空串），完全受控
    emit('uploading')  -> 上传开始/结束（父级可据此禁用保存按钮，避免用旧头像提交）
    emit('uploaded')   -> 上传成功，参数为新 URL（父级可做「立即写入」等即时反馈）

  说明：
    · 不做「写库」动作 —— 只负责拿到 URL，什么时候提交由所在抽屉的保存流程决定，
      这样用户误点上传后仍可用「取消」丢弃（不产生脏数据）。
    · 判定走 utils/avatar 的 isImageAvatar，与侧栏 / 聊天气泡 / 卡片共用一套规则，
      避免 emoji（内置 Agent「圆圆」就是 emoji）被塞进 <img src> 变裂图。
-->
<template>
  <div class="cc-avatar-up" :style="{ '--avatar-size': size + 'px' }">
    <button
      type="button"
      class="cc-avatar-up__box"
      :class="{ 'is-empty': !hasContent, 'is-busy': uploading }"
      :disabled="disabled || uploading"
      :title="title"
      @click="pick"
    >
      <img v-if="isImage" :src="modelValue" :alt="name || '头像'" class="cc-avatar-up__img" />
      <span v-else class="cc-avatar-up__text">{{ fallbackText }}</span>

      <!-- hover 遮罩：上传中常显 -->
      <span class="cc-avatar-up__mask">
        <svg v-if="uploading" class="cc-i cc-i--sm cc-avatar-up__spin"><use href="#i-refresh" /></svg>
        <svg v-else class="cc-i cc-i--sm"><use href="#i-brush" /></svg>
        <span v-if="size >= 56" class="cc-avatar-up__mask-text">{{ uploading ? '上传中' : '更换' }}</span>
      </span>
    </button>

    <!-- 有内容时提供「移除」，仅 hover 显示，避免误触 -->
    <button
      v-if="hasContent && !disabled && !uploading"
      type="button"
      class="cc-avatar-up__remove"
      title="移除头像"
      @click="remove"
    >
      <svg class="cc-i cc-i--sm"><use href="#i-close" /></svg>
    </button>

    <input
      ref="inputRef"
      type="file"
      class="cc-avatar-up__file"
      accept="image/png,image/jpeg,image/jpg,image/gif,image/webp,image/bmp"
      @change="onChange"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { uploadAvatar } from '../api/avatar'
import { compressImageIfNeeded } from '../utils/image-compress'
import { avatarFallback, isImageAvatar } from '../utils/avatar'
import { toast } from '../utils/toast'

const props = withDefaults(
  defineProps<{
    /** 头像值：图片 URL / emoji / 空串 */
    modelValue?: string
    /** 名称，用于无图时取首字符兜底 */
    name?: string
    /** 头像直径（px） */
    size?: number
    /** 原图体积上限（MB），超过直接拒绝（压缩前的前置校验） */
    maxSizeMB?: number
    /** 禁用（例如抽屉正在提交） */
    disabled?: boolean
  }>(),
  {
    modelValue: '',
    name: '',
    size: 64,
    maxSizeMB: 5,
    disabled: false
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'uploading', value: boolean): void
  (e: 'uploaded', value: string): void
}>()

const inputRef = ref<HTMLInputElement | null>(null)
const uploading = ref(false)

const isImage = computed(() => isImageAvatar(props.modelValue))
const hasContent = computed(() => (props.modelValue || '').trim().length > 0)
/** 非图片值（emoji / 汉字）直接原样展示；空值取名称首字 */
const fallbackText = computed(() => {
  const raw = (props.modelValue || '').trim()
  if (raw) return raw
  return avatarFallback(props.name, '?')
})

const title = computed(() => {
  if (props.disabled) return '不可修改'
  if (uploading.value) return '上传中…'
  return hasContent.value ? '点击更换头像' : '点击上传头像'
})

function pick() {
  if (props.disabled || uploading.value) return
  inputRef.value?.click()
}

function remove() {
  if (props.disabled || uploading.value) return
  emit('update:modelValue', '')
}

async function onChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  // 清空 value，允许连续选择同一个文件
  input.value = ''
  if (!file) return

  if (!file.type.startsWith('image/')) {
    toast.warning('请选择图片文件')
    return
  }
  const limit = props.maxSizeMB * 1024 * 1024
  if (file.size > limit) {
    toast.warning(`头像不能超过 ${props.maxSizeMB}MB`)
    return
  }

  uploading.value = true
  emit('uploading', true)
  try {
    // 头像场景：长边 256px、质量 0.9，阈值调低让小图也走一次统一编码
    const compressed = await compressImageIfNeeded(file, {
      thresholdBytes: 200 * 1024,
      maxLongEdge: 256,
      quality: 0.9
    })
    const res = await uploadAvatar(compressed)
    const url = res?.data?.url
    if (res?.code === 200 && url) {
      emit('update:modelValue', url)
      emit('uploaded', url)
      toast.success('头像已上传')
    } else {
      toast.error(res?.message || '头像上传失败')
    }
  } catch (err) {
    const msg = err instanceof Error ? err.message : '头像上传失败'
    toast.error(msg, { description: '请确认后端已启动，或稍后重试' })
  } finally {
    uploading.value = false
    emit('uploading', false)
  }
}
</script>
