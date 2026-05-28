<template>
  <div class="config-app">
    <!-- ============ 顶部标题栏 ============ -->
    <header class="top-bar">
      <div class="top-left">
        <div class="logo-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="3"/>
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
          </svg>
        </div>
        <span class="top-title">设置</span>
        <span class="top-subtitle">系统配置与偏好</span>
      </div>
    </header>

    <!-- ============ 内容区域 ============ -->
    <div class="config-content">
      <!-- DeepSeek API 配置卡片 -->
      <div class="config-card card-api">
        <div class="card-header">
          <div class="card-header-left">
            <span class="card-icon">🔑</span>
            <div class="card-header-text">
              <div class="card-title">DeepSeek API 配置</div>
              <div class="card-description">管理 API 密钥，用于调用 DeepSeek 大模型服务</div>
            </div>
          </div>
          <div class="card-badge" :class="maskedKey ? 'active' : ''">
            {{ maskedKey ? '已配置' : '未配置' }}
          </div>
        </div>
        <div class="card-body">
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 16 }" class="config-form">
            <a-form-item label="API Key">
              <a-input-password
                v-model:value="apiKey"
                :placeholder="maskedKey ? '输入新 Key 以更新...' : '请输入 DeepSeek API Key'"
                :maxLength="200"
                class="uniform-input"
              />
            </a-form-item>
            <a-form-item v-if="maskedKey" label="当前 Key">
              <span class="masked-key">{{ maskedKey }}</span>
            </a-form-item>
            <a-form-item :wrapper-col="{ offset: 4, span: 16 }">
              <a-button type="primary" :loading="apiKeySaving" class="btn-save" @click="handleSaveApiKey">
                <span v-if="!apiKeySaving">💾 保存</span>
              </a-button>
            </a-form-item>
          </a-form>
          <div class="config-hint">
            <div class="hint-icon">💡</div>
            <div class="hint-text">
              <p>配置后，所有聊天请求将使用此 Key 调用 DeepSeek API。</p>
              <p>如不配置，则使用服务端默认的 Key。</p>
            </div>
          </div>
        </div>
      </div>

      <!-- AI 角色性格配置卡片 -->
      <div class="config-card card-char">
        <div class="card-header">
          <div class="card-header-left">
            <span class="card-icon">🎭</span>
            <div class="card-header-text">
              <div class="card-title">AI 角色性格配置</div>
              <div class="card-description">自定义 AI 助手的人设，让它以你喜欢的风格对话</div>
            </div>
          </div>
          <div class="card-badge" :class="hasCharConfig ? 'active' : ''">
            {{ hasCharConfig ? '已配置' : '未配置' }}
          </div>
        </div>
        <div class="card-body">
          <p class="card-desc">配置后 AI 将按照设定的角色性格进行对话。留空则不启用。</p>
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 16 }" class="config-form char-form">
            <div class="form-grid">
              <a-form-item label="姓名">
                <a-input v-model:value="charProfile.name" placeholder="如：小柔" class="uniform-input" />
              </a-form-item>
              <a-form-item label="物种">
                <a-select v-model:value="charProfile.species" placeholder="请选择" class="uniform-input">
                  <a-select-option value="人">👤 人类</a-select-option>
                  <a-select-option value="猫娘">🐱 猫娘</a-select-option>
                  <a-select-option value="狗娘">🐶 狗娘</a-select-option>
                  <a-select-option value="精灵">🧝 精灵</a-select-option>
                  <a-select-option value="机器人">🤖 机器人</a-select-option>
                  <a-select-option value="自定义">✨ 自定义</a-select-option>
                </a-select>
              </a-form-item>
              <a-form-item label="性别">
                <a-select v-model:value="charProfile.gender" placeholder="请选择" class="uniform-input">
                  <a-select-option value="男">♂️ 男</a-select-option>
                  <a-select-option value="女">♀️ 女</a-select-option>
                  <a-select-option value="无性">⚪ 无性</a-select-option>
                  <a-select-option value="其他">🌈 其他</a-select-option>
                </a-select>
              </a-form-item>
              <a-form-item label="年龄">
                <a-input-number v-model:value="charProfile.age" :min="1" :max="9999" placeholder="年龄" class="uniform-input" />
              </a-form-item>
              <a-form-item label="性格">
                <a-textarea v-model:value="charProfile.personality" placeholder="如：温柔体贴，善解人意" :rows="2" :maxLength="500" class="uniform-input" />
              </a-form-item>
              <a-form-item label="称呼">
                <a-input v-model:value="charProfile.greeting" placeholder="用户对你的称呼，如：主人、哥哥" class="uniform-input" />
              </a-form-item>
              <a-form-item label="背景">
                <a-textarea v-model:value="charProfile.background" placeholder="如：来自未来世界的 AI 助手..." :rows="3" :maxLength="1000" class="uniform-input" />
              </a-form-item>
              <a-form-item label="喜好">
                <a-input v-model:value="charProfile.likes" placeholder="如：看书、听音乐、帮助人类" class="uniform-input" />
              </a-form-item>
              <a-form-item label="风格">
                <a-textarea v-model:value="charProfile.style" placeholder="如：说话温柔，喜欢用表情符号" :rows="2" :maxLength="500" class="uniform-input" />
              </a-form-item>
            </div>

            <a-form-item :wrapper-col="{ offset: 4, span: 16 }" class="form-actions">
              <a-button type="primary" :loading="charSaving" class="btn-save" @click="handleSaveChar">
                <span v-if="!charSaving">💾 保存角色配置</span>
              </a-button>
              <a-button v-if="hasCharConfig" class="btn-clear" @click="handleClearChar">
                🗑 清空配置
              </a-button>
            </a-form-item>
          </a-form>
          <div class="config-hint">
            <div class="hint-icon">💡</div>
            <div class="hint-text">
              <p>配置后首次对话时自动注入到系统提示词中，让 AI 扮演设定的角色。</p>
              <p>如不配置则 AI 按默认方式回复。</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { getConfig, setConfig } from '@/api/config'

