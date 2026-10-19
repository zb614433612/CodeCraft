<!--
  版本与更新页（F06）
  ----------------------------------------------------------------------------
  定位：桌面客户端「关于 + 升级」的唯一入口，同时作为浏览器环境下查看版本的落地页。
  组成：页面头 → 版本矩阵（VersionCard 自包含）→ 更新设置（自包含）→ 更新策略说明。
  数据接缝：
    · VersionCard：window.electronAPI.update（检查 / 下载 / 安装）+ GET /api/system/version（后端版本）
    · 本页设置区：electronAPI.update.getSettings / setSettings（持久化在主进程 userData/update-settings.json）
    · 清理本地缓存：electronAPI.clearLocalData（升级备份 / 已下载安装包 / 检测缓存）
  浏览器环境（无 electronAPI）：
    设置区退化为只读说明，不渲染开关与输入框 —— 不虚构「看起来能改其实改不了」的交互。
-->
<template>
  <div class="cc-page-scroll cc-about">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-refresh" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">版本与更新</h1>
          <p class="cc-page-head__sub">
            查看客户端与后端版本、检查并安装新版本；更新不会改动你的数据目录
          </p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn" :disabled="loading" @click="reload">
            <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>刷新
          </button>
        </div>
      </div>

      <!-- 统计 -->
      <div class="cc-stats-inline" style="margin-bottom: var(--sp-4)">
        <span>当前版本 <b>v{{ state?.currentVersion || backendInfo?.version || '—' }}</b></span><span class="cc-dot" />
        <span>运行环境 <b>{{ supported ? platformLabel(state?.platform || '') : '浏览器' }}</b></span><span class="cc-dot" />
        <span>自动检查 <b>{{ supported ? (settings.autoCheck ? '已开启' : '已关闭') : '不可用' }}</b></span>
      </div>

      <!-- 版本与更新主卡片 -->
      <VersionCard :backend-info="backendInfo" />

      <!-- 更新设置 -->
      <section class="cc-about-card">
        <div class="cc-about-card__head">
          <div class="cc-about-card__icon">
            <svg class="cc-i cc-i--sm"><use href="#i-cog" /></svg>
          </div>
          <div>
            <div class="cc-about-card__title">更新设置</div>
            <div class="cc-about-card__desc">
              {{ supported ? '设置保存在本机，只影响这台设备的更新行为' : '以下设置仅在桌面客户端内可修改' }}
            </div>
          </div>
        </div>

        <!-- 桌面端：可编辑 -->
        <div v-if="supported" class="cc-about-settings">
          <div class="cc-switch-row">
            <div class="cc-switch" :class="{ 'is-on': settings.autoCheck }" @click="toggleSetting('autoCheck')" />
            <div class="cc-switch-row__text">
              <div class="cc-switch-row__title">启动时自动检查更新</div>
              <div class="cc-switch-row__desc">启动 15 秒后静默检查一次；发现新版本只在应用内提示，不会自动下载</div>
            </div>
          </div>

          <div class="cc-switch-row">
            <div class="cc-switch" :class="{ 'is-on': settings.useMirror }" @click="toggleSetting('useMirror')" />
            <div class="cc-switch-row__text">
              <div class="cc-switch-row__title">下载使用国内加速</div>
              <div class="cc-switch-row__desc">优先走镜像前缀，失败会自动回退 GitHub 直连（不影响正确性，只影响速度）</div>
            </div>
          </div>

          <div class="cc-field">
            <span class="cc-field__label">镜像前缀</span>
            <input
              v-model="mirrorDraft"
              class="cc-input"
              type="text"
              placeholder="https://ghproxy.net/"
              :disabled="!settings.useMirror || saving"
              @keyup.enter="saveMirror"
              @blur="saveMirror"
            />
            <div class="cc-field__hint">
              需以 http(s):// 开头，末尾自动补 /；留空保存则恢复默认值 https://ghproxy.net/
            </div>
          </div>

          <div class="cc-field">
            <span class="cc-field__label">升级前备份保留份数</span>
            <div class="cc-about-inline">
              <input
                v-model.number="backupsDraft"
                class="cc-input cc-about-num"
                type="number"
                min="1"
                max="10"
                :disabled="saving"
                @change="saveBackups"
              />
              <span class="cc-field__hint">安装新版本前会把数据库目录备份到本机（1~10 份，超出自动清理最旧的）</span>
            </div>
          </div>

          <div class="cc-about-actions">
            <button class="cc-btn" :disabled="saving" @click="reload">
              <svg class="cc-i cc-i--sm"><use href="#i-sync" /></svg>重新读取设置
            </button>
            <button class="cc-btn cc-btn--danger" :disabled="clearing" @click="onClearLocal">
              <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>
              {{ clearing ? '清理中…' : '清理升级缓存' }}
            </button>
          </div>
          <div class="cc-field__hint">
            清理范围：升级前备份、已下载的安装包、更新检测缓存；<b>不会</b>动数据库与业务数据。
          </div>
        </div>

        <!-- 浏览器环境：只读说明 -->
        <div v-else class="cc-about-readonly">
          <div class="cc-about-readonly__row">
            <span class="cc-vercard__label">自动检查更新</span>
            <span>桌面客户端内可配置（启动 15 秒后静默检查）</span>
          </div>
          <div class="cc-about-readonly__row">
            <span class="cc-vercard__label">下载加速</span>
            <span>默认使用 ghproxy.net 镜像前缀，失败自动回退直连</span>
          </div>
          <div class="cc-about-readonly__row">
            <span class="cc-vercard__label">升级前备份</span>
            <span>安装前把数据库目录备份到本机，默认保留 3 份</span>
          </div>
        </div>
      </section>

      <!-- 更新策略说明 -->
      <section class="cc-about-card">
        <div class="cc-about-card__head">
          <div class="cc-about-card__icon">
            <svg class="cc-i cc-i--sm"><use href="#i-shield" /></svg>
          </div>
          <div>
            <div class="cc-about-card__title">更新是怎么做的</div>
            <div class="cc-about-card__desc">读一遍能省掉大部分「升级会不会丢数据」的担心</div>
          </div>
        </div>
        <ul class="cc-about-list">
          <li>
            <b>数据不在安装目录</b>：数据库、日志、接收文件都在用户数据目录（<code>userData/runtime</code>），
            安装器只替换程序文件，不会触碰数据。
          </li>
          <li>
            <b>升级前额外备份</b>：安装前把 <code>runtime</code> 下的数据库复制到 <code>userData/backups</code>，
            保留最近若干份（可在上方设置），升级出问题可回滚。
          </li>
          <li>
            <b>检测不依赖发布流水线</b>：直接读 GitHub Releases 的公开资产，不使用 latest.yml，
            因此不需要代码签名也能用。
          </li>
          <li>
            <b>各平台能力不同</b>：Windows 可静默安装并自动重启；macOS 受 Gatekeeper 限制，只能打开 dmg
            由你拖拽替换；Linux 定位安装包后手动替换。
          </li>
          <li>
            <b>版本号两处独立</b>：客户端版本来自安装包（<code>electron/package.json</code>），
            后端版本来自 jar（<code>pom.xml</code>）。只换 jar 不重装客户端时两者会不一致，属正常现象。
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import VersionCard from '../components/VersionCard.vue'
import { fetchAppVersion, type AppVersionInfo } from '../api/system'
import { toast } from '../utils/toast'
import {
  clearLocalAppData,
  getUpdateApi,
  isUpdateSupported,
  platformLabel,
  type UpdateSettings,
  type UpdateStateSnapshot
} from '../utils/update'

