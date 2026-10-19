<!--
  Toast 渲染宿主
  ----------------------------------------------------------------------------
  只负责渲染，不含业务逻辑；在 App.vue 挂载一次即可（内部 Teleport 到 body）。
  调用侧见 src/utils/toast.ts。
-->
<template>
  <Teleport to="body">
    <div class="cc-toast-host" role="region" aria-label="通知" aria-live="polite">
      <TransitionGroup name="cc-toast">
        <div
          v-for="item in toastItems"
          :key="item.id"
          class="cc-toast"
          :class="`is-${item.type}`"
          :role="item.type === 'error' ? 'alert' : 'status'"
        >
          <span class="cc-toast__icon" aria-hidden="true">
            <svg class="cc-i"><use :href="ICON_MAP[item.type]" /></svg>
          </span>

          <div class="cc-toast__body">
            <p class="cc-toast__msg">
              {{ item.message }}
              <span v-if="item.repeat > 1" class="cc-toast__repeat">×{{ item.repeat }}</span>
            </p>
            <p v-if="item.description" class="cc-toast__desc">{{ item.description }}</p>
          </div>

          <button
            v-if="item.closable"
            type="button"
            class="cc-toast__close"
            title="关闭"
            aria-label="关闭通知"
            @click="dismissToastItem(item.id)"
          >
            <svg class="cc-i cc-i--sm"><use href="#i-close" /></svg>
          </button>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { dismissToastItem, toastItems, type ToastType } from '../utils/toast'

const ICON_MAP: Record<ToastType, string> = {
  success: '#i-check',
  error: '#i-close',
  warning: '#i-alert',
  info: '#i-info',
  loading: '#i-refresh'
}
</script>