// ===== API Key 配置 =====
const apiKey = ref('')
const maskedKey = ref('')
const apiKeySaving = ref(false)

const loadApiKey = async () => {
  try {
    const res = await getConfig('deepseek_api_key')
    if (res.code === 200 && res.data) {
      maskedKey.value = res.data.value || ''
    }
  } catch { /* 静默失败 */ }
}

const handleSaveApiKey = async () => {
  if (!apiKey.value.trim()) {
    message.warning('请输入 API Key')
    return
  }
  apiKeySaving.value = true
  try {
    await setConfig('deepseek_api_key', apiKey.value.trim())
    message.success('API Key 已保存')
    apiKey.value = ''
    await loadApiKey()
  } catch (e: any) {
    message.error(e.message || '保存失败')
  } finally {
    apiKeySaving.value = false
  }
}

// ===== 角色性格配置 =====
const charProfile = reactive({
  name: '',
  species: '',
  gender: '',
  age: undefined as number | undefined,
  personality: '',
  greeting: '',
  background: '',
  likes: '',
  style: ''
})

const charSaving = ref(false)
const hasCharConfig = ref(false)

const loadCharProfile = async () => {
  try {
    const res = await getConfig('character_profile')
    if (res.code === 200 && res.data && res.data.value) {
      try {
        const data = JSON.parse(res.data.value)
        if (data && typeof data === 'object') {
          Object.assign(charProfile, {
            name: data.name || '',
            species: data.species || '',
            gender: data.gender || '',
            age: data.age || undefined,
            personality: data.personality || '',
            greeting: data.greeting || '',
            background: data.background || '',
            likes: data.likes || '',
            style: data.style || ''
          })
          hasCharConfig.value = true
        }
      } catch { /* JSON 解析失败则忽略 */ }
    }
  } catch { /* 静默失败 */ }
}

const handleSaveChar = async () => {
  const data: Record<string, any> = {}
  if (charProfile.name) data.name = charProfile.name
  if (charProfile.species) data.species = charProfile.species
  if (charProfile.gender) data.gender = charProfile.gender
  if (charProfile.age) data.age = charProfile.age
  if (charProfile.personality) data.personality = charProfile.personality
  if (charProfile.greeting) data.greeting = charProfile.greeting
  if (charProfile.background) data.background = charProfile.background
  if (charProfile.likes) data.likes = charProfile.likes
  if (charProfile.style) data.style = charProfile.style

  if (Object.keys(data).length === 0) {
    message.warning('请至少填写一项角色配置')
    return
  }

  const emojiRegex = /\p{Extended_Pictographic}/u
  for (const [, value] of Object.entries(data)) {
    if (typeof value === 'string' && emojiRegex.test(value)) {
      message.warning('角色配置不能包含 emoji 表情符号，请移除后重试')
      return
    }
  }

  charSaving.value = true
  try {
    await setConfig('character_profile', JSON.stringify(data))
    message.success('角色性格配置已保存')
    hasCharConfig.value = true
  } catch (e: any) {
    message.error(e.message || '保存失败')
  } finally {
    charSaving.value = false
  }
}

const handleClearChar = async () => {
  try {
    await setConfig('character_profile', '')
    Object.assign(charProfile, {
      name: '', species: '', gender: '', age: undefined,
      personality: '', greeting: '', background: '', likes: '', style: ''
    })
    hasCharConfig.value = false
    message.success('角色配置已清空')
  } catch (e: any) {
    message.error(e.message || '清空失败')
  }
}

