<!--
  数据管理 · 导出备份卡片（v2）
  ----------------------------------------------------------------------------
  结构：卡片头（标题 / 全选）→ 7 个类别勾选（带当前条数）→ 底部导出按钮
  数据接缝：
    stats           -> GET /api/system/data-stats（各类别行数）
    emit('export')  -> GET /api/system/export-data?types={勾选的 key 列表}
                       后端返回附件（Content-Disposition 带 codecraft-backup-*.json），由父组件触发下载
  说明：
    1) 默认全选，与后端「types 缺省 = 全部」语义一致；
    2) 类别行数 = 类别下各表行数之和（后端 stats() 口径）；
    3) 备份含 API Key、数据库密码密文、MCP headers 等敏感信息，卡片底部常驻提示。
-->
<template>
  <section class="cc-data-card">
    <div class="cc-data-card__head">
      <div class="cc-data-card__icon">
        <svg class="cc-i"><use href="#i-file" /></svg>
      </div>
      <div>
        <div class="cc-data-card__title">导出备份</div>
        <div class="cc-data-card__desc">选择数据类别，生成 JSON 备份文件；重装或迁移后一键恢复</div>
      </div>
      <div class="cc-data-card__head-actions">
        <button class="cc-text-btn" @click="toggleAll">{{ allSelected ? '全不选' : '全选' }}</button>
      </div>
    </div>

    <div class="cc-data-cats">
      <label
        v-for="cat in DATA_CATEGORIES"
        :key="cat.key"
        class="cc-data-cat"
        :class="{ 'is-on': selected.includes(cat.key) }"
        :title="cat.desc"
      >
        <input type="checkbox" :checked="selected.includes(cat.key)" @change="toggleCat(cat.key)" />
        <span class="cc-data-cat__label">{{ cat.label }}</span>
        <span class="cc-data-cat__count">{{ categoryCount(stats, cat.key) }} 条</span>
      </label>
    </div>

    <div class="cc-data-card__foot">
      <button
        class="cc-btn cc-btn--primary"
        :disabled="!selected.length || exporting"
        @click="$emit('export', [...selected])"
      >
        <svg class="cc-i cc-i--sm"><use href="#i-disk" /></svg>{{ exportText }}
      </button>
      <span class="cc-data-hint is-warn">备份含 API Key、数据库密码等凭证，请妥善保管</span>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { categoryCount, DATA_CATEGORIES } from '../utils/data-transfer'

const props = withDefaults(
  defineProps<{
    /** GET /api/system/data-stats 返回的类别行数 */
    stats?: Record<string, number>
    exporting?: boolean
  }>(),
  {
    stats: () => ({}),
    exporting: false
  }
)

defineEmits<{ (e: 'export', types: string[]): void }>()

/** 默认全选（与后端「types 缺省 = 全部」一致） */
const selected = ref<string[]>(DATA_CATEGORIES.map(cat => cat.key))

const allSelected = computed(() => selected.value.length === DATA_CATEGORIES.length)

const selectedCount = computed(() =>
  selected.value.reduce((sum, key) => sum + categoryCount(props.stats, key), 0)
)

const exportText = computed(() => {
  if (!selected.value.length) return '请选择数据类别'
  return `导出备份（${selected.value.length} 类 / ${selectedCount.value} 条）`
})

function toggleCat(key: string) {
  selected.value = selected.value.includes(key)
    ? selected.value.filter(item => item !== key)
    : [...selected.value, key]
}

function toggleAll() {
  selected.value = allSelected.value ? [] : DATA_CATEGORIES.map(cat => cat.key)
}
</script>
