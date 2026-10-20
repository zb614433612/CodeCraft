<!--
  外观设置页（v2）
  ----------------------------------------------------------------------------
  布局：页面头（恢复默认）→ 内联统计 → 三块卡片（主题 / 窗口 / 背景图）
  数据接缝：
    theme           -> settingsStore.theme（light / dark / auto，localStorage 持久化）
    bgImageUrl      -> POST /api/appearance/background 返回的 /api/appearance/file/<uuid>.<ext>
    bgOpacity       -> settingsStore.bgOpacity（0~1，滑块用 0~100）
    bgBlur          -> settingsStore.bgBlur（0~20px）
    windowOpacity   -> settingsStore.windowOpacity（0.2~1，仅 Electron 窗口级生效）
    emit('upload-bg')      -> 父级上传（multipart，字段名 file，≤10MB，图片类型）
    emit('remove-bg')      -> 清空 bgImageUrl
    emit('reset')          -> 恢复默认（背景图空 / 不透明度 50% / 模糊 0 / 窗口 100%）
  说明：
    1) 后端只存背景图文件，设置项不入库（与旧前端 store/settings.ts 一致）；
    2) theme=auto 时由父级读取 prefers-color-scheme 决定实际明暗；
    3) 浏览器环境窗口整体透明度无效，卡片内已标注。
