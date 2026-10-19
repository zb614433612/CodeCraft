<!--
  未配置模型提供商 · 全屏引导页（F15）
  ----------------------------------------------------------------------------
  为什么要在聊天页拦一道：

    `GET /api/llm-providers` 只返回 `enabled = 1` 的记录（后端 selectAllEnabled），
    列表为空 = 一个可用模型都没有。此时聊天页照常能输入、能点发送，然后请求一路失败，
    用户看到的是一句莫名奇妙的接口错误，而不是「你先去配个模型」。

    把空列表变成明确的一步操作引导，比任何错误提示都省事。

  判定口径（三者同时成立才显示）：
    · providers 为空 —— 真的没有可用 Provider；
    · !loading      —— 首屏会有一小段空数组窗口，抢在加载完成前显示会闪一下；
    · !error        —— 后端连不上属于另一类故障，由页面自己的错误条负责，
                       这里显示「没有 Provider」会误导用户去改配置。

  角色差异：
    · 管理员 → 主按钮「去配置」跳「模型配置」；
    · 普通用户 → 按钮置灰 + 提示「请联系管理员」，因为后端 Provider 读写接口要 checkAdmin。
-->
<template>
  <div class="cc-pguide">
    <div class="cc-pguide__icon">
      <svg class="cc-i cc-i--lg"><use href="#i-cog" /></svg>
    </div>

    <h2 class="cc-pguide__title">还没有可用的模型提供商</h2>
    <p class="cc-pguide__desc">
      CodeCraft 的每一次对话都要通过「模型配置」里的提供商（LLM Provider）转发。
      现在一个都没有启用，所以消息发不出去。
    </p>

    <ol class="cc-pguide__steps">
      <li class="cc-pguide__step">
        <span class="cc-pguide__num">1</span>
        打开左侧「设置 → 模型配置」
      </li>
      <li class="cc-pguide__step">
        <span class="cc-pguide__num">2</span>
        新增一个提供商，填写 API 地址、密钥与模型名（如 DeepSeek、火山引擎）
      </li>
      <li class="cc-pguide__step">
        <span class="cc-pguide__num">3</span>
        保存后回到这里，选好模型即可开始对话
      </li>
    </ol>

    <div class="cc-pguide__ops">
      <button class="cc-btn cc-btn--primary" :disabled="!isAdmin" @click="$emit('configure')">
        <svg class="cc-i cc-i--sm"><use href="#i-cog" /></svg>
        {{ isAdmin ? '去配置' : '联系管理员配置' }}
      </button>
      <button class="cc-btn cc-btn--ghost" @click="$emit('refresh')">
        <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>
        刷新
      </button>
    </div>

    <p v-if="!isAdmin" class="cc-pguide__hint">
      当前账号不是管理员，无法修改模型配置 —— 请联系管理员添加提供商后点「刷新」。
    </p>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    /** 是否管理员（决定「去配置」能否点击） */
    isAdmin?: boolean
  }>(),
  { isAdmin: false }
)

defineEmits<{
  (e: 'configure'): void
  (e: 'refresh'): void
}>()
</script>
