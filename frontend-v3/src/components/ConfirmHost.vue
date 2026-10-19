<!--
  确认弹窗渲染宿主
  ----------------------------------------------------------------------------
  只负责渲染与键盘可达性，业务调用见 src/utils/confirm.ts。
  在 App.vue 挂载一次即可（内部 Teleport 到 body，同时只展示一个弹窗）。
-->
<template>
  <Teleport to="body">
    <Transition name="cc-confirm">
      <div v-if="confirmState.current" class="cc-confirm-mask" @click.self="onCancel">
        <div
          ref="dialogRef"
          class="cc-confirm"
          :class="`is-${type}`"
          role="dialog"
          aria-modal="true"
          aria-labelledby="cc-confirm-title"
          @keydown="onKeydown"
        >
          <div class="cc-confirm__head">
            <span class="cc-confirm__icon" aria-hidden="true">
              <svg class="cc-i cc-i--lg"><use :href="ICON_MAP[type]" /></svg>
            </span>
            <div class="cc-confirm__head-main">
              <h3 id="cc-confirm-title" class="cc-confirm__title">{{ title }}</h3>
              <p v-if="content" class="cc-confirm__content">{{ content }}</p>
            </div>
          </div>

          <div class="cc-confirm__foot">
            <button
              ref="cancelRef"
              type="button"
              class="cc-btn"
              :disabled="confirmState.loading"
              @click="onCancel"
            >
              {{ cancelText }}
            </button>
            <button
              ref="okRef"
              type="button"
              class="cc-btn"
              :class="type === 'danger' ? 'is-danger' : 'cc-btn--primary'"
              :disabled="confirmState.loading"
              @click="onOk"
            >
              <svg v-if="confirmState.loading" class="cc-i cc-i--sm cc-spin"><use href="#i-refresh" /></svg>
              <span>{{ okText }}</span>
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { confirmState, settleConfirm, type ConfirmType } from '../utils/confirm'
import { toast } from '../utils/toast'

const ICON_MAP: Record<ConfirmType, string> = {
  info: '#i-info',
  warning: '#i-alert',
  danger: '#i-alert'
}

const dialogRef = ref<HTMLElement | null>(null)
const okRef = ref<HTMLButtonElement | null>(null)
const cancelRef = ref<HTMLButtonElement | null>(null)

const options = computed(() => confirmState.current?.options ?? null)
const title = computed(() => options.value?.title ?? '')
const content = computed(() => options.value?.content ?? '')
const okText = computed(() => options.value?.okText ?? '确定')
const cancelText = computed(() => options.value?.cancelText ?? '取消')
const type = computed<ConfirmType>(() => options.value?.type ?? 'info')

watch(
  () => confirmState.current,
  async current => {
    if (!current) return
    await nextTick()
    // 危险操作默认聚焦「取消」防误触；普通操作聚焦「确定」省一次 Tab
    if (type.value === 'danger') cancelRef.value?.focus()
    else okRef.value?.focus()
  }
)

async function onOk(): Promise<void> {
  try {
    await settleConfirm(true)
  } catch (error) {
    toast.error(error instanceof Error ? error.message : String(error))
  }
}

function onCancel(): void {
  if (confirmState.loading) return
  void settleConfirm(false)
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    onCancel()
    return
  }
  if (event.key !== 'Tab') return
  const nodes = [cancelRef.value, okRef.value].filter(
    (node): node is HTMLButtonElement => node !== null
  )
  if (nodes.length < 2) return
  const first = nodes[0]
  const last = nodes[nodes.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
</script>