-->
<template>
  <div class="cc-page-scroll cc-look">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-brush" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">外观设置</h1>
          <p class="cc-page-head__sub">
            主题、窗口背景图与透明度；设置仅保存在本机（背景图上传：/api/appearance/background）
          </p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn" @click="$emit('reset')">
            <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>恢复默认
          </button>
        </div>
      </div>

      <!-- 统计 -->
      <div class="cc-stats-inline" style="margin-bottom: var(--sp-4)">
        <span>当前主题 <b>{{ themeLabel }}</b></span><span class="cc-dot" />
        <span>背景图 <b>{{ bgImageUrl ? '已设置' : '未设置' }}</b></span><span class="cc-dot" />
        <span>背景不透明度 <b>{{ opacityPercent }}%</b></span><span class="cc-dot" />
        <span>模糊 <b>{{ bgBlur }}px</b></span><span class="cc-dot" />
        <span>右侧面板 <b>{{ workspaceMode ? '工作区' : '抽屉' }}</b></span>
      </div>

      <div class="cc-look-grid">
        <!-- ========== 主题 ========== -->
        <section class="cc-look-card">
          <div class="cc-look-card__head">
            <div class="cc-look-card__icon">
              <svg class="cc-i cc-i--sm"><use href="#i-sun" /></svg>
            </div>
            <div>
              <div class="cc-look-card__title">主题</div>
              <div class="cc-look-card__desc">跟随系统时由操作系统外观决定</div>
            </div>
          </div>
          <div class="cc-look-themes">
            <button
              v-for="opt in THEME_OPTIONS"
              :key="opt.value"
              class="cc-look-theme"
              :class="{ 'is-on': theme === opt.value }"
              @click="$emit('update:theme', opt.value)"
            >
              <span class="cc-look-theme__swatch" :class="'is-' + opt.value" />
              {{ opt.label }}
            </button>
          </div>
          <div class="cc-look-note">浅色 / 深色即时生效；「跟随系统」读取 prefers-color-scheme。</div>
        </section>

        <!-- ========== 窗口 ========== -->
        <section class="cc-look-card">
          <div class="cc-look-card__head">
            <div class="cc-look-card__icon">
              <svg class="cc-i cc-i--sm"><use href="#i-fold" /></svg>
            </div>
            <div>
              <div class="cc-look-card__title">窗口</div>
              <div class="cc-look-card__desc">整体不透明度仅桌面客户端生效</div>
            </div>
          </div>
          <div class="cc-look-range">
            <span class="cc-look-range__label">整体透明度</span>
            <input
              type="range"
              min="30"
              max="100"
              step="1"
              :value="windowOpacityPercent"
              @input="onWindowOpacityInput"
            />
            <span class="cc-look-range__val">{{ windowOpacityPercent }}%</span>
          </div>
          <div class="cc-look-range">
            <span class="cc-look-range__label">界面密度</span>
            <select class="cc-select" style="flex: 1" :value="uiDensity" @change="onDensityChange">
              <option value="comfortable">舒适（默认）</option>
              <option value="compact">紧凑</option>
            </select>
            <span class="cc-look-range__val">{{ uiDensity === 'compact' ? '紧凑' : '舒适' }}</span>
          </div>
          <!--
            P4：右侧面板形态开关。
            工作区 = 多标签 + 可拆分屏 + 内嵌网页；抽屉 = 原来的单面板。
            两套实现并存，随时可切回 —— 新形态万一有兼容问题，用户不必等修版。
          -->
          <div class="cc-look-range">
            <span class="cc-look-range__label">右侧面板</span>
            <select class="cc-select" style="flex: 1" :value="workspaceMode ? 'workspace' : 'drawer'" @change="onPanelModeChange">
              <option value="workspace">多标签工作区（可分屏 / 开网页）</option>
              <option value="drawer">传统单抽屉</option>
            </select>
            <span class="cc-look-range__val">—</span>
          </div>
          <div class="cc-look-note">
            浏览器环境整体透明度无效；界面密度只影响间距 token，不影响功能。
            右侧面板形态在聊天页头部点「文件 / Git / 终端」时生效，也可在工作区头部一键切回抽屉。
          </div>
        </section>

        <!-- ========== 背景图 ========== -->
        <section class="cc-look-card cc-look-card--wide">
          <div class="cc-look-card__head">
            <div class="cc-look-card__icon">
              <svg class="cc-i cc-i--sm"><use href="#i-image" /></svg>
            </div>
            <div>
              <div class="cc-look-card__title">窗口背景图</div>
              <div class="cc-look-card__desc">支持 png / jpg / webp / gif / bmp，单张 ≤10MB</div>
            </div>
            <span class="cc-look-card__hint">{{ bgImageUrl ? '已设置' : '未设置' }}</span>
          </div>

          <div class="cc-look-bg">
            <div
              v-if="bgImageUrl"
              class="cc-look-bg__img"
              :style="previewStyle"
            />
            <div v-else class="cc-look-bg__empty">
              <svg class="cc-i" style="width: 22px; height: 22px"><use href="#i-image" /></svg>
              <span>尚未设置背景图</span>
            </div>
          </div>

          <div class="cc-look-bg__ops">
            <input
              ref="fileRef"
              type="file"
              accept="image/png,image/jpeg,image/jpg,image/gif,image/webp,image/bmp"
              style="display: none"
              @change="onFileChange"
            />
            <button class="cc-btn cc-btn--primary" :disabled="uploading" @click="pickFile">
              <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>
              {{ uploading ? '上传中…' : bgImageUrl ? '更换图片' : '上传图片' }}
            </button>
            <button class="cc-btn" :disabled="!bgImageUrl" @click="$emit('remove-bg')">
              <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>移除
            </button>
          </div>

          <div class="cc-look-range">
            <span class="cc-look-range__label">不透明度</span>
            <input type="range" min="0" max="100" step="1" :value="opacityPercent" @input="onOpacityInput" />
            <span class="cc-look-range__val">{{ opacityPercent }}%</span>
          </div>
          <div class="cc-look-range">
            <span class="cc-look-range__label">模糊</span>
            <input type="range" min="0" max="20" step="1" :value="bgBlur" @input="onBlurInput" />
            <span class="cc-look-range__val">{{ bgBlur }}px</span>
          </div>

          <div class="cc-look-note">
            上传走 POST /api/appearance/background（multipart，字段名 file），返回
            /api/appearance/file/&lt;uuid&gt;.&lt;ext&gt; 直接作为 background-image；不透明度与模糊即时生效。
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'

/** 主题三态（与旧前端 store/settings.ts 的 ThemeMode 一致） */
export type ThemeMode = 'light' | 'dark' | 'auto'