const api = getUpdateApi()
const supported = isUpdateSupported()

const state = ref<UpdateStateSnapshot | null>(null)
const settings = reactive<UpdateSettings>({
  autoCheck: true,
  useMirror: true,
  mirrorPrefix: 'https://ghproxy.net/',
  maxBackups: 3
})
/** 设置区草稿（输入框用；保存成功后再回写 settings，避免抖动） */
const mirrorDraft = ref('')
const backupsDraft = ref(3)
/** 后端版本（统计行与 VersionCard 共用，只请求一次） */
const backendInfo = ref<AppVersionInfo | null>(null)
const loading = ref(false)
const saving = ref(false)
const clearing = ref(false)

/** 把主进程返回的设置同步到本地（草稿一并覆盖，避免用户看到旧值） */
function applySettings (next: UpdateSettings) {
  settings.autoCheck = next.autoCheck !== false
  settings.useMirror = next.useMirror !== false
  settings.mirrorPrefix = next.mirrorPrefix || 'https://ghproxy.net/'
  settings.maxBackups = Number(next.maxBackups) || 3
  mirrorDraft.value = settings.mirrorPrefix
  backupsDraft.value = settings.maxBackups
}

async function reload () {
  if (!api) return
  loading.value = true
  try {
    const snapshot = await api.getState()
    state.value = snapshot
    if (snapshot.settings) applySettings(snapshot.settings)
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '读取更新设置失败')
  } finally {
    loading.value = false
  }
}

/** 后端版本（不含敏感数据，无管理员校验）；失败只记日志，不阻塞页面 */
async function loadBackendInfo () {
  try {
    backendInfo.value = await fetchAppVersion()
  } catch (e) {
    console.warn('[about] 读取后端版本失败:', e)
  }
}

async function patchSettings (patch: Partial<UpdateSettings>) {
  if (!api || saving.value) return
  saving.value = true
  try {
    applySettings(await api.setSettings(patch))
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '保存更新设置失败')
    // 保存失败时把草稿拉回真实值，避免界面停留在未生效的状态
    await reload()
  } finally {
    saving.value = false
  }
}

function toggleSetting (key: 'autoCheck' | 'useMirror') {
  void patchSettings({ [key]: !settings[key] })
}

/** 镜像前缀：空串表示恢复默认（主进程 sanitizeSettings 会把空值替换成默认前缀） */
function saveMirror () {
  const next = mirrorDraft.value.trim()
  if (next && !/^https?:\/\//i.test(next)) {
    toast.warning('镜像前缀需以 http:// 或 https:// 开头')
    mirrorDraft.value = settings.mirrorPrefix
    return
  }
  if (next === settings.mirrorPrefix) return
  void patchSettings({ mirrorPrefix: next })
}

function saveBackups () {
  const value = Math.min(10, Math.max(1, Number(backupsDraft.value) || 3))
  backupsDraft.value = value
  if (value === settings.maxBackups) return
  void patchSettings({ maxBackups: value })
}

async function onClearLocal () {
  if (clearing.value) return
  clearing.value = true
  try {
    const result = await clearLocalAppData()
    if (!result) {
      toast.warning('当前环境不支持本地清理')
      return
    }
    if (result.removed.length) {
      toast.success('已清理本地升级缓存', { description: result.removed.join('、') })
    } else {
      toast.info('没有需要清理的文件')
    }
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '清理失败')
  } finally {
    clearing.value = false
  }
}

onMounted(() => {
  void loadBackendInfo()
  void reload()
})
</script>
