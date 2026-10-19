<!--
  输入弹窗渲染宿主
  ----------------------------------------------------------------------------
  只负责渲染与键盘可达性，业务调用见 src/utils/prompt.ts。
  在 App.vue 挂载一次即可（内部 Teleport 到 body，同时只展示一个弹窗）。

  交互：Enter 确认、ESC 取消、点击遮罩取消、打开时自动聚焦并全选原文本。
-->
<template>
  <Teleport to="body">
    <Transition name="cc-confirm">
      <div v-if="state.current" class="cc-confirm-mask" @click.self="onCancel">
        <div
          class="cc-confirm cc-prompt"
          role="dialog"
          aria-modal="true"
          aria-labelledby="cc-prompt-title"
          @keydown="onKeydown"
        >
          <div class="cc-confirm__head">
            <div class="cc-confirm__head-main">
              <h3 id="cc-prompt-title" class="cc-confirm__title">{{ state.current.title }}</h3>
              <p v-if="state.current.label" class="cc-confirm__content">{{ state.current.label }}</p>
            </div>
          </div>

          <div class="cc-prompt__field">
            <input
              ref="inputRef"
              v-model="text"
              class="cc-input"
              type="text"
              :placeholder="state.current.placeholder || ''"
              @input="error = ''"
            />
            <p v-if="error" class="cc-prompt__error">{{ error }}</p>
          </div>

          <div class="cc-confirm__foot">
            <button type="button" class="cc-btn" @click="onCancel">
              {{ state.current.cancelText || '取消' }}
            </button>
            <button type="button" class="cc-btn cc-btn--primary" @click="onOk">
              {{ state.current.okText || '确定' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { promptState as state, settlePrompt } from '../utils/prompt'

const inputRef = ref<HTMLInputElement | null>(null)
const text = ref('')
const error = ref('')

watch(
  () => state.current,
  async current => {
    if (!current) return
    text.value = current.value || ''
    error.value = ''
    await nextTick()
    // 全选原名称：用户直接输入即可覆盖，想微调也能保留
    inputRef.value?.focus()
    inputRef.value?.select()
  }
)

function validate(): boolean {
  const value = text.value.trim()
  const custom = state.current?.validate
  const message = custom ? custom(value) : value ? '' : '内容不能为空'
  if (message) {
    error.value = message
    inputRef.value?.focus()
    return false
  }
  return true
}

function onOk(): void {
  if (!validate()) return
  settlePrompt(text.value)
}

function onCancel(): void {
  settlePrompt(null)
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter') {
    event.preventDefault()
    onOk()
    return
  }
  if (event.key === 'Escape') {
    event.preventDefault()
    onCancel()
  }
}
</script>