onMounted(() => {
  loadApiKey()
  loadCharProfile()
})
</script>

<style scoped>
/* ============================================================
   Config View v2 — Bento卡片 · 紫色主题 · 暗色模式 · 微交互
   参考 P2P Panel 设计语言
   ============================================================ */

/* ---------- CSS 变量 ---------- */
.config-app {
  --accent: #8b5cf6;
  --accent-lt: rgba(139, 92, 246, 0.08);
  --accent-md: rgba(139, 92, 246, 0.18);
  --accent-dk: #7c3aed;
  --accent-glow: rgba(139, 92, 246, 0.3);

  --green: #10b981;
  --green-lt: rgba(16, 185, 129, 0.1);
  --green-md: rgba(16, 185, 129, 0.2);

  --bg-root: #f5f3fa;
  --bg-card: #ffffff;
  --bg-input: #faf9fc;
  --text-1: #1a1a2e;
  --text-2: #5c5c78;
  --text-3: #9696aa;
  --text-4: #b8b8c8;
  --border: #e8e5f0;
  --border-lt: #f0edf6;
  --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.04);
  --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.06);
  --shadow-lg: 0 8px 30px rgba(0, 0, 0, 0.08);
  --radius: 14px;
  --radius-sm: 10px;
  --radius-xs: 6px;

  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg-root);
  font-size: 14px;
  color: var(--text-1);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  overflow-y: auto;
}

/* ============ 顶部栏 ============ */
.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 24px;
  background: var(--bg-card);
  border-bottom: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
  flex-shrink: 0;
  backdrop-filter: blur(10px);
  position: sticky;
  top: 0;
  z-index: 10;
}

.top-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo-icon {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--accent), var(--accent-dk));
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  box-shadow: 0 2px 10px var(--accent-glow);
  flex-shrink: 0;
}

.logo-icon svg {
  width: 20px;
  height: 20px;
}

.top-title {
  font-weight: 700;
  font-size: 17px;
  letter-spacing: -0.2px;
  color: var(--text-1);
}

.top-subtitle {
  font-size: 13px;
  color: var(--text-3);
  padding-left: 12px;
  border-left: 1px solid var(--border);
}

/* ============ 内容区域 ============ */
.config-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 24px;
  max-width: 900px;
  width: 100%;
  margin: 0 auto;
  box-sizing: border-box;
}

/* ============ Bento 卡片 ============ */
.config-card {
  background: var(--bg-card);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  border: 1px solid transparent;
  overflow: hidden;
  transition: all 0.3s cubic-bezier(0.16, 1, 0.3, 1);
  animation: cardSlideIn 0.4s cubic-bezier(0.16, 1, 0.3, 1) both;
}

.config-card.card-api {
  animation-delay: 0s;
}

.config-card.card-char {
  animation-delay: 0.1s;
}

@keyframes cardSlideIn {
  from {
    opacity: 0;
    transform: translateY(16px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.config-card:hover {
  box-shadow: var(--shadow-md);
  border-color: var(--accent-md);
  transform: translateY(-1px);
}

/* ---------- 卡片头部 ---------- */
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 24px;
  border-bottom: 1px solid var(--border-lt);
  background: linear-gradient(180deg, var(--accent-lt), transparent);
}

.card-header-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.card-icon {
  font-size: 24px;
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--accent-lt), var(--accent-md));
  flex-shrink: 0;
  line-height: 1;
}

.card-header-text {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.card-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-1);
  letter-spacing: -0.2px;
  line-height: 1.2;
}

.card-description {
  font-size: 12px;
  color: var(--text-3);
  font-weight: 400;
  line-height: 1.4;
}

/* 状态徽章 */
.card-badge {
  font-size: 11px;
  font-weight: 700;
  padding: 5px 14px;
  border-radius: 20px;
  flex-shrink: 0;
  background: #f0edf6;
  color: var(--text-3);
  border: 1px solid transparent;
  transition: all 0.3s ease;
}

.card-badge.active {
  background: var(--green-lt);
  color: #059669;
  border-color: var(--green-md);
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.08);
}

/* ---------- 卡片内容 ---------- */
.card-body {
  padding: 24px;
}

.card-desc {
  margin: 0 0 20px 0;
  color: var(--text-2);
  font-size: 13px;
  line-height: 1.6;
  padding: 10px 16px;
  background: var(--accent-lt);
  border-radius: var(--radius-xs);
  border-left: 3px solid var(--accent);
}

/* ---------- 表单样式 ---------- */
.config-form :deep(.ant-form-item) {
  margin-bottom: 18px;
}

