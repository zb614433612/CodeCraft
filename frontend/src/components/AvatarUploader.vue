<template>
  <div
    class="avatar-uploader"
    :class="[`shape-${shape}`, { uploading, empty: !hasContent }]"
    :style="{ width: size + 'px', height: size + 'px' }"
    :title="uploading ? '上传中...' : '点击上传头像'"
    @click="pick"
  >
    <img v-if="isImage" :src="modelValue!" class="avatar-img" alt="avatar" />
    <span v-else class="avatar-text" :style="{ fontSize: Math.round(size * 0.48) + 'px' }">
      {{ fallback }}
    </span>

    <div class="avatar-mask">
      <LoadingOutlined v-if="uploading" class="mask-icon" />
      <UploadOutlined v-else class="mask-icon" />
      <span v-if="size >= 56" class="mask-text">{{ uploading ? '上传中' : '更换' }}</span>
    </div>

    <input
      ref="inputRef"
      type="file"
      accept="image/png,image/jpeg,image/jpg,image/gif,image/webp,image/bmp"
      class="avatar-file-input"
      @change="onChange"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { message } from 'ant-design-vue'
import { LoadingOutlined, UploadOutlined } from '@ant-design/icons-vue'
import { uploadAvatar } from '@/api/avatar'
import { compressImageIfNeeded } from '@/utils/image-compress'
import { isImageAvatar, avatarFallbackText } from '@/utils/avatar'

const props = withDefaults(
  defineProps<{
    /** 当前头像值：本地图片 URL 或 emoji/文字 */
    modelValue?: string
    /** 名称（用于无头像时取首字母兜底） */
    name?: string
    /** 头像尺寸（px） */
    size?: number
    /** 形状 */
    shape?: 'circle' | 'square'
    /** 无任何内容时的兜底 emoji */
    fallbackEmoji?: string
  }>(),
  {
    size: 64,
    shape: 'circle',
    fallbackEmoji: '🤖'
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'uploaded', value: string): void
}>()

const inputRef = ref<HTMLInputElement | null>(null)
const uploading = ref(false)

const isImage = computed(() => isImageAvatar(props.modelValue))
const hasContent = computed(() => !!props.modelValue)
const fallback = computed(() => avatarFallbackText(props.modelValue, props.name, props.fallbackEmoji))

const pick = () => {
  if (uploading.value) return
  inputRef.value?.click()
}

const onChange = async (e: Event) => {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  // 允许重复选择同一文件（清空 value）
  input.value = ''
  if (!file) return

  if (!file.type.startsWith('image/')) {
    message.warning('请选择图片文件')
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    message.warning('头像文件不能超过 5MB')
    return
  }

  uploading.value = true
  try {
    // 压缩到长边 256px（头像场景），降低体积
    const compressed = await compressImageIfNeeded(file, {
      thresholdBytes: 200 * 1024,
      maxLongEdge: 256,
      quality: 0.9
    })
    const res = await uploadAvatar(compressed)
    if (res.code === 200 && res.data?.url) {
      emit('update:modelValue', res.data.url)
      emit('uploaded', res.data.url)
      message.success('头像上传成功')
    } else {
      message.error(res.message || '头像上传失败')
    }
  } catch (err: any) {
    message.error(err?.message || '头像上传失败')
  } finally {
    uploading.value = false
  }
}
</script>

<style scoped>
.avatar-uploader {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  cursor: pointer;
  flex-shrink: 0;
  background: linear-gradient(135deg, #8b5cf6, #7c3aed);
  color: #fff;
  user-select: none;
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}

.avatar-uploader.shape-circle {
  border-radius: 50%;
}

.avatar-uploader.shape-square {
  border-radius: 12px;
}

.avatar-uploader:hover {
  box-shadow: 0 4px 14px rgba(139, 92, 246, 0.35);
}

.avatar-uploader.empty {
  background: #f0f2f7;
  color: #8c8c8c;
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.avatar-text {
  line-height: 1;
  font-weight: 600;
}

.avatar-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  opacity: 0;
  transition: opacity 0.2s ease;
}

.avatar-uploader:hover .avatar-mask {
  opacity: 1;
}

.avatar-uploader.uploading .avatar-mask {
  opacity: 1;
}

.mask-icon {
  font-size: 16px;
}

.mask-text {
  font-size: 12px;
  line-height: 1;
}

.avatar-file-input {
  display: none;
}
</style>
