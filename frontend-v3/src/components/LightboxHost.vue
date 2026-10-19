<!--
  图片灯箱宿主（F14）
  ----------------------------------------------------------------------------
  把「模块级单例状态」（utils/lightbox.ts）接到 ImageLightbox 组件上。
  挂载位置：App.vue 根部，与 ToastHost / ConfirmHost 并列（都 Teleport 到 body，
  因此不参与 .cc-shell 的栅格布局）。

  为什么需要这一层：
    调用方（输入区附件、文件选择器、详情抽屉、消息图片）分散在四五个组件里，
    如果各自持有一份 open/images/index 状态，就会出现「谁的 z-index 大谁说了算」
    以及「同时开两个灯箱」的问题。集中成单例后，调用方只需一句 openLightbox()。
-->
<template>
  <ImageLightbox
    :open="lightboxState.open"
    :images="lightboxState.images"
    :start-index="lightboxState.index"
    @update:open="onOpenChange"
    @update:start-index="onIndexChange"
  />
</template>

<script setup lang="ts">
import ImageLightbox from './ImageLightbox.vue'
import { closeLightbox, lightboxState } from '../utils/lightbox'

function onOpenChange(open: boolean) {
  if (!open) closeLightbox()
}

function onIndexChange(index: number) {
  lightboxState.index = index
}
</script>