.config-form :deep(.ant-form-item-label > label) {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
}

.config-form :deep(.ant-form-item-label > label)::after {
  content: ':';
  margin: 0 8px 0 2px;
}

/* 表单网格布局（角色配置） */
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 24px;
}

.form-grid :deep(.ant-form-item) {
  margin-bottom: 16px;
}

/* 让 textarea 和全宽字段占据整行 */
.form-grid :deep(.ant-form-item:last-child) {
  grid-column: 1 / -1;
}

/* 操作按钮 */
.form-actions {
  margin-top: 4px !important;
  padding-top: 8px;
}

.btn-save {
  border-radius: var(--radius-sm);
  font-weight: 600;
  font-size: 13px;
  padding: 0 22px;
  height: 36px;
  transition: all 0.2s ease;
  box-shadow: 0 2px 8px var(--accent-glow);
}

.btn-save:not(:disabled):hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 14px var(--accent-glow);
}

.btn-clear {
  margin-left: 12px;
  border-radius: var(--radius-sm);
  font-weight: 500;
  font-size: 13px;
  padding: 0 18px;
  height: 36px;
  transition: all 0.2s ease;
  border-color: var(--border);
  color: var(--text-2);
}

.btn-clear:hover {
  border-color: #ef4444;
  color: #ef4444;
  background: rgba(239, 68, 68, 0.04);
}

/* 统一输入框宽度 */
.uniform-input {
  max-width: 420px;
  width: 100%;
}

.char-form :deep(.ant-input-number) {
  max-width: 420px;
  width: 100%;
}

/* ---------- 掩码 Key ---------- */
.masked-key {
  color: var(--text-3);
  font-family: 'SF Mono', 'Consolas', 'Fira Code', monospace;
  font-size: 13px;
  background: var(--bg-input);
  padding: 4px 10px;
  border-radius: 4px;
  border: 1px solid var(--border-lt);
}

/* ---------- 提示区域 ---------- */
.config-hint {
  display: flex;
  gap: 12px;
  margin-top: 20px;
  padding: 14px 18px;
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.04), rgba(99, 102, 241, 0.02));
  border-radius: var(--radius-sm);
  border: 1px solid var(--accent-md);
  transition: all 0.3s ease;
}

.config-hint:hover {
  border-color: rgba(139, 92, 246, 0.35);
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.06), rgba(99, 102, 241, 0.04));
}

.hint-icon {
  font-size: 18px;
  flex-shrink: 0;
  margin-top: 1px;
}

.hint-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.hint-text p {
  margin: 0;
  color: var(--text-2);
  font-size: 13px;
  line-height: 1.6;
}

/* ============ 暗色模式 ============ */
[data-theme="dark"] .config-app {
  --bg-root: #121117;
  --bg-card: #1a1925;
  --bg-input: #1e1d2c;
  --text-1: #e4e2f0;
  --text-2: #a09eb8;
  --text-3: #6a6880;
  --text-4: #525070;
  --border: #2a2838;
  --border-lt: #222130;
  --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.3);
  --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.4);
  --shadow-lg: 0 8px 30px rgba(0, 0, 0, 0.5);
}

[data-theme="dark"] .card-badge {
  background: #2a2838;
}

[data-theme="dark"] .card-badge.active {
  background: rgba(16, 185, 129, 0.12);
}

[data-theme="dark"] .config-hint {
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.06), rgba(99, 102, 241, 0.03));
  border-color: rgba(139, 92, 246, 0.15);
}

[data-theme="dark"] .config-hint:hover {
  border-color: rgba(139, 92, 246, 0.25);
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.08), rgba(99, 102, 241, 0.05));
}

[data-theme="dark"] .btn-clear {
  border-color: #2a2838;
}

[data-theme="dark"] .btn-clear:hover {
  background: rgba(239, 68, 68, 0.08);
}

[data-theme="dark"] .masked-key {
  background: #1e1d2c;
  border-color: #2a2838;
}

[data-theme="dark"] .card-header {
  background: linear-gradient(180deg, rgba(139, 92, 246, 0.06), transparent);
}

/* ============ 响应式 ============ */
@media (max-width: 768px) {
  .config-content {
    padding: 16px;
    gap: 14px;
    max-width: 100%;
  }

  .form-grid {
    grid-template-columns: 1fr;
    gap: 0;
  }

  .config-form :deep(.ant-form-item) {
    margin-bottom: 14px;
  }

  .card-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
    padding: 14px 18px;
  }

  .card-body {
    padding: 18px;
  }

  .top-bar {
    padding: 12px 16px;
  }

  .top-subtitle {
    display: none;
  }
}
</style>