/** 界面密度（与 store/settings.ts 的 UiDensity 同构；本页只做展示与回传，不直连 store） */
export type UiDensity = 'comfortable' | 'compact'

const props = withDefaults(
  defineProps<{
    theme?: ThemeMode
    /** 背景图 URL，空串表示未设置 */
    bgImageUrl?: string
    /** 0~1 */
    bgOpacity?: number
    /** 0~20 px */
    bgBlur?: number
    /** 0.2~1（仅 Electron 窗口级生效） */
    windowOpacity?: number
    /** 背景图上传中（父级 POST /api/appearance/background 期间锁按钮） */
    uploading?: boolean
    /** 右侧面板形态：true = 多标签工作区，false = 传统抽屉（P4） */
    workspaceMode?: boolean
    /** 界面密度：comfortable = 默认，compact = 收紧间距（存 settingsStore 并写 <html data-density>） */
    uiDensity?: UiDensity
    /**
     * 进入页面时的数据加载（由 App.vue 的 PAGE_BINDINGS 注入）
     * 页面随路由真实挂载，所以在这里调一次即可；重复进页由父级的「已加载 / 加载中 / 已失败」跳过
     */
    load?: () => void
  }>(),
  {
    theme: 'light',
    bgImageUrl: '',
    bgOpacity: 0.5,
    bgBlur: 0,
    windowOpacity: 1,
    uploading: false,
    workspaceMode: true,
    uiDensity: 'comfortable'
  }
)

const emit = defineEmits<{
  (e: 'update:theme', value: ThemeMode): void
  (e: 'update:bgOpacity', value: number): void
  (e: 'update:bgBlur', value: number): void
  (e: 'update:windowOpacity', value: number): void
  (e: 'update:workspaceMode', value: boolean): void
  (e: 'update:uiDensity', value: UiDensity): void
  (e: 'upload-bg', file: File): void
  (e: 'remove-bg'): void
  (e: 'reset'): void
}>()

const THEME_OPTIONS: Array<{ label: string; value: ThemeMode }> = [
  { label: '浅色', value: 'light' },
  { label: '深色', value: 'dark' },
  { label: '跟随系统', value: 'auto' }
]

const fileRef = ref<HTMLInputElement | null>(null)

const opacityPercent = computed(() => Math.round((props.bgOpacity ?? 0.5) * 100))
const windowOpacityPercent = computed(() => Math.round((props.windowOpacity ?? 1) * 100))

const themeLabel = computed(() => {
  const hit = THEME_OPTIONS.find(opt => opt.value === props.theme)
  return hit ? hit.label : '浅色'
})

const previewStyle = computed(() => ({
  backgroundImage: `url("${props.bgImageUrl}")`,
  opacity: String(opacityPercent.value / 100),
  filter: props.bgBlur ? `blur(${props.bgBlur}px)` : 'none'
}))

function pickFile() {
  fileRef.value?.click()
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  // 类型/大小校验与提示统一放在父级（App.vue onLookUploadBg），避免两处口径不一致
  emit('upload-bg', file)
}

function onOpacityInput(event: Event) {
  const value = Number((event.target as HTMLInputElement).value)
  emit('update:bgOpacity', value / 100)
}

function onBlurInput(event: Event) {
  emit('update:bgBlur', Number((event.target as HTMLInputElement).value))
}

function onWindowOpacityInput(event: Event) {
  const value = Number((event.target as HTMLInputElement).value)
  emit('update:windowOpacity', value / 100)
}

/** 右侧面板形态切换（工作区 ↔ 抽屉），值由父级写回 settingsStore 并持久化 */
function onPanelModeChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  emit('update:workspaceMode', value === 'workspace')
}

/**
 * 界面密度切换。
 *
 * <p>值回传父级写入 settingsStore（persist），再由 AppShell 写到 `<html data-density>`；
 * tokens.css 末尾按该属性收紧 --sp-* 与顶栏高度 —— 选完立刻见效（此前只是个不落地的本地 ref）。</p>
 */
function onDensityChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  emit('update:uiDensity', value === 'compact' ? 'compact' : 'comfortable')
}

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>
