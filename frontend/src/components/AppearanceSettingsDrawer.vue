<template>
  <a-drawer
    :open="open"
    title="外观设置"
    placement="right"
    :width="380"
    :body-style="{ padding: '20px 22px' }"
    @close="emit('update:open', false)"
  >
    <div class="appearance-body">
      <!-- ============ 窗口背景图 ============ -->
      <div class="section-title">窗口背景图</div>
      <div class="bg-preview" :class="{ empty: !settings.bgImageUrl }">
        <div
          v-if="settings.bgImageUrl"
          class="bg-preview-img"
          :style="{ backgroundImage: `url(&quot;${settings.bgImageUrl}&quot;)`, opacity: settings.bgOpacity }"
        ></div>
        <div v-else class="bg-empty-tip">
          <PictureOutlined />
          <span>尚未设置背景图</span>
        </div>
      </div>

      <div class="bg-actions">
        <a-button type="primary" :loading="uploading" @click="pick">
          <UploadOutlined />
          {{ settings.bgImageUrl ? '更换图片' : '上传图片' }}
        </a-button>
        <a-button v-if="settings.bgImageUrl" danger @click="removeBg">移除</a-button>
        <input
          ref="fileRef"
          type="file"
          accept="image/png,image/jpeg,image/jpg,image/gif,image/webp,image/bmp"
          class="hidden-input"
          @change="onChange"
        />
      </div>

      <div class="slider-row">
        <span class="slider-label">背景不透明度</span>
        <a-slider
          v-model:value="bgOpacityPercent"
          :min="0"
          :max="100"
          :disabled="!settings.bgImageUrl"
          class="slider-control"
        />
        <span class="slider-value">{{ bgOpacityPercent }}%</span>
      </div>

      <div class="slider-row">
        <span class="slider-label">背景模糊</span>
        <a-slider
          v-model:value="settings.bgBlur"
          :min="0"
          :max="20"
          :disabled="!settings.bgImageUrl"
          class="slider-control"
        />
        <span class="slider-value">{{ settings.bgBlur }}px</span>
      </div>

      <a-divider />

      <!-- ============ 窗口整体透明度 ============ -->
      <div class="section-title">窗口整体透明度</div>
      <div class="slider-row">
        <span class="slider-label">整体透明度</span>
        <a-slider v-model:value="windowOpacityPercent" :min="30" :max="100" class="slider-control" />
        <span class="slider-value">{{ windowOpacityPercent }}%</span>
      </div>
      <p class="tip">调节整个软件窗口的不透明度（需桌面客户端；浏览器环境无效）</p>

      <a-divider />

      <a-button block @click="reset">恢复默认</a-button>
    </div>
  </a-drawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { message } from 'ant-design-vue'
import { UploadOutlined, PictureOutlined } from '@ant-design/icons-vue'
import { useSettingsStore } from '@/store/settings'
import { uploadBackground } from '@/api/appearance'
import { compressImageIfNeeded } from '@/utils/image-compress'

defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'update:open', v: boolean): void }>()

const settings = useSettingsStore()
const uploading = ref(false)
const fileRef = ref<HTMLInputElement | null>(null)

// 背景不透明度（store 存 0~1，滑块用 0~100）
const bgOpacityPercent = computed({
  get: () => Math.round(settings.bgOpacity * 100),
  set: (v: number) => { settings.bgOpacity = v / 100 }
})

// 窗口整体透明度（store 存 0.2~1，滑块用 30~100）
const windowOpacityPercent = computed({
  get: () => Math.round(settings.windowOpacity * 100),
  set: (v: number) => { settings.windowOpacity = v / 100 }
})

const pick = () => {
  if (!uploading.value) fileRef.value?.click()
}

const onChange = async (e: Event) => {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 允许重复选择同一文件
  if (!file) return

  if (!file.type.startsWith('image/')) {
    message.warning('请选择图片文件')
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    message.warning('背景图文件不能超过 10MB')
    return
  }

  uploading.value = true
  try {
    // 压缩到长边 2560（背景图保留较高分辨率）
    const compressed = await compressImageIfNeeded(file, {
      thresholdBytes: 500 * 1024,
      maxLongEdge: 2560,
      quality: 0.85
    })
    const res = await uploadBackground(compressed)
    if (res.code === 200 && res.data?.url) {
      settings.bgImageUrl = res.data.url
      message.success('背景图已更新')
    } else {
      message.error(res.message || '背景图上传失败')
    }
  } catch (err: any) {
    message.error(err?.message || '背景图上传失败')
  } finally {
    uploading.value = false
  }
}

const removeBg = () => {
  settings.bgImageUrl = ''
  message.success('已移除背景图')
}

const reset = () => {
  settings.resetAppearance()
  message.success('已恢复默认外观')
}
</script>

<style scoped>
.appearance-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.section-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-2, #5c5c78);
  letter-spacing: 0.3px;
}

.bg-preview {
  position: relative;
  width: 100%;
  height: 150px;
  border-radius: 12px;
  overflow: hidden;
  background:
    linear-gradient(45deg, #f0f0f0 25%, transparent 25%, transparent 75%, #f0f0f0 75%),
    linear-gradient(45deg, #f0f0f0 25%, #fafafa 25%, #fafafa 75%, #f0f0f0 75%);
  background-size: 16px 16px;
  background-position: 0 0, 8px 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(0, 0, 0, 0.06);
}

.bg-preview-img {
  position: absolute;
  inset: 0;
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  transition: opacity 0.2s ease;
}

.bg-empty-tip {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: #b0b0c0;
  font-size: 13px;
}

.bg-empty-tip :deep(.anticon) {
  font-size: 26px;
}

.bg-actions {
  display: flex;
  gap: 8px;
}

.hidden-input {
  display: none;
}

.slider-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.slider-label {
  width: 88px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--text-2, #5c5c78);
}

.slider-control {
  flex: 1;
  min-width: 0;
  margin: 4px 0;
}

.slider-value {
  width: 44px;
  flex-shrink: 0;
  text-align: right;
  font-size: 12px;
  color: var(--text-3, #9696aa);
  font-variant-numeric: tabular-nums;
}

.tip {
  margin: 0;
  font-size: 12px;
  color: var(--text-3, #9696aa);
  line-height: 1.5;
}
</style>
