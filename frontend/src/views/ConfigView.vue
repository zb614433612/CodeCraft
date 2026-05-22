<template>
  <div class="config-container">
    <div class="page-header">
      <h2 class="page-title">配置</h2>
    </div>

    <div class="config-content">
      <!-- DeepSeek API 配置 -->
      <div class="config-card">
        <div class="card-title">DeepSeek API 配置</div>
        <div class="card-body">
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 16 }">
            <a-form-item label="API Key">
              <a-input-password
                v-model:value="apiKey"
                :placeholder="maskedKey || '请输入 DeepSeek API Key'"
                :maxLength="200"
                class="uniform-input"
              />
            </a-form-item>
            <a-form-item v-if="maskedKey" label="当前 Key">
              <span class="masked-key">{{ maskedKey }}</span>
            </a-form-item>
            <a-form-item :wrapper-col="{ offset: 4, span: 16 }">
              <a-button type="primary" :loading="apiKeySaving" @click="handleSaveApiKey">
                保存
              </a-button>
            </a-form-item>
          </a-form>
          <div class="config-hint">
            <p>💡 配置后，所有聊天请求将使用此 Key 调用 DeepSeek API。</p>
            <p>如不配置，则使用服务端默认的 Key。</p>
          </div>
        </div>
      </div>

      <!-- AI 角色性格配置 -->
      <div class="config-card">
        <div class="card-title">AI 角色性格配置</div>
        <div class="card-body">
          <p class="card-desc">配置后 AI 将按照设定的角色性格进行对话。留空则不启用。</p>
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 16 }" class="char-form">
            <a-form-item label="姓名">
              <a-input v-model:value="charProfile.name" placeholder="如：小柔" class="uniform-input" />
            </a-form-item>

            <a-form-item label="物种">
              <a-select v-model:value="charProfile.species" placeholder="请选择" class="uniform-input">
                <a-select-option value="人">人类</a-select-option>
                <a-select-option value="猫娘">猫娘</a-select-option>
                <a-select-option value="狗娘">狗娘</a-select-option>
                <a-select-option value="精灵">精灵</a-select-option>
                <a-select-option value="机器人">机器人</a-select-option>
                <a-select-option value="自定义">自定义</a-select-option>
              </a-select>
            </a-form-item>

            <a-form-item label="性别">
              <a-select v-model:value="charProfile.gender" placeholder="请选择" class="uniform-input">
                <a-select-option value="男">男</a-select-option>
                <a-select-option value="女">女</a-select-option>
                <a-select-option value="无性">无性</a-select-option>
                <a-select-option value="其他">其他</a-select-option>
              </a-select>
            </a-form-item>

            <a-form-item label="年龄">
              <a-input-number v-model:value="charProfile.age" :min="1" :max="9999" class="uniform-input" />
            </a-form-item>

            <a-form-item label="性格">
              <a-textarea v-model:value="charProfile.personality" placeholder="如：温柔体贴，善解人意" :rows="2" :maxLength="500" class="uniform-input" />
            </a-form-item>

            <a-form-item label="称呼">
              <a-input v-model:value="charProfile.greeting" placeholder="用户对你的称呼，如：主人、哥哥" class="uniform-input" />
            </a-form-item>

            <a-form-item label="背景设定">
              <a-textarea v-model:value="charProfile.background" placeholder="如：来自未来世界的 AI 助手..." :rows="3" :maxLength="1000" class="uniform-input" />
            </a-form-item>

            <a-form-item label="喜好">
              <a-input v-model:value="charProfile.likes" placeholder="如：看书、听音乐、帮助人类" class="uniform-input" />
            </a-form-item>

            <a-form-item label="说话风格">
              <a-textarea v-model:value="charProfile.style" placeholder="如：说话温柔，喜欢用表情符号" :rows="2" :maxLength="500" class="uniform-input" />
            </a-form-item>

            <a-form-item :wrapper-col="{ offset: 4, span: 16 }">
              <a-button type="primary" :loading="charSaving" @click="handleSaveChar">
                保存角色配置
              </a-button>
              <a-button v-if="hasCharConfig" style="margin-left: 12px" @click="handleClearChar">
                清空配置
              </a-button>
            </a-form-item>
          </a-form>
          <div class="config-hint">
            <p>💡 配置后首次对话时自动注入到系统提示词中，让 AI 扮演设定的角色。</p>
            <p>如不配置则 AI 按默认方式回复。</p>
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
  // 构建 JSON，只保存有值的字段
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

  // 如果全部为空，提示用户
  if (Object.keys(data).length === 0) {
    message.warning('请至少填写一项角色配置')
    return
  }

  // 检测 emoji 和特殊符号
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
.config-container {
  padding: 24px;
  height: 100%;
  background: #f5f7fa;
  overflow-y: auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding: 16px 20px;
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #1a202c;
}

.config-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.config-card {
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

.card-title {
  padding: 16px 20px;
  font-size: 16px;
  font-weight: 500;
  color: #1a202c;
  border-bottom: 1px solid #f0f0f0;
}

.card-desc {
  margin: 0 0 16px 0;
  color: #8c8c8c;
  font-size: 13px;
}

.card-body {
  padding: 24px 20px;
}

.masked-key {
  color: #8c8c8c;
  font-family: monospace;
}

.config-hint {
  margin-top: 16px;
  padding: 12px 16px;
  background: #f6f8fa;
  border-radius: 6px;
  border-left: 3px solid #1890ff;
}

.config-hint p {
  margin: 4px 0;
  color: #595959;
  font-size: 13px;
  line-height: 1.6;
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
</style>
