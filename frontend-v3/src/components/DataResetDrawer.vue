<!--
  数据管理 · 重置确认抽屉（v2）
  ----------------------------------------------------------------------------
  二次确认机制：必须手输「重置」才能点确认（与后端 reset-data 的破坏性匹配）
  数据接缝：
    emit('confirm')      -> POST /api/system/reset-data（管理员；后端在事务内清表 + 重建出厂数据 + 删文件类数据）
    emit('update:open')  -> 关闭抽屉
  说明：
    1) 重置执行期间（resetting=true）关闭按钮与取消按钮失效，避免中途关闭造成状态错乱；
    2) 抽屉打开时清空输入，避免上一次的确认文本残留；
    3) 重置完成后由父组件设置 resetting=false，视图层随之收起。
-->
<template>
  <div v-if="open" class="cc-mask is-open" @click="close" />

  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 480px">
    <DrawerResizer storage-key="data-reset" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">重置所有数据</div>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <div
        class="cc-banner"
        style="background: var(--danger-lt); border-color: rgba(239, 68, 68, 0.22); margin-bottom: var(--sp-4)"
      >
        <svg class="cc-i cc-i--sm" style="color: var(--danger)"><use href="#i-shield" /></svg>
        <span class="cc-banner__text">即将清空本机全部业务数据并恢复出厂状态，此操作不可撤销。</span>
      </div>

      <ul class="cc-data-danger-list" style="margin-bottom: var(--sp-4)">
        <li>14 张业务表（会话 / 消息 / 智能体 / 技能 / 任务 / 经验 / 用户 等）</li>
        <li>文件类数据（快照 / 日志 / P2P 接收文件 / 附件暂存）</li>
        <li>LLM Provider 内存缓存（清空后需重新配置）</li>
      </ul>

      <div class="cc-data-confirm">
        <label class="cc-data-confirm__label" for="dataResetConfirmInput">请输入 <b>重置</b> 以确认：</label>
        <input
          id="dataResetConfirmInput"
          v-model="confirmText"
          class="cc-input"
          placeholder="请输入「重置」"
          autocomplete="off"
          @keydown.enter="submit"
        />
        <span class="cc-data-confirm__hint">确认后立即执行，无需重启应用；重置完成后统计数据会归零</span>
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button class="cc-btn" :disabled="resetting" @click="close">取消</button>
      <button class="cc-btn cc-btn--danger" :disabled="!canConfirm || resetting" @click="submit">
        {{ resetting ? '重置中…' : '确认重置' }}
      </button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import DrawerResizer from './DrawerResizer.vue'
import { computed, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    open?: boolean
    resetting?: boolean
  }>(),
  {
    open: false,
    resetting: false
  }
)

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'confirm'): void
}>()

const confirmText = ref('')

/** 必须精确输入「重置」（去掉首尾空白） */
const canConfirm = computed(() => confirmText.value.trim() === '重置')

/** 每次打开抽屉都清空输入，避免上一次的确认文本残留 */
watch(
  () => props.open,
  value => {
    if (value) confirmText.value = ''
  }
)

function close() {
  if (props.resetting) return
  emit('update:open', false)
}

function submit() {
  if (!canConfirm.value || props.resetting) return
  emit('confirm')
}
</script>
