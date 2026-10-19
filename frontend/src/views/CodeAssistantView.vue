<template>
  <div class="chat-container">
    <!-- 侧边栏: 会话列表 / 文件树 -->
    <aside class="sidebar" :class="{ collapsed }">
      <div class="sidebar-header">
        <!-- 有子Agent时显示tab栏，否则显示简洁标题 -->
        <div v-if="agentInfos.length > 0" class="sidebar-tabs">
          <button
            :class="['tab-btn', { active: sidebarTab === 'conversation' }]"
            @click="sidebarTab = 'conversation'"
          ><span class="tab-full">会话</span><span class="tab-short">会话</span></button>
          <button
            :class="['tab-btn', { active: sidebarTab === 'agents' }]"
            @click="sidebarTab = 'agents'"
          ><span class="tab-full">子Agent</span><span class="tab-short">Agent</span></button>
        </div>
        <div v-else class="sidebar-title">
          <span class="sidebar-title-icon">💬</span>
          <span class="sidebar-title-text">会话</span>
        </div>
        <div class="sidebar-collapse-btn" @click="toggleCollapsed">
          <MenuFoldOutlined v-if="!collapsed" />
          <MenuUnfoldOutlined v-else />
        </div>
      </div>

      <!-- 会话列表 -->
      <div v-show="sidebarTab === 'conversation'" class="conversation-list">
        <div class="new-chat-btn" @click="startNewChat">
          <PlusOutlined />
          <span>新建会话</span>
        </div>
        <!-- Agent 选择器 -->
        <AgentSelector @change="onAgentChange" ref="agentSelectorRef" :collapsed="collapsed" />
        <div v-if="isLoadingConversations" class="loading-conversations">
          <a-spin size="small" />
          <span>加载会话中...</span>
        </div>
        <div v-else-if="conversations.length === 0" class="empty-conversations">
          <p>暂无会话</p>
          <p class="empty-hint">输入消息开始编码</p>
        </div>
        <div
          v-for="conv in conversations"
          :key="conv.id"
          :class="['conversation-item', { active: currentConversationId === conv.id }]"
          @click="selectConversation(conv.id)"
        >
          <div class="conv-icon">
            <CodeOutlined />
          </div>
          <div class="conv-info">
            <div v-if="editingConvId === conv.id" class="conv-title-editing" @click.stop>
              <a-input
                v-model:value="editingConvTitle"
                size="small"
                @keydown.enter.prevent="saveEditTitle(conv)"
                @keydown.esc.prevent="cancelEditTitle"
              />
            </div>
            <div v-else class="conv-title" @dblclick="startEditTitle(conv, $event)">{{ conv.title }}</div>
            <div class="conv-time">{{ formatTime(conv.updatedAt) }}</div>
          </div>
          <div class="conv-actions">
            <template v-if="editingConvId === conv.id">
              <a-button type="text" size="small" @click.stop="saveEditTitle(conv)" :loading="isSavingConvTitle">
                <check-outlined />
              </a-button>
              <a-button type="text" size="small" @click.stop="cancelEditTitle">
                <close-outlined />
              </a-button>
            </template>
            <template v-else>
              <a-button type="text" size="small" @click.stop="deleteConversation(conv.id)">
                <delete-outlined />
              </a-button>
            </template>
          </div>
        </div>
      </div>

      <!-- 子Agent面板 -->
      <div v-show="sidebarTab === 'agents'" class="agent-panel-wrapper">
        <AgentPanel :agents="agentInfos" :collapsed="collapsed" />
      </div>
    </aside>

    <!-- 主聊天区域（标签页系统） -->
    <main class="chat-main">
      <SplitLayout
        :direction="splitState.direction"
        :has-split="splitState.enabled && splitState.secondary !== null"
        :split-ratio="splitState.splitRatio"
        :drag-state="splitDrag"
        @ratio-change="onSplitRatioChange"
      >
        <template #primary>
      <!-- 标签栏 -->
      <div class="tab-bar" v-if="primaryTabs.length > 0">
        <div class="tab-items">
          <div
            v-for="tab in primaryTabs"
            :key="tab.id"
            :class="['tab-item', { active: activeTabId === tab.id, dirty: tab.isDirty, dragging: splitDrag.dragging && splitDrag.tabId === tab.id }]"
            draggable="false"
            @click="activeTabId = tab.id"
            @mousedown.middle.prevent="closeTab(tab.id)"
            @mousedown.left="(e: MouseEvent) => onTabDragStart(e, tab)"
          >
            <span class="tab-title">{{ tab.title }}</span>
            <span v-if="tab.isDirty" class="tab-dirty-dot">●</span>
            <span
              v-if="tab.type !== 'chat' && !splitState.enabled"
              class="tab-split-btn"
              @click.stop="splitTabRight(tab)"
              title="向右分屏"
            >⊞</span>
            <span v-if="tab.type !== 'chat'" class="tab-close" @click.stop="closeTab(tab.id)">×</span>
          </div>
        </div>
        <!-- 文件标签操作按钮 -->
        <div v-if="activeTab?.type === 'file' || activeTab?.type === 'chat'" class="tab-actions">
          <a-button
            size="small"
            class="tb-btn"
            @click="handleBuild"
            :loading="isBuilding"
          >
            <PlaySquareOutlined /> 编译
          </a-button>
          <a-button
            size="small"
            type="primary"
            class="tb-btn"
            @click="handleRun"
            :disabled="isServiceRunning"
          >
            <CaretRightOutlined /> 运行
          </a-button>
          <a-button
            size="small"
            danger
            class="tb-btn"
            @click="handleStop"
            :disabled="!isServiceRunning"
          >
            <PoweroffOutlined /> 停止
          </a-button>
        </div>
      </div>
      <!-- 标签内容区 -->
      <div class="tab-content">
        <!-- ===== 聊天标签 ===== -->
        <template v-if="activeTab?.type === 'chat'">
          <div class="chat-messages-area" @click="handleMessageLinkClick">
            <!-- ★ 无 Provider 时：全屏引导界面 -->
            <div v-if="providerOptions.length === 0" class="no-provider-guide">
              <div class="guide-icon">🔌</div>
              <h2 class="guide-title">尚未配置 LLM Provider</h2>
              <p class="guide-desc">AI 助手需要通过 LLM Provider 连接大模型才能工作</p>
              <a-button type="primary" size="large" class="guide-btn" @click="router.push('/config')">
                前往设置 创建 Provider
              </a-button>
            </div>
            <!-- 无消息时的占位区（撑满弹性空间，使输入框始终在底部） -->
            <div v-else-if="currentMessages.length === 0" class="message-list-empty">
        <div v-if="isLoadingMessages" class="loading-messages">
          <a-spin size="small" />
          <span>加载消息中...</span>
        </div>
      </div>

      <!-- 虚拟滚动消息列表
           :key=会话ID：切换会话时强制重建，避免旧会话的视图池/尺寸表被复用导致首屏位置错乱
           min-item-size：聊天消息普遍 150~2000px，占位过小（原 80）会让初始累计位置严重低估 -->
      <DynamicScroller
        v-if="currentMessages.length > 0"
        :key="currentConversationId"
        class="message-list"
        ref="scrollerRef"
        :items="currentMessages"
        :min-item-size="120"
        key-field="id"
        v-slot="{ item: msg, active }"
        @scroll.passive="handleMessageListScroll"
      >
        <DynamicScrollerItem :item="msg" :active="active" :size-dependencies="msg.isStreaming ? [streamingUpdateTick] : [msg.content?.length || 0, msg.thinking?.length || 0, msg.snapshotId || '', msg.taskError?.message?.length || 0]">
        <!-- v-track-msg-size：实测高度兜底（内容异步变化/展开折叠后尺寸表未同步 → 触发重测，防消息重叠） -->
        <div :class="['message-item', msg.role]" v-track-msg-size="msg.id">
          <div class="message-avatar">
            <div v-if="msg.role === 'user'" class="avatar user-avatar">
              <img
                v-if="isImageAvatar(userStore.userInfo?.avatar)"
                :src="userStore.userInfo?.avatar"
                class="avatar-img"
                alt=""
              />
              <span v-else>{{ userStore.userInfo?.username?.charAt(0).toUpperCase() || 'U' }}</span>
            </div>
            <div v-else class="avatar ai-avatar">
              <img v-if="isImageAvatar(currentAgentAvatar)" :src="currentAgentAvatar" class="avatar-img" alt="" />
              <template v-else-if="currentAgentAvatar">{{ currentAgentAvatar }}</template>
              <CodeOutlined v-else />
            </div>
          </div>
          <div class="message-content">
            <div class="message-header">
              <span class="message-sender">{{ msg.role === 'user' ? userStore.userInfo?.username || '用户' : '编码助手' }}</span>
              <span class="message-time">{{ formatMessageTime(msg.timestamp) }}</span>
              <span class="message-token" v-if="msg.tokenCount !== undefined">· {{ formatTokenCount(msg.tokenCount) }} token</span>
              <span class="message-token" v-else-if="msg.isStreaming">· 计算中...</span>
              <span v-if="msg.isStreaming" class="streaming-indicator">
                <span class="streaming-dot"></span>
                正在输入...
              </span>
              <div
                v-if="msg.role === 'user' && msg.snapshotId"
                class="rollback-btn"
                title="回滚代码"
                @click="handleRollback(msg)"
              >
                <UndoOutlined />
              </div>
            </div>

            <!-- 思考过程（仅AI消息） -->
            <div v-if="msg.role === 'assistant' && (msg.thinking || msg.toolResults?.length || msg.hitSkills?.length)" class="thinking-section">
              <div class="thinking-header" @click="toggleThinkingVisibility(msg.id)">
                <span class="thinking-title">
                  <DownOutlined v-if="!getThinkingVisible(msg.id)" />
                  <UpOutlined v-else />
                  思考过程
                </span>
                <!-- 命中技能（动态）：仅当 LLM 真正加载执行技能（skill action=load）时展示；
                     技能目录已改为全量简介注入，不再展示全量「技能匹配」列表 -->
                <span v-if="msg.hitSkills?.length" class="skill-hit-tags" :title="`本次已执行技能：${msg.hitSkills.join('、')}`">
                  <span v-for="name in msg.hitSkills" :key="name" class="skill-hit-tag">🎯 命中技能：{{ name }}</span>
                </span>
                <span class="thinking-summary" v-if="msg.toolResults?.length">
                  · {{ msg.toolResults.length }} 次工具调用
                </span>
                <span class="thinking-hint">点击{{ getThinkingVisible(msg.id) ? '收起' : '展开' }}</span>
              </div>
              <div v-if="getThinkingVisible(msg.id)" class="thinking-content" :data-think-scroll="msg.id" @scroll.passive="handleThinkScroll">
                <div class="thinking-text" :class="{ 'thinking-timeline': !!(msg.toolResults && msg.toolResults.length) }" @click="handleToolCardClick($event, msg)">
                  <div v-for="seg in buildThinkingSegmentsFor(msg)" :key="seg.key" class="think-seg" :data-card-at="seg.cardAt" v-html="seg.html"></div>
                </div>
                <div v-if="msg.isStreaming && thinkShowScrollBtn" class="think-scroll-btn-wrap">
                  <div class="think-scroll-btn" @click.stop="thinkScrollToBottom" title="回到底部">
                    <DownOutlined />
                  </div>
                </div>
              </div>
            </div>
            <!-- M4/M7：消息附件（图片→缩略图点击放大；文档→文件图标卡片点击下载）
                 修复：AI 消息同样支持（桌面截图注入资产归并到宿主 AI 消息回显） -->
            <div
              v-if="(msg.role === 'user' || msg.role === 'assistant') && msg.fileAssets && msg.fileAssets.length > 0"
              class="message-images"
            >
              <template v-for="asset in msg.fileAssets" :key="asset.id">
                <!-- 文档（M7：文件格式图标 + 文件名） -->
                <div
                  v-if="asset.kind === 'doc'"
                  class="message-doc-chip"
                  :title="fileAssetDisplayName(asset) + '（点击下载）'"
                  @click="downloadMessageFile(asset)"
                >
                  <span class="doc-chip-icon">{{ docTypeIcon(docTypeOf(asset.filename, asset.mimeType)) }}</span>
                  <span class="doc-chip-name">{{ fileAssetDisplayName(asset) }}</span>
                </div>
                <!-- 图片（缩略图） -->
                <div
                  v-else
                  class="message-image-thumb"
                  :title="fileAssetDisplayName(asset)"
                  @click="openBigPreview(asset.previewUrl)"
                >
                  <img v-if="asset.previewUrl" :src="asset.previewUrl" :alt="asset.filename" @load="scheduleScrollerSelfHeal" />
                  <span v-else class="thumb-loading">🖼️</span>
                </div>
              </template>
            </div>
            <div class="message-text code-message" v-if="msg.content">
              <div v-for="seg in buildMessageSegments(msg)" :key="seg.key" class="msg-seg" v-html="seg.html"></div>
            </div>
            <!-- 任务错误（LLM 接口错误停止）：错误详情（标题 + 原因 + 解决建议） -->
            <div v-if="msg.role === 'assistant' && msg.taskError" class="message-task-error">
              <div class="task-error-title">⚠️ {{ msg.taskError.title || '任务已中断' }}</div>
              <div class="task-error-detail">{{ msg.taskError.message }}</div>
            </div>
          </div>
        </div>
        </DynamicScrollerItem>
      </DynamicScroller>

      <!-- 顶部懒加载提示：向上滚动到顶自动加载更早的历史记录（每次 5 轮） -->
      <transition name="scroll-btn-fade">
        <div v-if="loadingMoreMessages" class="loading-more-tip">正在加载更早的聊天记录…</div>
      </transition>

      <!-- 回到底部浮动按钮：流式输出时用户手动上滚后显示 -->
      <transition name="scroll-btn-fade">
        <div
          v-if="showScrollToBottomBtn && currentMessages.length > 0"
          class="scroll-to-bottom-btn"
          @click="scrollToBottomManually"
          title="回到底部"
        >
          <DownOutlined />
        </div>
      </transition>

      <!-- M4：大图预览遮罩（点击消息/附件缩略图打开） -->
      <div v-if="bigPreviewUrl" class="big-preview-mask" @click="bigPreviewUrl = ''">
        <img :src="bigPreviewUrl" alt="big-preview" />
      </div>

      <!-- 附件入口 → 选择历史文件（图片+文档多选加入附件区，随消息以 fileAssetIds 发送） -->
      <a-modal
        v-model:open="historyPickerVisible"
        title="选择历史文件"
        :width="600"
        ok-text="添加到附件"
        :ok-button-props="{ disabled: historySelectedIds.size === 0 }"
        @ok="confirmHistoryPick"
      >
        <div class="history-picker-toolbar">
          <a-input
            v-model:value="historyKeyword"
            placeholder="搜索文件名"
            allow-clear
            class="history-picker-search"
          >
            <template #prefix><SearchOutlined /></template>
          </a-input>
          <span class="history-picker-count">已选 {{ historySelectedIds.size }} 个</span>
        </div>

        <a-spin :spinning="historyPickerLoading">
          <div v-if="filteredHistoryImages.length > 0" class="history-picker-grid">
            <div
              v-for="f in filteredHistoryImages"
              :key="f.id"
              :class="['history-picker-item', {
                'history-picker-item--selected': historySelectedIds.has(f.id),
                'history-picker-item--attached': isAssetAttached(f.id)
              }]"
              @click="toggleHistorySelect(f)"
            >
              <div class="history-picker-thumb">
                <!-- 图片：缩略图；文档：文件格式图标（M7） -->
                <template v-if="isImageMime(f.mimeType)">
                  <img v-if="historyPreviewMap[f.id]" :src="historyPreviewMap[f.id]" :alt="fileAssetDisplayName(f)" />
                  <span v-else class="history-picker-thumb-placeholder">🖼️</span>
                </template>
                <span v-else class="history-picker-thumb-placeholder history-picker-doc-icon">
                  {{ docTypeIcon(docTypeOf(f.filename, f.mimeType)) }}
                </span>
                <span v-if="historySelectedIds.has(f.id)" class="history-picker-check">✓</span>
                <span v-if="isAssetAttached(f.id)" class="history-picker-attached-badge">已在附件</span>
              </div>
              <div class="history-picker-name" :title="fileAssetDisplayName(f)">{{ fileAssetDisplayName(f) }}</div>
              <div class="history-picker-size">{{ formatFileSize(f.size) }}</div>
            </div>
          </div>
          <div v-else-if="!historyPickerLoading" class="history-picker-empty">
            {{ historyKeyword ? '没有匹配的文件' : '暂无历史文件，可先上传文件' }}
          </div>
        </a-spin>
      </a-modal>

      <!-- 流式加载指示器（hasRunningTask：前台流式/后台任务/重连恢复都显示，让「后台输出中...」在切回时可见） -->
      <div v-if="hasRunningTask &amp;&amp; streamStatus" class="stream-indicator">
        <span class="stream-pulse-dot"></span>
        <span class="stream-indicator-text">{{ streamStatus }}</span>
        <span class="stream-elapsed" v-if="elapsedTime > 0">{{ formatElapsed(elapsedTime) }}</span>
      </div>

      <!-- 补充需求面板：只要该会话「有任务在执行」就显示（前台流式 / 切走后的后台流 / 切回后的重连恢复）。
           原条件 isSending 只覆盖前台流式，导致切回智能体后流仍在输出、但补充需求入口消失 -->
      <div v-if="hasRunningTask &amp;&amp; !pendingQuestion" class="supplement-panel">
        <!-- 收起状态：显示「补充需求」按钮 -->
        <div v-if="!showSupplementInput" class="supplement-trigger" @click="showSupplementInput = true">
          <span class="supplement-icon">💡</span>
          <span>补充需求</span>
        </div>
        <!-- 展开状态：输入框 + 发送/取消按钮 -->
        <div v-else class="supplement-input-row">
          <a-input
            ref="supplementInputRef"
            v-model:value="supplementMessage"
            placeholder="输入补充需求，AI 将在下一轮处理中收到..."
            @pressEnter="sendSupplement"
            size="small"
          />
          <a-button type="primary" size="small" @click="sendSupplement" :loading="supplementSending">发送</a-button>
          <a-button size="small" @click="cancelSupplement">取消</a-button>
        </div>
      </div>

      <!-- P3：后台授权等待角标（其他会话有授权请求待处理时提示，切过去即可处理） -->
      <div v-if="pendingQuestionCount > 0" class="bg-permission-tip" @click="showTaskDropdown = false">
        <span class="bg-permission-tip-icon">🔒</span>
        {{ pendingQuestionCount }} 个后台任务正在等待授权
      </div>

      <!-- ask_user 问答面板 -->
      <div v-if="pendingQuestion" class="ask-user-panel">
        <!-- 任务迭代确认面板（askType=iteration_confirm）：
             评委判定不通过 / 评委无法评估 / 累计扩容达上限时，由用户决定「结束」还是「继续并扩容」 -->
        <div v-if="pendingQuestion.askType === 'iteration_confirm'" class="iteration-confirm-panel">
          <div class="ask-user-header">
            <span class="ask-user-header-icon">🤖</span>
            任务迭代确认
            <!-- 归属 Agent 标识 -->
            <span v-if="pendingQuestion.agentName" class="ask-user-agent-tag">来自 {{ pendingQuestion.agentName }}</span>
          </div>
          <div class="ask-user-question" v-html="formatQuestion(pendingQuestion.question)"></div>
          <div class="iteration-confirm-actions">
            <a-button danger class="iteration-confirm-btn" :loading="iterationConfirmSending" @click="handleIterationDecision('stop')">结束任务</a-button>
            <a-button type="primary" class="iteration-confirm-btn" :loading="iterationConfirmSending" @click="handleIterationDecision('continue', 10)">继续 +10 次</a-button>
            <a-button type="primary" class="iteration-confirm-btn" :loading="iterationConfirmSending" @click="handleIterationDecision('continue', 20)">继续 +20 次</a-button>
            <a-button type="primary" class="iteration-confirm-btn" :loading="iterationConfirmSending" @click="handleIterationDecision('continue', 50)">继续 +50 次</a-button>
          </div>
          <div class="iteration-confirm-hint">
            选择「继续」后任务将从当前进度接着执行；5 分钟内未选择将按「结束任务」处理。
          </div>
        </div>
        <!-- 权限授权面板（askType=permission） -->
        <div v-else-if="pendingQuestion.askType === 'permission'" class="permission-panel">
          <div class="ask-user-header">
            <span class="ask-user-header-icon">🔒</span>
            需要授权
            <!-- P3：归属 Agent 标识（防误授权——明确"来自谁"） -->
            <span v-if="pendingQuestion.agentName" class="ask-user-agent-tag">来自 {{ pendingQuestion.agentName }}</span>
          </div>
          <div class="ask-user-question" v-html="formatQuestion(pendingQuestion.question)"></div>
          <div class="permission-btn-grid">
            <a-button type="primary" class="permission-btn permission-btn-approve" @click="handlePermissionAction('approve')">同意</a-button>
            <a-button type="primary" ghost class="permission-btn permission-btn-approve-all" @click="handlePermissionAction('approve_all')">本会话全部同意</a-button>
            <a-button danger class="permission-btn permission-btn-reject" @click="handlePermissionAction('reject')">拒绝</a-button>
            <a-button class="permission-btn permission-btn-custom" :class="{ active: pendingShowCustomInput }" @click="pendingShowCustomInput = !pendingShowCustomInput">
              {{ pendingShowCustomInput ? '收起' : '其他（输入消息）' }}
            </a-button>
          </div>
          <!-- 选择「其他」后展开输入框，带平滑过渡 -->
          <transition name="custom-fade">
            <div v-if="pendingShowCustomInput" class="permission-custom-row">
              <a-input
                v-model:value="pendingQuestionAnswer"
                placeholder="请输入您的消息..."
                @pressEnter="handlePermissionAction('custom')"
              />
              <a-button type="primary" class="custom-send-btn" @click="handlePermissionAction('custom')">发送</a-button>
            </div>
          </transition>
        </div>
        <!-- 询问用户需求面板（默认/clarification）：保持原有输入框 -->
        <div v-else class="ask-user-clarify-panel">
          <div class="ask-user-header">
            <span class="ask-user-header-icon">💬</span>
            需要确认
            <!-- P3：归属 Agent 标识 -->
            <span v-if="pendingQuestion.agentName" class="ask-user-agent-tag">来自 {{ pendingQuestion.agentName }}</span>
          </div>
          <div class="ask-user-question" v-html="formatQuestion(pendingQuestion.question)"></div>
          <div class="ask-user-input-row">
            <a-input
              v-model:value="pendingQuestionAnswer"
              placeholder="请输入回答..."
              @pressEnter="submitPendingAnswer"
            />
            <a-button type="primary" @click="submitPendingAnswer">确认</a-button>
          </div>
        </div>
      </div>
      </div>

        </template>
        <!-- ===== 文件编辑标签 ===== -->
        <template v-else-if="activeTab?.type === 'file'">
          <div class="file-tab-layout">
            <div class="file-tab-editor">
               <FileEditor
                :key="activeTab.id"
                :file-path="activeTab.filePath || ''"
                :content="activeTab.content || ''"
                :original-content="activeTab.originalContent"
                :highlight-lines="activeTab.highlightLines"
                @save="onFileSaved"
                @content-change="onFileContentChange"
              />
            </div>
          </div>
        </template>
        <!-- ===== 差异对比标签 ===== -->
        <template v-else-if="activeTab?.type === 'diff'">
          <div class="diff-tab-content">
            <div class="diff-tab-header">
              <span class="diff-tab-title">差异对比: {{ activeTab.filePath }}</span>
              <div class="diff-tab-actions">
                <a-button size="small" danger @click="handleRevertFile(activeTab.filePath || '')">
                  <UndoOutlined />
                  撤销此文件改动
                </a-button>
              </div>
            </div>
            <div class="diff-tab-body">
              <DiffView
                :head-content="activeTab.headContent || ''"
                :current-content="activeTab.currentContent || ''"
                :title="'Diff: ' + (activeTab.filePath || '')"
                :file-path="activeTab.filePath"
                :project-root="agentRuntime.workDir"
                @hunks-restored="onHunksRestored"
                @restore-all="handleRevertFile"
              />
            </div>
          </div>
        </template>
        <!-- ===== 网页标签（内嵌浏览 LLM 输出的链接，可自由关闭） ===== -->
        <template v-else-if="activeTab?.type === 'web'">
          <div class="web-tab-layout">
            <div class="web-tab-toolbar">
              <span class="web-tab-url" :title="activeTab.url">{{ activeTab.url }}</span>
              <div class="web-tab-actions">
                <a-button size="small" class="tb-btn" @click="reloadWebTab(activeTab)">
                  <ReloadOutlined /> 刷新
                </a-button>
                <a-button size="small" type="primary" class="tb-btn" @click="openWebExternal(activeTab.url)">
                  <ExportOutlined /> 在浏览器打开
                </a-button>
              </div>
            </div>
            <div class="web-tab-body">
              <!-- Electron：<webview> 可加载绝大多数站点（不受 X-Frame-Options 限制） -->
              <webview
                v-if="canUseWebview"
                :key="'wv-' + activeTab.id + '-' + (webTabReloadKeys[activeTab.id] || 0)"
                :src="activeTab.url"
                class="web-tab-frame"
              ></webview>
              <!-- 浏览器环境兜底：iframe -->
              <iframe
                v-else
                :key="'if-' + activeTab.id + '-' + (webTabReloadKeys[activeTab.id] || 0)"
                :src="activeTab.url"
                class="web-tab-frame"
                referrerpolicy="no-referrer"
              ></iframe>
            </div>
          </div>
        </template>
        <!-- ===== 图片标签（内嵌展示本地/项目图片，可自由关闭） ===== -->
        <template v-else-if="activeTab?.type === 'image'">
          <div class="image-tab-layout">
            <div class="image-tab-toolbar">
              <span class="image-tab-path" :title="activeTab.imagePath">{{ activeTab.imagePath }}</span>
              <div class="image-tab-actions">
                <a-button size="small" class="tb-btn" @click="reloadImageTab(activeTab)">
                  <ReloadOutlined /> 重新加载
                </a-button>
              </div>
            </div>
            <div class="image-tab-body">
              <div v-if="activeTab.imageLoading" class="image-tab-hint">正在加载图片…</div>
              <div v-else-if="activeTab.imageError" class="image-tab-hint error">{{ activeTab.imageError }}</div>
              <img v-else :src="activeTab.imageBlobUrl" :alt="activeTab.title" class="image-tab-img" />
            </div>
          </div>
        </template>
      </div>

      <!-- ===== 底部运行日志面板 ===== -->
      <div v-if="showBuildRunLog" class="run-log-panel" :class="{ minimized: logMinimized }">
        <div
          class="log-resize-handle"
          @mousedown.prevent="startLogResize"
        ></div>
        <div class="log-tab-bar">
          <div class="log-tab active">
            <span class="console-dot" :class="{ running: isServiceRunning }"></span>
            <span>运行日志</span>
          </div>
          <div class="console-tab-actions">
            <span class="console-tab-cwd" :title="displayCwd">{{ displayCwd }}</span>
            <a-button size="small" type="text" @click="consoleLines = []">
              <ClearOutlined /> 清屏
            </a-button>
            <span v-if="!isServiceRunning" class="console-tab-close" @click="closeLogTab">×</span>
            <DownOutlined v-if="!logMinimized" @click="logMinimized = true" />
            <UpOutlined v-else @click="logMinimized = false" />
          </div>
        </div>
        <div v-show="!logMinimized" class="log-xterm-container" ref="logXtermContainer"></div>
      </div>

      <!-- ===== 输入区域（仅聊天标签 + 有Provider时显示） ===== -->
      <footer v-if="activeTab?.type === 'chat' && providerOptions.length > 0" class="chat-input-area">
        <!-- 隐藏的文件选择器（M4：支持多选；accept 收窄图片 + 文档类型） -->
        <input
          ref="fileInputRef"
          type="file"
          accept="image/jpeg,image/png,image/gif,image/webp,.pdf,.doc,.docx,.xls,.xlsx,.csv,.txt,.md,.log,.json,.yml,.yaml,.xml,.properties,.ini,.toml,.ts,.tsx,.vue,.js,.jsx,.css,.scss,.less,.html,.java,.py,.go,.rs,.c,.cpp,.h,.sh,.bat,.ps1,.sql"
          multiple
          style="display: none"
          @change="handleFileSelected"
        />
        <!-- 任务中断提示（LLM 接口错误导致任务停止）：错误详情 + 一键继续任务 -->
        <div v-if="currentInterruptedTask" class="task-interrupted-banner">
          <div class="interrupted-info">
            <span class="interrupted-icon">⚠️</span>
            <div class="interrupted-texts">
              <div class="interrupted-title">{{ currentInterruptedTask.title }}</div>
              <div class="interrupted-message">{{ currentInterruptedTask.message }}</div>
            </div>
          </div>
          <a-button type="primary" size="small" class="continue-task-btn" @click="continueInterruptedTask">
            <ReloadOutlined />
            继续任务
          </a-button>
        </div>
        <!-- 附件标签区 -->
        <div v-if="attachedFiles.length > 0" class="attachment-tags">
          <div
            v-for="att in attachedFiles"
            :key="att.id"
            :class="['attachment-tag', { 'attachment-error': att.error, 'attachment-image': att.kind === 'image' }]"
          >
            <!-- M4：图片附件显示缩略图（点击放大） -->
            <div
              v-if="att.kind === 'image'"
              class="attachment-thumb"
              :title="att.fileName"
              @click="openBigPreview(att.previewUrl)"
            >
              <img v-if="att.previewUrl" :src="att.previewUrl" :alt="att.fileName" />
              <span v-else class="thumb-loading">🖼️</span>
            </div>
            <span v-else class="attachment-icon">{{ getAttachmentIcon(att.type) }}</span>
            <span class="attachment-name" :title="att.fileName">{{ att.fileName }}</span>
            <span class="attachment-size">{{ formatFileSize(att.size) }}</span>
            <span v-if="att.uploading" class="attachment-uploading">
              <LoadingOutlined spin />
            </span>
            <template v-else>
              <span v-if="att.error" class="attachment-error-msg" :title="att.error">{{ att.error }}</span>
              <span class="attachment-remove" @click="removeAttachment(att.id)">×</span>
            </template>
          </div>
        </div>
        <div class="input-wrapper">
          <a-dropdown :trigger="['click']" placement="topLeft" :disabled="hasRunningTask">
            <a-button
              class="attach-btn"
              :disabled="hasRunningTask"
              title="添加附件"
            >
              <PaperClipOutlined />
            </a-button>
            <template #overlay>
              <a-menu @click="onAttachMenuClick">
                <a-menu-item key="upload">
                  <span class="attach-menu-icon"><UploadOutlined /></span>
                  上传新文件
                </a-menu-item>
                <a-menu-item key="history">
                  <span class="attach-menu-icon"><HistoryOutlined /></span>
                  用历史文件
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
          <a-textarea
            v-model:value="inputMessage"
            placeholder="描述你的编码需求...（Shift+Enter换行，Enter发送，Ctrl+V粘贴截图）"
            :auto-size="{ minRows: 1, maxRows: 8 }"
            :disabled="hasRunningTask || isOptimizing"
            @keydown.enter.exact.prevent="sendMessage()"
            @keydown.shift.enter="handleShiftEnter"
            @paste="handlePasteImage"
          />
          <a-button
            class="optimize-btn"
            @click="optimizeMessage"
            :disabled="!inputMessage.trim() || isOptimizing"
            title="AI 优化提示词"
          >
            <template v-if="!isOptimizing">✨</template>
            <LoadingOutlined v-else spin />
          </a-button>
          <a-button
            v-if="hasActiveStream"
            class="stop-btn"
            @click="stopStreaming"
          >
            <CloseOutlined />
          </a-button>
          <a-button
            v-else
            type="primary"
            class="send-btn"
            @click="sendMessage()"
            :disabled="!inputMessage.trim() && attachedFiles.length === 0"
          >
            <SendOutlined />
          </a-button>
        </div>
        <div class="input-footer">
          <div class="footer-left">
            <!-- 高频：执行模式 -->
            <div class="mode-selector">
              <span class="mode-emoji">🛡️</span>
              <span class="mode-label">执行</span>
              <a-select
                :value="agentRuntime.executionMode"
                @change="(v: string) => { updateAgentRuntime('executionMode', v) }"
                size="small"
                class="mode-select"
                dropdown-class-name="mode-dropdown"
              >
                <a-select-option value="manual">手动</a-select-option>
                <a-select-option value="auto">自动</a-select-option>
              </a-select>
            </div>
            <!-- 高频：模型选择 -->
            <div class="mode-selector">
              <span class="mode-emoji">⚡</span>
              <a-select
                :value="agentRuntime.model"
                @change="(v: string) => { updateAgentRuntime('model', v) }"
                size="small"
                class="model-select"
                dropdown-class-name="mode-dropdown"
              >
                <a-select-option
                  v-for="m in availableModels"
                  :key="m"
                  :value="m"
                >{{ formatModelLabel(m) }}</a-select-option>
                <!-- 兜底：当前模型不在列表中时也能显示 -->
                <a-select-option
                  v-if="!availableModels.includes(agentRuntime.model)"
                  :key="agentRuntime.model"
                  :value="agentRuntime.model"
                >{{ agentRuntime.model }}</a-select-option>
              </a-select>
            </div>
            <!-- 高频：任务进度触发器（loading 用 hasRunningTask：切走/切回的任务同样显示执行中） -->
            <div class="mode-selector task-trigger" @click="toggleTaskDropdown" :class="{ active: showTaskDropdown, loading: hasRunningTask }">
              <span class="mode-icon task-trigger-icon">
                <LoadingOutlined v-if="hasRunningTask" spin />
                <span v-else>📋</span>
              </span>
              <span class="task-trigger-text" v-if="taskItems.length === 0">任务</span>
              <span class="task-trigger-text" v-else>{{ completedTaskCount }}/{{ taskItems.length }}</span>
            </div>
            <!-- 高频：文件改动触发器 -->
            <div class="mode-selector changes-trigger" @click="toggleChangesPanel" :class="{ active: showChangesPanel }" :style="{ display: currentConversationId && !currentConversationId.startsWith('local-') ? '' : 'none' }">
              <span class="mode-icon changes-trigger-icon">
                <span>📄</span>
              </span>
              <span class="changes-trigger-text">改动</span>
              <span v-if="sessionChanges" class="changes-trigger-badge">
                +{{ sessionChanges.totalLinesAdded }}/-{{ sessionChanges.totalLinesDeleted }}
              </span>
            </div>
            <!-- 低频：更多设置折叠 -->
            <div class="mode-selector more-settings-trigger" @click="showMoreSettings = !showMoreSettings" :class="{ active: showMoreSettings }">
              <span class="mode-emoji">⚙️</span>
              <span class="mode-label">更多</span>
              <DownOutlined v-if="!showMoreSettings" class="more-settings-arrow" />
              <UpOutlined v-else class="more-settings-arrow" />
            </div>
            <!-- 低频：更多设置弹出面板 -->
            <div v-if="showMoreSettings" class="more-settings-popover">
              <div class="more-settings-item">
                <span class="mode-emoji">📡</span>
                <span class="mode-label">Provider</span>
                <a-select
                  :value="agentRuntime.providerCode || providerOptions[0]?.code"
                  @change="(v: string) => { onProviderSwitch(v) }"
                  size="small"
                  class="model-select"
                  dropdown-class-name="mode-dropdown"
                  :getPopupContainer="trigger => trigger.parentElement"
                >
                  <a-select-option
                    v-for="prov in providerOptions"
                    :key="prov.code"
                    :value="prov.code"
                  >{{ prov.name }}</a-select-option>
                </a-select>
              </div>
              <div class="more-settings-item">
                <span class="mode-emoji">💡</span>
                <span class="mode-label">思考模式</span>
                <a-select
                  :value="agentRuntime.thinkingMode"
                  @change="(v: string) => { updateAgentRuntime('thinkingMode', v) }"
                  size="small"
                  class="model-select"
                  dropdown-class-name="mode-dropdown"
                >
                  <a-select-option value="non-thinking">关闭思考</a-select-option>
                  <a-select-option value="thinking">思考</a-select-option>
                  <a-select-option value="thinking_max">深度思考</a-select-option>
                </a-select>
              </div>
              <div class="more-settings-item">
                <span class="mode-emoji">🧠</span>
                <span class="mode-label">上下文</span>
                <a-select
                  :value="settingsStore.contextMode"
                  @change="(v: string) => { settingsStore.contextMode = v as 'full' | 'compact'; saveContextModeToServer(v) }"
                  size="small"
                  class="model-select"
                  dropdown-class-name="mode-dropdown"
                >
                  <a-select-option value="full">📋 全量</a-select-option>
                  <a-select-option value="compact">⚡ 精简</a-select-option>
                </a-select>
              </div>
            </div>
          </div>
          <div class="footer-right" v-if="balanceText || currentMessages.length > 0">
            <span v-if="balanceText" class="balance-chip" :title="balanceTitle">💰 {{ balanceChipLabel }} {{ balanceText }}</span>
            <span v-if="currentMessages.length > 0" class="context-tokens">
              上下文 Token: {{ formatTokenCount(totalContextTokens) }}
              <span v-if="settingsStore.contextMode === 'compact'" class="context-mode-badge" title="精简模式：非本轮工具调用和思考过程已精简">⚡ 精简</span>
            </span>
          </div>
        </div>
        <!-- 任务清单下拉面板 -->
        <div v-if="showTaskDropdown && taskItems.length > 0" class="task-dropdown">
          <div class="task-dropdown-header">
            <span>📋 任务清单</span>
            <span class="task-dropdown-progress">{{ completedTaskCount }}/{{ taskItems.length }}</span>
          </div>
          <div class="task-dropdown-body">
            <div
              v-for="item in taskItems"
              :key="item.id"
              :class="['task-dropdown-item', {
                'task-dropdown-item--completed': item.status === 'completed',
                'task-dropdown-item--executing': item.id === executingTaskId
              }]"
            >
              <span class="task-dropdown-icon">
                {{ item.status === 'completed' ? '✅' : item.id === executingTaskId ? '🔄' : '⏳' }}
              </span>
              <span class="task-dropdown-id">{{ item.id }}</span>
              <span class="task-dropdown-desc">{{ item.description }}</span>
              <span v-if="item.priority === 'HIGH'" class="task-dropdown-priority-high">HIGH</span>
              <span v-if="item.depends_on && item.depends_on.length > 0" class="task-dropdown-deps">← {{ item.depends_on.join(', ') }}</span>
            </div>
          </div>      </div>
        <!-- 文件改动面板 -->
        <div v-if="showChangesPanel && sessionChanges" class="changes-panel">
          <div class="changes-panel-header">
            <span class="changes-panel-title">📄 文件改动</span>
            <span class="changes-panel-summary">
              {{ sessionChanges.totalFiles }} 个文件
              <span class="changes-summary-divider">·</span>
              <span class="changes-add">+{{ sessionChanges.totalLinesAdded }}</span>
              <span class="changes-del">-{{ sessionChanges.totalLinesDeleted }}</span>
            </span>
          </div>
          <div class="changes-panel-body">
            <div
              v-for="file in sessionChanges.files"
              :key="file.relativePath"
              :class="['changes-panel-item', { 'is-rolled': file.rolledBack }]"
              @click="handleChangesFileClick(file)"
            >
              <div class="changes-file-row">
                <div class="changes-file-info">
                  <div class="changes-file-path-row">
                    <span class="changes-file-icon">{{ file.wasNewFile ? '✨' : '📄' }}</span>
                    <span class="changes-file-path" :title="file.relativePath">{{ file.relativePath }}</span>
                  </div>
                  <div class="changes-file-meta">
                    <span v-if="file.wasNewFile" class="changes-badge changes-badge-new">新建</span>
                    <span v-if="file.rolledBack" class="changes-badge changes-badge-rolled">已回滚</span>
                    <span v-else class="changes-file-stats">
                      <span class="changes-add">+{{ file.linesAdded }}</span>
                      <span class="changes-del">-{{ file.linesDeleted }}</span>
                    </span>
                  </div>
                </div>
                <div class="changes-file-actions">
                  <a-button
                    size="small"
                    :class="['changes-rollback-btn', { 'changes-rollback-btn--rolled': file.rolledBack }]"
                    @click="handleRollbackFile(file.relativePath)"
                    :disabled="file.rolledBack"
                  >
                    <UndoOutlined />
                    <span>{{ file.rolledBack ? '已回滚' : '回滚' }}</span>
                  </a-button>
                </div>
              </div>
            </div>
            <div v-if="sessionChanges.files.length === 0" class="changes-empty">
              <span class="changes-empty-icon">📭</span>
              <span>暂无文件改动</span>
            </div>
          </div>
          <div class="changes-panel-footer">
            <a-button
              size="small"
              class="changes-rollback-all-btn"
              :disabled="!sessionChanges || sessionChanges.files.length === 0 || sessionChanges.files.every(f => f.rolledBack)"
              @click="handleRollbackAll"
            >
              <UndoOutlined /> 全部回滚
            </a-button>
          </div>
        </div>
      </footer>
        </template>

        <!-- ===== 副面板（分屏时显示） ===== -->
        <template #secondary>
          <template v-if="splitState.enabled && splitState.secondary">
            <!-- 副面板标签栏 -->
            <div class="tab-bar">
              <div class="tab-items">
                <div
                  v-for="tab in splitState.secondary.paneTabs"
                  :key="tab.id"
                  :class="['tab-item', { active: splitState.secondary.activeTabId === tab.id, dirty: tab.isDirty }]"
                  draggable="false"
                  @click="splitState.secondary.activeTabId = tab.id; activeTabId = tab.id"
                  @mousedown.middle.prevent="closeTab(tab.id)"
                  @mousedown.left="(e: MouseEvent) => onTabDragStart(e, tab)"
                >
                  <span class="tab-title">{{ tab.title }}</span>
                  <span v-if="tab.isDirty" class="tab-dirty-dot">●</span>
                  <span class="tab-unsplit-btn" @click.stop="mergeTabBack(tab)" title="合并回主面板">⊟</span>
                  <span class="tab-close" @click.stop="closeTab(tab.id)">×</span>
                </div>
              </div>
            </div>
            <!-- 副面板内容 -->
            <div class="tab-content">
              <template v-if="getSecondaryActiveTab()?.type === 'file'">
                <div class="file-tab-layout">
                  <div class="file-tab-editor">
                    <FileEditor
                      :key="getSecondaryActiveTab()!.id"
                      :file-path="getSecondaryActiveTab()!.filePath || ''"
                      :content="getSecondaryActiveTab()!.content || ''"
                      :original-content="getSecondaryActiveTab()!.originalContent"
                      :highlight-lines="getSecondaryActiveTab()?.highlightLines"
                      @save="onFileSaved"
                      @content-change="onFileContentChange"
                    />
                  </div>
                </div>
              </template>
              <template v-else-if="getSecondaryActiveTab()?.type === 'diff'">
                <div class="diff-tab-content">
                  <div class="diff-tab-header">
                    <span class="diff-tab-title">差异对比: {{ getSecondaryActiveTab()!.filePath }}</span>
                    <div class="diff-tab-actions">
                      <a-button size="small" danger @click="handleRevertFile(getSecondaryActiveTab()!.filePath || '')">
                        <UndoOutlined /> 撤销此文件改动
                      </a-button>
                    </div>
                  </div>
                  <div class="diff-tab-body">
                    <DiffView
                      :head-content="getSecondaryActiveTab()!.headContent || ''"
                      :current-content="getSecondaryActiveTab()!.currentContent || ''"
                      :title="'Diff: ' + (getSecondaryActiveTab()!.filePath || '')"
                      :file-path="getSecondaryActiveTab()!.filePath"
                      :project-root="agentRuntime.workDir"
                      @hunks-restored="onHunksRestored"
                      @restore-all="handleRevertFile"
                    />
                  </div>
                </div>
              </template>
              <div v-else class="file-tab-layout">
                <div class="file-tab-empty">选择一个文件开始编辑</div>
              </div>
            </div>
          </template>
        </template>
      </SplitLayout>
    </main>

    <!-- 右侧工具栏（文件 / Git / 技能） -->
    <RightToolbar
      :project-root="agentRuntime.workDir"
      :work-dir="agentRuntime.workDir"
      :file-tree-load-path="fileTreeLoadPath"
      :agent-config-id="currentAgentConfigId"
      @select-project-root="selectProjectRoot"
      @file-select="onFileSelect"
      @file-dbl-click="onFileDblClick"
      @git-file-dbl-click="onGitFileDblClick"
    />
    <!-- 目录浏览器（在右侧工具栏文件面板中也会用到） -->
    <DirectoryBrowser :visible="showDirBrowser" @select="onDirSelected" @close="showDirBrowser = false" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, nextTick, watch, h } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { useUserStore } from '@/store/user'
import { getConversationList, mapConversationResponseToConversation, getConversationMessages, getConversationMessagesPage, processMessageGroups, deleteConversation as deleteConversationApi, updateConversationName } from '@/api/conversation'
import { streamChat, checkActiveTask, taskStream, cancelTask, supplementRequest, optimizePrompt } from '@/api/chat'
import { listAgentConfigs, type AgentConfig } from '@/api/agent-config'
import { setConfig } from '@/api/config'
import type { AgentStreamEvent } from '@/utils/sse-client'
import type { DelegateProgressData } from '@/utils/sse-client'
import type { TaskErrorData } from '@/utils/sse-client'
import { submitAnswer } from '@/api/askUser'
import { listSnapshots, previewRollback, executeRollback, getSessionChanges, rollbackFile, rollbackAllFiles, getSnapshotFileContent, type SessionChanges } from '@/api/snapshot'
import { useSettingsStore } from '@/store/settings'
import { renderMarkdown, renderMarkdownLight } from '@/utils/markdown'
import { estimateTokenCount, formatTokenCount } from '@/utils/tokenCalculator'
import {
  CodeOutlined,
  DeleteOutlined,
  SendOutlined,
  DownOutlined,
  UpOutlined,
  MenuFoldOutlined,
  LoadingOutlined,
  MenuUnfoldOutlined,
  PlusOutlined,
  SettingOutlined,
  CloseOutlined,
  CheckOutlined,
  UndoOutlined,
  PaperClipOutlined,
  PlaySquareOutlined,
  CaretRightOutlined,
  PoweroffOutlined,
  ClearOutlined,
  UploadOutlined,
  HistoryOutlined,
  SearchOutlined,
  ReloadOutlined,
  ExportOutlined
} from '@ant-design/icons-vue'
import FileTree from '@/components/FileTree.vue'
import FileEditor from '@/components/FileEditor.vue'
import GitSidebar from '@/components/GitSidebar.vue'
import DiffView from '@/components/DiffView.vue'
import SkillList from '@/components/SkillList.vue'
import AgentPanel from '@/components/AgentPanel.vue'
import type { AgentInfo } from '@/components/AgentPanel.vue'
import RightToolbar from '@/components/RightToolbar.vue'
import SplitLayout from '@/components/SplitLayout.vue'
import DirectoryBrowser from '@/components/DirectoryBrowser.vue'
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'
import { readProjectFile, getProjectFileBlobUrl, resolveProjectFilePath, buildProject, runProject, stopProject, getRunStatus, getRunOutput } from '@/api/project'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { getGitDiff, gitRestore, gitShowFile } from '@/api/git'
import AgentSelector from '@/components/AgentSelector.vue'
import { isImageAvatar } from '@/utils/avatar'
import { listProviders, parseModelList, getProviderBalance, type ProviderConfig, type ProviderBalance, type ProviderBalanceInfo } from '@/api/llm-provider'
// M4：图像理解（文件资产上传/历史回显/跨页引用）
import { uploadFileAsset, getFilePreviewBlobUrl, downloadFileAsset, listFileAssets, fileAssetDisplayName, docTypeOf, docTypeIcon, isImageMime, type FileAssetData } from '@/api/file-asset'
import { useFileRefStore } from '@/store/file-ref'
import { compressImageIfNeeded } from '@/utils/image-compress'
import { useRoute } from 'vue-router'

const PROMPT_FILE = 'code_agent_prompt.txt'

const userStore = useUserStore()
const settingsStore = useSettingsStore()
const router = useRouter()

interface Conversation {
  id: string
  title: string
  updatedAt: number
  messageCount: number
  isLocal?: boolean
}

interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  thinking?: string
  toolResults?: {
    at: number
    content: string
    toolName?: string         // 工具名称
    pending?: boolean          // 是否正在执行（加载中），true 时 content 为空
    operationSummary?: string  // 操作摘要（如 "📄 创建文件: xxx"）
    // 2026-09-21：委托进度（agent_invoke pending 卡片展示「已等待 N 秒」+「前往 B 会话」跳转）
    delegateSessionId?: number    // B 会话 ID（心跳 / 完成卡解析后填充）
    delegateTarget?: string       // 目标智能体名（显示用）
    delegateStatus?: string       // RUNNING 等（预留）
    delegateElapsedSec?: number   // 已等待秒数
  }[]
  /** 命中技能（LLM 真正执行 skill action=load 时动态追加；去重，供「思考过程」横栏标签展示） */
  hitSkills?: string[]
  timestamp: number
  isStreaming?: boolean
  tokenCount?: number
  turnId?: string
  snapshotId?: string
  expandedCards?: Set<number>  // M1b-2：工具卡片展开状态（数据驱动折叠；默认折叠=不在集合中）
  fullCards?: Set<number>      // M3：超长卡片「显示全部」状态（解除截断；默认不在集合中）
  /** M4：消息附带的图片文件（本轮发送 或 历史回显——按 messageId 映射 file_reference） */
  fileAssets?: MessageFileAsset[]
  /** 修复：桌面截图注入消息id列表（role=user 的 desktop_inject 消息不渲染气泡，
   *  其截图资产由 loadMessageFileRefs 归并到本 AI 消息的 fileAssets 回显） */
  injectSourceIds?: string[]
  /** 任务错误（LLM 接口错误停止时由 task_error 事件写入；聊天区展示错误详情，
   *  输入区同步展示「任务中断」横幅 + 「一键继续」入口） */
  taskError?: TaskErrorData
}

/** M4/M7：消息气泡中展示的文件（图片→缩略图；文档→文件图标卡片） */
interface MessageFileAsset {
  id: number
  /** 远端 Files API 文件ID（本地存储文档为 null） */
  fileId?: string | null
  filename: string
  displayName?: string
  mimeType?: string
  size?: number
  /** M7：图片/文档（气泡渲染分流：缩略图 vs 文件图标卡片） */
  kind?: 'image' | 'doc'
  /** blob 预览 URL（异步填充；无则显示占位；仅图片） */
  previewUrl?: string
}

// ===== 附件系统 =====
interface AttachedFile {
  id: string
  /** Legacy：旧临时通道附件ID（M7 起弃用，恒为空；文档也走 fileAssetId） */
  attachmentId: string
  fileName: string
  size: number
  type: string      // text/pdf/word/excel/image
  extension: string
  uploading: boolean
  error?: string
  /** 附件种类——image（云端 Files API，注入 vision 内容块）/ doc（本地存储，LLM 工具读取） */
  kind?: 'image' | 'doc'
  /** 附件的本地资产ID（发送时组装 fileAssetIds，图片+文档统一通道） */
  fileAssetId?: number
  /** 图片附件缩略图（本地上传为 objectURL；引用/历史为 blob URL；仅图片） */
  previewUrl?: string
  /** previewUrl 是否由本组件创建（true 时移除需 revoke；缓存 URL 不释放） */
  previewOwned?: boolean
}

// 单文件上传上限：64MB（这是**聊天附件自己的业务上限**，对齐 DeepSeek Files API 单文件上限）。
// 注意：application.yml 的 spring.servlet.multipart.max-file-size 已放宽到 500MB（供「导入备份」用），
// 两者不是同一个额度，不要把这里的 64MB 跟着调大——上传超 64MB 的附件 Web 端本来就传不到 DeepSeek。
const MAX_UPLOAD_SIZE = 64 * 1024 * 1024

const attachedFiles = ref<AttachedFile[]>([])
const fileInputRef = ref<HTMLInputElement | null>(null)

// ===== M4：图像理解（Files API 图片附件） =====
const fileRefStore = useFileRefStore()
const route = useRoute()
/** 大图预览遮罩 URL（点击消息/附件缩略图打开；点击遮罩关闭） */
const bigPreviewUrl = ref('')

/** 打开大图预览遮罩（无 URL 时提示加载中） */
const openBigPreview = (url?: string) => {
  if (url) {
    bigPreviewUrl.value = url
  } else {
    message.warning('图片加载中，请稍候')
  }
}

/** M7：消息气泡中文档附件点击下载 */
const downloadMessageFile = async (asset: MessageFileAsset) => {
  try {
    await downloadFileAsset(asset.id, fileAssetDisplayName(asset))
  } catch (e: any) {
    message.error(e.message || '下载失败')
  }
}

// ===== 附件入口两步交互：上传新文件 / 用历史文件 =====
/** 📎 下拉菜单点击分发 */
const onAttachMenuClick = ({ key }: { key: string | number }) => {
  if (key === 'upload') {
    triggerFileUpload()
  } else if (key === 'history') {
    openHistoryPicker()
  }
}

// 历史图片选择弹窗
const historyPickerVisible = ref(false)
const historyPickerLoading = ref(false)
const historyImages = ref<FileAssetData[]>([])
const historyKeyword = ref('')
const historySelectedIds = ref<Set<number>>(new Set())
/** 弹窗缩略图（id → blob URL；来自 file-asset 全局缓存，无需 revoke） */
const historyPreviewMap = ref<Record<number, string>>({})

/** 按关键字过滤（前端过滤，避免频繁请求） */
const filteredHistoryImages = computed(() => {
  const kw = historyKeyword.value.trim().toLowerCase()
  if (!kw) return historyImages.value
  return historyImages.value.filter(f => fileAssetDisplayName(f).toLowerCase().includes(kw))
})

/** 该文件是否已在附件区（避免重复添加） */
const isAssetAttached = (assetId: number) => attachedFiles.value.some(a => a.fileAssetId === assetId)

/** 打开历史图片选择弹窗（每次打开重新拉取最新列表） */
const openHistoryPicker = async () => {
  historyPickerVisible.value = true
  historyKeyword.value = ''
  historySelectedIds.value = new Set()
  historyPickerLoading.value = true
  try {
    const res = await listFileAssets({ limit: 200, order: 'desc' })
    if (res.code === 200 && res.data) {
      historyImages.value = res.data
      // 异步加载缩略图（仅图片；失败保留占位图）
      for (const f of res.data) {
        if (!isImageMime(f.mimeType)) continue
        if (historyPreviewMap.value[f.id]) continue
        getFilePreviewBlobUrl(f.id).then(url => {
          historyPreviewMap.value = { ...historyPreviewMap.value, [f.id]: url }
        }).catch(() => {})
      }
    } else {
      message.error(res.message || '加载历史文件失败')
    }
  } catch (e: any) {
    message.error(e.message || '加载历史文件失败')
  } finally {
    historyPickerLoading.value = false
  }
}

/** 切换选中（已在附件中的不可再选） */
const toggleHistorySelect = (f: FileAssetData) => {
  if (isAssetAttached(f.id)) {
    message.info('该文件已在附件中')
    return
  }
  const next = new Set(historySelectedIds.value)
  if (next.has(f.id)) next.delete(f.id)
  else next.add(f.id)
  historySelectedIds.value = next
}

/** 确认添加：写入附件区（与「引用到聊天」同一附件结构，随消息以 fileAssetIds 发送） */
const confirmHistoryPick = () => {
  const selected = historyImages.value.filter(f => historySelectedIds.value.has(f.id) && !isAssetAttached(f.id))
  // M7：仅当选中有图片时才做模型 vision 能力检查（纯文档不需要视觉模型）
  if (selected.some(f => isImageMime(f.mimeType)) && !ensureVisionModel()) return
  let added = 0
  for (const f of selected) {
    const isImg = isImageMime(f.mimeType)
    const attFile: AttachedFile = {
      id: `hist-${f.id}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      attachmentId: '',
      fileName: fileAssetDisplayName(f),
      size: f.size || 0,
      type: isImg ? 'image' : docTypeOf(f.filename, f.mimeType),
      extension: (f.filename.split('.').pop() || '').toLowerCase(),
      uploading: false,
      kind: isImg ? 'image' : 'doc',
      fileAssetId: f.id
    }
    attachedFiles.value.push(attFile)
    added++
    if (isImg) {
      getFilePreviewBlobUrl(f.id).then(url => {
        const found = attachedFiles.value.find(a => a.id === attFile.id)
        if (found) found.previewUrl = url
      }).catch(() => {})
    }
  }
  historyPickerVisible.value = false
  if (added > 0) message.success(`已添加 ${added} 个历史文件`)
}

/** 模型 vision 能力判定（与后端 FileVisionService.supportsVisionModel 规则一致；非 deepseek 宽松放行） */
const isVisionModel = (model?: string): boolean => {
  if (!model) return true
  const m = model.toLowerCase()
  if (m.startsWith('deepseek')) {
    return m.includes('flash') || m.includes('vision') || m.includes('-vl')
  }
  return true
}

/** 当前模型不支持图片时：尝试自动切换 flash 系模型；无可用则返回 false（调用方阻止上传） */
const ensureVisionModel = (): boolean => {
  const runtime = agentSelectorRef.value?.runtime
  const model = runtime?.model || agentRuntime.value.model
  if (isVisionModel(model)) return true
  const flash = availableModels.value.find(m => isVisionModel(m))
  if (flash && runtime) {
    runtime.model = flash
    agentSelectorRef.value?.saveRuntime()
    message.warning(`当前模型不支持图片，已自动切换为 ${flash}`)
    return true
  }
  message.error('当前模型不支持图片理解，且未找到可用的视觉模型（如 deepseek-flash），请先切换模型')
  return false
}

// Agent 选择器相关
const agentSelectorRef = ref<InstanceType<typeof AgentSelector> | null>(null)
const currentAgentConfigId = ref<number | null>(null)
const currentAgentWorkDir = ref<string>('')
const skillsRefreshKey = ref(0)

// ===== Phase 18 P3：Agent 列表（授权归属标识 + 后台任务完成通知需要按 agentConfigId 查名称/工作目录）=====
const agentOptions = ref<AgentConfig[]>([])
const fetchAgentList = async () => {
  try {
    const res = await listAgentConfigs()
    if (res.code === 200 && res.data) {
      agentOptions.value = res.data
    }
  } catch (e) {
    console.warn('加载 Agent 列表失败:', e)
  }
}
/** 按 agentConfigId 查询 Agent 元信息（名称/工作目录/头像），查不到时降级为占位 */
const getAgentMeta = (agentConfigId: number | null | undefined): { name: string; workDir: string; avatar: string } => {
  const a = agentOptions.value.find(x => x.id === agentConfigId)
  return { name: a?.name || `Agent#${agentConfigId ?? '?'}`, workDir: a?.workDir || '', avatar: a?.avatar || '' }
}
/**
 * 构建「后台任务通知」的内容节点：头像取该 Agent 自己的 avatar（图片渲染 img、emoji/文字原样显示），
 * 不再写死 🤖 —— 多智能体并行时各 Agent 头像不同，写死会导致通知头像「货不对板」。
 * 注意：message 弹层挂在 body 上，组件 scoped 样式不生效，故用内联样式控制尺寸/对齐。
 */
const buildAgentNotice = (meta: { name: string; avatar?: string }, text: string) => {
  const avatar = (meta.avatar || '').trim()
  const boxStyle = 'display:inline-block;width:16px;height:16px;line-height:16px;text-align:center;border-radius:4px;flex:none'
  const avatarNode = isImageAvatar(avatar)
    ? h('img', { src: avatar, alt: '', style: `${boxStyle};object-fit:cover` })
    : h('span', { style: `${boxStyle};font-size:13px` }, avatar || '🤖')
  return h('span', { style: 'display:inline-flex;align-items:center;gap:6px;vertical-align:middle' }, [
    avatarNode,
    h('span', null, text)
  ])
}
/** 当前智能体头像（对话消息 AI 头像用：本地图片 URL 或默认 emoji） */
const currentAgentAvatar = computed(() => {
  const a = agentOptions.value.find(x => x.id === currentAgentConfigId.value)
  return a?.avatar || ''
})
// 组件初始化时拉取一次 Agent 列表（授权归属标识/完成通知需要）
fetchAgentList()

// Agent 运行时配置（computed，确保响应式追踪）
const agentRuntime = computed(() => ({
  model: agentSelectorRef.value?.runtime?.model || 'deepseek-v4-flash',
  thinkingMode: agentSelectorRef.value?.runtime?.thinkingMode || 'non-thinking',
  executionMode: agentSelectorRef.value?.runtime?.executionMode || 'manual',
  workDir: agentSelectorRef.value?.runtime?.workDir || '',
  providerId: agentSelectorRef.value?.runtime?.providerId || undefined,
  providerCode: agentSelectorRef.value?.runtime?.providerCode || ''
}))

const updateAgentRuntime = (key: string, value: string) => {
  const rt = agentSelectorRef.value?.runtime
  if (rt) {
    (rt as any)[key] = value
    agentSelectorRef.value?.saveRuntime()
  }
}

/** Provider 切换：持久化用户选择到 AgentConfig */
const onProviderSwitch = (v: string) => {
  const rt = agentSelectorRef.value?.runtime
  if (!rt) return
  rt.providerCode = v
  refreshModelList()
  agentSelectorRef.value?.saveRuntime()
}

// ===== LLM Provider + 动态模型列表 =====
const providerOptions = ref<ProviderConfig[]>([])
const availableModels = ref<string[]>(['deepseek-v4-flash', 'deepseek-v4-pro']) // 默认兜底

const fetchAvailableModels = async () => {
  try {
    const res = await listProviders()
    if (res.code === 200 && res.data && res.data.length > 0) {
      providerOptions.value = res.data
      refreshModelList()
    }
  } catch (e) {
    console.warn('加载 Provider 列表失败，使用默认模型列表', e)
  }
}

/** 根据当前 Provider 刷新模型列表：runtime.providerCode > 第一个 Provider */
const refreshModelList = () => {
  const providers = providerOptions.value
  if (providers.length === 0) {
    availableModels.value = []
    return
  }

  const runtime = agentSelectorRef.value?.runtime
  // 找到当前使用的 Provider
  const prov = (runtime?.providerCode
    ? providers.find((p: ProviderConfig) => p.code === runtime.providerCode)
    : null) || providers[0]

  if (prov?.parsedModelList && prov.parsedModelList.length > 0) {
    availableModels.value = prov.parsedModelList
    // 如果当前模型不在新 Provider 列表中，自动切到默认模型
    const currentModel = runtime?.model
    if (runtime && (!currentModel || !prov.parsedModelList.includes(currentModel))) {
      runtime.model = prov.defaultModel || prov.parsedModelList[0]
      agentSelectorRef.value?.saveRuntime()
    }
  } else {
    availableModels.value = providers.flatMap((p: ProviderConfig) => p.parsedModelList || [])
  }
}

// ===== DeepSeek 余额展示（仅当前 Provider 为 DeepSeek 时展示） =====
const providerBalance = ref<ProviderBalance | null>(null)

/** 解析当前生效的 Provider（runtime.providerCode > 第一个 Provider，与 refreshModelList 逻辑一致） */
const resolveActiveProvider = (): ProviderConfig | null => {
  const providers = providerOptions.value
  if (providers.length === 0) return null
  const runtime = agentSelectorRef.value?.runtime
  return (runtime?.providerCode
    ? providers.find((p: ProviderConfig) => p.code === runtime.providerCode)
    : null) || providers[0]
}

/** 支持余额/额度查询的 Provider 模板（DeepSeek / 火山引擎 / MiniMax / MiMo / 通义千问） */
const BALANCE_TEMPLATES = [
  'deepseek', 'volcengine', 'ark', 'doubao',
  'minimax', 'minimax-cn', 'minimax-intl', 'minimax-global',
  'mimo', 'xiaomi-mimo',
  'qwen', 'dashscope', 'tongyi'
]

/** 当前生效的 Provider 是否支持余额/额度查询 */
const hasBalanceCapability = computed(() => {
  const prov = resolveActiveProvider()
  if (!prov) return false
  return BALANCE_TEMPLATES.includes((prov.requestTemplate || prov.code || '').toLowerCase())
})

/** 当前 Provider 是否为火山引擎（余额字段语义与 DeepSeek 不同） */
const isVolcengineProvider = computed(() => {
  const prov = resolveActiveProvider()
  if (!prov) return false
  const template = (prov.requestTemplate || prov.code || '').toLowerCase()
  return template === 'volcengine' || template === 'ark' || template === 'doubao'
})

/** 当前 Provider 是否为小米 MiMo（余额需控制台 Cookie） */
const isMiMoProvider = computed(() => {
  const prov = resolveActiveProvider()
  if (!prov) return false
  const template = (prov.requestTemplate || prov.code || '').toLowerCase()
  return template === 'mimo' || template === 'xiaomi-mimo'
})

/** 当前 Provider 是否为通义千问（余额来自阿里云费用中心） */
const isQwenProvider = computed(() => {
  const prov = resolveActiveProvider()
  if (!prov) return false
  const template = (prov.requestTemplate || prov.code || '').toLowerCase()
  return template === 'qwen' || template === 'dashscope' || template === 'tongyi'
})

/** 当前返回的是「额度」（quota）而非「余额」（金额） */
const isQuotaType = computed(() => providerBalance.value?.balanceType === 'quota')

/** 指标标签：额度 / 余额 */
const balanceChipLabel = computed(() => isQuotaType.value ? '额度' : '余额')

/** 待展示的余额明细：优先人民币，否则取第一条 */
const activeBalance = computed(() => {
  const infos = providerBalance.value?.balanceInfos || []
  if (infos.length === 0) return null
  return infos.find((i: ProviderBalanceInfo) => i.currency === 'CNY') || infos[0]
})

/** 货币符号 */
const currencySymbol = (currency: string) => currency === 'CNY' ? '¥' : currency === 'USD' ? '$' : `${currency} `

/** 余额/额度文本（如 "¥110.00" / "67%"）；不支持或无数时为空串（对应 UI 不展示） */
const balanceText = computed(() => {
  if (!hasBalanceCapability.value) return ''
  const info = activeBalance.value
  if (!info) return ''
  if (isQuotaType.value) {
    const percent = info.remainingPercent
    if (percent != null) return `${Number(percent).toFixed(0)}%`
    return info.totalBalance
  }
  return `${currencySymbol(info.currency || '')}${info.totalBalance}`
})

/**
 * 悬浮提示：
 * - quota（MiniMax Token Plan）：套餐 / 剩余百分比 / 剩余次数 / 重置时间
 * - 火山引擎：可用余额 / 现金余额 / 信控额度 / 冻结 / 欠费
 * - MiMo：余额 / 现金 / 赠送 / 冻结
 * - DeepSeek：总余额 / 赠金 / 充值
 */
const balanceTitle = computed(() => {
  const info = activeBalance.value
  if (!info) return ''
  const sym = currencySymbol(info.currency || '')

  if (isQuotaType.value) {
    const parts: string[] = []
    if (info.planName) parts.push(info.planName)
    if (info.remainingPercent != null) parts.push(`剩余 ${Number(info.remainingPercent).toFixed(1)}%`)
    if (info.remainingCount != null && info.totalCount != null) {
      parts.push(`${info.remainingCount}/${info.totalCount} 次`)
    }
    if (info.resetAt) parts.push(`重置 ${new Date(info.resetAt).toLocaleString()}`)
    return parts.join(' · ')
  }

  if (isVolcengineProvider.value) {
    const cash = info.cashBalance ?? info.toppedUpBalance ?? '0.00'
    const credit = info.creditLimit ?? '0.00'
    const freeze = info.freezeAmount ?? '0.00'
    const arrears = info.arrearsBalance ?? '0.00'
    return `可用余额 ${sym}${info.totalBalance} · 现金余额 ${sym}${cash} · 信控额度 ${sym}${credit} · 冻结 ${sym}${freeze} · 欠费 ${sym}${arrears}`
  }

  if (isMiMoProvider.value) {
    const cash = info.cashBalance ?? info.toppedUpBalance ?? '0.00'
    const gift = info.grantedBalance ?? '0.00'
    const frozen = info.freezeAmount ?? '0.00'
    return `余额 ${sym}${info.totalBalance} · 现金 ${sym}${cash} · 赠送 ${sym}${gift} · 冻结 ${sym}${frozen}`
  }

  if (isQwenProvider.value) {
    const cash = info.cashBalance ?? info.toppedUpBalance ?? '0.00'
    const credit = info.creditLimit ?? '0.00'
    return `可用余额 ${sym}${info.totalBalance} · 现金余额 ${sym}${cash} · 信用额度 ${sym}${credit}（阿里云费用中心）`
  }

  return `总余额 ${sym}${info.totalBalance} · 赠金 ${sym}${info.grantedBalance} · 充值 ${sym}${info.toppedUpBalance}`
})

/**
 * 刷新 Provider 余额
 * 触发时机：进入聊天页面（Provider 列表加载完成）、切换 Provider / Agent、每次对话结束后
 * 不支持余额的 Provider 清空展示；查询失败时静默保留上一次成功数据，不影响聊天主流程
 */
const refreshBalance = async () => {
  const prov = resolveActiveProvider()
  const template = prov ? (prov.requestTemplate || prov.code || '').toLowerCase() : ''
  if (!prov || !BALANCE_TEMPLATES.includes(template)) {
    providerBalance.value = null
    return
  }
  try {
    const res = await getProviderBalance(prov.code)
    if (res.code === 200 && res.data) {
      providerBalance.value = res.data
    }
  } catch (e) {
    // 静默失败：保留上一次成功数据（如 API Key / AK/SK 未配置、网络异常等）
    console.warn('获取 Provider 余额失败:', e)
  }
}

// ★ 监听 Provider 手动切换 → 重新加载模型列表 + 刷新 DeepSeek 余额
watch(() => agentSelectorRef.value?.runtime?.providerCode, () => {
  if (providerOptions.value.length > 0) {
    refreshModelList()
    refreshBalance()
  }
})

// ★ 监听 providerOptions 首次加载完成 → 刷新一次（解决初始化时序问题；进入页面时由此触发余额查询）
watch(providerOptions, (opts) => {
  if (opts.length > 0) {
    refreshModelList()
    refreshBalance()
  }
})

/** 格式化模型显示名称 */
const formatModelLabel = (model: string): string => {
  // 简短显示，去掉常见前缀
  return model.replace(/^deepseek-/, '').replace(/^gpt-/, '')
}

// ===== 多Agent后台流式 - 辅助函数 =====
const saveAgentSnapshot = (agentId: number) => {
  agentSnapshots.value[agentId] = {
    conversations: JSON.parse(JSON.stringify(conversations.value)),
    currentConversationId: currentConversationId.value
  }
}
const loadAgentSnapshot = (agentId: number): AgentSnapshotData | null => {
  return agentSnapshots.value[agentId] || null
}
const onStreamComplete = (convId: string, agentId: number | null, completed = false, abortCtrl?: AbortController | null) => {
  // ★ 归属修复（头像/名称货不对板）：入参 agentId 是「当前选中的 Agent」（调用方传 currentAgentConfigId），
  // 多智能体并行时用户很可能已切到别的 Agent（如小熊任务在跑、人切到了 AI 助手），此时用它弹通知
  // 会显示错误的名字与头像。切走转后台时已把归属 agentConfigId 记进 backgroundStreams，
  // 这里在移除条目之前按「本流」精确取回归属 ID，取不到（纯前台流）才回退入参。
  const ownerAgentId = (
    abortCtrl
      ? backgroundStreams.value.find(s => s.convId === convId && s.abortController === abortCtrl)
      : backgroundStreams.value.find(s => s.convId === convId)
  )?.agentConfigId ?? agentId
  // 三轮审查深修：按「本流」精确移除——同一会话可能并存多个后台流条目（P2 双流：
  // 前台流切走转后台 → 切回再派新任务再切走），按 convId 全量移除会把仍在运行的
  // 其他后台流一并摘除 → hasActiveStream 查不到 → 停止按钮失效、状态残留。
  // 传 abortCtrl 时仅移除「本流」条目；不传（旧调用兜底）保持按会话移除。
  backgroundStreams.value = backgroundStreams.value.filter(s =>
    abortCtrl ? (s.convId !== convId || s.abortController !== abortCtrl) : s.convId !== convId
  )
  // filter 之后判断「该会话是否还有任何活跃流」：
  // - 前台流式（sendingStates）：后台流结束不得误清仍在运行的前台流状态（授权弹窗/任务看板）
  // - 后台流（backgroundStreams）：移除本流后若还有其他后台流，同样不清理
  // 两者皆无才清理执行状态（原 H2「filter 前判断」在全量移除语义下导致后台流结束永不清理，状态残留）
  const isLastStream = !sendingStates.has(convId) && !backgroundStreams.value.some(s => s.convId === convId)
  // H1 修复：只清理「执行状态」（状态文字/活跃任务），不再无条件 clearConvState——
  // 1. 保留任务清单 taskItemsMap：任务看板是历史记录，前台流式正常结束后也应保留（P2 前行为）
  // 2. 不删 sendingStates：前台流式结束时 sendMessage finally 已 setSending(false)，
  //    若同一会话刚有新的前台流（setSending(true)），这里删除会误清新流的锁
  // 3. activeTaskMap 在任务结束后清掉（重连状态已无用），重连路径也会 setActiveTask 覆盖
  if (isLastStream) {
    streamStatusMap.delete(convId)
    activeTaskMap.delete(convId)
    // P3：任务结束后清掉该会话的待审批问题（已处理或已随流结束失效，防止残留弹窗/角标）
    pendingQuestions.delete(convId)
  }
  // P3：后台任务完成通知——仅正常完成（completed=true，sendMessage try 正常走完）且非当前会话才提示；
  // 用户中止（__USER_ABORT__）/发送失败路径 completed=false，不误报「已完成」
  if (completed && convId !== currentConversationId.value) {
    const meta = getAgentMeta(ownerAgentId)
    message.open({ type: 'success', content: buildAgentNotice(meta, `${meta.name} 的后台任务已完成`) })
  }
}

const onAgentChange = (agentId: number | null | undefined, agent: any) => {
  const oldAgentId = currentAgentConfigId.value

  // 第一步：保存当前 Agent 的快照
  if (oldAgentId) {
    saveAgentSnapshot(oldAgentId)
  }

  // 第二步：如果当前有流式输出，转移到后台继续运行（不 abort）
  if (isSending.value && stopAbortController.value && currentConversationId.value) {
    backgroundStreams.value.push({
      convId: currentConversationId.value,
      agentConfigId: oldAgentId!,
      abortController: stopAbortController.value,
      realSessionId: activeStreamInfo.value?.realSessionId
    })
    // 清除 activeStreamInfo，信息已转移到 backgroundStreams
    activeStreamInfo.value = null
  }

  // 第三步：重置当前会话状态（不停止后台流式）
  // P2：按会话清理——前台流已转后台（sending 不锁），状态文字清空（后台流的 SSE 事件会实时更新该会话状态）
  // P3：不清 pendingQuestion——Map 化后弹窗自动切到新会话；旧会话若有后台授权请求，
  //     保留在 map 中，切回时自动恢复显示 + 角标提示
  const oldConvId = currentConversationId.value
  setSending(oldConvId, false)
  setStreamStatus(oldConvId, '')
  stopAbortController.value = null
  // 前台流已转后台：清空全局「流真实会话 ID」，避免切到新 Agent 后补充需求/停止任务误用它指向旧会话
  // （后台流自己的 realSessionId 已记录在 backgroundStreams 条目里，取消能力不受影响）
  currentStreamSessionId.value = null

  // 第四步：更新当前 Agent
  currentAgentConfigId.value = agentId ?? null
  currentAgentWorkDir.value = agent?.workDir || ''
  // ★ 同步 settingsStore.projectRoot → 确保 Git 工具栏等所有组件使用一致的工作目录
  if (agent?.workDir) {
    settingsStore.projectRoot = agent.workDir
  }
  if (agent?.workDir) {
    fileTreeLoadPath.value = agent.workDir
  }

  // 第五步：恢复目标 Agent 的快照 或 重新加载
  // ★ 关键：messages 不清空！后台流式还在写入旧会话的数据
  const snap = agentId ? loadAgentSnapshot(agentId) : null
  if (snap) {
    conversations.value = snap.conversations
    currentConversationId.value = snap.currentConversationId
  } else {
    currentConversationId.value = null
    conversations.value = []
    fetchConversations()
  }
  // ★ 切回 Agent 的滚动位置修复：
  //   1) 消息列表带 :key=会话ID，currentConversationId 变化会重建 DynamicScroller → scrollTop 归零；
  //   2) 上一个 Agent 可能停留在「上滑浏览历史」状态（autoScrollToBottom=false）。
  //   两者叠加会让切回后的聊天记录停在最顶部（表现为位置错乱 / 看不到最新记录）。
  //   这里统一恢复「跟随底部」并强制贴底。
  autoScrollToBottom.value = true
  showScrollToBottomBtn.value = false
  nextTick(() => {
    scrollToBottomForce(currentConversationId.value || undefined)
  })
  // M4 优化：仅真实切换 Agent 时清「上次查看会话」记忆——首次加载保留，供 fetchConversations 恢复；
  // 原实现在 fetchConversations 异步完成（await）前就无条件清除，导致「恢复上次会话」分支永远落到第一个会话。
  if (oldAgentId) {
    clearSavedConversationId()
  }
  skillsRefreshKey.value++

  // 第六步：文件树
  if (agent?.workDir) {
    fileTreeLoadPath.value = agent.workDir
  } else {
    fileTreeLoadPath.value = ''
  }

  // 第七步：恢复流式状态（如果新 Agent 的当前会话有后台流式在运行）
  // P2：显示「后台输出中...」提示；2026-08-14 需求变更：hasRunningTask 检测到后台任务时输入框自动锁定
  //（有任务在执行的智能体限制输入，任务结束 onStreamComplete 移除后台流后自动解锁）
  if (agentId && currentConversationId.value && backgroundStreams.value.some(s => s.convId === currentConversationId.value)) {
    setStreamStatus(currentConversationId.value, '后台输出中...')
  }

  // 第八步（P3，F4 修复）：切回 Agent 后检查该会话是否有活跃后台任务（页面刷新后的场景），
  // 有则重连恢复事件流 + 授权弹窗。checkAndReconnect 内部已跳过「已有后台流」的会话，不会双流。
  // 异步执行不阻塞切换；任务已完成时只是无害的一次查询。
  if (agentId && currentConversationId.value && !currentConversationId.value.startsWith('local-')) {
    checkAndReconnect(currentConversationId.value)
  }
}

const triggerFileUpload = () => {
  fileInputRef.value?.click()
}

const handleFileSelected = async (event: Event) => {
  const target = event.target as HTMLInputElement
  const files = target.files
  if (!files || files.length === 0) return

  // M4：支持多选（循环上传）；图片/文档自动分流
  for (const file of Array.from(files)) {
    await attachLocalFile(file)
  }

  // 清空 input 以便重复选择同一个文件
  target.value = ''
}

/**
 * M4/M7 统一附件入口：
 * - 图片（image/*）→ 文件资产·云端 Files API（/api/files 上传 + fileAssetId 随发送，注入 vision 内容块）；含模型能力检查
 * - 文档（pdf/word/excel/text）→ 文件资产·本地存储（fileAssetId 随发送，LLM 通过 chat_attachment 工具读取）
 */
const attachLocalFile = async (file: File, source: 'upload' | 'paste' = 'upload') => {
  const attId = `att-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
  const isImage = file.type.startsWith('image/')
  const attFile: AttachedFile = {
    id: attId,
    attachmentId: '',
    fileName: file.name,
    size: file.size,
    type: isImage ? 'image' : '',
    extension: '',
    uploading: true,
    kind: isImage ? 'image' : 'doc'
  }

  // 超限预检：超过上限的文件不发请求，直接提示（与后端校验/提示保持一致）
  if (file.size > MAX_UPLOAD_SIZE) {
    attFile.uploading = false
    attFile.error = `文件大小超过上限（最大 ${MAX_UPLOAD_SIZE / 1024 / 1024}MB）`
    attachedFiles.value.push(attFile)
    return
  }

  if (isImage) {
    // 模型能力检查：不支持则尝试自动切换；无法切换时阻止上传（后端亦有防线）
    if (!ensureVisionModel()) {
      attFile.uploading = false
      attFile.error = '当前模型不支持图片'
      attachedFiles.value.push(attFile)
      return
    }
    // 本地缩略图（上传过程中即时可见；previewOwned 标记供移除时 revoke）
    attFile.previewUrl = URL.createObjectURL(file)
    attFile.previewOwned = true
    attachedFiles.value.push(attFile)
    try {
      // P3：上传前可选预压缩（超阈值转 JPEG；失败自动回退原图，不阻断上传）
      const uploadTarget = await compressImageIfNeeded(file)
      if (uploadTarget !== file) {
        attFile.fileName = uploadTarget.name
        attFile.size = uploadTarget.size
      }
      const res = await uploadFileAsset(uploadTarget, {
        source,
        providerCode: agentRuntime.value.providerCode || undefined
      })
      const found = attachedFiles.value.find(a => a.id === attId)
      if (found) {
        if (res.code === 200 && res.data) {
          found.fileAssetId = res.data.id
          found.extension = (res.data.mimeType || '').split('/')[1] || ''
          found.uploading = false
        } else {
          found.error = res.message || '上传失败'
          found.uploading = false
        }
      }
    } catch (e: any) {
      const found = attachedFiles.value.find(a => a.id === attId)
      if (found) {
        found.error = e.message || '上传失败'
        found.uploading = false
      }
    }
    return
  }

  // 文档（M7）：统一走文件资产（本地存储）；发送后 LLM 通过 chat_attachment(read_by_file_asset) 读取
  attFile.type = docTypeOf(file.name)
  attachedFiles.value.push(attFile)
  try {
    const res = await uploadFileAsset(file, { source })
    const found = attachedFiles.value.find(a => a.id === attId)
    if (found) {
      if (res.code === 200 && res.data) {
        found.fileAssetId = res.data.id
        found.type = docTypeOf(res.data.filename, res.data.mimeType)
        found.extension = (res.data.filename.split('.').pop() || '').toLowerCase()
        found.uploading = false
      } else {
        found.error = res.message || '上传失败'
        found.uploading = false
      }
    }
  } catch (e: any) {
    const found = attachedFiles.value.find(a => a.id === attId)
    if (found) {
      found.error = e.message || '上传失败'
      found.uploading = false
    }
  }
}

// ===== M4：粘贴图片（Ctrl+V） =====
/** 粘贴图片的 MIME → 扩展名映射（Files API 支持范围） */
const PASTE_IMAGE_EXT: Record<string, string> = {
  'image/png': 'png',
  'image/jpeg': 'jpg',
  'image/gif': 'gif',
  'image/webp': 'webp'
}

const handlePasteImage = (e: ClipboardEvent) => {
  const items = e.clipboardData?.items
  if (!items) return
  for (const item of Array.from(items)) {
    if (!item.type.startsWith('image/')) continue
    const blob = item.getAsFile()
    if (!blob) continue
    // 命中图片：拦截默认粘贴（避免把二进制塞进文本框），走统一上传
    e.preventDefault()
    const ext = PASTE_IMAGE_EXT[item.type] || 'png'
    const now = new Date()
    const pad = (n: number) => String(n).padStart(2, '0')
    const name = `pasted-${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}.${ext}`
    const file = new File([blob], name, { type: item.type })
    attachLocalFile(file, 'paste')
    return // 只取第一张图片
  }
  // 非图片粘贴不拦截（正常文本粘贴）
}

// ===== M4：跨页引用消费（文件管理页「引用到聊天」→ 附件区） =====
let consumingRefs = false
const tryConsumePendingRefs = async () => {
  if (consumingRefs) return
  const refs = fileRefStore.consumeRefs()
  if (refs.length === 0) return
  consumingRefs = true
  try {
    let added = 0
    for (const r of refs) {
      // 去重：附件区已有同一 fileAssetId 则跳过
      if (attachedFiles.value.some(a => a.fileAssetId === r.id)) continue
      // M7：按 MIME 区分图片/文档（文档显示文件图标，发送后 LLM 用 chat_attachment 读取）
      const isImg = isImageMime(r.mimeType)
      const attFile: AttachedFile = {
        id: `ref-${r.id}-${Date.now()}`,
        attachmentId: '',
        fileName: r.filename,
        size: r.size || 0,
        type: isImg ? 'image' : docTypeOf(r.filename, r.mimeType),
        extension: (r.filename.split('.').pop() || '').toLowerCase(),
        uploading: false,
        kind: isImg ? 'image' : 'doc',
        fileAssetId: r.id
      }
      attachedFiles.value.push(attFile)
      added++
      if (isImg) {
        // 异步加载缩略图（blob 缓存 URL，previewOwned=false 不 revoke；文档不需要）
        getFilePreviewBlobUrl(r.id).then(url => {
          const found = attachedFiles.value.find(a => a.id === attFile.id)
          if (found) found.previewUrl = url
        }).catch(e => console.warn('引用缩略图加载失败:', r.id, e))
      }
    }
    if (added > 0) {
      message.success(`已引用 ${added} 个文件`)
    }
  } finally {
    consumingRefs = false
  }
}

// ===== M4 优化：跨页引用目标（文件管理页「引用到聊天」可指定目标会话/新聊天） =====
// 路由协议：/code-assistant?refConv=<会话ID>&refAgent=<Agent配置ID>（引用到已有会话）；
//          /code-assistant?refNew=1（引用到新聊天）。
// 消费时机：fetchConversations 完成后优先应用（见 fetchConversations）；应用后清 URL query，避免刷新重复触发。
type RefNavTarget = { type: 'conv'; id: string; agentId?: number } | { type: 'new' }
const pendingRefTarget = ref<RefNavTarget | null>(null)

/** 从路由 query 解析引用目标（组件挂载时执行一次） */
const readRefTargetFromQuery = () => {
  const q = route.query
  const refConv = typeof q.refConv === 'string' ? q.refConv : ''
  const refNew = typeof q.refNew === 'string' ? q.refNew : ''
  const refAgent = typeof q.refAgent === 'string' ? parseInt(q.refAgent) : NaN
  if (refConv) {
    pendingRefTarget.value = { type: 'conv', id: refConv, agentId: isNaN(refAgent) ? undefined : refAgent }
  } else if (refNew === '1') {
    pendingRefTarget.value = { type: 'new' }
  }
}
readRefTargetFromQuery()

/** 应用完成后清理目标 + 清除 URL query（避免刷新重复触发） */
const clearPendingRefTarget = () => {
  pendingRefTarget.value = null
  if (route.query.refConv || route.query.refNew || route.query.refAgent) {
    router.replace({ path: '/code-assistant' })
  }
}

/** 应用引用目标：新聊天直接新建；指定会话优先在当前列表定位；跨 Agent 时先切换 Agent（切换后 fetchConversations 会再次应用） */
const applyPendingRefTarget = () => {
  const t = pendingRefTarget.value
  if (!t) return
  if (t.type === 'new') {
    startNewChat()
    clearPendingRefTarget()
    return
  }
  const found = conversations.value.find(c => c.id === t.id)
  if (found) {
    selectConversation(t.id)
    clearPendingRefTarget()
    return
  }
  if (t.agentId && t.agentId !== currentAgentConfigId.value) {
    const ok = agentSelectorRef.value?.switchToAgentById?.(t.agentId)
    if (ok) return
  }
  message.warning('未找到目标会话，可能已被删除或不属于当前智能体')
  clearPendingRefTarget()
}

// M4：引用消费时机——① 组件挂载（跨页跳转重新进入）；② 路由切回本页（keep-alive 场景兜底）
onMounted(() => {
  tryConsumePendingRefs()
})
watch(() => route.path, (p) => {
  if (p === '/code-assistant') tryConsumePendingRefs()
})

const removeAttachment = (id: string) => {
  const target = attachedFiles.value.find(a => a.id === id)
  // 仅释放本组件创建的 objectURL（引用/历史回显的缓存 URL 由 file-asset 模块统一管理）
  if (target?.previewOwned && target.previewUrl) {
    URL.revokeObjectURL(target.previewUrl)
  }
  attachedFiles.value = attachedFiles.value.filter(a => a.id !== id)
}

const getAttachmentIcon = (type: string) => {
  return {
    pdf: '📕',
    word: '📘',
    excel: '📊',
    image: '🖼️',
    text: '📄'
  }[type] || '📎'
}

const formatFileSize = (bytes: number): string => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

// ===== 编译/运行/控制台 =====
const isBuilding = ref(false)
const isServiceRunning = ref(false)
const consoleLines = ref<string[]>([])
const runElapsed = ref(0)
let consolePollTimer: ReturnType<typeof setInterval> | null = null
let runElapsedTimer: ReturnType<typeof setInterval> | null = null

const handleBuild = async () => {
  const projectRoot = agentRuntime.value.workDir
  if (!projectRoot) {
    message.warning('请先设置工作目录')
    return
  }
  isBuilding.value = true
  showBuildRunLog.value = true
  consoleLines.value = []
  try {
    const result = await buildProject(projectRoot)
    const lines = result.output.split('\n')
    consoleLines.value = lines
    if (result.success) {
      message.success(`编译成功 (耗时 ${(result.duration / 1000).toFixed(1)}s)`)
    } else {
      message.error(`编译失败 (exitCode=${result.exitCode})`)
    }
  } catch (e: any) {
    consoleLines.value = [`[ERROR] 编译请求失败: ${e.message}`]
    message.error('编译请求失败')
  } finally {
    isBuilding.value = false
  }
}

const handleRun = async () => {
  const projectRoot = agentRuntime.value.workDir
  if (!projectRoot) {
    message.warning('请先设置工作目录')
    return
  }
  showBuildRunLog.value = true
  consoleLines.value = ['正在启动服务...']
  try {
    const result = await runProject(projectRoot)
    consoleLines.value = [result.message]
    if (result.success) {
      isServiceRunning.value = true
      message.success(result.message)
      startConsolePolling()
    } else {
      message.error(result.message)
      // 尝试获取更多输出
      const output = await getRunOutput(20)
      if (output.success && output.lines.length > 0) {
        consoleLines.value = output.lines
      }
    }
  } catch (e: any) {
    consoleLines.value = [`[ERROR] 启动失败: ${e.message}`]
    message.error('启动请求失败')
  }
}

const handleStop = async () => {
  try {
    const result = await stopProject()
    consoleLines.value.push('--- ' + result.message + ' ---')
    isServiceRunning.value = false
    stopConsolePolling()
    runElapsed.value = 0
    message.success(result.message)
  } catch (e: any) {
    message.error('停止请求失败: ' + e.message)
  }
}

const startConsolePolling = () => {
  stopConsolePolling()
  // 轮询输出
  consolePollTimer = setInterval(async () => {
    try {
      const output = await getRunOutput(200)
      if (output.success) {
        consoleLines.value = output.lines
        isServiceRunning.value = output.running
        if (!output.running) {
          stopConsolePolling()
          runElapsed.value = 0
        }
      }
    } catch { /* ignore polling errors */ }
  }, 2000)
  // 计时器
  runElapsed.value = 0
  runElapsedTimer = setInterval(() => {
    if (isServiceRunning.value) {
      runElapsed.value += 1000
    }
  }, 1000)
}

const stopConsolePolling = () => {
  if (consolePollTimer) {
    clearInterval(consolePollTimer)
    consolePollTimer = null
  }
  if (runElapsedTimer) {
    clearInterval(runElapsedTimer)
    runElapsedTimer = null
  }
}

// ===== 运行日志面板 =====
const showBuildRunLog = ref(false)
const logMinimized = ref(false)
const terminalCwd = ref('')  // 用于 displayCwd 显示（跟随 workDir 同步）
// 拖拽调整日志面板高度
const logResizeStartY = ref(0)
const logResizeStartHeight = ref(0)
let logPanelRef: HTMLElement | null = null

// ===== xterm.js 日志终端 =====
const logXtermContainer = ref<HTMLElement | null>(null)
let logXterm: Terminal | null = null
let logFitAddon: FitAddon | null = null
let logLineCount = 0          // 已同步到 xterm 的行数
let logSystemThemeListener: (() => void) | null = null  // matchMedia 监听器引用

const LOG_DARK_THEME = {
  background: '#1e1d2c', foreground: '#d4d4d4', cursor: '#d4d4d4',
  selectionBackground: '#3b3a52',
  black: '#1e1d2c', red: '#f48771', green: '#3fb950', yellow: '#dcdcaa',
  blue: '#569cd6', magenta: '#c586c0', cyan: '#4ec9b0', white: '#d4d4d4',
  brightBlack: '#525070', brightRed: '#f48771', brightGreen: '#3fb950',
  brightYellow: '#dcdcaa', brightBlue: '#569cd6', brightMagenta: '#c586c0',
  brightCyan: '#4ec9b0', brightWhite: '#ffffff',
}
const LOG_LIGHT_THEME = {
  background: '#fafafa', foreground: '#1a1a2e', cursor: '#1a1a2e',
  selectionBackground: '#c4b5fd',
  black: '#fafafa', red: '#d32f2f', green: '#2e7d32', yellow: '#f9a825',
  blue: '#1565c0', magenta: '#7b1fa2', cyan: '#00838f', white: '#1a1a2e',
  brightBlack: '#9e9e9e', brightRed: '#ef5350', brightGreen: '#43a047',
  brightYellow: '#fdd835', brightBlue: '#1e88e5', brightMagenta: '#ab47bc',
  brightCyan: '#00acc1', brightWhite: '#424242',
}

const applyLogXtermTheme = () => {
  if (!logXterm) return
  const mode = settingsStore.getTheme()
  let isDark: boolean
  if (mode === 'auto') {
    isDark = window.matchMedia('(prefers-color-scheme: dark)').matches
  } else {
    isDark = mode === 'dark'
  }
  logXterm.options.theme = isDark ? LOG_DARK_THEME : LOG_LIGHT_THEME
}

const initLogXterm = () => {
  if (!logXtermContainer.value || logXterm) return
  logFitAddon = new FitAddon()
  logXterm = new Terminal({
    cursorBlink: false,
    disableStdin: true,        // 只读日志终端
    fontSize: 12,
    fontFamily: "'SF Mono','Monaco','Fira Code',monospace",
    theme: LOG_DARK_THEME,
    allowProposedApi: true,
    scrollback: 3000,
  })
  logXterm.loadAddon(logFitAddon)
  logXterm.open(logXtermContainer.value)
  logFitAddon.fit()
  applyLogXtermTheme()

  // resize observer
  const ro = new ResizeObserver(() => { try { logFitAddon?.fit() } catch { /* ignore */ } })
  ro.observe(logXtermContainer.value)

  // 主题监听：Pinia settingsStore 变化 + 系统媒体查询变化（auto 模式）
  watch(() => settingsStore.theme, () => applyLogXtermTheme())
  const systemDarkMq = window.matchMedia('(prefers-color-scheme: dark)')
  const sysThemeHandler = () => { if (settingsStore.getTheme() === 'auto') applyLogXtermTheme() }
  systemDarkMq.addEventListener('change', sysThemeHandler)
  logSystemThemeListener = () => systemDarkMq.removeEventListener('change', sysThemeHandler)

  // 同步已有日志行
  logLineCount = 0
  for (const line of consoleLines.value) {
    logXterm.writeln(line)
    logLineCount++
  }
}

const disposeLogXterm = () => {
  logSystemThemeListener?.()
  logSystemThemeListener = null
  logXterm?.dispose()
  logXterm = null
  logFitAddon = null
  logLineCount = 0
}

/** 将 consoleLines 增量同步到 xterm */
const syncConsoleToXterm = () => {
  if (!logXterm) return
  const lines = consoleLines.value
  // 如果行数减少（清屏/替换），先清空再全量重写
  if (lines.length < logLineCount) {
    logXterm.clear()
    logLineCount = 0
  }
  for (let i = logLineCount; i < lines.length; i++) {
    logXterm.writeln(lines[i])
  }
  logLineCount = lines.length
}

/** 清屏：xterm.clear + 重置计数 */
const clearLogXterm = () => {
  logXterm?.clear()
  logLineCount = 0
}

// consoleLines 变化 → 增量同步到 xterm
watch(consoleLines, () => syncConsoleToXterm(), { deep: true, flush: 'post' })

// 日志面板展开时初始化 xterm（懒初始化）
watch(showBuildRunLog, (show) => {
  if (show) nextTick(() => initLogXterm())
})

const displayCwd = computed(() => {
  return terminalCwd.value || agentRuntime.value.workDir || '未设置工作目录'
})

const closeLogTab = () => {
  showBuildRunLog.value = false
  disposeLogXterm()
}

// 确保 terminalCwd 和 settingsStore 跟随 workDir 实时变化
watch(() => agentRuntime.value.workDir, (val) => {
  if (val) {
    terminalCwd.value = val
    settingsStore.projectRoot = val
    fileTreeLoadPath.value = val
  }
}, { immediate: true })

// ===== 日志面板拖拽调整高度 =====
const startLogResize = (e: MouseEvent) => {
  const panel = (e.target as HTMLElement)?.parentElement
  if (!panel) return
  logPanelRef = panel
  logResizeStartY.value = e.clientY
  logResizeStartHeight.value = panel.offsetHeight
  document.addEventListener('mousemove', onLogResize)
  document.addEventListener('mouseup', stopLogResize)
}

const onLogResize = (e: MouseEvent) => {
  const panel = logPanelRef
  if (!panel) return
  const delta = logResizeStartY.value - e.clientY
  const newHeight = logResizeStartHeight.value + delta
  const minH = 80
  const maxH = window.innerHeight * 0.5
  panel.style.height = Math.max(minH, Math.min(maxH, newHeight)) + 'px'
}

const stopLogResize = () => {
  document.removeEventListener('mousemove', onLogResize)
  document.removeEventListener('mouseup', stopLogResize)
}

// 页面离开时停止轮询
onMounted(() => {
  // 加载 Provider 模型列表（用于动态渲染模型选择器）
  fetchAvailableModels()
  // 检查当前是否有运行中的服务
  getRunStatus().then(status => {
    isServiceRunning.value = status.running
    if (status.running) {
      startConsolePolling()
      // 获取已有输出
      getRunOutput(200).then(output => {
        if (output.success) consoleLines.value = output.lines
      })
    }
  })
})

// ===== 标签页系统（Tab 管理） =====

// ===== 标签页系统（Tab 管理） =====

// ===== 标签页系统（Tab 管理） =====
interface EditorTab {
  id: string
  type: 'chat' | 'file' | 'diff' | 'web' | 'image'
  title: string
  filePath?: string
  content?: string
  originalContent?: string
  isDirty?: boolean
  diffContent?: string
  headContent?: string       // Git HEAD 版本（diff 左侧）
  currentContent?: string    // 当前工作区版本（diff 右侧）
  highlightLines?: { line: number; type: 'remove' | 'add'; content?: string }[]  // 行高亮标记
  language?: string
  url?: string               // 'web' 标签：内嵌的网页地址
  imagePath?: string         // 'image' 标签：本地/项目图片路径
  imageBlobUrl?: string      // 'image' 标签：图片的 blob URL（用于 <img> 显示）
  imageLoading?: boolean     // 'image' 标签：是否正在加载
  imageError?: string        // 'image' 标签：加载失败信息
}

// ===== 分屏系统 =====
interface SplitPaneData {
  id: string
  paneTabs: EditorTab[]          // 此面板中的 tabs
  activeTabId: string            // 当前 active tab
}
interface SplitState {
  enabled: boolean               // 是否分屏
  direction: 'h' | 'v'          // h=左右分屏, v=上下分屏
  splitRatio: number             // 主面板占比 (0.25 ~ 0.75)
  primary: SplitPaneData
  secondary: SplitPaneData | null
}
// 拖拽分屏状态
interface SplitDragState {
  dragging: boolean
  tabId: string | null
  startX: number
  startY: number
  currentX: number
  currentY: number
  // 分屏预览区域：'left'|'right'|'top'|'bottom'|null
  dropZone: string | null
}

const splitState = ref<SplitState>({
  enabled: false,
  direction: 'v',
  splitRatio: 0.5,
  primary: { id: 'main', paneTabs: [], activeTabId: 'chat' },
  secondary: null
})
const splitDrag = reactive<SplitDragState>({
  dragging: false,
  tabId: null,
  startX: 0,
  startY: 0,
  currentX: 0,
  currentY: 0,
  dropZone: null
})

// 同步主 tabs 到分屏主面板
const syncPrimaryPane = () => {
  splitState.value.primary.paneTabs = [...tabs.value]
  splitState.value.primary.activeTabId = activeTabId.value
}
// 从所有分屏面板收集 tabs 回主列表
const collectAllTabs = (): EditorTab[] => {
  const all = [...splitState.value.primary.paneTabs]
  if (splitState.value.secondary) {
    for (const t of splitState.value.secondary.paneTabs) {
      if (!all.find(x => x.id === t.id)) all.push(t)
    }
  }
  return all
}

// 当前活跃面板引用（computed）
const activePane = computed(() => {
  if (!splitState.value.enabled) return splitState.value.primary
  // 判断 activeTabId 在哪个面板
  if (splitState.value.primary.paneTabs.find(t => t.id === activeTabId.value)) {
    return splitState.value.primary
  }
  if (splitState.value.secondary?.paneTabs.find(t => t.id === activeTabId.value)) {
    return splitState.value.secondary
  }
  return splitState.value.primary
})

// 主面板显示的 tabs：分屏时用面板自己的，未分屏用全局
const primaryTabs = computed(() => {
  if (splitState.value.enabled) {
    return splitState.value.primary.paneTabs
  }
  return tabs.value
})

// 获取副面板当前 active tab
const getSecondaryActiveTab = (): EditorTab | undefined => {
  const s = splitState.value.secondary
  if (!s) return undefined
  return s.paneTabs.find(t => t.id === s.activeTabId)
}

// ===== 分屏操作 =====

// 向右分屏（按钮触发）
const splitTabRight = (tab: EditorTab) => {
  performSplit(tab, 'h')
}
// 向下分屏（拖拽触发）
const splitTabDown = (tab: EditorTab) => {
  performSplit(tab, 'v')
}

const performSplit = (tab: EditorTab, direction: 'h' | 'v') => {
  if (tab.type === 'chat') return // 聊天标签不能分屏
  if (splitState.value.enabled) return // 已经分屏了

  syncPrimaryPane()
  // 从主面板移除该 tab
  const idx = splitState.value.primary.paneTabs.findIndex(t => t.id === tab.id)
  if (idx === -1) return
  splitState.value.primary.paneTabs.splice(idx, 1)
  // 如果移除的是 active，切换到聊天
  if (splitState.value.primary.activeTabId === tab.id) {
    splitState.value.primary.activeTabId = 'chat'
    activeTabId.value = 'chat'
  }

  // 创建副面板
  splitState.value.secondary = {
    id: 'secondary',
    paneTabs: [tab],
    activeTabId: tab.id
  }
  splitState.value.direction = direction
  splitState.value.splitRatio = 0.5
  splitState.value.enabled = true

  // 更新主 tabs 列表
  tabs.value = collectAllTabs()
}

// 取消分屏（按钮触发）
const unsplitTab = (tab: EditorTab) => {
  if (!splitState.value.enabled || !splitState.value.secondary) return
  // 把副面板的 tabs 移回主面板
  const secondary = splitState.value.secondary
  for (const t of secondary.paneTabs) {
    if (!splitState.value.primary.paneTabs.find(x => x.id === t.id)) {
      splitState.value.primary.paneTabs.push(t)
    }
  }
  splitState.value.secondary = null
  splitState.value.enabled = false
  splitState.value.primary.activeTabId = tab.id
  activeTabId.value = tab.id
  tabs.value = collectAllTabs()
}

// ===== Tab 拖拽分屏 / 合并逻辑 =====

const onTabDragStart = (e: MouseEvent, tab: EditorTab) => {
  if (tab.type === 'chat') return // 聊天标签不能拖拽
  splitDrag.dragging = true
  splitDrag.tabId = tab.id
  splitDrag.startX = e.clientX
  splitDrag.startY = e.clientY
  splitDrag.currentX = e.clientX
  splitDrag.currentY = e.clientY
  splitDrag.dropZone = null
  document.addEventListener('mousemove', onGlobalDragMove)
  document.addEventListener('mouseup', onGlobalDragUp)
  e.preventDefault()
}

/** 获取 split-container 的 DOM rect（用于 dropZone 计算，覆盖整个聊天区域） */
const getSplitContainerRect = (): DOMRect | null => {
  const container = document.querySelector('.split-container') as HTMLElement | null
  if (!container) return null
  return container.getBoundingClientRect()
}

const onGlobalDragMove = (e: MouseEvent) => {
  if (!splitDrag.dragging) return
  splitDrag.currentX = e.clientX
  splitDrag.currentY = e.clientY

  // 用 .split-container 的 rect 计算 dropZone（覆盖 tab-bar + 消息 + 控制台 + 输入框）
  const rect = getSplitContainerRect()
  if (!rect) {
    splitDrag.dropZone = null
    return
  }

  // 只有鼠标在 container 内才计算 dropZone；外部不展示提示
  const inContainer =
    e.clientX >= rect.left && e.clientX <= rect.right &&
    e.clientY >= rect.top && e.clientY <= rect.bottom
  if (!inContainer) {
    splitDrag.dropZone = null
    return
  }

  const xRel = (e.clientX - rect.left) / rect.width
  const yRel = (e.clientY - rect.top) / rect.height

  if (xRel < 0.25) splitDrag.dropZone = 'left'
  else if (xRel > 0.75) splitDrag.dropZone = 'right'
  else if (yRel < 0.25) splitDrag.dropZone = 'top'
  else if (yRel > 0.75) splitDrag.dropZone = 'bottom'
  else splitDrag.dropZone = null
}

const onGlobalDragUp = () => {
  if (!splitDrag.dragging) { resetSplitDrag(); return }

  const tabId = splitDrag.tabId
  const zone = splitDrag.dropZone

  // 有有效 dropZone 时才执行操作
  if (tabId && zone) {
    // ★ 未分屏时用 tabs.value（primary.paneTabs 未同步），已分屏时用 collectAllTabs()
    const allTabs = splitState.value.enabled ? collectAllTabs() : tabs.value
    const tab = allTabs.find(t => t.id === tabId)
    if (tab) {
      if (splitState.value.enabled && splitState.value.secondary) {
        // 已分屏状态：拖拽 → 判断是合并还是移动
        const isFromSecondary = splitState.value.secondary.paneTabs.some(t => t.id === tabId)
        if (isFromSecondary) {
          // 从副面板拖拽到主面板 → 合并该 tab 回主面板
          mergeTabBack(tab)
        }
        // 主面板的 tab 在已分屏时不再次分屏（忽略）
      } else {
        // 未分屏状态：拖拽 → 执行分屏
        const direction = (zone === 'left' || zone === 'right') ? 'h' : 'v'
        performSplit(tab, direction)
      }
    }
  }
  resetSplitDrag()
}

/** 将副面板中的 tab 合并回主面板 */
const mergeTabBack = (tab: EditorTab) => {
  if (!splitState.value.enabled || !splitState.value.secondary) return

  const secondary = splitState.value.secondary
  // 从副面板移除
  const idx = secondary.paneTabs.findIndex(t => t.id === tab.id)
  if (idx === -1) return
  secondary.paneTabs.splice(idx, 1)

  // 加入主面板
  if (!splitState.value.primary.paneTabs.find(t => t.id === tab.id)) {
    splitState.value.primary.paneTabs.push(tab)
  }
  splitState.value.primary.activeTabId = tab.id
  activeTabId.value = tab.id

  // 如果副面板空了，取消分屏
  if (secondary.paneTabs.length === 0) {
    splitState.value.secondary = null
    splitState.value.enabled = false
  }
  tabs.value = collectAllTabs()
}

const resetSplitDrag = () => {
  splitDrag.dragging = false
  splitDrag.tabId = null
  splitDrag.dropZone = null
  document.removeEventListener('mousemove', onGlobalDragMove)
  document.removeEventListener('mouseup', onGlobalDragUp)
}

// 分隔条拖拽调整分屏比例
const onSplitRatioChange = (ratio: number) => {
  splitState.value.splitRatio = ratio
}

const tabs = ref<EditorTab[]>([
  { id: 'chat', type: 'chat', title: '💬 对话' }
])
const activeTabId = ref('chat')

const activeTab = computed(() => tabs.value.find(t => t.id === activeTabId.value) || tabs.value[0])

// 判断是否为绝对路径（Windows盘符或Unix绝对路径）
const isAbsolutePath = (p: string) => /^[a-zA-Z]:[/\\]/.test(p) || p.startsWith('/')

// 规范化路径：反斜杠统一为正斜杠（避免 "C:\a/b" 混合形式；供拼接与去重比较使用）
const normalizeFsPath = (p: string) => p.replace(/\\/g, '/')

// 双击文件树中的文件，打开文件编辑标签
const onFileDblClick = async (path: string, isDirectory: boolean) => {
  if (isDirectory) return
  // 将相对路径转为绝对路径：文件树返回的 path 是相对于 fileTreeLoadPath 的相对路径
  // 直接传给后端 /api/project/read 时，后端用 user.dir 拼接可能路径不对（Electron桌面端 user.dir 是jar目录）
  const resolvedPath = (!isAbsolutePath(path) && fileTreeLoadPath.value)
    ? normalizeFsPath(fileTreeLoadPath.value).replace(/\/+$/, '') + '/' + normalizeFsPath(path)
    : normalizeFsPath(path)
  const existing = tabs.value.find(t => t.type === 'file' && t.filePath === resolvedPath)
  if (existing) {
    activeTabId.value = existing.id
    return
  }
  try {
    const res = await readProjectFile(resolvedPath)
    if (res.code === 200 && res.data !== null) {
      const fileName = path.split('/').pop() || path.split('\\').pop() || path
      const tabId = `file-${resolvedPath}`
      tabs.value.push({
        id: tabId,
        type: 'file',
        title: fileName,
        filePath: resolvedPath,
        content: res.data,
        originalContent: res.data,
        isDirty: false
      })
      activeTabId.value = tabId
      // 分屏模式下同步到主面板
      if (splitState.value.enabled) {
        syncPrimaryPane()
      }
    }
  } catch (e: any) {
    console.error('打开文件失败:', e)
  }
}

// ===== 网页标签页（LLM 输出的链接 → 应用内嵌浏览）=====
// 是否可使用 Electron <webview>（需主进程开启 webviewTag）；否则自动降级为 <iframe>
const canUseWebview = (() => {
  if (typeof window === 'undefined' || !(window as any).electronAPI) return false
  try {
    const el = document.createElement('webview') as any
    return typeof el.getWebContentsId === 'function' || typeof el.loadURL === 'function'
  } catch {
    return false
  }
})()

// 网页标签刷新计数（改变 key 触发重新加载）
const webTabReloadKeys = ref<Record<string, number>>({})

// 打开网页标签（聊天中的链接 / 手动调用）
const openWebTab = (url: string) => {
  if (!/^https?:\/\//i.test(url)) return
  // 已打开相同地址则直接切换
  const existing = tabs.value.find(t => t.type === 'web' && t.url === url)
  if (existing) {
    activeTabId.value = existing.id
    if (splitState.value.enabled) syncPrimaryPane()
    return
  }
  let label = url
  try {
    const u = new URL(url)
    label = u.hostname + (u.pathname && u.pathname !== '/' ? u.pathname : '')
  } catch { /* 保留原始 url */ }
  const tabId = `web-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  const tab: EditorTab = {
    id: tabId,
    type: 'web',
    title: `🌐 ${label}`,
    url
  }
  tabs.value.push(tab)
  activeTabId.value = tabId
  if (splitState.value.enabled) syncPrimaryPane()
}

// 刷新网页标签（重建组件 key 触发重新加载）
const reloadWebTab = (tab?: EditorTab) => {
  if (!tab?.id) return
  webTabReloadKeys.value[tab.id] = (webTabReloadKeys.value[tab.id] || 0) + 1
}

// 用系统默认浏览器打开（Electron 走 IPC）；非 Electron 环境退回 window.open
const openWebExternal = (url?: string) => {
  if (!url) return
  const api = (window as any).electronAPI
  if (api?.openExternal) {
    api.openExternal(url)
  } else {
    window.open(url, '_blank', 'noopener,noreferrer')
  }
}

// 是否为图片文件路径
const isImageFilePath = (p: string) => /\.(png|jpe?g|gif|webp|bmp|svg|ico)$/i.test(p.split(/[?#]/)[0])

// 将"相对路径"转为"绝对路径"（基于当前工作目录；已是绝对路径则统一斜杠后返回）
// 说明：后端 /api/project/read 对相对路径用后端进程 user.dir 拼接（Electron 下是 jar 目录，不可靠），
// 故与文件树双击 / 授权面板一致，统一在前端拼好绝对路径再请求；
// 输出统一为正斜杠（避免 "C:\dir\sub/name.md" 混合形式——显示观感一致、tab 去重可靠，后端亦可正常读取）。
const toAbsolutePath = (path: string): string => {
  const normalized = normalizeFsPath(path)
  if (isAbsolutePath(normalized)) return normalized
  const workDir = agentRuntime.value.workDir
  if (!workDir) return normalized
  return normalizeFsPath(workDir).replace(/\/+$/, '') + '/' + normalized
}

// 读取失败兜底：LLM 常输出省略前缀的短路径（如 api/appearance.ts），
// 调用后端按路径后缀在项目内匹配；返回候选"相对路径"列表（已过滤原始路径自身）
const resolvePathCandidates = async (path: string): Promise<string[]> => {
  const workDir = agentRuntime.value.workDir
  if (!workDir || isAbsolutePath(path)) return []
  try {
    const res = await resolveProjectFilePath(workDir, path)
    if (res.code === 200 && Array.isArray(res.data)) {
      const normalized = normalizeFsPath(path)
      return res.data.filter(c => !!c && normalizeFsPath(c) !== normalized)
    }
  } catch { /* 解析接口异常按无候选处理 */ }
  return []
}

// 文件读取失败后的兜底：
//   ① 尝试把短路径解析为项目内真实文件（唯一命中时关掉失败标签、用完整路径重新打开）；
//   ② 多个候选 / 无候选 → 在原标签内给出明确提示（避免用户看到"空白"不知道原因）。
const handleFileReadFailure = async (failedTab: EditorTab, originalPath: string, failMsg: string) => {
  const candidates = await resolvePathCandidates(originalPath)
  if (candidates.length === 1) {
    closeTab(failedTab.id)
    await openLocalFileTab(candidates[0])
    return
  }
  if (candidates.length > 1) {
    failedTab.content = `// 无法唯一确定文件：${originalPath}\n// 项目内找到多个匹配文件，请改用完整路径或从文件树打开：\n`
      + candidates.map(c => `//   - ${c}`).join('\n')
  } else {
    failedTab.content = `${failMsg}\n// 提示：项目内未找到与「${originalPath}」匹配的文件（可让 AI 输出相对项目根目录的完整路径）`
  }
  failedTab.originalContent = failedTab.content
}

// 打开本地/项目文件标签（文本文件，复用现有 file 标签与编辑器）
const openLocalFileTab = async (path: string) => {
  if (!path) return
  const readPath = toAbsolutePath(path)
  // 去重比较：统一斜杠后比较（同一文件可能以不同斜杠形式出现，避免重复开标签）
  const pathNorm = normalizeFsPath(path)
  const readNorm = normalizeFsPath(readPath)
  const existing = tabs.value.find(t => t.type === 'file' && !!t.filePath
    && (normalizeFsPath(t.filePath) === pathNorm || normalizeFsPath(t.filePath) === readNorm))
  if (existing) {
    activeTabId.value = existing.id
    if (splitState.value.enabled) syncPrimaryPane()
    return
  }
  const fileName = readPath.split(/[/\\]/).pop() || readPath
  const tabId = `file-${readPath}`
  // ★ 必须用 reactive 包装：tab 入列后读文件是异步的（await），之后要回填 content——
  //   若对原始对象直接赋值，不会触发 Vue 响应式更新（现象：标签已打开但内容区空白）
  const tab = reactive<EditorTab>({
    id: tabId,
    type: 'file',
    title: fileName,
    filePath: readPath,
    content: '',
    originalContent: '',
    isDirty: false
  })
  tabs.value.push(tab)
  activeTabId.value = tabId
  if (splitState.value.enabled) syncPrimaryPane()
  try {
    const res = await readProjectFile(readPath)
    if (res.code === 200 && res.data !== null) {
      tab.content = res.data
      tab.originalContent = res.data
      return
    }
    await handleFileReadFailure(tab, path, `// 无法读取文件：${readPath}`)
  } catch (err: any) {
    await handleFileReadFailure(tab, path, `// 读取文件失败：${err?.message || err}`)
  }
}

// 打开图片标签（本地/项目图片，通过后端原始字节接口加载为 blob）
const openImageTab = async (path: string) => {
  if (!path) return
  const readPath = toAbsolutePath(path)
  // 去重比较：统一斜杠后比较（同一图片可能以不同斜杠形式出现）
  const pathNorm = normalizeFsPath(path)
  const readNorm = normalizeFsPath(readPath)
  const existing = tabs.value.find(t => t.type === 'image' && !!t.imagePath
    && (normalizeFsPath(t.imagePath) === pathNorm || normalizeFsPath(t.imagePath) === readNorm))
  if (existing) {
    activeTabId.value = existing.id
    if (splitState.value.enabled) syncPrimaryPane()
    return
  }
  const fileName = readPath.split(/[/\\]/).pop() || readPath
  const tabId = `image-${readPath}`
  // ★ 同 openLocalFileTab：异步回填（blob 加载 / 失败态）必须经响应式对象赋值才会更新 UI
  const tab = reactive<EditorTab>({
    id: tabId,
    type: 'image',
    title: `🖼️ ${fileName}`,
    imagePath: readPath,
    imageBlobUrl: '',
    imageLoading: true,
    imageError: ''
  })
  tabs.value.push(tab)
  activeTabId.value = tabId
  if (splitState.value.enabled) syncPrimaryPane()
  try {
    const blobUrl = await getProjectFileBlobUrl(readPath)
    tab.imageBlobUrl = blobUrl
    tab.imageLoading = false
  } catch (err: any) {
    // 兜底：LLM 输出的短路径尝试在项目内解析，唯一命中则用完整路径重新打开
    const candidates = await resolvePathCandidates(path)
    if (candidates.length === 1) {
      closeTab(tab.id)
      await openImageTab(candidates[0])
      return
    }
    tab.imageLoading = false
    tab.imageError = candidates.length > 1
      ? `找到多个匹配图片，请使用完整路径：${candidates.join('、')}`
      : (err?.message || '图片加载失败')
  }
}

// 重新加载图片标签（释放旧 blob 后重新拉取）
const reloadImageTab = async (tab?: EditorTab) => {
  if (!tab?.imagePath) return
  if (tab.imageBlobUrl) {
    try { URL.revokeObjectURL(tab.imageBlobUrl) } catch { /* 忽略 */ }
  }
  tab.imageBlobUrl = ''
  tab.imageError = ''
  tab.imageLoading = true
  try {
    tab.imageBlobUrl = await getProjectFileBlobUrl(tab.imagePath)
  } catch (err: any) {
    tab.imageError = err?.message || '图片加载失败'
  } finally {
    tab.imageLoading = false
  }
}

// 聊天消息区点击（事件委托）：
//   ① 本地文件/图片（markdown 渲染时挂了 data-cc-path）→ 应用内标签页打开
//   ② http(s) 外链 → 应用内「网页标签页」打开（不再让窗口导航走）
const handleMessageLinkClick = (e: MouseEvent) => {
  const el = e.target as HTMLElement | null
  if (!el) return
  // ① 本地文件/图片
  const localEl = el.closest?.('[data-cc-path]') as HTMLElement | null
  if (localEl) {
    const path = localEl.getAttribute('data-cc-path') || ''
    const kind = localEl.getAttribute('data-cc-kind') || ''
    if (path) {
      e.preventDefault()
      if (kind === 'image' || isImageFilePath(path)) {
        openImageTab(path)
      } else {
        openLocalFileTab(path)
      }
      return
    }
  }
  // ② http(s) 外链
  const anchor = el.closest?.('a') as HTMLAnchorElement | null
  if (!anchor) return
  const href = anchor.getAttribute('href') || ''
  if (!/^https?:\/\//i.test(href)) return
  e.preventDefault()
  openWebTab(href)
}

// 关闭标签
const closeTab = (tabId: string) => {
  const idx = tabs.value.findIndex(t => t.id === tabId)
  if (idx === -1 || tabs.value[idx].type === 'chat') return
  const closingTab = tabs.value[idx]
  // 图片标签：释放 blob URL，避免内存泄漏
  if (closingTab.type === 'image' && closingTab.imageBlobUrl) {
    try { URL.revokeObjectURL(closingTab.imageBlobUrl) } catch { /* 忽略 */ }
  }
  tabs.value.splice(idx, 1)

  // 分屏模式下也同步从面板移除
  if (splitState.value.enabled) {
    const priIdx = splitState.value.primary.paneTabs.findIndex(t => t.id === tabId)
    if (priIdx !== -1) splitState.value.primary.paneTabs.splice(priIdx, 1)
    const secIdx = splitState.value.secondary?.paneTabs.findIndex(t => t.id === tabId)
    if (secIdx !== undefined && secIdx !== -1) {
      splitState.value.secondary!.paneTabs.splice(secIdx, 1)
      // 副面板空了则自动取消分屏
      if (splitState.value.secondary!.paneTabs.length === 0) {
        splitState.value.secondary = null
        splitState.value.enabled = false
        splitState.value.primary.activeTabId = 'chat'
      }
    }
    tabs.value = collectAllTabs()
  }

  if (activeTabId.value === tabId) {
    const allTabs = splitState.value.enabled ? collectAllTabs() : tabs.value
    const closedIdx = allTabs.findIndex(t => t.id === tabId)
    const newIdx = Math.min(closedIdx, allTabs.length - 1)
    activeTabId.value = allTabs[newIdx]?.id || 'chat'
  }
}

// 文件保存后回调
const onFileSaved = (path: string, content: string) => {
  const tab = tabs.value.find(t => t.type === 'file' && t.filePath === path)
  if (tab) {
    tab.originalContent = content
    tab.content = content
    tab.isDirty = false
  }
}

// 文件内容变更回调
const onFileContentChange = (path: string, content: string) => {
  const tab = tabs.value.find(t => t.type === 'file' && t.filePath === path)
  if (tab) {
    tab.content = content
    tab.isDirty = content !== tab.originalContent
  }
}

// Git 文件双击回调
const onGitFileDblClick = (filePath: string, projectRoot: string) => {
  openDiffTab(filePath, projectRoot)
}

// 部分 hunks 恢复后刷新 diff
const onHunksRestored = async (filePath: string) => {
  const projectRoot = agentRuntime.value.workDir
  if (!projectRoot) return
  const tabId = `diff-${filePath}`
  const tab = tabs.value.find(t => t.id === tabId)
  if (!tab) return

  // 构建绝对路径
  const absFilePath = toAbsolutePath(filePath)

  // 重新获取 HEAD 版本 + 工作区文件 + diff
  const [showResult, fileResult, diffResult] = await Promise.all([
    gitShowFile(projectRoot, filePath).catch(() => ({ success: false, content: '' })),
    readProjectFile(absFilePath).catch(() => ({ code: 0, data: null })),
    getGitDiff(projectRoot, filePath).catch(() => ({ success: false, diff: '' }))
  ])

  tab.headContent = showResult.success ? (showResult.content || '') : ''
  tab.currentContent = fileResult.code === 200 && fileResult.data !== null ? fileResult.data : ''
  tab.diffContent = diffResult.diff

  // 如果 diff 为空且两边内容相同 → 关闭 tab
  if (!diffResult.success && tab.headContent === tab.currentContent) {
    closeTab(tabId)
  }
}

// 撤销文件改动
const handleRevertFile = async (filePath: string) => {
  const projectRoot = agentRuntime.value.workDir
  if (!projectRoot) return
  try {
    const result = await gitRestore(projectRoot, filePath)
    if (result.success) {
      // 关闭当前的 diff 标签
      const tabId = `diff-${filePath}`
      closeTab(tabId)
      // 如果有对应的文件编辑标签，也关闭（内容已变化）
      const fileTabId = `file-${filePath}`
      const fileTab = tabs.value.find(t => t.id === fileTabId)
      if (fileTab) closeTab(fileTabId)
    } else {
      console.error('撤销失败:', result.error)
    }
  } catch (e: any) {
    console.error('撤销文件失败:', e)
  }
}

// 打开差异对比标签（Git 双击）
const openDiffTab = async (filePath: string, projectRoot: string) => {
  const tabId = `diff-${filePath}`
  const existing = tabs.value.find(t => t.id === tabId)

  // 构建绝对文件路径（readProjectFile 需要绝对路径；projectRoot 为调用方指定的项目根）
  const isAbsPath = /^[a-zA-Z]:[/\\]/.test(filePath) || filePath.startsWith('/')
  const absFilePath = isAbsPath
    ? normalizeFsPath(filePath)
    : normalizeFsPath(projectRoot).replace(/\/+$/, '') + '/' + normalizeFsPath(filePath)

  try {
    // 并行获取 HEAD 版本内容和当前工作区内容
    const [showResult, fileResult, diffResult] = await Promise.all([
      gitShowFile(projectRoot, filePath).catch(() => ({ success: false, content: '' })),
      readProjectFile(absFilePath).catch(() => ({ code: 0, data: null })),
      getGitDiff(projectRoot, filePath).catch(() => ({ success: false, diff: '' }))
    ])

    const headContent = showResult.success ? (showResult.content || '') : ''
    const currentContent = fileResult.code === 200 && fileResult.data !== null ? fileResult.data : ''

    if (existing) {
      // 已存在的 tab：刷新版本内容
      existing.headContent = headContent
      existing.currentContent = currentContent
      existing.diffContent = diffResult.diff
      existing.title = filePath
      activeTabId.value = tabId
      return
    }

    tabs.value.push({
      id: tabId,
      type: 'diff',
      title: filePath,
      filePath: filePath,
      headContent: headContent,
      currentContent: currentContent,
      diffContent: diffResult.diff
    })
    activeTabId.value = tabId
  } catch (e: any) {
    console.error('获取 diff 失败:', e)
  }
}

const conversations = ref<Conversation[]>([])
const messages = ref<Record<string, ChatMessage[]>>({})
const currentConversationId = ref<string>('')
const inputMessage = ref('')

// ===== Phase 18 P2：发送/流式状态按会话隔离（Map 化）=====
// 原全局单值 isSending 会导致：Agent 有后台任务时切过去锁死输入框、后台任务结束误关当前会话锁。
// 改造：sendingStates 按 conversationId 记录「前台流式」状态（isSending 仅指前台流式）；
//      输入框锁定由 hasRunningTask 统一判断（前台流式 + 后台流 + 重连中 + 活跃任务），
//      2026-08-14 需求变更：有任务在执行的智能体输入框锁定，无任务的智能体输入框可用（原「后台任务不锁输入框」已废弃）。
const sendingStates = reactive(new Map<string, boolean>())
const setSending = (convId: string | null | undefined, val: boolean) => {
  if (!convId) return
  if (val) sendingStates.set(convId, true)
  else sendingStates.delete(convId)
}
/** 当前会话是否正在前台流式（模板 261/268/434/443/457/514/516 直接读；补充需求面板等 UI 仍以「前台流式」为准） */
const isSending = computed(() => sendingStates.get(currentConversationId.value) ?? false)
/**
 * 当前会话是否有活跃流 —— 停止按钮（结束当前任务）显示依据。
 *
 * 覆盖四类运行态（与 hasRunningTask 同源，避免「任务在跑但按钮消失」）：
 * 1) 前台流式（sendingStates）；
 * 2) 切走智能体后转入后台继续跑的流（backgroundStreams）；
 * 3) 切回智能体 / 页面刷新后正在重连恢复的事件流（reconnectingConvIds）；
 * 4) 重连已确认存在、但事件流尚未建立完成的活跃任务（activeTaskMap）。
 *
 * 原实现只判断前两者，导致「切回智能体 → 重连恢复」期间停止按钮不显示，用户无法结束任务。
 */
const hasActiveStream = computed(() => {
  const convId = currentConversationId.value
  if (!convId) return false
  if (sendingStates.get(convId)) return true
  if (backgroundStreams.value.some(s => s.convId === convId)) return true
  if (reconnectingConvIds.has(convId)) return true
  if (activeTaskMap.get(convId)) return true
  return false
})
/**
 * 当前会话是否有任务在执行——输入框锁定依据（2026-08-14 需求修复）
 * 四源判断：前台流式（sendingStates）｜后台流（backgroundStreams，切换 Agent 后继续跑的）｜
 * 重连中（reconnectingConvIds，页面刷新/切回时恢复事件流）｜重连恢复的活跃任务（activeTaskMap）
 * 任一命中即视为「有任务在执行」→ 锁定输入框；全部清空（onStreamComplete/重连 finally 移除）才解锁。
 * 与 isSending 的区别：isSending 仅指前台流式（补充需求面板等 UI 依赖此语义），
 * hasRunningTask 额外覆盖「切走后仍在后台执行 / 重连恢复中」的任务，避免切回智能体时任务还在跑却解锁输入框。
 */
const hasRunningTask = computed(() => {
  const convId = currentConversationId.value
  if (!convId) return false
  if (sendingStates.get(convId)) return true
  if (backgroundStreams.value.some(s => s.convId === convId)) return true
  if (reconnectingConvIds.has(convId)) return true
  if (activeTaskMap.get(convId)) return true
  return false
})

// ===== 任务中断状态（LLM 接口错误停止 → 错误反馈 + 一键继续） =====

/** 任务异常中断信息（按会话维度） */
interface InterruptedTaskInfo {
  title: string
  message: string
  status?: number
  retryable?: boolean
}

/**
 * 「任务中断」状态表：convId → 中断信息。
 * 写入来源：① 实时流收到 task_error 事件（后端把 DeepSeek 错误码转为友好提示下发）；
 * ② 刷新页面/切换会话时 checkAndReconnect 发现最近一次任务 failed（异常停止）。
 * 用途：输入区横幅展示错误详情 + 「继续任务」按钮（一键续跑，无需用户复制上次发言重发）。
 */
const interruptedTasks = ref<Record<string, InterruptedTaskInfo>>({})

/** 当前会话的中断任务（任务运行中不展示——新一轮任务已在进行，旧中断已由用户响应） */
const currentInterruptedTask = computed<InterruptedTaskInfo | null>(() => {
  const cid = currentConversationId.value
  if (!cid || hasRunningTask.value) return null
  return interruptedTasks.value[cid] || null
})

const isOptimizing = ref(false)
const isLoadingConversations = ref(false)
const isLoadingMessages = ref(false)
const scrollerRef = ref<any>(null)
// 本地消息 id 自增序号：保证同一毫秒内创建的多条消息 id 也绝不重复
// （虚拟列表以 id 为 key-field，重复 key 会让两条消息复用同一个视图 → 位置完全重叠）
let localMsgSeq = 0
const collapsed = ref(false)
const sidebarTab = ref<'conversation' | 'agents'>('conversation')

// ===== 虚拟列表高度自愈（修复消息重叠 / 位置错乱）=====
// 背景：DynamicScroller 的 item 高度依赖内部 ResizeObserver 上报；当出现下列情况时，
//   尺寸表会与实际渲染高度不一致，后续消息按旧高度定位 → 内容互相压盖（重叠）/ 位置错乱：
//   1) 内容异步变化：图片/文档缩略图加载、代码高亮、字体就绪；
//   2) 交互改变高度：思考过程展开/收起、工具结果卡片折叠/展开；
//   3) 流式内容在测量时机之间快速增高；
//   4) 列表重建（切换会话）时旧尺寸残留。
// 兜底：直接观测每个 .message-item 的实测高度，与 scroller 记录的尺寸比对，
//   不一致就节流触发一次 forceUpdate（让 DynamicScroller 重新测量并重排位置）。
let sizeSelfHealTimer: number | null = null
const scheduleScrollerSelfHeal = () => {
  if (sizeSelfHealTimer !== null) return
  sizeSelfHealTimer = window.setTimeout(() => {
    sizeSelfHealTimer = null
    scrollerRef.value?.forceUpdate?.()
  }, 120)
}

const scrollerSizeObserver: ResizeObserver | null = typeof ResizeObserver !== 'undefined'
  ? new ResizeObserver((entries) => {
      const scroller = scrollerRef.value
      if (!scroller?.getItemSize) return
      for (const entry of entries) {
        const el = entry.target as HTMLElement & { __ccMsgId?: string }
        const id = el.__ccMsgId
        if (!id) continue
        // borderBoxSize 可能因尺寸过大被拆分为多段，逐段累加得到真实高度
        let measured = 0
        const raw: any = entry.borderBoxSize as any
        if (Array.isArray(raw) && raw.length > 0) {
          for (const seg of raw) measured += (seg?.blockSize as number) || 0
        } else if (raw && typeof raw === 'object' && 'blockSize' in raw) {
          measured = (raw.blockSize as number) || 0
        }
        if (!measured) measured = el.offsetHeight
        if (!measured) continue
        const recorded = scroller.getItemSize({ id })
        // 差异超过 1.5px 才认为尺寸表失真（避免亚像素抖动反复重测）
        if (Math.abs((recorded || 0) - measured) > 1.5) {
          scheduleScrollerSelfHeal()
          break
        }
      }
    })
  : null

/** 指令：把消息 id 绑到 DOM，并纳入高度观测（虚拟列表尺寸兜底） */
const vTrackMsgSize = {
  mounted(el: HTMLElement & { __ccMsgId?: string }, binding: { value?: string | number }) {
    el.__ccMsgId = binding.value === undefined || binding.value === null ? '' : String(binding.value)
    scrollerSizeObserver?.observe(el)
  },
  updated(el: HTMLElement & { __ccMsgId?: string }, binding: { value?: string | number }) {
    el.__ccMsgId = binding.value === undefined || binding.value === null ? '' : String(binding.value)
  },
  unmounted(el: HTMLElement & { __ccMsgId?: string }) {
    scrollerSizeObserver?.unobserve(el)
    el.__ccMsgId = undefined
  },
}


// ===== 多Agent后台流式 =====
interface BackgroundStream {
  convId: string
  agentConfigId: number
  abortController: AbortController
  /** 后端返回的真实会话ID（用于取消后端任务） */
  realSessionId?: number
}
interface AgentSnapshotData {
  conversations: Conversation[]
  currentConversationId: string
}
/** 切换到后台继续运行的流式列表（切换Agent时不中止） */
const backgroundStreams = ref<BackgroundStream[]>([])
/** 各Agent的会话快照（用于切换Agent时保存和恢复conversations列表） */
const agentSnapshots = ref<Record<number, AgentSnapshotData>>({})
/** 当前正在前台运行的流式信息（直接关联 stopAbortController，用于停止时取消后端任务） */
const activeStreamInfo = ref<{ convId: string; realSessionId?: number } | null>(null)
/** 当前流式的真实会话ID（后端在第一个SSE事件中返回），stopStreaming直接读取 */
const currentStreamSessionId = ref<number | null>(null)

// 会话标题编辑相关状态
const editingConvId = ref<string>('')
const editingConvTitle = ref('')
const isSavingConvTitle = ref(false)

// 开始编辑会话标题
const startEditTitle = (conv: Conversation, event: Event) => {
  event.stopPropagation()
  editingConvId.value = conv.id
  editingConvTitle.value = conv.title
}

// 取消编辑会话标题
const cancelEditTitle = () => {
  editingConvId.value = ''
  editingConvTitle.value = ''
}

// 保存编辑会话标题
const saveEditTitle = async (conv: Conversation) => {
  const name = editingConvTitle.value.trim()
  if (!name) {
    message.warning('名称不能为空')
    return
  }
  isSavingConvTitle.value = true
  try {
    const id = parseInt(conv.id)
    if (!isNaN(id)) {
      await updateConversationName(id, name)
      conv.title = name
      message.success('会话名称已更新')
    }
    editingConvId.value = ''
    editingConvTitle.value = ''
  } catch (error: any) {
    console.error('更新会话名称失败:', error)
    message.error(error.message || '更新会话名称失败')
  } finally {
    isSavingConvTitle.value = false
  }
}

/** 子Agent状态列表（由 SSE agent_event 动态更新） */
const agentInfos = ref<AgentInfo[]>([])
const stopAbortController = ref<AbortController | null>(null)
// 当用户点击「应用」时更新此值，触发文件树加载
const fileTreeLoadPath = ref('')
const showDirBrowser = ref(false)
// ===== Phase 18 P3：待审批问题按会话隔离（Map 化）=====
// 原全局单值 pendingQuestion 会导致：Agent1 后台要授权时弹窗覆盖 Agent2 的弹窗（误授权风险）。
// 改造：pendingQuestions 按 conversationId 存储，弹窗只显示「当前会话」的待审批问题；
//      后台 Agent 的授权请求保留在它自己会话的 map 里，切回时自动弹窗，且可做角标提示。
interface PendingQuestion {
  uuid: string
  question: string
  askType?: string
  toolName?: string
  filePath?: string
  fullDetail?: string
  /** P3 新增：归属 Agent 名（防误授权——弹窗展示"来自谁"） */
  agentName?: string
  /** P3 新增：归属会话工作目录（授权文件联动按此解析，不再错用当前 Agent 目录） */
  workDir?: string
}
const pendingQuestions = reactive(new Map<string, PendingQuestion>())
const setPendingQuestion = (convId: string | null | undefined, q: PendingQuestion | null) => {
  if (!convId) return
  if (q) pendingQuestions.set(convId, q)
  else pendingQuestions.delete(convId)
}
/** 当前会话的待审批问题（模板直接读；切会话自动切换显示对应会话的授权请求） */
const pendingQuestion = computed(() => pendingQuestions.get(currentConversationId.value) ?? null)
/** P3：非当前会话的待审批问题数量（后台授权等待角标） */
const pendingQuestionCount = computed(() => {
  const cur = currentConversationId.value
  let count = 0
  for (const [convId, q] of pendingQuestions) {
    if (convId !== cur && q) count++
  }
  return count
})
const pendingQuestionAnswer = ref('')
const pendingShowCustomInput = ref(false)
/** 任务迭代确认面板提交中（防连点；与权限面板的 permissionLock 独立） */
const iterationConfirmSending = ref(false)
// P3：切换会话时清空答案输入与自定义输入展开态（避免把上个会话的答案带到新会话）
watch(currentConversationId, () => {
  pendingQuestionAnswer.value = ''
  pendingShowCustomInput.value = false
})
// 补充需求状态
const showSupplementInput = ref(false)
const supplementMessage = ref('')
const supplementSending = ref(false)
const supplementInputRef = ref<InstanceType<typeof HTMLInputElement> | null>(null)
// ===== Phase 18 P2：流式状态按会话隔离（Map 化）=====
// 原全局单值 streamStatus 会导致：后台流的「执行工具中...」覆盖前台会话的状态文字。
const streamStatusMap = reactive(new Map<string, string>())
const setStreamStatus = (convId: string | null | undefined, val: string) => {
  if (!convId) return
  if (val) streamStatusMap.set(convId, val)
  else streamStatusMap.delete(convId)
}
/** 当前会话的流式状态文字（模板 261/263 直接读） */
const streamStatus = computed(() => streamStatusMap.get(currentConversationId.value) ?? '')
const taskStartTime = ref<number | null>(null)
const elapsedTime = ref(0)
let elapsedTimer: ReturnType<typeof setInterval> | null = null

const startElapsedTimer = () => {
  stopElapsedTimer()
  taskStartTime.value = Date.now()
  elapsedTime.value = 0
  elapsedTimer = setInterval(() => {
    if (taskStartTime.value) {
      elapsedTime.value = Date.now() - taskStartTime.value
    }
  }, 1000)
}

const stopElapsedTimer = () => {
  if (elapsedTimer !== null) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
  taskStartTime.value = null
}

const formatElapsed = (ms: number): string => {
  const totalSec = Math.floor(ms / 1000)
  const hours = Math.floor(totalSec / 3600)
  const minutes = Math.floor((totalSec % 3600) / 60)
  const secs = totalSec % 60
  if (hours > 0) return `${hours}h ${minutes}m ${secs}s`
  if (minutes > 0) return `${minutes}m ${secs}s`
  return `${secs}s`
}


// 任务清单状态（从 task_manager 工具调用结果中解析）
interface TaskItem {
  id: string
  description: string
  status: 'pending' | 'completed' | 'executing'
  priority?: 'HIGH' | 'MEDIUM' | 'LOW'
  depends_on?: string[]
}
// ===== Phase 18 P2：任务清单按会话隔离（Map 化）=====
// 原全局单值 taskItems 会导致：不同 Agent 的 T1/T2 按编号互相覆盖、后台流事件污染当前看板。
const taskItemsMap = reactive(new Map<string, TaskItem[]>())
const getTaskItems = (convId: string | null | undefined): TaskItem[] => {
  if (!convId) return []
  return taskItemsMap.get(convId) ?? []
}
const setTaskItems = (convId: string | null | undefined, items: TaskItem[]) => {
  if (!convId) return
  taskItemsMap.set(convId, items)
}
/** 当前会话的任务清单（模板 519/520/598/601/605 直接读） */
const taskItems = computed(() => getTaskItems(currentConversationId.value))
const completedTaskCount = computed(() => taskItems.value.filter(t => t.status === 'completed').length)
const showTaskDropdown = ref(false)
const showMoreSettings = ref(false)
// 「执行中」的任务：当 isSending 或 activeTask 时，第一个 pending 任务视为 executing（均按当前会话计算）
const executingTaskId = computed(() => {
  if (!isSending.value && !activeTask.value) return null
  const first = taskItems.value.find(t => t.status === 'pending')
  return first?.id ?? null
})
// 切换任务下拉
const toggleTaskDropdown = () => {
  if (taskItems.value.length > 0) {
    showChangesPanel.value = false
    showTaskDropdown.value = !showTaskDropdown.value
  }
}
// 清除任务清单（新消息或刷新页面时调用——只清当前会话，不影响其他 Agent 的看板）
const clearTaskList = () => {
  if (currentConversationId.value) taskItemsMap.delete(currentConversationId.value)
}
// ===== Phase 18 P2：活跃任务按会话隔离（Map 化）=====
interface ActiveTaskInfo {
  taskId: number
  status: string
  iteration: number
  eventCount: number
  pendingQuestionUuid?: string
  pendingQuestionText?: string
  seq?: number
}
const activeTaskMap = reactive(new Map<string, ActiveTaskInfo>())
const setActiveTask = (convId: string | null | undefined, task: ActiveTaskInfo | null) => {
  if (!convId) return
  if (task) activeTaskMap.set(convId, task)
  else activeTaskMap.delete(convId)
}
/** 当前会话的活跃任务（重连状态，模板/逻辑按当前会话读取） */
const activeTask = computed(() => activeTaskMap.get(currentConversationId.value) ?? null)
// ===== Phase 18 P2 M1：会话状态统一清理（防 Map 内存泄漏 + 会话ID复用污染）=====
// 删除会话、后台任务结束时调用；否则本地会话频繁创建/删除会让 4 个 Map 无限增长，
// 且被删除会话的 ID 复用后残留状态（旧任务看板/状态文字）会在新会话里重新显示
const clearConvState = (convId: string | null | undefined) => {
  if (!convId) return
  sendingStates.delete(convId)
  streamStatusMap.delete(convId)
  taskItemsMap.delete(convId)
  activeTaskMap.delete(convId)
  pendingQuestions.delete(convId)  // P3：待审批问题随会话删除一并清理
}

// Markdown 渲染缓存（避免历史消息重复解析 markdown，性能优化）
// key=content原文, value=渲染后的HTML，最大缓存200条
const markdownCache = new Map<string, string>()
// thinking 段列表缓存（key 含折叠状态签名）。缓存「带 cardAt 的段列表」而非合并单段 HTML——
// 修复：合并单段会丢失 cardAt，导致静态消息（历史/已结束）的工具卡片点击无法定位
const thinkingCache = new Map<string, ThinkSegment[]>()

// ===== M1a 分段渲染（2026-09-15 性能改造）=====
// 段级缓存：稳定段（块级片段）文本 → 渲染 HTML；段文本不变则永久命中。
// —— 流式期间消息内容持续增长，稳定段缓存命中后仅尾段增量解析，单次成本 O(尾段)。
const segmentCache = new Map<string, string>()
const SEGMENT_CACHE_LIMIT = 800

/**
 * 查找最后一个"块级安全切点"（切点之前为稳定前缀，之后为尾段）。
 * 规则：以 \n\n 为候选边界；以下情况不切（保守优先，防格式错乱）：
 *  1) 候选边界处于未闭合的围栏代码块（```）内部；
 *  2) 边界下一行以空格/Tab 开头（疑似缩进续行，避免改变块语义）。
 * 实现为纯字符扫描（无 markdown 解析），成本远低于全量渲染。
 * @returns 切点索引；无安全切点返回 -1
 */
const findStablePrefixEnd = (text: string): number => {
  // 快速路径：全文无三反引号时，直接取最后一个合格的 \n\n
  if (text.indexOf('```') === -1) {
    let g = text.lastIndexOf('\n\n')
    while (g !== -1) {
      const c = text.charCodeAt(g + 2)
      if (c !== 32 && c !== 9) return g + 2
      g = text.lastIndexOf('\n\n', g - 1)
    }
    return -1
  }
  // 一般路径：线性配对扫描（围栏感知）
  let lastSafe = -1
  let pos = 0
  let inFence = false
  const len = text.length
  while (pos < len) {
    if (inFence) {
      // 查找行首 ```（闭合）；跳过行中出现的三反引号
      let close = text.indexOf('```', pos)
      while (close !== -1 && close > 0 && text[close - 1] !== '\n') {
        close = text.indexOf('```', close + 3)
      }
      if (close === -1) break // 围栏未闭合：其后内容全部视为尾段
      inFence = false
      pos = close + 3
    } else {
      // 查找下一个行首 ```（开围栏）与下一个 \n\n（候选切点），取更近者
      let fence = text.indexOf('```', pos)
      while (fence !== -1 && fence > 0 && text[fence - 1] !== '\n') {
        fence = text.indexOf('```', fence + 3)
      }
      const gap = text.indexOf('\n\n', pos)
      if (gap === -1 && fence === -1) break
      if (gap !== -1 && (fence === -1 || gap < fence)) {
        const c = text.charCodeAt(gap + 2)
        if (c !== 32 && c !== 9) lastSafe = gap + 2
        pos = gap + 2
      } else {
        inFence = true
        pos = fence + 3
      }
    }
  }
  return lastSafe
}

/** 渲染单个稳定段（带段级缓存；段文本不变则永久命中） */
const renderSegmentCached = (segment: string): string => {
  if (!segment) return ''
  const hit = segmentCache.get(segment)
  if (hit !== undefined) return hit
  const html = renderMarkdown(segment)
  if (segmentCache.size >= SEGMENT_CACHE_LIMIT) {
    const firstKey = segmentCache.keys().next().value
    if (firstKey !== undefined) segmentCache.delete(firstKey)
  }
  segmentCache.set(segment, html)
  return html
}

const toggleCollapsed = () => { collapsed.value = !collapsed.value }

// 文件树选择
const onFileSelect = (_path: string, _isDirectory: boolean) => {
  // 可以后续扩展为预览文件内容等
}

// 会话相关
// 注意：虚拟列表以消息 id 作为唯一 key（key-field="id"）。一旦出现重复 id，
// vue-virtual-scroller 会把两条消息复用同一个视图/尺寸槽位 → 两条消息位置完全重叠。
// 这里做渲染层兜底：发现重复 id 时仅在本次返回值中重命名（不改动源数据），确保 key 唯一。
const currentMessages = computed<ChatMessage[]>(() => {
  const arr = messages.value[currentConversationId.value] || []
  if (arr.length < 2) return arr
  let duplicated = false
  const seenIds = new Set<string>()
  for (const m of arr) {
    if (!m || !m.id) continue
    if (seenIds.has(m.id)) {
      duplicated = true
      break
    }
    seenIds.add(m.id)
  }
  if (!duplicated) return arr
  const used = new Set<string>()
  return arr.map((m) => {
    if (!m || !m.id) return m
    let id = m.id
    if (used.has(id)) {
      let n = 1
      while (used.has(`${id}#${n}`)) n++
      id = `${id}#${n}`
    }
    used.add(id)
    return id === m.id ? m : { ...m, id }
  })
})

const totalContextTokens = computed(() => {
  const msgs = currentMessages.value
  if (msgs.length === 0) return 0

  // full 模式：直接累加所有消息的 tokenCount
  if (settingsStore.contextMode !== 'compact') {
    return msgs.reduce((sum, msg) => sum + (msg.tokenCount || 0), 0)
  }

  // compact 模式：找到最后一条 user 消息作为"本轮对话"起点
  let lastUserIdx = -1
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i].role === 'user') {
      lastUserIdx = i
      break
    }
  }

  let total = 0
  for (let i = 0; i < msgs.length; i++) {
    const msg = msgs[i]
    if (i >= lastUserIdx) {
      // 本轮对话：完整计入
      total += msg.tokenCount || 0
    } else {
      // 非本轮对话：应用精简折扣
      if (msg.role === 'assistant') {
        // assistant 消息：reasoning 已移除，tokenCount 中 reasoning 部分去掉
        // 粗略按 content 占比 20% 估算（reasoning 通常占大头）
        total += Math.round((msg.tokenCount || 0) * 0.2)
      } else if (msg.role === 'tool' || (msg as any).role === 'tool') {
        // tool 消息：被精简为 ~30 token 的摘要
        total += 30
      } else {
        // user/system 消息：完整保留
        total += msg.tokenCount || 0
      }
    }
  }
  return total
})

const SAVED_CONV_KEY = 'code_assistant_last_conv_id'

const saveConversationId = (id: string) => {
  try { localStorage.setItem(SAVED_CONV_KEY, id) } catch (_) {}
}
const loadConversationId = (): string | null => {
  try { return localStorage.getItem(SAVED_CONV_KEY) } catch (_) { return null }
}
const clearSavedConversationId = () => {
  try { localStorage.removeItem(SAVED_CONV_KEY) } catch (_) {}
}

const fetchConversations = async () => {
  isLoadingConversations.value = true
  try {
    const response = await getConversationList(undefined, currentAgentConfigId.value || undefined)
    if (response.code === 200 && response.data) {
      conversations.value = response.data.map(mapConversationResponseToConversation)
      // M4 优化：优先应用跨页引用目标（文件管理页「引用到聊天」指定会话/新聊天）
      if (pendingRefTarget.value) {
        applyPendingRefTarget()
        return
      }
      // 优先恢复上次查看的会话
      if (!currentConversationId.value && conversations.value.length > 0) {
        const savedId = loadConversationId()
        const savedConv = savedId ? conversations.value.find(c => c.id === savedId) : null
        if (savedConv) {
          selectConversation(savedConv.id)
        } else {
          const firstReal = conversations.value.find(c => !c.isLocal)
          if (firstReal) {
            selectConversation(firstReal.id)
          }
        }
      }
    }
  } catch (error) {
    console.error('获取会话列表失败:', error)
  } finally {
    isLoadingConversations.value = false
  }
}

// ===== 聊天记录懒加载（按轮次分页）=====
// 一轮 = 一次用户提问 + 其后该轮的全部回复。
// 改造原因：超长会话一次性全量查询会让 DB 扫描、网络传输、前端 markdown 分组渲染全部变重，打开会话明显卡顿。
// 现策略：首屏只取最近 MESSAGE_PAGE_ROUNDS 轮，用户向上滚动到顶部时用 beforeId 游标继续加载更早的同样轮数。
const MESSAGE_PAGE_ROUNDS = 5

/** 各会话是否还有更早的历史消息（false / undefined 时不再触发上滑加载） */
const hasMoreMessages = ref<Record<string, boolean>>({})
/** 各会话的分页游标：本页最早一条 DB 消息 id（继续往前翻时作为 beforeId） */
const messageCursors = ref<Record<string, number | null>>({})
/**
 * 正在加载更早历史的会话集合（按会话隔离）。
 * 原来用全局 boolean：会话 A 的请求未完成时切到会话 B，B 的顶部加载会被 A 的 loading 直接拦掉，
 * 表现为「某些会话上滑一直不加载历史」；顶部提示也会串到 B 会话上。
 */
const loadingMoreConvIds = reactive(new Set<string>())
/** 当前会话是否正在加载更早历史（顶部提示显示依据） */
const loadingMoreMessages = computed(() => loadingMoreConvIds.has(currentConversationId.value))
/** 最近一次「头部插入历史消息」的时间戳：用于让自动贴底跳过这一次 length 变化（见下方 watch） */
let lastHistoryPrependAt = 0

/**
 * 头部插入新内容后维持视口位置：虚拟列表的高度是异步测量的，需要多次校正 scrollTop。
 *
 * 要点（修复位置漂移）：
 * 1) 必须双向补偿。虚拟列表对尚未测量的新项先按 min-item-size 估算，随后真实测量可能更小 →
 *    总高度「先增后减」。原实现只补偿升高（delta > 0），高度回落的差值不会补回，
 *    于是一屏历史加载完成后用户正在看的内容会整体上移/下移一段（表现为「位置错乱」）。
 * 2) 若用户在此期间自己滚动了列表，则立即停止补偿，避免与用户操作打架。
 * 3) 多次校正（rAF×2 + 120ms + 320ms）吸收虚拟列表分批测量的高度变化。
 */
const preserveScrollAfterPrepend = (el: HTMLElement, prevHeight: number) => {
  let lastHeight = prevHeight
  let expectedScrollTop = el.scrollTop
  const adjust = () => {
    // 用户已手动滚动（与上次补偿后的位置偏差过大）→ 放弃补偿
    if (Math.abs(el.scrollTop - expectedScrollTop) > 48) return
    const h = el.scrollHeight
    const delta = h - lastHeight
    if (delta !== 0) {
      el.scrollTop = Math.max(0, el.scrollTop + delta)
      lastHeight = h
      expectedScrollTop = el.scrollTop
    }
  }
  requestAnimationFrame(() => {
    adjust()
    requestAnimationFrame(adjust)
  })
  window.setTimeout(adjust, 120)
  window.setTimeout(adjust, 320)
}

const fetchMessages = async (conversationId: string, force = false) => {
  if (!conversationId) return
  if (!force && messages.value[conversationId]) return // 已缓存（首次加载免重复请求）
  isLoadingMessages.value = true
  try {
    // 分页接口：只取最近 MESSAGE_PAGE_ROUNDS 轮（从最新往前翻）
    const response = await getConversationMessagesPage(parseInt(conversationId), null, MESSAGE_PAGE_ROUNDS)
    if (response.code === 200 && response.data) {
      const groups = processMessageGroups(response.data.messages || [])
      messages.value[conversationId] = groups as ChatMessage[]
      // 记录分页状态（是否还有更早历史 + 下一页游标）
      hasMoreMessages.value[conversationId] = !!response.data.hasMore
      messageCursors.value[conversationId] = response.data.nextBeforeId ?? null
      // 历史消息加载后，匹配回滚快照
      matchSnapshotsForMessages(conversationId)
      // M4：历史图片回显（按 messageId 映射 file_reference）
      loadMessageFileRefs(conversationId)
      // 加载会话改动统计
      loadSessionChanges(conversationId)
      // 首屏内容若不足一屏（无法通过滚动触顶），自动补加载更早历史
      maybeAutoFillHistory(conversationId)
    }
  } catch (error) {
    console.error('获取消息失败:', error)
  } finally {
    isLoadingMessages.value = false
  }
}

/**
 * 加载更早的历史消息（滚动到顶部时触发）
 *
 * 设计要点：
 * 1) 用 beforeId 游标而非 offset：新消息持续写入时不会出现分页错位/漏消息；
 * 2) 合并时按消息 id 去重：重复触发或并发请求都安全；
 * 3) 插入到数组头部后补偿 scrollTop：让用户当前阅读位置保持不动（虚拟列表高度异步测量，多次校正）。
 */
const loadMoreMessages = async (conversationId: string) => {
  // 防重入按会话判定：别的会话正在加载不应阻塞本会话的顶部加载
  if (!conversationId || loadingMoreConvIds.has(conversationId)) return
  if (!hasMoreMessages.value[conversationId]) return
  const cursor = messageCursors.value[conversationId]
  if (cursor === undefined || cursor === null) return
  const existing = messages.value[conversationId]
  if (!existing || existing.length === 0) return

  const el = scrollerRef.value?.$el as HTMLElement | undefined
  const prevHeight = el ? el.scrollHeight : 0

  loadingMoreConvIds.add(conversationId)
  try {
    const res = await getConversationMessagesPage(parseInt(conversationId), cursor, MESSAGE_PAGE_ROUNDS)
    if (res.code !== 200 || !res.data) return
    // 请求期间会话可能被切换/清空，重新取一次当前数组
    const current = messages.value[conversationId]
    if (!current) return

    const olderGroups = processMessageGroups(res.data.messages || []) as ChatMessage[]
    const existingIds = new Set(current.map(m => m.id))
    const toAdd = olderGroups.filter(m => !existingIds.has(m.id))

    // 分页状态始终更新：即使是空页也要落 hasMore=false，避免反复空请求
    hasMoreMessages.value[conversationId] = !!res.data.hasMore
    messageCursors.value[conversationId] = res.data.nextBeforeId ?? null
    if (toAdd.length === 0) {
      // 本页没有可合并的新消息 → 视为到底，避免与「内容不足一屏自动补加载」互相触发形成死循环
      hasMoreMessages.value[conversationId] = false
      return
    }

    // 标记「本次 length 变化来自头部插入」，让自动贴底 watch 跳过本次
    lastHistoryPrependAt = Date.now()
    messages.value[conversationId] = [...toAdd, ...current]
    // 新加载消息的附件/快照回显补齐（内部按 messageId 匹配，重复调用幂等）
    matchSnapshotsForMessages(conversationId)
    loadMessageFileRefs(conversationId)
    // 维持视口：把「新增内容撑高的高度」补进 scrollTop（双向补偿，见函数注释）
    if (el && prevHeight > 0) preserveScrollAfterPrepend(el, prevHeight)
    // 若 5 轮内容仍不足一屏（超短对话），继续补加载，否则用户永远无法通过滚动触顶
    maybeAutoFillHistory(conversationId)
  } catch (e) {
    console.warn('加载更早聊天记录失败:', e)
  } finally {
    loadingMoreConvIds.delete(conversationId)
  }
}

/**
 * 内容不足一屏时自动补加载更早历史。
 * 原因：懒加载靠 scroll 事件触发，若首屏内容未撑满视口（scrollTop 恒为 0，不会产生滚动事件），
 *      用户即使想翻更早记录也无法触发加载。
 */
const maybeAutoFillHistory = (conversationId: string) => {
  nextTick(() => {
    // 等一帧再等 120ms：避开虚拟列表刚重建/正在测量的时机，避免把「尚未撑开」误判成「不足一屏」
    window.setTimeout(() => {
      if (currentConversationId.value !== conversationId) return
      if (!hasMoreMessages.value[conversationId]) return
      if (loadingMoreConvIds.has(conversationId)) return
      const el = scrollerRef.value?.$el as HTMLElement | undefined
      if (!el) return
      if (el.scrollHeight > el.clientHeight + 40) return // 已撑满一屏：交给滚动到顶触发
      void loadMoreMessages(conversationId)
    }, 120)
  })
}

/**
 * 会话 id 迁移（本地临时会话 local-xxx → 后端真实会话 id）时同步分页状态。
 * 不做这一步的话，迁移后新 id 下没有 hasMore/cursor，上滑将不再加载更早历史。
 */
const migratePaginationState = (fromConvId: string, toConvId: string) => {
  if (!fromConvId || !toConvId || fromConvId === toConvId) return
  hasMoreMessages.value[toConvId] = hasMoreMessages.value[fromConvId] ?? false
  messageCursors.value[toConvId] = messageCursors.value[fromConvId] ?? null
  delete hasMoreMessages.value[fromConvId]
  delete messageCursors.value[fromConvId]
}

const matchSnapshotsForMessages = async (convId: string) => {
  if (convId.startsWith('local-')) return
  const sid = parseInt(convId)
  if (isNaN(sid)) return
  try {
    const snapshots = await listSnapshots(sid)
    if (!snapshots || snapshots.length === 0) return
    const msgs = messages.value[convId]
    if (!msgs) return
    let changed = false
    for (let i = 0; i < msgs.length; i++) {
      const msg = msgs[i]
      if (msg.role === 'user' && msg.turnId) {
        const match = snapshots.find(s => s.turnId === msg.turnId && !s.rolledBack)
        if (match && !msg.snapshotId) {
          msgs[i].snapshotId = match.snapshotId
          changed = true
        }
      }
    }
    // 强制刷新 DynamicScroller
    if (changed) {
      messages.value[convId] = [...msgs]
    }
  } catch (e) {
    console.warn('匹配快照失败:', e)
  }
}

/**
 * M4/M7：历史文件回显
 * 拉取会话被引用的文件（GET /api/files?conversationId=X&withRefs=true）→
 * 按 file_reference.messageId 分组映射到对应 user 消息 → 气泡渲染（图片缩略图 / 文档图标卡片）。
 * 兼容：无引用数据的旧消息保持纯文本（不修改）。
 */
const loadMessageFileRefs = async (convId: string) => {
  if (convId.startsWith('local-')) return
  const sid = parseInt(convId)
  if (isNaN(sid)) return
  try {
    const res = await listFileAssets({ conversationId: sid, withRefs: true, limit: 200 })
    if (res.code !== 200 || !res.data || res.data.length === 0) return
    const msgs = messages.value[convId]
    if (!msgs) return

    // 按 messageId 分组（同一消息去重；M7：按 mime 标注图片/文档，供气泡分流渲染）
    const byMessage = new Map<number, MessageFileAsset[]>()
    for (const f of res.data) {
      for (const ref of f.references || []) {
        if (ref.messageId == null) continue
        const list = byMessage.get(ref.messageId) || []
        if (!list.some(x => x.id === f.id)) {
          list.push({
            id: f.id,
            fileId: f.fileId,
            filename: f.filename,
            displayName: f.displayName,
            mimeType: f.mimeType,
            size: f.size,
            kind: isImageMime(f.mimeType) ? 'image' : 'doc'
          })
        }
        byMessage.set(ref.messageId, list)
      }
    }
    if (byMessage.size === 0) return

    let changed = false
    for (let i = 0; i < msgs.length; i++) {
      const msg = msgs[i]

      // 常规：user 消息直接挂载（真实用户发送/引用的文件）
      if (msg.role === 'user') {
        if (msg.fileAssets) continue
        const mid = parseInt(msg.id)
        if (isNaN(mid)) continue
        const assets = byMessage.get(mid)
        if (assets && assets.length > 0) {
          msgs[i].fileAssets = assets
          changed = true
        }
        continue
      }

      // 修复：桌面截图注入消息（不渲染为用户气泡）的资产归并到宿主 AI 消息回显
      if (msg.role === 'assistant' && msg.injectSourceIds && msg.injectSourceIds.length > 0) {
        const merged = msg.fileAssets ? [...msg.fileAssets] : []
        let added = false
        for (const sid of msg.injectSourceIds) {
          const sidNum = parseInt(sid)
          if (isNaN(sidNum)) continue
          const assets = byMessage.get(sidNum)
          if (assets) {
            for (const a of assets) {
              if (!merged.some(x => x.id === a.id)) {
                merged.push(a)
                added = true
              }
            }
          }
        }
        if (added) {
          msgs[i].fileAssets = merged
          changed = true
        }
      }
    }
    if (!changed) return

    // 强制刷新 DynamicScroller（与快照匹配同模式）
    messages.value[convId] = [...msgs]

    // 异步加载缩略图（blob URL 缓存；仅图片；逐张填充后触发刷新）
    for (const msg of messages.value[convId]) {
      if (!msg.fileAssets) continue
      for (const asset of msg.fileAssets) {
        if (asset.kind === 'doc' || asset.previewUrl) continue
        getFilePreviewBlobUrl(asset.id).then(url => {
          asset.previewUrl = url
          // 嵌套对象变更不在 DynamicScroller 的 size-dependencies 内，主动刷新一次
          const cur = messages.value[convId]
          if (cur) messages.value[convId] = [...cur]
        }).catch(e => console.warn('历史图片缩略图加载失败:', asset.id, e))
      }
    }
  } catch (e) {
    console.warn('加载历史图片引用失败:', e)
  }
}

// ===== 文件改动统计 =====
const showChangesPanel = ref(false)
const sessionChanges = ref<SessionChanges | null>(null)

const toggleChangesPanel = async () => {
  if (showChangesPanel.value) {
    showChangesPanel.value = false
    return
  }
  // 关闭任务面板
  showTaskDropdown.value = false
  // 加载数据
  const sid = parseInt(currentConversationId.value)
  if (!isNaN(sid) && !currentConversationId.value.startsWith('local-')) {
    const changes = await getSessionChanges(sid)
    if (changes) {
      sessionChanges.value = changes
    }
  }
  showChangesPanel.value = true
}

const loadSessionChanges = async (convId: string) => {
  if (convId.startsWith('local-')) return
  const sid = parseInt(convId)
  if (isNaN(sid)) return
  try {
    const changes = await getSessionChanges(sid)
    if (changes) {
      sessionChanges.value = changes
    }
  } catch (e) {
    console.warn('加载会话改动统计失败:', e)
  }
}

const handleRollbackFile = async (relativePath: string) => {
  const sid = parseInt(currentConversationId.value)
  if (isNaN(sid)) return
  Modal.confirm({
    title: '回滚文件',
    content: `确定要将 "${relativePath}" 回滚到本会话开始前的状态吗？`,
    okText: '确认回滚',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      const success = await rollbackFile(sid, relativePath)
      if (success) {
        message.success(`文件已回滚: ${relativePath}`)
        // 重新加载改动统计
        await loadSessionChanges(currentConversationId.value)
      } else {
        message.error('文件回滚失败')
      }
    }
  })
}

// 点击改动面板中的文件 → 获取快照原始内容 + 当前内容 → 构建 diff 预览 → 分屏打开
const handleChangesFileClick = async (file: { relativePath: string; wasNewFile: boolean }) => {
  const sid = parseInt(currentConversationId.value)
  if (isNaN(sid)) return

  const workDir = agentRuntime.value.workDir
  if (!workDir) {
    message.warning('请先设置工作目录')
    return
  }

  const relativePath = file.relativePath
  const absPath = /^[a-zA-Z]:[/\\]/.test(relativePath) || relativePath.startsWith('/')
    ? relativePath
    : workDir.replace(/[/\\]$/, '') + '/' + relativePath.replace(/\\/g, '/')

  try {
    // 1. 读取当前文件内容
    const currRes = await readProjectFile(absPath)
    const currentContent = (currRes.code === 200 && currRes.data !== null) ? currRes.data : ''
    const currLines = currentContent.split('\n')

    // 2. 获取快照中的原始内容（wasNewFile 的文件也能拿到初始版本内容）
    const originalContent = await getSnapshotFileContent(sid, relativePath)

    // 3. 构建预览内容和行高亮
    const highlightLines: { line: number; type: 'remove' | 'add' }[] = []
    let previewContent: string

    if (!originalContent) {
      // 真正无法获取原始内容（快照不存在或备份文件丢失）→ 全部绿色兜底
      previewContent = currentContent
      for (let i = 0; i < currLines.length; i++) {
        highlightLines.push({ line: i + 1, type: 'add' })
      }
    } else {
      // 有原始内容 → 逐行 LCS 匹配，建立对应关系
      const origLines = originalContent.split('\n')
      const usedOrig = new Set<number>()

      // 收集 green add 行（当前文件中有、原始文件中没有的）
      const greenMap = new Map<number, string>() // 原始行号(近似) → 当前行内容
      const redSet = new Set<number>()           // 原始行号 → 删除

      // 先标记：当前文件中匹配到的原始行
      let greenIdx = 0
      for (let ci = 0; ci < currLines.length; ci++) {
        const cl = currLines[ci]
        let matched = false
        for (let oi = greenIdx; oi < origLines.length; oi++) {
          if (!usedOrig.has(oi) && origLines[oi] === cl) {
            usedOrig.add(oi)
            greenIdx = oi + 1
            matched = true
            break
          }
        }
        if (!matched) {
          // 未匹配 → 绿色新增行，放在最近的已匹配原始行之后
          const key = greenIdx > 0 ? greenIdx : 1
          const existing = greenMap.get(key)
          greenMap.set(key, existing ? existing + '\n' + cl : cl)
        }
      }

      // 未匹配的原始行 → 红色删除
      for (let oi = 0; oi < origLines.length; oi++) {
        if (!usedOrig.has(oi)) {
          redSet.add(oi + 1)
        }
      }

      // 构建预览内容：原始文件内容，remove 行保留并标红，add 行紧跟其后标绿
      const previewLines: string[] = []
      const highlightLinesLocal: typeof highlightLines = []

      for (let oi = 0; oi < origLines.length; oi++) {
        const ln = oi + 1
        previewLines.push(origLines[oi])
        if (redSet.has(ln)) {
          highlightLinesLocal.push({ line: previewLines.length, type: 'remove' })
        }
        // 该行之后紧跟的绿色新增行
        const greenText = greenMap.get(ln)
        if (greenText) {
          const greenLines = greenText.split('\n')
          for (const gl of greenLines) {
            previewLines.push(gl)
            highlightLinesLocal.push({ line: previewLines.length, type: 'add' })
          }
        }
      }

      // 原始文件结束后仍有未放置的绿色行 → 追加到末尾
      for (const [key, greenText] of greenMap) {
        if (key > origLines.length) {
          const greenLines = greenText.split('\n')
          for (const gl of greenLines) {
            previewLines.push(gl)
            highlightLinesLocal.push({ line: previewLines.length, type: 'add' })
          }
        }
      }

      previewContent = previewLines.join('\n')
      highlightLines.push(...highlightLinesLocal)
      console.log('[CHANGES-CLICK] remove:', highlightLines.filter(h => h.type === 'remove').length,
        'add:', highlightLines.filter(h => h.type === 'add').length,
        '预览总行数:', previewLines.length)
    }

    // 4. 构建 tab 并打开分屏
    const tabId = `changes-${Date.now()}-${relativePath.replace(/[/\\:]/g, '_')}`
    const tab: EditorTab = {
      id: tabId,
      type: 'file',
      title: relativePath.split(/[/\\]/).pop() || relativePath,
      filePath: absPath,
      content: previewContent,
      originalContent: previewContent,
      isDirty: false,
      highlightLines
    }

    if (!splitState.value.enabled) {
      syncPrimaryPane()
      splitState.value.secondary = {
        id: 'secondary',
        paneTabs: [tab],
        activeTabId: tab.id
      }
      splitState.value.direction = 'h'
      splitState.value.splitRatio = 0.5
      splitState.value.enabled = true
    } else if (splitState.value.secondary) {
      splitState.value.secondary.paneTabs.push(tab)
      splitState.value.secondary.activeTabId = tab.id
    }
    tabs.value = collectAllTabs()
    activeTabId.value = 'chat'
  } catch (e) {
    console.warn('打开文件 diff 预览失败:', e)
    message.error('打开文件预览失败')
  }
}

const handleRollbackAll = async () => {
  const sid = parseInt(currentConversationId.value)
  if (isNaN(sid)) return
  if (!sessionChanges.value) return
  Modal.confirm({
    title: '回滚全部文件',
    content: `确定要将全部 ${sessionChanges.value.files.length} 个文件回滚到本会话开始前的状态吗？此操作不可撤销！`,
    okText: '确认全部回滚',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      const success = await rollbackAllFiles(sid)
      if (success) {
        message.success('全部文件已回滚')
        // 重新加载改动统计
        await loadSessionChanges(currentConversationId.value)
      } else {
        message.error('回滚失败')
      }
    }
  })
}

// 监听消息加载完成后滚动到底部
// 使用 nextTick 确保 Vue 完成 DOM 更新（DynamicScroller 的 v-if 条件已生效）后再滚动
// ★ 改用 scrollToBottomForce：切换历史会话必须定位到「最新一条记录」，
//   不能被 autoScrollToBottom（上一个会话浏览历史留下的 false）拦截
watch(isLoadingMessages, (loading) => {
  if (!loading) {
    nextTick(() => {
      scrollToBottomForce()
    })
  }
})

const selectConversation = (id: string) => {
  currentConversationId.value = id
  // ★ 切会话：重置滚动状态为「跟随底部」。否则上一个会话向上翻历史后 autoScrollToBottom=false，
  //   本次进入 scrollToBottom 会直接 return → 界面停在第一条消息（表现为「没定位到最新记录」）
  autoScrollToBottom.value = true
  showScrollToBottomBtn.value = false
  fetchMessages(id)
  // 检查是否有正在运行的后台任务
  checkAndReconnect(id)
  // 命中缓存时 fetchMessages 会直接 return（不触发 isLoadingMessages 变化），这里补一次强制到底。
  // DynamicScroller 带 :key=会话ID 会重建，需等重建后的 DOM 就绪再滚。
  nextTick(() => {
    scrollToBottomForce(id)
  })
}

const startNewChat = () => {
  const id = `local-${Date.now()}`
  const conv: Conversation = {
    id,
    title: '编码对话',
    updatedAt: Date.now(),
    messageCount: 0,
    isLocal: true
  }
  conversations.value.unshift(conv)
  currentConversationId.value = id
  messages.value[id] = []
  // 新会话没有历史：显式关闭分页，避免上滑时用空游标发起无意义请求
  hasMoreMessages.value[id] = false
  messageCursors.value[id] = null
}

/**
 * 处理子Agent SSE事件，更新 agentInfos 状态
 */
const handleAgentEvent = (event: AgentStreamEvent) => {
  const { agent_id, event: eventType, name, status, tool_name, file_path, result, reasoning_content, skills, summary } = event

  let agent = agentInfos.value.find(a => a.agentId === agent_id)

  if (eventType === 'agent_forked') {
    if (!agent) {
      agent = {
        agentId: agent_id,
        name: name || agent_id,
        status: 'running' as const,
        skills: [],
        events: [] as any[]
      }
      agentInfos.value.push(agent)
    }
    agent.events.push({ type: 'status_change' as const, newStatus: 'running' as const, content: `子Agent「${name || agent_id}」已创建` })
    return
  }

  if (!agent) return

  if (eventType === 'agent_thinking' && reasoning_content) {
    agent.events.push({ type: 'thinking' as const, content: reasoning_content })
    return
  }

  if (eventType === 'agent_tool_call') {
    const toolResult = result || ''
    const toolNameVal = tool_name || ''
    maybeShowDesktopPrivacyNotice([toolNameVal])
    const actionStr = toolNameVal ? extractActionFromToolContent(toolResult, toolNameVal) : ''
    agent.events.push({ type: 'tool_call' as const, toolName: toolNameVal, action: actionStr, filePath: file_path || '', result: toolResult })
    return
  }

  if (eventType === 'agent_skill_match' && skills) {
    agent.events.push({ type: 'skill_match' as const, skills })
    return
  }

  if (eventType === 'agent_status' && status) {
    agent.status = status as 'running' | 'completed' | 'failed' | 'pending'
    agent.events.push({ type: 'status_change' as const, newStatus: status, content: summary || '' })
    return
  }

  if (eventType === 'agent_completed') {
    agent.status = 'completed' as const
    agent.events.push({ type: 'status_change' as const, newStatus: 'completed' as const, content: summary || '执行完毕' })
  }
}

const deleteConversation = (id: string) => {
  Modal.confirm({
    title: '删除会话',
    content: '确定要删除这个会话吗？',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        if (!id.startsWith('local-')) {
          await deleteConversationApi(Number(id))
        }
        conversations.value = conversations.value.filter(c => c.id !== id)
        // M1：删除会话时清理按会话隔离的状态 Map（防内存泄漏 + 会话ID复用污染）
        clearConvState(id)
        if (currentConversationId.value === id) {
          const remaining = conversations.value.filter(c => !c.isLocal)
          currentConversationId.value = remaining[0]?.id || ''
        }
      } catch (e: any) {
        message.error('删除失败: ' + (e.message || '未知错误'))
      }
    }
  })
}

// ===== 代码回滚 =====

const handleRollback = async (msg: ChatMessage) => {
  if (!msg.snapshotId) return

  // 加载预览信息
  const preview = await previewRollback(msg.snapshotId)
  if (!preview) {
    message.error('无法获取回滚预览信息')
    return
  }

  // 构建确认对话框内容
  const fileListHtml = preview.files.map(f =>
    `<div style="padding:2px 0;font-size:13px">${f.action === 'delete' ? '🗑️ 删除' : '📄 恢复'} ${escapeHtml(f.relativePath)}</div>`
  ).join('')

  const contentHtml = `<div style="margin-bottom:8px;color:#666;font-size:13px">将回滚 ${preview.files.length} 个文件到修改前的状态：</div>${fileListHtml}`

  Modal.confirm({
    title: '确认回滚代码',
    content: h('div', { innerHTML: contentHtml }),
    okText: '确认回滚',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      const success = await executeRollback(msg.snapshotId!)
      if (success) {
        message.success('代码回滚成功')
        msg.snapshotId = undefined // 移除回滚按钮，防止重复回滚
      } else {
        message.error('代码回滚失败')
      }
    }
  })
}

// 执行模式切换
const handleModeChange = (val: string) => {
  settingsStore.setMode('code_assistant', val as 'auto' | 'manual')
}

// 模型与思考模式切换
const handleModelChange = (val: string) => {
  settingsStore.setModel('code_assistant', val as 'deepseek-v4-flash' | 'deepseek-v4-pro')
}
const handleThinkingModeChange = (val: string) => {
  settingsStore.setThinkingMode('code_assistant', val as 'non-thinking' | 'thinking' | 'thinking_max')
}

// 上下文模式切换 — 同步更新 settingsStore + 持久化到服务端
const saveContextModeToServer = (mode: string) => {
  setConfig('context_mode', mode).catch(() => { /* 静默失败，settingsStore 已更新 */ })
}

// 停止流式响应（只停止当前会话的流式，其他会话/Agent 的后台流式不受影响）
const stopStreaming = () => {
  const currentConvId = currentConversationId.value

  // 收集要取消的后端任务ID
  const cancelIds: number[] = []

  // 停止当前会话的前台流式（stopAbortController 中的流式）
  if (stopAbortController.value) {
    stopAbortController.value.abort()
    stopAbortController.value = null
  }

  // 停止当前会话的后台流式（P2：按会话取消，不再按 Agent 一锅端——切换 Agent 后不会误杀其他会话的后台任务）
  if (currentConvId) {
    backgroundStreams.value = backgroundStreams.value.filter(bg => {
      if (bg.convId === currentConvId) {
        bg.abortController.abort()
        if (bg.realSessionId) {
          cancelIds.push(bg.realSessionId)
        }
        return false
      }
      return true
    })
  }

  // 从 currentStreamSessionId 获取（★ 仅当该流确属当前会话时才采用）
  // 切智能体/切会话后这个全局值可能仍指向上一个会话，无条件使用会误取消其他会话的后台任务
  if (currentStreamSessionId.value && activeStreamInfo.value?.convId === currentConvId) {
    cancelIds.push(currentStreamSessionId.value)
  }
  currentStreamSessionId.value = null

  // 从 currentConversationId 获取（如果不是 local-xxx）
  if (currentConvId && !currentConvId.startsWith('local-')) {
    const id = parseInt(currentConvId)
    if (!isNaN(id)) {
      cancelIds.push(id)
    }
  }

  // 兜底：从 conversations 列表中找对应 local-xxx 的真实ID
  if (currentConvId && currentConvId.startsWith('local-')) {
    const conv = conversations.value.find(c => c.id === currentConvId)
    if (conv && !conv.id.startsWith('local-')) {
      const id = parseInt(conv.id)
      if (!isNaN(id)) {
        cancelIds.push(id)
      }
    }
  }

  // 统一去重后取消所有后端任务
  const uniqueIds = [...new Set(cancelIds)]
  if (uniqueIds.length === 0 && currentConvId && !currentConvId.startsWith('local-')) {
    const id = parseInt(currentConvId)
    if (!isNaN(id)) {
      uniqueIds.push(id)
    }
  }
  for (const id of uniqueIds) {
    cancelTask(id)
  }

  // 立即重置当前会话的 UI 状态（P2：按会话清理，不影响其他会话）
  setSending(currentConvId, false)
  setStreamStatus(currentConvId, '')
}


// 调用原生目录选择对话框（Electron）或目录浏览器（浏览器）
const selectProjectRoot = async () => {
  if ((window as any).electronAPI?.selectDirectory) {
    const dir = await (window as any).electronAPI.selectDirectory()
    if (dir) {
      // 同步更新 Agent 运行时配置，确保左侧面板 UI 即时刷新
      if (agentSelectorRef.value) {
        agentSelectorRef.value.runtime.workDir = dir
        agentSelectorRef.value.saveRuntime()
      }
      settingsStore.projectRoot = dir
      fileTreeLoadPath.value = dir
    }
  } else {
    // 浏览器环境：弹出目录浏览器弹窗
    showDirBrowser.value = true
  }
}

// 目录浏览器选择完成回调
const onDirSelected = (path: string) => {
  showDirBrowser.value = false
  if (agentSelectorRef.value) {
    agentSelectorRef.value.runtime.workDir = path
    agentSelectorRef.value.saveRuntime()
  }
  // ★ 始终同步 settingsStore 和 fileTree，无论 agentSelectorRef 是否已初始化
  settingsStore.projectRoot = path
  fileTreeLoadPath.value = path
}

// 发送补充需求
const sendSupplement = async () => {
  const msg = supplementMessage.value.trim()
  if (!msg || supplementSending.value) return

  // ★ 目标会话判定（多智能体并行场景必须精确，否则补充需求会串台到别的会话）：
  //   - 当前是本地临时会话（local-xxx）：真实 ID 只能来自「本会话正在跑的前台流」（currentStreamSessionId），
  //     且必须校验 activeStreamInfo.convId 就是当前会话，避免用到其他会话残留的 sessionId；
  //   - 当前已是真实会话 ID：直接用它（后端会话 ID 即接口入参，无需依赖全局流会话变量）。
  //   原实现无条件优先 currentStreamSessionId，切智能体后该变量可能仍指向另一个会话 →
  //   补充需求被发到错误的会话（多智能体并行时尤其危险）。
  const currentConv = currentConversationId.value
  let convId = currentConv
  if (currentConv && currentConv.startsWith('local-')) {
    const streamConv = activeStreamInfo.value?.convId
    const realSession = currentStreamSessionId.value
    if (streamConv === currentConv && realSession) {
      convId = String(realSession)
    }
  }
  if (!convId || convId.startsWith('local-')) {
    message.warning('请等待会话建立后再补充需求')
    return
  }

  supplementSending.value = true
  try {
    const res = await supplementRequest(parseInt(convId), msg)
    if (res.success) {
      message.success('补充需求已发送')
      supplementMessage.value = ''
      showSupplementInput.value = false
    } else {
      message.error(res.error || '发送失败')
    }
  } catch (e: any) {
    message.error('发送失败: ' + (e.message || '未知错误'))
  } finally {
    supplementSending.value = false
  }
}

const cancelSupplement = () => {
  supplementMessage.value = ''
  showSupplementInput.value = false
}

// ask_user 回答函数（用于 clarification 类型）
const submitPendingAnswer = async () => {
  if (!pendingQuestion.value || !pendingQuestionAnswer.value.trim()) return
  const q = pendingQuestion.value
  const answer = pendingQuestionAnswer.value.trim()
  try {
    await submitAnswer(q.uuid, answer, 'approve')
    // 三轮审查修复：pendingQuestion 是只读 computed（无 setter），赋值静默失效（弹窗残留/重复提交）；
    // 必须通过 setPendingQuestion 操作底层的 Map
    setPendingQuestion(currentConversationId.value, null)
    pendingQuestionAnswer.value = ''
    pendingShowCustomInput.value = false
    cleanupPermissionPanel()
    message.success('回答已发送')
  } catch (e: any) {
    message.error('回答发送失败: ' + (e.message || '未知错误'))
  }
}

// 处理权限授权按钮（4种 action）
const permissionLock = ref(false)

// ===== P3：桌面自动化隐私提示（M5 遗留） =====
// 首次检测到桌面工具（screen_capture / desktop_control）调用时弹窗告知；仅弹一次（localStorage 持久化标记）
const DESKTOP_PRIVACY_NOTICE_KEY = 'desktop.privacy-notice-shown'
let desktopPrivacyNoticeShownInSession = false

const maybeShowDesktopPrivacyNotice = (tools: string[]) => {
  if (desktopPrivacyNoticeShownInSession) return
  if (!tools.some(t => t === 'screen_capture' || t === 'desktop_control')) return
  desktopPrivacyNoticeShownInSession = true
  try {
    if (localStorage.getItem(DESKTOP_PRIVACY_NOTICE_KEY)) return
  } catch (_) { /* 读取失败按未展示处理 */ }
  try { localStorage.setItem(DESKTOP_PRIVACY_NOTICE_KEY, '1') } catch (_) {}
  Modal.info({
    title: '桌面自动化隐私提示',
    content: h('div', { style: 'line-height:1.8' }, [
      h('p', null, '即将使用桌面自动化功能（桌面截图 / 键鼠控制），请知悉：'),
      h('p', null, '1. 截图会通过文件服务上传至云端 LLM 用于视觉分析，并保存在「文件管理」中（来源标记 tool_capture）；'),
      h('p', null, '2. 键鼠操作会真实控制您的电脑（移动/点击/输入等），操作期间请勿同时使用鼠标键盘；'),
      h('p', null, '3. 请勿在桌面自动化场景中处理密码、支付、验证码等敏感信息。')
    ]),
    okText: '我知道了'
  })
}

// ★ 判断当前授权是否是 file_writer
const isPermissionFileTool = computed(() => {
  const q = pendingQuestion.value
  if (!q) return false
  return q.toolName === 'file_writer'
})

const handlePermissionAction = async (action: string) => {
  if (permissionLock.value || !pendingQuestion.value) return
  const q = pendingQuestion.value
  // 立即加锁并清空面板，防止 SSE 流重复推送覆盖
  // （三轮审查修复：pendingQuestion 为只读 computed，赋值失效；改走 setPendingQuestion 操作 Map）
  permissionLock.value = true
  setPendingQuestion(currentConversationId.value, null)
  // 对于 custom 类型，需要用户输入了内容
  if (action === 'custom') {
    if (!pendingQuestionAnswer.value.trim()) {
      message.warning('请输入您的消息')
      permissionLock.value = false
      setPendingQuestion(currentConversationId.value, q)
      return
    }
  }
  const answer = action === 'custom' ? pendingQuestionAnswer.value.trim() : action
  try {
    await submitAnswer(q.uuid, answer, action)
    pendingQuestionAnswer.value = ''
    pendingShowCustomInput.value = false
    if (action === 'approve_all') {
      message.success('已同意本会话后续所有操作')
    } else if (action === 'approve') {
      message.success('已同意')
    } else if (action === 'reject') {
      message.success('已拒绝')
    } else if (action === 'custom') {
      message.success('消息已发送')
    }
  } catch (e: any) {
    message.error('操作失败: ' + (e.message || '未知错误'))
    // 三轮审查修复：恢复被清空的授权问题（只读 computed 赋值失效，改走 setPendingQuestion）
    setPendingQuestion(currentConversationId.value, q)
  } finally {
    permissionLock.value = false
  }
  // ★ 清理：关闭文件 tab + 取消分屏
  cleanupPermissionPanel()
}

/**
 * 处理「任务迭代确认」面板（askType=iteration_confirm）：
 * - stop     → 结束任务（按拒绝语义提交，后端终止工具循环）
 * - continue → 继续执行，并指定本次扩容的迭代次数（10 / 20 / 50）
 *
 * 回答由后端 Controller 组装为 __ACTION__:action:message：
 * 继续时 message 为扩容次数，结束时不带 message。
 */
const handleIterationDecision = async (type: 'stop' | 'continue', additional = 20) => {
  const q = pendingQuestion.value
  if (!q || iterationConfirmSending.value) return
  iterationConfirmSending.value = true
  try {
    const action = type === 'continue' ? 'approve' : 'reject'
    const answer = type === 'continue' ? String(additional) : ''
    await submitAnswer(q.uuid, answer, action)
    // 提交成功：关闭面板，避免重复提交（后端同时会清掉 agent_task 的待处理字段）
    setPendingQuestion(currentConversationId.value, null)
    pendingQuestionAnswer.value = ''
    pendingShowCustomInput.value = false
    cleanupPermissionPanel()
    if (type === 'continue') {
      message.success(`已选择继续执行（扩容 ${additional} 次）`)
    } else {
      message.success('已结束任务')
    }
  } catch (e: any) {
    message.error('提交失败: ' + (e.message || '未知错误'))
  } finally {
    iterationConfirmSending.value = false
  }
}

// ★ 授权面板文件操作：对 file_writer 自动打开差异对比
const permissionFileTabId = ref<string | null>(null)  // 记录授权面板打开的文件 tab

const cleanupPermissionPanel = () => {
  // 关闭授权面板打开的文件 tab
  if (permissionFileTabId.value) {
    closeTab(permissionFileTabId.value)
    permissionFileTabId.value = null
  }
  // 如果副面板只剩下空壳，取消分屏
  if (splitState.value.enabled && splitState.value.secondary) {
    const secTabs = splitState.value.secondary.paneTabs
    if (secTabs.length === 0) {
      splitState.value.secondary = null
      splitState.value.enabled = false
    }
  }
}

// ★ 监听授权面板：对 file_writer 自动分屏打开文件
watch(() => pendingQuestion.value, async (q) => {
  if (!q || q.askType !== 'permission') return
  const tool = q.toolName
  const filePath = q.filePath
  if (!filePath || tool !== 'file_writer') return
  // 自动分屏并打开文件
  // P5 修复：优先用归属会话的工作目录（q.workDir），不再错用当前 Agent 的 workDir——
  // 否则后台 Agent 的授权弹窗打开的文件路径会拼到当前 Agent 目录下（展示与授权对象错位）
  const projectRoot = q.workDir || agentRuntime.value.workDir
  if (!projectRoot) return
  const isAbs = /^[a-zA-Z]:[/\\]/.test(filePath) || filePath.startsWith('/')
  const absPath = isAbs ? normalizeFsPath(filePath) : normalizeFsPath(projectRoot).replace(/\/+$/, '') + '/' + normalizeFsPath(filePath)
  try {
    const res = await readProjectFile(absPath)
    const tabId = `permission-${Date.now()}-${filePath.replace(/[/\\:]/g, '_')}`
    permissionFileTabId.value = tabId

    const detail = q.fullDetail || ''
    const highlightLines: { line: number; type: 'remove' | 'add'; content?: string }[] = []
    let fileContent: string

    // ── 分支1：文件已存在（file_writer 编辑已有文件）──
    if (res.code === 200 && res.data !== null) {
      const currLines = (res.data || '').split('\n')

      // 1. 解析 fullDetail 中所有 +/- 行
      interface DiffLine { type: 'remove' | 'add'; text: string }
      const parsedDiff: DiffLine[] = []
      const changes = detail.match(/^[-+].*/gm)
      if (changes) {
        for (const ch of changes) {
          const text = ch.substring(1)
          if (!text) continue
          parsedDiff.push({ type: ch.startsWith('-') ? 'remove' : 'add', text })
        }
      }
      console.log('[PERM-HL] file_writer 变更行:', parsedDiff.length)

      // 2. 按 diff 顺序建立 remove→add 对应关系
      interface RemoveInfo { line: number; addTexts: string[] }
      const removeInfos: RemoveInfo[] = []
      let curInfo: RemoveInfo | null = null
      for (const dl of parsedDiff) {
        if (dl.type === 'remove') {
          let ln = -1
          for (let li = 0; li < currLines.length; li++) {
            if (currLines[li] === dl.text || currLines[li].trim() === dl.text.trim()) {
              ln = li + 1
              break
            }
          }
          curInfo = { line: ln, addTexts: [] }
          removeInfos.push(curInfo)
        } else if (curInfo) {
          curInfo.addTexts.push(dl.text)
        } else {
          // add 行没有前置 remove → 创建虚拟 remove（line=-1 表示追加末尾）
          curInfo = { line: -1, addTexts: [dl.text] }
          removeInfos.push(curInfo)
        }
      }

      // 3. 建立原文件行号 → RemoveInfo 映射
      const removeMap = new Map<number, RemoveInfo>()
      for (const ri of removeInfos) {
        if (ri.line > 0) removeMap.set(ri.line, ri)
      }

      // 4. 构建预览内容：遍历原文件，每个 remove 行之后紧跟绿色 add 行
      const previewLines: string[] = []
      const highlightLinesLocal: typeof highlightLines = []

      for (let i = 0; i < currLines.length; i++) {
        const ln = i + 1
        const ri = removeMap.get(ln)
        if (ri) {
          // 原文件行标记为红色删除
          previewLines.push(currLines[i])
          highlightLinesLocal.push({ line: previewLines.length, type: 'remove' })
          // 绿色新增行紧跟其后
          for (const addText of ri.addTexts) {
            previewLines.push(addText)
            highlightLinesLocal.push({ line: previewLines.length, type: 'add' })
          }
        } else {
          previewLines.push(currLines[i])
        }
      }

      // 5. 处理 line=-1 的孤儿块（没有对应 remove 的 add 行）→ 追加到末尾
      for (const ri of removeInfos) {
        if (ri.line < 0) {
          for (const addText of ri.addTexts) {
            previewLines.push(addText)
            highlightLinesLocal.push({ line: previewLines.length, type: 'add' })
          }
        }
      }

      fileContent = previewLines.join('\n')
      highlightLines.push(...highlightLinesLocal)
      console.log('[PERM-HL] remove:', highlightLines.filter(h => h.type === 'remove').length,
        'add:', highlightLines.filter(h => h.type === 'add').length,
        '预览总行数:', previewLines.length)
    } else if (tool === 'file_writer') {
      // ── 分支2：file_writer 创建新文件（文件还不存在）──
      // file_writer action=write 的 fullDetail 就是新文件内容（不带 +/- 前缀，是纯文本）
      const rawDetail = (detail || '').trim()
      if (rawDetail) {
        fileContent = rawDetail
        const lines = rawDetail.split('\n')
        for (let i = 0; i < lines.length; i++) {
          // ★ 不传 content：避免 renderBlocks 中与文件行重复；行号对上行号区即可
          highlightLines.push({ line: i + 1, type: 'add' })
        }
      } else {
        console.warn('[PERM-HL] file_writer fullDetail 为空，无法生成虚拟预览')
        return
      }
      console.log('[PERM-HL] file_writer 新文件虚拟预览:', highlightLines.length, '行（全部绿色）')
    } else {
      // 文件不存在且不是 file_writer → 跳过
      return
    }

    // ── 公共：构建 tab → 分屏打开 ──
    const tab: EditorTab = {
      id: tabId,
      type: 'file',
      title: filePath.split(/[/\\]/).pop() || filePath,
      filePath: absPath,
      content: fileContent,
      originalContent: fileContent,
      isDirty: false,
      highlightLines
    }
    if (!splitState.value.enabled) {
      syncPrimaryPane()
      splitState.value.secondary = {
        id: 'secondary',
        paneTabs: [tab],
        activeTabId: tab.id
      }
      splitState.value.direction = 'h'
      splitState.value.splitRatio = 0.5
      splitState.value.enabled = true
    } else if (splitState.value.secondary) {
      splitState.value.secondary.paneTabs.push(tab)
      splitState.value.secondary.activeTabId = tab.id
    }
    tabs.value = collectAllTabs()
    activeTabId.value = 'chat'
  } catch (e) {
    console.warn('授权面板打开文件失败:', e)
  }
})

// 节流渲染：将流式更新聚合并限制在每 150ms 一次，避免高频 chunk 导致卡死
let updateTimer: number | null = null

const scheduleMessageUpdate = (convId: string, thinkingMsgId?: string | number) => {
  if (updateTimer !== null) return
  updateTimer = window.setTimeout(() => {
    updateTimer = null
    if (autoScrollToBottom.value) {
      // 自动滚动模式：统一刷新入口（节拍器 + 数组替换，50ms 去重）+ 滚动到底部
      refreshScrollerData(convId)
      scrollToBottom()
    }
    // autoScrollToBottom = false 时：不主动替换数组、不滚动，避免用户浏览历史时视口被拽走
    // 注意：若 item 内容变化导致高度改变，ResizeObserver 仍会修正尺寸表（属正确行为）
    if (thinkingMsgId !== undefined && thinkAutoScroll.value) {
      nextTick(() => {
        const el = document.querySelector(`[data-think-scroll="${thinkingMsgId}"]`)
        if (el) el.scrollTop = el.scrollHeight
      })
    }
  }, 150)
}

const flushMessageUpdate = (convId: string, thinkingMsgId?: string | number) => {
  if (updateTimer !== null) {
    clearTimeout(updateTimer)
    updateTimer = null
  }
  if (autoScrollToBottom.value) {
    refreshScrollerData(convId)
    scrollToBottom()
  }
  if (thinkingMsgId !== undefined && thinkAutoScroll.value) {
    nextTick(() => {
      const el = document.querySelector(`[data-think-scroll="${thinkingMsgId}"]`)
      if (el) el.scrollTop = el.scrollHeight
    })
  }
}

// 优化提示词
const optimizeMessage = async () => {
  const text = inputMessage.value.trim()
  if (!text || isOptimizing.value) return

  isOptimizing.value = true
  try {
    const result = await optimizePrompt(text)
    // ★ 如果用户在优化期间已发送消息，不要覆盖已清空的输入框
    //    （否则优化结果会在消息发送完成后"复活"输入框内容）
    if (!isSending.value) {
      inputMessage.value = result
    }
  } catch (e) {
    message.warning('优化失败，请稍后重试')
  } finally {
    isOptimizing.value = false
  }
}

// 发送消息
// 发送消息
const sendMessage = async (textOverride?: string) => {
  // ★ 「继续任务」等程序化续跑场景：传入覆盖文本（字符串）；模板事件对象等非字符串入参忽略
  const overrideText = typeof textOverride === 'string' ? textOverride : ''
  const text = (overrideText || inputMessage.value).trim()
  // ★ 2026-08-14：守卫改用 hasRunningTask——输入框 disabled 后 Enter 等路径虽不会触发，
  //   双保险防止「切回智能体后任务仍在后台执行」时误发新消息造成同会话双流冲突
  if (hasRunningTask.value) return
  // M4：支持"仅图片无文字"发送（粘贴截图后直接发）；两者皆无则忽略
  if (!text && attachedFiles.value.length === 0) return
  // M4：图片上传未完成时阻止发送（避免静默丢图）
  if (attachedFiles.value.some(a => a.kind === 'image' && a.uploading)) {
    message.warning('图片正在上传中，请稍候再发送')
    return
  }

  // 检查工作目录是否已设置（Agent 配置或全局设置均可）
  if (!agentRuntime.value.workDir && !settingsStore.projectRoot) {
    message.warning('请先在左侧「文件」面板中设置工作目录')
    return
  }

  // 如果没有激活的会话，自动创建一个本地会话
  if (!currentConversationId.value) {
    startNewChat()
  }

  // 发送新消息时清除旧的任务清单
  clearTaskList()

  // ★ 发送（含「继续任务」一键续跑）即视为用户已响应中断：清除该会话的「任务中断」提示
  //   （若本轮再次异常停止，新的 task_error 事件会重新写入）
  const clearInterruptedKeys = [
    currentConversationId.value,
    currentStreamSessionId.value != null ? String(currentStreamSessionId.value) : ''
  ]
  for (const key of clearInterruptedKeys) {
    if (key && interruptedTasks.value[key]) {
      delete interruptedTasks.value[key]
    }
  }

  // 发送新消息时重置自动滚动状态：重新固定到底部
  autoScrollToBottom.value = true
  showScrollToBottomBtn.value = false
  thinkAutoScroll.value = true
  thinkShowScrollBtn.value = false

  // 关闭补充需求面板
  showSupplementInput.value = false
  supplementMessage.value = ''

  // 构建最终消息：附件不再拼接到消息中（图片走 vision 内容块；文档由 LLM 通过 chat_attachment 工具读取）
  // M4：纯图片无文字场景用占位文本（保证消息非空）
  let finalMessage = text || (attachedFiles.value.some(a => a.kind === 'image') ? '[图片]' : text)

  // M7：统一收集文件资产ID（图片 + 文档；发送时建立引用：图片注入 vision 块，文档注入读取提示）
  const fileAssetIds: number[] = []
  // M4：本轮消息气泡展示用（附件区发送后清空，缩略图/文件图标需保留在消息上）
  const messageFileAssets: MessageFileAsset[] = []
  if (attachedFiles.value.length > 0) {
    for (const att of attachedFiles.value) {
      if (att.error || att.uploading) continue
      if (att.fileAssetId) {
        fileAssetIds.push(att.fileAssetId)
        messageFileAssets.push({
          id: att.fileAssetId,
          filename: att.fileName,
          size: att.size,
          kind: att.kind,
          previewUrl: att.previewUrl
        })
      }
    }
  }

  let convId = currentConversationId.value
  // 如果是本地会话，后续流式结束后会迁移到真实会话ID
  if (convId && convId.startsWith('local-')) {
    // 先发消息让后端创建会话
  }
  // 用于在流式结束后统一迁移 convId（避免流式过程中数组引用变动导致内容重复）
  let realSessionId: string | undefined = undefined

  // 生成 turnId 用于快照匹配
  const turnId = `${Date.now()}-${Math.random().toString(36).substring(2, 10)}`

  // 添加用户消息
  const userMsg: ChatMessage = {
    id: `user-${Date.now()}-${++localMsgSeq}`,
    role: 'user',
    content: finalMessage,
    timestamp: Date.now(),
    tokenCount: estimateTokenCount(finalMessage),
    turnId,
    // M4：本轮图片缩略图滞留显示（previewUrl 所有权转移给消息，不再 revoke）
    fileAssets: messageFileAssets.length > 0 ? messageFileAssets : undefined
  }

  // 清空附件列表
  attachedFiles.value = []

  if (!messages.value[convId]) {
    messages.value[convId] = []
    // 新开的本地会话：显式关闭分页（无历史可加载），避免上滑用空游标发起无意义请求
    hasMoreMessages.value[convId] = false
    messageCursors.value[convId] = null
  }
  messages.value[convId].push(userMsg)
  inputMessage.value = ''
  // ★ 关键：先让 Vue 完成 DOM 更新（清空输入框），再禁用 textarea
  //    否则 Ant Design Vue 的 a-textarea 在 disabled 状态下不响应 v-model 变化，
  //    导致输入框直到流式结束后（isSending=false）才显示为空
  await nextTick()
  setSending(convId, true)
  // ★ 重置 currentStreamSessionId，确保新会话的 SSE 流能设置新的真实 sessionId
  // 否则上一次流式残留的值会导致补充需求发到旧的会话
  currentStreamSessionId.value = null
  startElapsedTimer()
  setStreamStatus(convId, '正在连接...')

  // 添加占位的助手消息
  const assistantMsg: ChatMessage = {
    id: `assistant-${Date.now()}-${++localMsgSeq}`,
    role: 'assistant',
    content: '',
    thinking: '',
    toolResults: [],
    timestamp: Date.now(),
    isStreaming: true
  }
  messages.value[convId].push(assistantMsg)
  scrollToBottom()

  // 更新会话标题
  const conv = conversations.value.find(c => c.id === convId)
  if (conv && conv.isLocal) {
    conv.title = text.length > 12 ? text.substring(0, 12) + '...' : text
  }

  const abortCtrl = new AbortController()
  stopAbortController.value = abortCtrl
  // 记录当前流式信息，用于停止时直接取消后端任务
  activeStreamInfo.value = { convId }
  // H1 修复：标记流式是否「正常完成」——try 正常走完才置 true；
  // 用户中止（__USER_ABORT__）/发送失败路径保持 false，onStreamComplete 据此不误报「后台任务已完成」
  let streamCompleted = false
  // ★ 任务错误标志：收到 task_error 事件（LLM 接口错误异常停止）后置 true——
  //   流会以 complete 正常收尾，但任务实际是「错误停止」，不得标记为「正常完成」
  let streamHadTaskError = false

  try {
    const sessionId = convId.startsWith('local-') ? undefined : parseInt(convId)

    // 构建thinking内容
    let thinkingContent = ''
    // 追踪上一个事件类型：thinking→content 切换时需要补换行（与历史消息加载逻辑一致）
    let lastEventType: string | null = null

    for await (const event of streamChat(finalMessage, sessionId, {
      promptFileName: PROMPT_FILE,
      executionMode: agentRuntime.value.executionMode,
      projectRoot: agentRuntime.value.workDir || settingsStore.projectRoot || undefined,
      model: agentRuntime.value.model,
      thinkingMode: agentRuntime.value.thinkingMode,
      turnId,
      agentConfigId: currentAgentConfigId.value,
      providerCode: agentRuntime.value.providerCode || undefined,
      contextMode: settingsStore.contextMode,
      // M7：文件资产ID（图片 + 文档统一通道；后端建立引用并分流注入）
      fileAssetIds: fileAssetIds.length > 0 ? fileAssetIds : undefined
    }, abortCtrl)) {
      // 每个事件都检查 sessionId —— 后端在第一个 SSE 事件中就返回了真实会话 ID，
      // 但不在流式过程中迁移 convId（避免数组引用变动导致内容重复），
      // 流式结束后在 finally 中统一迁移。
      // 这里只保存 realSessionId，确保用户过早停止时 finally 中也能正确迁移。
      if (convId.startsWith('local-') && event.sessionId && !realSessionId) {
        realSessionId = String(event.sessionId)
      }
      // ★ 提前设置 currentStreamSessionId，让补充需求按钮能在流式早期就拿到真实会话 ID
      if (event.sessionId && !currentStreamSessionId.value) {
        currentStreamSessionId.value = event.sessionId
      }
      if (event.type === 'thinking') {
        setStreamStatus(convId, '思考分析中...')
        // 检测工具调用结果标记，分离存储
        const markerIdx = event.data.indexOf('----工具调用:----')
        if (markerIdx !== -1) {
          setStreamStatus(convId, '执行工具中...')
          const contentStart = event.data.indexOf('\n', markerIdx)
          const toolContent = contentStart !== -1
            ? event.data.substring(contentStart + 1).trim()
            : ''
          if (toolContent) {
            // 解析工具名（从 "> **toolName**" 格式中提取）
            const toolNameMatch = toolContent.match(/^>\s*\*\*(.+?)\*\*/)
            const resolvedToolName = toolNameMatch ? toolNameMatch[1].trim() : undefined
            // 查找是否有对应的 pending 条目（通过工具名匹配），有则更新，无则新增
            const pendingIdx = assistantMsg.toolResults!.findIndex(
              tr => tr.pending && tr.toolName && resolvedToolName && tr.toolName === resolvedToolName
            )
            if (pendingIdx !== -1) {
              // 更新 pending 条目为完成状态
              assistantMsg.toolResults![pendingIdx].content = toolContent
              assistantMsg.toolResults![pendingIdx].pending = false
            } else {
              // 无匹配的 pending 条目，新增
              assistantMsg.toolResults!.push({
                at: thinkingContent.length,
                content: toolContent,
                toolName: resolvedToolName
              })
            }
            // 解析 task_manager 工具的任务清单（P2：按会话隔离）
            console.log('[TaskList] toolContent:', toolContent.substring(0, 200))
            parseTaskManagerResult(toolContent, convId)
            // ★ 技能命中兜底：若摘要通道缺失，从技能加载结果（skill load 固定输出「【技能详情】xxx (ID:n)」）解析
            const skillDetailMatch = toolContent.match(/【技能详情】(.+?)\s*\(ID:/)
            if (skillDetailMatch) {
              const hitName = skillDetailMatch[1].trim()
              if (hitName) {
                if (!assistantMsg.hitSkills) assistantMsg.hitSkills = []
                if (!assistantMsg.hitSkills.includes(hitName)) assistantMsg.hitSkills.push(hitName)
              }
            }
          }
        } else {
          thinkingContent += event.data
        }
        assistantMsg.thinking = thinkingContent
        lastEventType = 'thinking'
        // 节流刷新（携带思考消息ID，更新 DOM 后自动滚动到底部）
        scheduleMessageUpdate(convId, assistantMsg.id)
      } else if (event.type === 'tool_call_start') {
        // 工具调用开始事件：创建 pending 条目，显示加载动画
        setStreamStatus(convId, '执行工具中...')
        const tcData = event.data as { tools: string[]; summaries: string[] }
        const tools = tcData.tools || []
        const summaries = tcData.summaries || []
        maybeShowDesktopPrivacyNotice(tools)
        // ★ 技能命中（动态展示）：后端在 skill action=load 时生成「🎯 命中技能」操作摘要，
        //   随 tool_call_start.summaries 一起下发——到此才代表 LLM 真正选中执行该技能
        for (const s of summaries) {
          if (!s) continue
          const m = s.match(/🎯\s*命中技能[:：]?\*{0,2}\s*(.+)/)
          const hitName = m ? m[1].trim() : ''
          if (hitName) {
            if (!assistantMsg.hitSkills) assistantMsg.hitSkills = []
            if (!assistantMsg.hitSkills.includes(hitName)) assistantMsg.hitSkills.push(hitName)
          }
        }
        for (let i = 0; i < tools.length; i++) {
          assistantMsg.toolResults!.push({
            at: thinkingContent.length,
            content: '',
            toolName: tools[i],
            pending: true,
            operationSummary: summaries[i] || ''
          })
        }
        scheduleMessageUpdate(convId, assistantMsg.id)
      } else if (event.type === 'delegate_progress') {
        // 委托进度心跳（2026-09-21）：更新当前 agent_invoke pending 卡片为「已委托「B」· 已等待 N 秒」+ 跳转入口
        if (updateAgentInvokePending(assistantMsg, event.data as DelegateProgressData)) {
          scheduleMessageUpdate(convId, assistantMsg.id)
        }
      } else if (event.type === 'content') {
        setStreamStatus(convId, '正在生成回答...')
        // 实时更新消息内容
        if (event.data) {
          // thinking→content 切换时，若已有内容则补换行（与历史消息加载 processMessageGroups 逻辑一致）
          if (lastEventType === 'thinking' && assistantMsg.content) {
            assistantMsg.content += '\n'
          }
          assistantMsg.content += event.data
          lastEventType = 'content'
        }
        // 第一个 content 事件中获取 sessionId（后端在第一个事件中就会返回）
        if (event.sessionId && !currentStreamSessionId.value) {
          currentStreamSessionId.value = event.sessionId
        }
        // 始终同步更新 backgroundStreams 中的 realSessionId（后台流式场景，不依赖 !realSessionId 条件）
        if (event.sessionId) {
          const bgEntry = backgroundStreams.value.find(s => s.convId === convId)
          if (bgEntry) {
            bgEntry.realSessionId = event.sessionId
          }
        }
        scheduleMessageUpdate(convId)
      } else if (event.type === 'task_error') {
        // ★ 任务因 LLM 接口错误停止（非人为停止、非正常完成）：
        //   1) 消息区展示友好错误详情（标题+原因+解决建议，映射自 DeepSeek 官方错误码表）
        //   2) 会话级中断状态 → 输入区横幅 + 「继续任务」一键续跑入口
        const errData = event.data as TaskErrorData
        assistantMsg.taskError = errData
        assistantMsg.isStreaming = false
        streamHadTaskError = true
        const errConvId = realSessionId || convId
        const interruptedInfo: InterruptedTaskInfo = {
          title: errData.title || '任务已中断',
          message: errData.message,
          status: errData.status,
          retryable: errData.retryable
        }
        interruptedTasks.value[errConvId] = interruptedInfo
        if (errConvId !== convId) {
          // 局部会话尚未迁移：同时挂到当前键，保证横幅即时可见
          interruptedTasks.value[convId] = interruptedInfo
        }
        setStreamStatus(convId, '')
        flushMessageUpdate(convId, assistantMsg.id)
        message.error(errData.title ? `任务中断：${errData.title}` : '任务已中断')
      } else if (event.type === 'complete' && event.sessionId) {
        // 保存真实会话ID，流式结束后在 finally 中统一迁移
        realSessionId = String(event.sessionId)
        // 同步更新 backgroundStreams 中的 realSessionId，用于停止时取消后端任务
        const bgEntry = backgroundStreams.value.find(s => s.convId === convId)
        if (bgEntry) {
          bgEntry.realSessionId = event.sessionId
        }
        if (convId.startsWith('local-') && realSessionId) {
          const localConv = conversations.value.find(c => c.id === convId)
          if (localConv) {
            localConv.id = realSessionId
            localConv.isLocal = false
          }
        }
      } else if (event.type === 'ask_user') {
        try {
          // P3：按会话写入待审批问题（Map 化）——切走后该会话的授权请求保留，切回自动恢复弹窗
          const isCurrentConv = convId === currentConversationId.value
          // 后台流时从 backgroundStreams 取归属 agentConfigId（切走时已记录）
          const bg = backgroundStreams.value.find(s => s.convId === convId)
          const meta = getAgentMeta(bg?.agentConfigId ?? currentAgentConfigId.value)
          setPendingQuestion(convId, {
            uuid: event.data.uuid,
            question: event.data.question,
            askType: event.data.askType || 'clarification',
            toolName: event.data.toolName,
            filePath: event.data.filePath,
            fullDetail: event.data.fullDetail,
            agentName: meta.name,
            workDir: meta.workDir || agentRuntime.value.workDir
          })
          pendingQuestionAnswer.value = ''
          // 后台任务等待授权时提示用户（不弹窗到当前界面，防误授权）
          if (!isCurrentConv) {
            // 头像同样取归属 Agent 的真实 avatar（原写死 🤖，多智能体并行时会货不对板）
            message.open({ type: 'info', content: buildAgentNotice(meta, `${meta.name} 的后台任务正在等待授权，切换过去即可处理`) })
          }
        } catch (e) {
          console.warn('解析 ask_user 事件失败:', e)
        }
      } else if (event.type === 'agent_event') {
          const agentEvent = event.data as AgentStreamEvent
          handleAgentEvent(agentEvent)
        }
    }

    // 计算token
    const fullContent = assistantMsg.content
    const fullThinking = assistantMsg.thinking
    const toolResultsText = (assistantMsg.toolResults || []).map(r => r.content).join('')
    assistantMsg.tokenCount = estimateTokenCount(fullContent) + (fullThinking ? estimateTokenCount(fullThinking) : 0) + estimateTokenCount(toolResultsText)
    assistantMsg.isStreaming = false
    setStreamStatus(convId, '')
    flushMessageUpdate(convId, assistantMsg.id)

    // 查询快照，匹配当前消息的 turnId
    if (!convId.startsWith('local-') && turnId) {
      matchSnapshotsForMessages(convId)
      // 加载会话改动统计
      loadSessionChanges(convId)
    }

    // 消息不为空时更新会话标题
    if (conv && !conv.isLocal) {
      // 从后端刷新获取标题
    }
    // H1 修复：try 正常走完（流式完整结束无异常/无中止）才标记完成；
    // ★ 收到 task_error（任务错误停止）时不算正常完成，避免误报「后台任务已完成」通知
    streamCompleted = !streamHadTaskError
  } catch (error: any) {
    if (error.message === '__USER_ABORT__') {
      console.log('用户中断流式响应')
      assistantMsg.isStreaming = false
      setStreamStatus(convId, '')
      flushMessageUpdate(convId, assistantMsg.id)
      // 如果已经获取到真实会话ID，提前迁移 convId，避免 finally 中迁移触发 checkAndReconnect 重连
      if (realSessionId) {
        messages.value[realSessionId] = messages.value[convId]
        migratePaginationState(convId, realSessionId)
        delete messages.value[convId]
        currentConversationId.value = realSessionId
        convId = realSessionId
      }
      return
    }
    console.error('发送消息失败:', error)
    assistantMsg.content = error.message || '发送失败，请重试'
    assistantMsg.isStreaming = false
    setStreamStatus(convId, '')
    flushMessageUpdate(convId, assistantMsg.id)
    message.error('发送失败: ' + (error.message || '未知错误'))
  } finally {
    setSending(convId, false)
    // ★ 兜底清空输入框：确保消息发送后输入条一定被清空
    //    （第 2257 行已有 inputMessage.value = ''，但若发送过程中出现异常提前 return，
    //      或 Vue 响应式更新时序问题导致视图未更新，这里再次清空确保万无一失）
    if (inputMessage.value !== '') {
      inputMessage.value = ''
    }
    stopElapsedTimer()
    setStreamStatus(convId, '')
    stopAbortController.value = null
    // 清除 activeStreamInfo（流式已结束，可能是正常结束或被 stopStreaming 提前清掉）
    if (activeStreamInfo.value?.convId === convId) {
      activeStreamInfo.value = null
    }
    // 通知后台流式管理：当前流式已结束（H1：携带是否正常完成标志——中止/失败不误报完成通知；
    // 三轮审查：携带 abortCtrl 精确移除本流条目，避免误删同会话其他仍在运行的后台流）
    onStreamComplete(convId, currentAgentConfigId.value, streamCompleted, abortCtrl)
    scrollToBottom()
    // 流式结束后统一迁移 convId（避免流式过程中数组引用变动导致内容重复）
    if (convId.startsWith('local-') && realSessionId) {
      messages.value[realSessionId] = messages.value[convId]
      migratePaginationState(convId, realSessionId)
      delete messages.value[convId]
      // 只有当前还在看这个会话，才迁移 currentConversationId
      if (currentConversationId.value === convId) {
        currentConversationId.value = realSessionId
      } else {
        // 用户已切走，更新快照中的 convId 以保持一致性
        for (const key in agentSnapshots.value) {
          if (agentSnapshots.value[key]?.currentConversationId === convId) {
            agentSnapshots.value[key].currentConversationId = realSessionId
            break
          }
        }
      }
      convId = realSessionId
    }
    // 流式完全结束后再刷新会话列表
    if (!convId.startsWith('local-')) {
      fetchConversations()
    }
    // ★ 每次对话结束后刷新一次 DeepSeek 余额（当前 Provider 为 DeepSeek 时自动展示）
    refreshBalance()
  }
}

/** 「继续任务」指令文案：任务异常中断后一键续跑（程序化发送，用户无需复制上次发言重发） */
const CONTINUE_TASK_TEXT = '请从中断处继续完成刚才因错误中断的任务。注意：先检查当前进度，不要重复已完成的工作。'

/**
 * 「继续任务」：一键续跑异常中断的任务。
 * 程序化发送续跑指令（等价于用户主动发消息，但无需手动复制重发）——
 * sendMessage 开头会清除该会话的「任务中断」提示并走正常发送流程；
 * 若本轮再次异常停止，新的 task_error 事件会重新写入中断状态。
 */
const continueInterruptedTask = async () => {
  const cid = currentConversationId.value
  if (!cid || !interruptedTasks.value[cid] || hasRunningTask.value) return
  await sendMessage(CONTINUE_TASK_TEXT)
}

const handleShiftEnter = () => {
  // Shift+Enter 会由 textarea 自动处理为换行
}

// ===== 后台任务重连 =====

const reconnectToTaskStream = async (convId: number, cursor = 0) => {
  const stringConvId = String(convId)
  setStreamStatus(stringConvId, '任务恢复中...')
  // ★ 重连 = 该会话任务「恢复前台查看」：必须补记「前台流式」标记（sendingStates）。
  //   补充需求面板（hasRunningTask）、停止按钮（hasActiveStream）、输入框 loading 等运行态 UI 都依赖它；
  //   原实现只设了 streamStatus / activeTask，缺少 sendingStates →
  //   表现为「切回智能体后流能继续输出，但『补充需求』和『结束当前任务』按钮都不见了」。
  setSending(stringConvId, true)
  // P2：重连是「该会话后台任务恢复前台查看」，不再额外锁输入框判据——hasRunningTask 已统一覆盖
  startElapsedTimer()

  // 游标续传模式下历史事件（seq <= cursor）不再重放，先强制刷新 DB 完整内容作为续接基线，
  // 后续增量 content/thinking 追加到该基线上（根治重放历史内容导致的一句话重复两次）
  await fetchMessages(stringConvId, true)

  // 确保消息数组存在
  if (!messages.value[stringConvId]) {
    messages.value[stringConvId] = []
  }
  const msgs = messages.value[stringConvId]

  // 查找最后一个 assistant 消息作为续接目标
  let targetMsg: ChatMessage | undefined
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i].role === 'assistant') {
      targetMsg = msgs[i]
      break
    }
  }

  // 没有 assistant 消息则新建一个
  if (!targetMsg) {
    targetMsg = {
      id: `reconnect-${Date.now()}-${++localMsgSeq}`,
      role: 'assistant',
      content: '',
      thinking: '',
      toolResults: [],
      timestamp: Date.now(),
      isStreaming: true
    }
    msgs.push(targetMsg)
  } else {
    // 已有消息，设为 streaming 状态让 UI 显示加载动画
    targetMsg.isStreaming = true
  }
  let thinkingContent = targetMsg.thinking || ''
  // 追踪上一个事件类型：thinking→content 切换时需要补换行
  let lastEventType: string | null = null

  const abortCtrl = new AbortController()
  stopAbortController.value = abortCtrl

  try {
    for await (const event of taskStream(convId, abortCtrl, cursor)) {
      if (event.type === 'thinking') {
        const data = typeof event.data === 'string' ? event.data : ''
        const markerIdx = data.indexOf('----工具调用:----')
        if (markerIdx !== -1) {
          setStreamStatus(stringConvId, '执行工具中...')
          const contentStart = data.indexOf('\n', markerIdx)
          const toolContent = contentStart !== -1
            ? data.substring(contentStart + 1).trim()
            : ''
          if (toolContent) {
            // 后端使用 Replay Sink（Sinks.unsafe().many().replay().limit(100000)），
            // 页面刷新后重连时会重放所有历史事件，导致工具结果重复添加到 toolResults。
            // 通过比较 content 内容去重，避免工具卡片在页面上重复显示。
            const isDuplicate = targetMsg.toolResults!.some(
              existing => existing.content === toolContent
            )
            if (!isDuplicate) {
              targetMsg.toolResults!.push({
                at: thinkingContent.length,
                content: toolContent
              })
            }
            parseTaskManagerResult(toolContent, stringConvId)
          }
        } else {
          // 普通 thinking 内容：Replay Sink 重放的历史内容已在 thinkingContent 中，跳过重复追加
          if (!thinkingContent.endsWith(data)) {
            thinkingContent += data
          }
        }
        targetMsg.thinking = thinkingContent
        lastEventType = 'thinking'
        // 携带思考消息ID，更新 DOM 后自动滚动到底部
        scheduleMessageUpdate(stringConvId, targetMsg.id)
      } else if (event.type === 'content') {
        setStreamStatus(stringConvId, '正在生成回答...')
        // thinking→content 切换时，若已有内容则补换行（与历史消息加载 processMessageGroups 逻辑一致）
        if (lastEventType === 'thinking' && targetMsg.content) {
          targetMsg.content = (targetMsg.content || '') + '\n' + event.data
        } else {
          targetMsg.content = (targetMsg.content || '') + event.data
        }
        lastEventType = 'content'
        scheduleMessageUpdate(stringConvId)
      } else if (event.type === 'ask_user') {
        setStreamStatus(stringConvId, '等待用户授权...')
        // P3：重连路径也按会话写入（带归属标识）
        const bg = backgroundStreams.value.find(s => s.convId === stringConvId)
        const meta = getAgentMeta(bg?.agentConfigId ?? currentAgentConfigId.value)
        setPendingQuestion(stringConvId, {
          uuid: event.data.uuid,
          question: event.data.question,
          askType: event.data.askType || 'clarification',
          toolName: event.data.toolName,
          filePath: event.data.filePath,
          fullDetail: event.data.fullDetail,
          agentName: meta.name,
          workDir: meta.workDir || agentRuntime.value.workDir
        })
        if (stringConvId === currentConversationId.value) {
          pendingQuestionAnswer.value = ''
        }
      } else if (event.type === 'resume') {
        setStreamStatus(stringConvId, '继续执行中...')
      } else if (event.type === 'delegate_progress') {
        // 委托进度心跳（2026-09-21，重连场景）：更新最近的 agent_invoke pending 卡片（无 pending 条目时忽略）
        if (targetMsg && updateAgentInvokePending(targetMsg, event.data as DelegateProgressData)) {
          scheduleMessageUpdate(stringConvId, targetMsg.id)
        }
      } else if (event.type === 'task_error') {
        // ★ 重连场景：后台任务因 LLM 接口错误停止——记录错误详情 + 恢复「一键继续」入口
        const errData = event.data as TaskErrorData
        if (targetMsg) {
          targetMsg.taskError = errData
          flushMessageUpdate(stringConvId, targetMsg.id)
        }
        interruptedTasks.value[stringConvId] = {
          title: errData.title || '任务已中断',
          message: errData.message,
          status: errData.status,
          retryable: errData.retryable
        }
        message.error(errData.title ? `任务中断：${errData.title}` : '任务已中断')
      } else if (event.type === 'complete') {
        break
      }
    }

    // 流结束，取消 streaming 状态
    if (targetMsg) {
      targetMsg.isStreaming = false
    }
    setStreamStatus(stringConvId, '')
    flushMessageUpdate(stringConvId, targetMsg.id)
  } catch (error: any) {
    if (error?.name === 'AbortError') {
      console.log('重连任务流被用户终止')
    } else {
      console.warn('重连任务流失败:', error)
    }
  } finally {
    // ★ 重连流结束：清掉「前台流式」标记（停止按钮 / 补充需求面板随之隐藏）
    setSending(stringConvId, false)
    setStreamStatus(stringConvId, '')
    stopElapsedTimer()
    setActiveTask(stringConvId, null)
    stopAbortController.value = null
    // 任务可能已完成或有新消息，从 DB 强制刷新最新消息列表（绕过缓存）
    await fetchMessages(stringConvId, true)
    // ★ 后台任务（对话）结束后刷新一次 DeepSeek 余额
    refreshBalance()
  }
}

// H3 修复：重连防重入标志（reconnectToTaskStream 建立的流不记录在 backgroundStreams，
// 原「backgroundStreams.some」防重入对重连流无效——快速切换 Agent（B→A→B）会对同一会话
// 发起多个并发重连 → 重复事件流/重复 complete 处理/消息内容重复。标志在重连流真正结束后才清除）
// ★ 2026-08-14：改为 reactive Set——hasRunningTask（输入框锁定）依赖该标志，
//   非响应式 Set 的 add/delete 不会触发 computed 重算，重连开始/结束期间输入框无法正确锁定/解锁
const reconnectingConvIds = reactive(new Set<string>())

const checkAndReconnect = async (convId: string) => {
  if (convId.startsWith('local-')) return
  // 如果该会话已经在后台流式运行中，跳过重连（避免双流式冲突）
  if (backgroundStreams.value.some(s => s.convId === convId)) return
  // H3：重连进行中（checkActiveTask 或 reconnectToTaskStream 未结束）则跳过，防并发双流
  if (reconnectingConvIds.has(convId)) return
  reconnectingConvIds.add(convId)
  try {
    const task = await checkActiveTask(parseInt(convId))
    if (task.active) {
      setActiveTask(convId, task as any)
      // 如果有待审批问题，立即展示审批对话框（页面刷新后重连；P3：按会话写入 + 带归属标识）
      if (task.pendingQuestionUuid) {
        const meta = getAgentMeta(currentAgentConfigId.value)
        // 页面刷新 / 切回智能体后恢复待处理问题：agent_task 只持久化 uuid+文本，
        // 按文本前缀识别「任务迭代确认」（评委否决 / 扩容达上限）面板，其余按权限审批展示
        const pendingText = task.pendingQuestionText || '请确认是否执行以上操作'
        const restoredAskType = pendingText.startsWith('【任务迭代确认】') ? 'iteration_confirm' : 'permission'
        setPendingQuestion(convId, {
          uuid: task.pendingQuestionUuid,
          question: pendingText,
          askType: restoredAskType,
          agentName: meta.name,
          workDir: meta.workDir || agentRuntime.value.workDir
        })
        pendingQuestionAnswer.value = ''
        message.info('检测到有待审批的操作，请确认')
      }
      if (task.status === 'running') {
        // 任务仍在运行，携带已消费游标连接事件流（只接收增量事件，历史内容已由 DB 兜底）
        // H3：await 重连流结束——标志在 finally 清理，期间重复 checkAndReconnect 被拦截
        await reconnectToTaskStream(parseInt(convId), task.seq || 0)
      } else {
        // 任务已完成/失败/取消，清除 activeTask（数据已在 DB 中）
        setActiveTask(convId, null)
        // 缺陷B修复（2026-09-18）：任务已结束的会话强制刷新消息，避免停留过期缓存
        //（如委托任务完成后再切回时界面仍是旧内容、需重启软件才可见）
        await fetchMessages(convId, true)
      }
    } else if (task.lastTask && task.lastTask.status === 'failed') {
      // ★ 任务上次异常停止（错误停止，非人为停止/非正常完成）：恢复「任务中断」提示
      //   与「一键继续」入口（输入区横幅）。用户手动停止（cancelled）/正常完成不触发。
      //   后端已保证：仅「最近一次任务 failed 且之后没有新用户消息」时返回该摘要。
      interruptedTasks.value[convId] = {
        title: '上次任务执行失败',
        message: task.lastTask.errorMessage || '任务因错误中断，可点击「继续任务」重试。'
      }
    }
  } catch (e) {
    // 网络抖动/后端不可达：不抛未处理异常，仅记录（重连失败不阻断 UI）
    console.warn('检查后台任务失败:', e)
  } finally {
    reconnectingConvIds.delete(convId)
  }
}

// 思考过程可见性
const thinkingVisible = ref<Set<string>>(new Set())

const toggleThinkingVisibility = (msgId: string) => {
  const s = new Set(thinkingVisible.value)
  if (s.has(msgId)) s.delete(msgId)
  else s.add(msgId)
  thinkingVisible.value = s
}

const getThinkingVisible = (msgId: string) => thinkingVisible.value.has(msgId)

// 格式化
const formatTime = (timestamp: number) => {
  const d = new Date(timestamp)
  const now = new Date()
  const isToday = d.toDateString() === now.toDateString()
  if (isToday) {
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  }
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const formatMessageTime = (timestamp: number) => {
  const d = new Date(timestamp)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
}

/**
 * 从工具调用结果内容中解析工具名称（格式：> **toolName**）
 */
function parseToolNameLine(content: string): { toolName: string; cleanContent: string } {
  const firstLine = content.split('\n')[0]
  const match = firstLine.match(/^> \*\*(.+?)\*\*$/)
  if (match) {
    const rest = content.indexOf('\n')
    return {
      toolName: match[1],
      cleanContent: rest !== -1 ? content.substring(rest + 1).trim() : ''
    }
  }
  return { toolName: '', cleanContent: content }
}

/**
 * 从工具调用结果内容中提取具体操作类型（如 read/glob/write/edit 等）
 * 解析结果内容前几行中的关键信息
 *
 * 后端各工具输出格式速查：
 *
 * file_explorer read(文件):  `路径: ...\n编码: ...\n类型: 文件\n行数: ...`
 * file_explorer read(目录):  `路径: ...\n类型: 目录\n条目数: ...`
 * file_explorer glob:        `搜索模式：xxx\n...\n匹配结果：共 N 个文件\n`
 * file_explorer grep(纯文本):`搜索模式：xxx（纯文本）\n...\n匹配结果：共 N 个\n`
 * file_explorer grep(正则):  `搜索模式：xxx（正则）\n...\n匹配结果：共 N 个\n`
 * file_explorer grep(默认):  `搜索模式：xxx\n...\n匹配结果：共 N 个\n`  ← 没括号后缀！
 * file_explorer tree:        `项目目录：...\n────────────────\n【概览树】`
 * file_explorer tree(空):    `（空目录）`
 *
 * file_writer write:         `✅ 写入成功：...\n大小：...`
 * file_writer edit:          `✅ 编辑成功：...\n匹配位置：第 N~M 行`
 * file_writer delete(文件):  `✅ 文件已删除：...`
 * file_writer delete(目录):  `✅ 目录已删除：...`
 *
 * command exec:              `执行命令：xxx`
 * command start:             `后台服务已启动`
 * command logs:              `服务 #N 输出（共 N 行）：`
 * command list:              `后台服务列表：`
 * command stop:              `服务 #N 已停止`
 *
 * task_manager create:       `✅ 任务清单已创建（共 N 项）：`
 * task_manager complete:     `✅ 任务 T1 已完成！`
 * task_manager batch_complete:`✅ 批量完成任务：T1, T2`
 * task_manager list:         `📋 任务进度列表（共 N 项）：`
 *
 * 错误格式：`【缺少参数】...` / `【目录不存在】...` / `❌ 错误：...`
 */
function extractActionFromToolContent(content: string, toolName: string): string {
  const firstLines = content.split('\n').slice(0, 5).join('\n')
  const isError = /^【[^】]+】|^❌\s*错误/.test(firstLines)

  // ============ 按工具类型分类 ============
  switch (toolName) {
    // ======== file_explorer ========
    case 'file_explorer': {
      // 优先识别错误类型，从错误内容推断意图
      if (isError) {
        if (/action=(\w+)/.test(content)) {
          const act = content.match(/action=(\w+)/)![1]
          const map: Record<string, string> = { read: '读取', glob: '搜索文件', grep: '搜索内容', tree: '目录树' }
          return map[act] || act
        }
        // 常见参数缺失错误
        if (/action=glob 需要 pattern/.test(firstLines)) return '搜索文件'
        if (/action=grep 需要 pattern/.test(firstLines)) return '搜索内容'
        if (/需要 action/.test(firstLines)) return '操作'
        if (/【目录不存在】|【不是目录】/.test(firstLines)) return '操作'
        if (/【路径越界】|【路径不存在】/.test(firstLines)) return '操作'
        if (/【include 语法错误】/.test(firstLines)) return '搜索内容'
        if (/【glob 语法错误】/.test(firstLines)) return '搜索文件'
        if (/【正则表达式语法错误】/.test(firstLines)) return '搜索内容'
        if (/【搜索异常】/.test(firstLines)) return '搜索文件'
        if (/【读取异常】/.test(firstLines)) return '读取'
        // 未知 action 错误
        if (/未知的 action/.test(firstLines)) {
          const actMatch = content.match(/支持 ([a-z/ ]+)/)
          if (actMatch) return actMatch[1].split('/')[0]
          return '操作'
        }
        return '操作'
      }

      // read → 路径: xxx\n编码: xxx\n类型: 文件/目录
      if (/^路径:/.test(firstLines) && /类型: (文件|目录)/.test(firstLines)) return '读取'
      if (/^路径:/.test(firstLines) && /条目数/.test(firstLines)) return '读取目录'
      if (/提示：文件为空|起始行.*超出|文件不存在/.test(firstLines)) return '读取'

      // grep → 搜索模式：xxx（带纯文本/正则后缀）或 搜索模式：xxx（默认isRegex=true无后缀）
      // 关键区分：grep 的匹配结果行是「共 N 个」不带"文件"二字
      if (/搜索模式：/.test(firstLines)) {
        // 单独处理正则/纯文本后缀的（这些一定是grep）
        if (/搜索模式：.*[（(]纯文本[）)]/.test(firstLines)) return '搜索内容'
        if (/搜索模式：.*[（(]正则[）)]/.test(firstLines)) return '搜索内容'
        if (/搜索模式：.*[（(]忽略大小写[）)]/.test(firstLines)) return '搜索内容'
        // 用匹配结果行区分：glob 说「共 N 个文件」，grep 说「共 N 个」
        if (/匹配结果：共 \d+ 个文件/.test(firstLines)) return '搜索文件'
        if (/匹配结果：共 \d+ 个/.test(firstLines)) return '搜索内容'
        // 没有匹配结果行时，看后续有没有文件路径列表（glob特征）
        const allContentLines = content.split('\n').slice(0, 10).join('\n')
        if (/────────────────────────────────────────[\s\S]*  \d/.test(allContentLines)) return '搜索文件'
        return '搜索内容'
      }

      // tree → 项目目录：... 或（空目录）
      if (/^项目目录：/.test(firstLines) || /【概览树】/.test(firstLines)) return '目录树'
      if (/^（空目录）/.test(firstLines)) return '目录树'

      // 兜底
      const actionMatch = content.match(/action=(\w+)/)
      if (actionMatch) {
        const act = actionMatch[1]
        const map: Record<string, string> = { read: '读取', glob: '搜索文件', grep: '搜索内容', tree: '目录树' }
        return map[act] || act
      }
      return '操作'
    }

    // ======== file_writer ========
    case 'file_writer': {
      if (isError) {
        if (/action=delete/.test(content)) return '删除'
        if (/action=edit/.test(content)) return '编辑'
        if (/action=write/.test(content)) return '写入'
        if (/需要 file_path/.test(firstLines)) return '操作'
        if (/需要 old_text/.test(firstLines)) return '编辑'
        if (/需要 force/.test(firstLines)) return '写入'
        if (/【安全限制】/.test(firstLines)) return '删除'
        if (/【文件不存在】/.test(firstLines)) {
          if (/edit/.test(firstLines)) return '编辑'
          return '删除'
        }
        if (/【匹配失败】|【匹配冲突】/.test(firstLines)) return '编辑'
        if (/【写入失败】/.test(firstLines)) return '写入'
        if (/【编辑失败】/.test(firstLines)) return '编辑'
        if (/【删除失败】|【部分失败】/.test(firstLines)) return '删除'
        if (/【删除确认】/.test(firstLines)) return '删除'
        if (/【路径类型错误】/.test(firstLines)) return '编辑'
        if (/【权限不足】/.test(firstLines)) return '编辑'
        if (/【并发写入冲突】/.test(firstLines)) return '写入'
        return '操作'
      }
      // write → ✅ 写入成功：...
      if (/✅.*写入成功/.test(firstLines)) return '写入'
      // edit → ✅ 编辑成功：...
      if (/✅.*编辑成功/.test(firstLines)) return '编辑'
      // delete → ✅ 文件已删除 / ✅ 目录已删除
      if (/✅.*已删除/.test(firstLines)) return '删除'
      if (/文件已删除|目录已删除/.test(firstLines)) return '删除'
      // 兜底
      const actionMatch = content.match(/action=(\w+)/)
      if (actionMatch) {
        const act = actionMatch[1]
        const map: Record<string, string> = { write: '写入', edit: '编辑', delete: '删除' }
        return map[act] || act
      }
      return '操作'
    }

    // ======== command ========
    case 'command': {
      if (isError) {
        if (/action=(\w+)/.test(content)) {
          const act = content.match(/action=(\w+)/)![1]
          const map: Record<string, string> = { exec: '执行命令', start: '启动服务', logs: '查看日志', list: '列出服务', stop: '停止服务' }
          return map[act] || act
        }
        if (/【参数缺失】|【参数错误】/.test(firstLines)) return '参数错误'
        if (/【并发限制】/.test(firstLines)) return '启动服务'
        if (/【启动失败】/.test(firstLines)) return '启动服务'
        if (/【命令未找到】|【执行异常】|【执行中断】/.test(firstLines)) return '执行命令'
        return '执行'
      }
      if (/^执行命令：/.test(firstLines)) return '执行命令'
      if (/后台服务已启动/.test(firstLines)) return '启动服务'
      if (/服务 #\d+ 输出/.test(firstLines)) return '查看日志'
      if (/（服务运行中，暂无输出）/ .test(firstLines)) return '查看日志'
      if (/（服务已结束，无输出）/.test(firstLines)) return '查看日志'
      if (/^后台服务列表：/.test(firstLines)) return '列出服务'
      if (/（无后台服务）/.test(firstLines)) return '列出服务'
      if (/服务 #\d+ 已停止/.test(firstLines)) return '停止服务'
      if (/不存在.*服务/.test(firstLines)) return '停止服务'
      // 兜底
      const cmdMatch = content.match(/action=(\w+)/)
      if (cmdMatch) {
        const act = cmdMatch[1]
        const map: Record<string, string> = { exec: '执行命令', start: '启动服务', logs: '查看日志', list: '列出服务', stop: '停止服务' }
        return map[act] || act
      }
      return '执行'
    }

    // ======== execute_sql ========
    case 'execute_sql': {
      if (isError) {
        if (/SQL 过长/.test(firstLines)) return 'SQL'
        if (/多语句禁止/.test(firstLines)) return 'SQL'
        if (/SQL 类型未知/.test(firstLines)) return 'SQL'
        if (/SQL 执行异常/.test(firstLines)) return 'SQL'
        return 'SQL'
      }
      if (/查询完成|SELECT/.test(firstLines)) return 'SQL查询'
      if (/执行成功，影响/.test(firstLines)) {
        // 从 SQL 内容判断是 INSERT/UPDATE/DELETE
        const sqlMatch = content.match(/sql=([^\n]+)/)
        if (sqlMatch) {
          const sql = sqlMatch[1].toUpperCase()
          if (/^INSERT/.test(sql)) return 'SQL插入'
          if (/^UPDATE/.test(sql)) return 'SQL更新'
          if (/^DELETE/.test(sql)) return 'SQL删除'
          if (/^CREATE|^DROP|^ALTER|^TRUNCATE/.test(sql)) return 'DDL变更'
        }
        return 'SQL写操作'
      }
      return 'SQL'
    }

    // ======== git_submit ========
    case 'git_submit': {
      if (isError) {
        if (/action=(\w+)/.test(content)) {
          const act = content.match(/action=(\w+)/)![1]
          const map: Record<string, string> = { add: '暂存', commit: '提交', push: '推送', pull: '拉取', fetch: '获取', unstage: '取消暂存', restore: '还原', reset: '回退', revert: '撤销提交' }
          return map[act] || act
        }
        return 'Git'
      }
      if (/📌|暂存/.test(firstLines)) return '暂存'
      if (/📦|提交|commit/.test(firstLines)) return '提交'
      if (/⬆️|推送|push/.test(firstLines)) return '推送'
      if (/拉取|pull/.test(firstLines)) return '拉取'
      if (/获取远程|fetch/.test(firstLines)) return '获取'
      const gsMatch = content.match(/action=(\w+)/)
      if (gsMatch) {
        const act = gsMatch[1]
        const map: Record<string, string> = { add: '暂存', commit: '提交', push: '推送', pull: '拉取', fetch: '获取', unstage: '取消暂存', restore: '还原', reset: '回退', revert: '撤销提交' }
        return map[act] || act
      }
      return 'Git'
    }

    // ======== git_query ========
    case 'git_query': {
      if (isError) return '查询'
      if (/状态|Status|status/.test(firstLines)) return '状态'
      if (/差异|diff|Diff/.test(firstLines)) return '差异'
      if (/^commit [0-9a-f]{6,}/m.test(content)) return '提交详情'
      if (/历史|log|提交|commit/.test(firstLines)) return '日志'
      return '查询'
    }

    // ======== git_branch ========
    case 'git_branch': {
      if (isError) {
        if (/action=(\w+)/.test(content)) {
          const act = content.match(/action=(\w+)/)![1]
          const map: Record<string, string> = { create: '创建分支', switch: '切换分支', delete: '删除分支', list: '列出分支', merge: '合并分支' }
          return map[act] || act
        }
        return '分支管理'
      }
      if (/创建分支/.test(firstLines)) return '创建分支'
      if (/切换分支/.test(firstLines)) return '切换分支'
      if (/删除分支/.test(firstLines)) return '删除分支'
      if (/合并/.test(firstLines)) return '合并分支'
      const gbMatch = content.match(/action=(\w+)/)
      if (gbMatch) {
        const act = gbMatch[1]
        const map: Record<string, string> = { create: '创建分支', switch: '切换分支', delete: '删除分支', list: '列出分支', merge: '合并分支' }
        return map[act] || act
      }
      return '分支管理'
    }

    // ======== git_stash ========
    case 'git_stash': {
      if (isError) {
        if (/action=(\w+)/.test(content)) {
          const act = content.match(/action=(\w+)/)![1]
          const map: Record<string, string> = { save: '储藏', list: '储藏列表', pop: '弹出储藏', drop: '删除储藏' }
          return map[act] || act
        }
        return '储藏'
      }
      if (/已储藏/.test(firstLines)) return '储藏'
      if (/储藏记录|没有储藏/.test(firstLines)) return '储藏列表'
      if (/已恢复储藏|已弹出/.test(firstLines)) return '弹出储藏'
      if (/已删除储藏/.test(firstLines)) return '删除储藏'
      const stashMatch = content.match(/action=(\w+)/)
      if (stashMatch) {
        const act = stashMatch[1]
        const map: Record<string, string> = { save: '储藏', list: '储藏列表', pop: '弹出储藏', drop: '删除储藏' }
        return map[act] || act
      }
      return '储藏'
    }

    // ======== git_tag ========
    case 'git_tag': {
      if (isError) {
        if (/action=(\w+)/.test(content)) {
          const act = content.match(/action=(\w+)/)![1]
          const map: Record<string, string> = { list: '标签列表', create: '创建标签', delete: '删除标签', push: '推送标签' }
          return map[act] || act
        }
        return '标签'
      }
      if (/标签列表|暂无标签/.test(firstLines)) return '标签列表'
      if (/创建成功/.test(firstLines)) return '创建标签'
      if (/已删除/.test(firstLines)) return '删除标签'
      if (/推送成功/.test(firstLines)) return '推送标签'
      const tagMatch = content.match(/action=(\w+)/)
      if (tagMatch) {
        const act = tagMatch[1]
        const map: Record<string, string> = { list: '标签列表', create: '创建标签', delete: '删除标签', push: '推送标签' }
        return map[act] || act
      }
      return '标签'
    }

    // ======== git_remote ========
    case 'git_remote': {
      if (isError) {
        if (/action=(\w+)/.test(content)) {
          const act = content.match(/action=(\w+)/)![1]
          const map: Record<string, string> = { list: '远程列表', add: '添加远程', set_url: '修改远程', remove: '删除远程' }
          return map[act] || act
        }
        return '远程仓库'
      }
      if (/远程仓库列表|未配置远程/.test(firstLines)) return '远程列表'
      if (/已添加/.test(firstLines)) return '添加远程'
      if (/已更新/.test(firstLines)) return '修改远程'
      if (/已删除/.test(firstLines)) return '删除远程'
      const remoteMatch = content.match(/action=(\w+)/)
      if (remoteMatch) {
        const act = remoteMatch[1]
        const map: Record<string, string> = { list: '远程列表', add: '添加远程', set_url: '修改远程', remove: '删除远程' }
        return map[act] || act
      }
      return '远程仓库'
    }

    // ======== task_manager ========
    case 'task_manager': {
      if (isError) {
        if (/action=(\w+)/.test(content)) {
          const act = content.match(/action=(\w+)/)![1]
          const map: Record<string, string> = { create: '创建任务', complete: '完成任务', batch_complete: '完成任务', batch_reopen: '重开任务', reopen: '重开任务', list: '列出任务' }
          return map[act] || act
        }
        if (/需要 task_id/.test(firstLines)) return '完成任务'
        if (/需要 task_ids/.test(firstLines)) return '完成任务'
        if (/需要 tasks/.test(firstLines)) return '创建任务'
        if (/无任务记录/.test(firstLines)) return '列出任务'
        return '任务管理'
      }
      // 先检查精确匹配的批量完成任务（避免被"创建任务"误匹配）
      if (/批量完成任务/.test(firstLines)) return '完成任务'
      if (/任务.*已完成/.test(firstLines)) return '完成任务'
      if (/已经标记为完成/.test(firstLines)) return '完成任务'
      if (/已是完成状态/.test(firstLines)) return '完成任务'
      // 创建任务
      if (/任务清单已创建/.test(firstLines)) return '创建任务'
      if (/创建了.*个任务/.test(firstLines)) return '创建任务'
      // 重开任务
      if (/已重新打开/.test(firstLines)) return '重开任务'
      if (/reopen/.test(firstLines)) return '重开任务'
      // 列表
      if (/任务进度列表|任务列表/.test(firstLines)) return '列出任务'
      if (/进度.*已完成/.test(firstLines)) return '列出任务'
      // 兜底
      const tmMatch = content.match(/action=(\w+)/)
      if (tmMatch) {
        const act = tmMatch[1]
        const map: Record<string, string> = { create: '创建任务', complete: '完成任务', batch_complete: '完成任务', batch_reopen: '重开任务', reopen: '重开任务', list: '列出任务' }
        return map[act] || act
      }
      return '任务管理'
    }

    // ======== 其他单操作工具 ========
    case 'web_search': return '搜索'
    case 'web_fetch':  return '获取网页'
    case 'http_request': return 'API请求'
    case 'check_network': return '网络检测'
    case 'ask_clarification': return '提问'
    case 'project_info': return '项目信息'
    case 'query_tool_history': return '查询历史'

    case 'skill': {
      if (isError) return '技能管理'
      if (/执行结果：成功/.test(firstLines)) return '技能完成'
      if (/执行结果：失败/.test(firstLines)) return '技能失败'
      return '技能管理'
    }

    case 'agent': {
      // agent 工具的 content 是执行结果文本（非调用参数），不含 "action=fork" 等字样。
      // 且 content 尾部可能含 instructions 中的 "action=collect"（系统提示词），
      // 因此只取前 5 行做第一行特征匹配，简单可靠。
      const line1 = content.split('\n')[0] || ''
      if (isError) {
        if (/创建|fork/i.test(line1)) return '创建子任务'
        if (/收集|collect/i.test(line1)) return '收集结果'
        return '子Agent'
      }
      // fork 第一行："✅ 子Agent「XXX」(ID: xxx) 已创建并在后台运行"
      if (/已创建.*后台运行|已在后台运行/.test(line1)) return '创建子任务'
      // collect 第一行："✅ 子Agent「XXX」执行完毕"
      if (/执行完毕/.test(line1)) return '收集结果'
      // inspect 第一行：可能含 "详情" 等
      if (/详情|inspect/i.test(line1)) return '查看详情'
      return '子Agent'
    }

    // ======== agent_invoke（Phase 19 智能体互调，2026-09-21 卡片标签） ========
    case 'agent_invoke': {
      const line1 = content.split('\n')[0] || ''
      if (isError) return '委托操作'
      if (/异步派发|委托已受理/.test(line1)) return '发起委托'
      if (/任务已完成/.test(line1)) return '委托完成'
      if (/任务失败|执行失败/.test(line1)) return '委托失败'
      if (/等待|超时|TIMEOUT/i.test(firstLines)) return '批量等待'
      if (/取消/.test(firstLines)) return '取消委托'
      if (/执行中|运行中|状态/.test(firstLines)) return '查询进度'
      return '委托操作'
    }

    // ======== schedule_task ========
    case 'schedule_task': {
      if (isError) {
        if (/action=(\w+)/.test(content)) {
          const act = content.match(/action=(\w+)/)![1]
          const map: Record<string, string> = { create: '创建定时任务', list: '查看列表', update: '修改任务', delete: '删除任务', toggle: '启停任务' }
          return map[act] || act
        }
        if (/【缺少参数】/.test(firstLines)) return '参数错误'
        if (/【不支持的操作】/.test(firstLines)) return '参数错误'
        if (/【任务不存在】/.test(firstLines)) return '查改任务'
        if (/【无法识别时间描述】/.test(firstLines)) return '创建任务'
        if (/【Cron 格式错误】/.test(firstLines)) return '创建任务'
        if (/【时间格式错误】/.test(firstLines)) return '创建任务'
        if (/【创建失败】/.test(firstLines)) return '创建任务'
        if (/【查询失败】/.test(firstLines)) return '查看列表'
        if (/【更新失败】/.test(firstLines)) return '修改任务'
        if (/【删除失败】/.test(firstLines)) return '删除任务'
        if (/【操作失败】/.test(firstLines)) return '启停任务'
        return '定时任务'
      }
      if (/定时任务创建成功/.test(firstLines)) return '创建任务'
      if (/定时任务列表/.test(firstLines)) return '查看列表'
      if (/定时任务.*已更新/.test(firstLines)) return '修改任务'
      if (/已删除定时任务/.test(firstLines)) return '删除任务'
      if (/🟢\s*已启用/.test(firstLines)) return '启用任务'
      if (/🔴\s*已禁用/.test(firstLines)) return '禁用任务'
      // 兜底
      const stMatch = content.match(/action=(\w+)/)
      if (stMatch) {
        const act = stMatch[1]
        const map: Record<string, string> = { create: '创建任务', list: '查看列表', update: '修改任务', delete: '删除任务', toggle: '启停任务' }
        return map[act] || act
      }
      return '定时任务'
    }

    default: return '操作'
  }
}

// ===== M3：巨型消息瘦身（2026-09-15 性能改造）=====
// 1) 工具卡片「延迟渲染」：折叠态不生成正文 DOM（展开时才构建），随 expandedCards 数据驱动；
// 2) 超长截断：展开态正文超 100KB 时截断渲染，附「显示全部」入口（fullCards 状态）；
// 3) 卡片渲染缓存：卡片 HTML 按内容 + 状态分槽缓存，流式期间免重复构建（对齐 M1a 段缓存思路）。
const TOOL_BODY_RENDER_LIMIT = 100 * 1024
const TOOL_CARD_CACHE_LIMIT = 500
type ToolCardCacheEntry = Partial<Record<'collapsed' | 'expanded' | 'full', string>>
const toolCardCache = new Map<string, ToolCardCacheEntry>()

/** 构建卡片正文 HTML（仅展开态调用；超长截断，full=显示全部） */
const buildToolBodyHtml = (content: string, full: boolean): string => {
  if (!full && content.length > TOOL_BODY_RENDER_LIMIT) {
    let cut = TOOL_BODY_RENDER_LIMIT
    const lastCode = content.charCodeAt(cut - 1)
    if (lastCode >= 0xd800 && lastCode <= 0xdbff) cut -= 1  // 避免切开代理对（emoji 等）
    const omitted = content.length - cut
    return `<div class="tool-result-body"><pre>${escapeHtml(content.slice(0, cut))}</pre></div>`
      + `<div class="tool-result-truncated">内容过长，已省略 ${omitted.toLocaleString()} 字符 <span class="tr-expand-all">显示全部</span></div>`
  }
  return `<div class="tool-result-body"><pre>${escapeHtml(content)}</pre></div>`
}

/**
 * 渲染技能使用卡片（skill action=report 工具结果专用）
 * 解析格式：
 *   技能「名称」(ID:N) 执行结果：成功/失败
 *   当前置信度：XX%（共使用 N 次，成功 N / 失败 N）
 */
function renderSkillUsageCard(content: string, expanded = false, full = false): string | null {
  // ⚠️ 只检查第一行！避免后续文本中的「技能」「执行结果」等关键词误匹配
  // 参考下方 tool-result-card 的 isError 判断也做了同样的限制（line 4009-4011）
  const firstLine = content.split('\n')[0]
  // 必须包含"执行结果"关键词，否则不是 report 结果（可能是 create/list/update/delete）
  // 例如 skill create 第一行"技能「xxx」创建成功 (ID: N)"也会匹配 /技能「(.+?)」/，但不含"执行结果"
  if (!/执行结果/.test(firstLine)) return null
  const nameMatch = firstLine.match(/技能「(.+?)」/)
  if (!nameMatch) return null
  const skillName = nameMatch[1]
  const success = /执行结果[：:]\s*(成功)/.test(firstLine)
  const confMatch = content.match(/当前置信度[：:]\s*(\d+%?)%/)
  const confidence = confMatch ? confMatch[1].replace('%', '') + '%' : ''
  const statusText = success ? '技能使用成功' : '技能使用失败'
  const statusIcon = success ? '&#10003;' : '&#10007;'
  const cardClass = success ? 'skill-usage-success' : 'skill-usage-fail'
  const firstLineSummary = firstLine.substring(0, 80)

  // M1b-2：折叠状态数据驱动（expanded=true 时不带 collapsed 类）
  // M3：折叠态不生成正文 DOM；展开态走超长截断逻辑
  return `<div class="skill-usage-card ${expanded ? '' : 'tool-result-collapsed'} ${cardClass}">
    <div class="tool-result-header">
      <span class="tr-toggle">▶</span>
      <span class="skill-usage-badge ${success ? 'skill-usage-badge-ok' : 'skill-usage-badge-fail'}">${statusIcon}</span>
      <span class="tr-label">${escapeHtml(statusText)}：${escapeHtml(skillName)}</span>
      ${confidence ? `<span class="skill-usage-conf">${escapeHtml(confidence)}</span>` : ''}
      <span class="tr-summary">${escapeHtml(firstLineSummary)}</span>
    </div>
    ${expanded ? buildToolBodyHtml(content, full) : ''}
  </div>`
}

// ===== M1b-2：thinking 段级渲染 + 折叠状态数据化（2026-09-15 性能改造）=====
// 与 content 段化（M1b-1）同机制：纯函数式段化 + 稳定 key 的 v-for DOM 复用。
// 折叠状态数据化：工具卡片展开态存于 msg.expandedCards（reactive），渲染时读取决定卡片 class。

/** thinking 渲染段：key 稳定（供 v-for DOM 复用）；cardAt 供点击定位卡片（pending 卡片无此标记，不响应折叠） */
type ThinkSegment = { key: string; html: string; cardAt?: number }

/** 已等待时长格式化（秒 → 「N 秒」/「N 分 M 秒」；委托心跳展示用，2026-09-21） */
const formatWaitSec = (sec: number): string =>
  sec >= 60 ? `${Math.floor(sec / 60)} 分 ${sec % 60} 秒` : `${sec} 秒`

/** 更新最近的 agent_invoke pending 条目（委托进度心跳 delegate_progress，2026-09-21）：
 *  卡片文案 → 「已委托「B」· 执行中 · 已等待 N 秒」，并携带 B 会话信息供「前往 B 会话」跳转。
 *  无 pending 条目（如刷新重连后）时返回 false，调用方跳过刷新。 */
const updateAgentInvokePending = (msg: ChatMessage, dp: DelegateProgressData): boolean => {
  const pendings = (msg.toolResults || []).filter(tr => tr.pending && tr.toolName === 'agent_invoke')
  if (pendings.length === 0) return false
  const t = pendings[pendings.length - 1]
  t.delegateSessionId = dp.targetSessionId
  t.delegateTarget = dp.targetAgent
  t.delegateStatus = dp.status
  t.delegateElapsedSec = dp.elapsedSec
  t.operationSummary = `🤝 已委托「${dp.targetAgent || 'B'}」· 执行中 · 已等待 ${formatWaitSec(dp.elapsedSec ?? 0)}`
  return true
}

/** 构建 pending 卡片 HTML（工具执行中；不响应折叠） */
const buildPendingCardHtml = (tr: { toolName?: string; operationSummary?: string; delegateSessionId?: number; delegateTarget?: string; delegateElapsedSec?: number }) => {
  const toolLabel = tr.toolName || '工具'
  // 2026-09-21：委托卡片特判（agent_invoke 等待中）——「已委托「B」· 已等待 N 秒」+「前往 B 会话」跳转入口
  if (tr.toolName === 'agent_invoke' && tr.delegateSessionId) {
    const targetName = escapeHtml(tr.delegateTarget || 'B')
    const waitText = formatWaitSec(tr.delegateElapsedSec ?? 0)
    return `<div class="tool-result-card tool-result-pending">
      <div class="tool-result-header">
        <span class="tr-toggle tr-toggle-spin">⏳</span>
        <span class="tr-icon tr-icon-pending">
          <span class="tr-pending-dot"></span>
        </span>
        <span class="tr-label">🤝 已委托「${targetName}」· 执行中</span>
        <span class="tr-summary">已等待 ${escapeHtml(waitText)}</span>
        <button class="jump-to-session-btn" data-session-id="${tr.delegateSessionId}">前往 B 会话 →</button>
      </div>
      <div class="tool-result-body tool-result-loading">
        <div class="tool-loading-bar">
          <div class="tool-loading-bar-inner"></div>
        </div>
        <div class="tool-loading-hint">智能体正在独立执行任务，可前往 B 会话查看实时详情...</div>
      </div>
    </div>`
  }
  const summaryText = tr.operationSummary || `正在调用 ${toolLabel}...`
  // 提取操作摘要的第一行（去掉 markdown 标记后截取前80字符）
  const cleanSummary = summaryText.replace(/^>\s*/, '').replace(/[*`]/g, '').substring(0, 80)
  return `<div class="tool-result-card tool-result-pending">
    <div class="tool-result-header">
      <span class="tr-toggle tr-toggle-spin">⏳</span>
      <span class="tr-icon tr-icon-pending">
        <span class="tr-pending-dot"></span>
      </span>
      <span class="tr-label">${escapeHtml(toolLabel)} · 执行中...</span>
      <span class="tr-summary">${escapeHtml(cleanSummary)}</span>
    </div>
    <div class="tool-result-body tool-result-loading">
      <div class="tool-loading-bar">
        <div class="tool-loading-bar-inner"></div>
      </div>
      <div class="tool-loading-hint">工具执行中，请稍候...</div>
    </div>
  </div>`
}

/** 构建工具结果卡片 HTML（已完成；expanded 控制折叠态，full 控制截断解除，数据驱动） */
const buildToolCardHtml = (displayContent: string, toolName: string | undefined, expanded: boolean, full: boolean) => {
  // ⚠️ 只检查第一行！工具报错永远在第一行，避免读取文件内容中的「错误关键词」导致误判
  // 注意：【[^】]+】不能用作通用匹配——project_info 等工具的成功结果也以【结构】【依赖】开头
  const firstLineOfResult = displayContent.split('\n')[0]
  const isError = /^(错误[：:]|❌\s*错误)/.test(firstLineOfResult)
               || /【失败|【异常|【错误|【超时|【无数据|【未找到|【权限不足|【缺少参数|【不支持|【操作失败|【查询失败|【更新失败|【删除失败|【创建失败|【启动失败|【命令未找到|【执行异常|【执行中断|【参数缺失|【参数错误|【并发限制|【任务不存在|【无法识别|【格式错误|【缺少参数/.test(firstLineOfResult)
               || /\bCannot\b/.test(firstLineOfResult)
  const firstLine = displayContent.split('\n')[0].substring(0, 80)
  // 提取操作类型并构建更有信息的标签
  const actionLabel = toolName ? extractActionFromToolContent(displayContent, toolName) : ''
  const labelText = isError
    ? ('执行出错' + (toolName ? ' ' + toolName : ''))
    : (toolName ? (toolName + ' · ' + actionLabel) : '工具执行')
  // 2026-09-21：agent_invoke 完成卡——解析【B 会话 ID】附「前往 B 会话」跳转入口（invoke/poll/wait 结果均含此字段）
  let delegateJumpBtn = ''
  if (toolName === 'agent_invoke') {
    const bm = displayContent.match(/【B 会话 ID】\s*(\d+)/)
    if (bm) {
      delegateJumpBtn = `<button class="jump-to-session-btn" data-session-id="${bm[1]}">前往 B 会话 →</button>`
    }
  }
  // M3：折叠态不生成正文 DOM（全文转义/渲染成本仅在展开时支付）；展开态走超长截断逻辑
  return `<div class="tool-result-card ${expanded ? '' : 'tool-result-collapsed'} ${isError ? 'tool-result-error' : ''}">
    <div class="tool-result-header">
      <span class="tr-toggle">▶</span>
      <span class="tr-icon ${isError ? 'tr-icon-error' : 'tr-icon-success'}">${isError ? '&#10007;' : '&#10003;'}</span>
      <span class="tr-label">${labelText}</span>
      <span class="tr-summary">${escapeHtml(firstLine)}</span>
      ${delegateJumpBtn}
    </div>
    ${expanded ? buildToolBodyHtml(displayContent, full) : ''}
  </div>`
}

/** 构建单个已完成工具卡片的 HTML（带渲染缓存）：
 *  - key=内容（值寻址），折叠/展开/完整三态分槽存储；
 *  - 流式期间每 tick 对历史卡片重复构建的成本 → 降为 Map 命中（O(1)）；
 *  - 缓存按内容值寻址，内容变化（重连重建）时自动失效并重建。 */
const buildToolCardCached = (
  tr: { at: number; content: string; toolName?: string },
  expanded: boolean,
  full: boolean,
): string => {
  const slot: 'collapsed' | 'expanded' | 'full' = !expanded ? 'collapsed' : (full ? 'full' : 'expanded')
  let entry = toolCardCache.get(tr.content)
  if (entry) {
    const hit = entry[slot]
    if (hit !== undefined) return hit
  }
  const { toolName, cleanContent } = parseToolNameLine(tr.content)
  const displayContent = cleanContent || tr.content
  let html = ''
  if (toolName === 'skill') {
    html = renderSkillUsageCard(displayContent, expanded, full) || ''
  }
  if (!html) html = buildToolCardHtml(displayContent, toolName, expanded, full)
  if (!entry) {
    if (toolCardCache.size >= TOOL_CARD_CACHE_LIMIT) {
      const firstKey = toolCardCache.keys().next().value
      if (firstKey !== undefined) toolCardCache.delete(firstKey)
    }
    entry = {}
    toolCardCache.set(tr.content, entry)
  }
  entry[slot] = html
  return html
}

/** 构建 thinking 的渲染段列表（纯派生；读取 msg.expandedCards / msg.fullCards 决定卡片折叠/截断态）
 *  M4：streaming=true 时尾段走降级渲染（跳过 hljs/katex），封段/消息结束后由完整渲染补齐 */
const buildThinkingSegments = (thinking: string | undefined, toolResults: ChatMessage['toolResults'], msg?: ChatMessage, streaming = false): ThinkSegment[] => {
  const t = thinking || ''
  const segs: ThinkSegment[] = []
  let idx = 0 // 段序号（稳定：已定稿前缀不变 → 序号不变 → DOM 复用）
  if (toolResults && toolResults.length > 0) {
    const sorted = [...toolResults].sort((a, b) => a.at - b.at)
    let lastPos = 0
    for (const tr of sorted) {
      // 思考过程片段（到工具调用之间的推理）：稳定段走段级缓存
      if (tr.at > lastPos) {
        const textSegment = t.slice(lastPos, tr.at)
        if (textSegment.trim()) {
          segs.push({ key: 's-' + idx++, html: `<div class="thinking-text-block">${renderSegmentCached(textSegment)}</div>` })
        }
      }
      // ★ pending 条目：工具正在执行中，显示加载动画（不响应折叠）
      if (tr.pending) {
        segs.push({ key: 's-' + idx++, html: buildPendingCardHtml(tr) })
        lastPos = tr.at
        continue
      }
      // 工具调用结果卡片（含工具名称解析）；折叠/截断态数据驱动 + M3 卡片渲染缓存
      const expanded = !!(msg && msg.expandedCards && msg.expandedCards.has(tr.at))
      const full = !!(msg && msg.fullCards && msg.fullCards.has(tr.at))
      segs.push({ key: 's-' + idx++, html: buildToolCardCached(tr, expanded, full), cardAt: tr.at })
      lastPos = tr.at
    }
    // 剩余思考过程（尾段：每 tick 增量渲染）
    if (t && lastPos < t.length) {
      const remaining = t.slice(lastPos)
      if (remaining.trim()) {
        segs.push({ key: 'tail', html: `<div class="thinking-text-block">${streaming ? renderMarkdownLight(remaining) : renderMarkdown(remaining)}</div>` })
      }
    }
    return segs
  }
  // 无工具调用，纯渲染思考文本（单尾段）
  if (t.trim()) {
    segs.push({ key: 'tail', html: streaming ? renderMarkdownLight(t) : renderMarkdown(t) })
  }
  return segs
}

/** thinking 渲染段列表（模板调用）：
 *  - 静态消息（非流式）→ 段列表缓存（缓存 key 含折叠状态签名，状态变化自动重建）
 *  - 流式消息 → 多段输出：稳定段 DOM 复用，仅尾段节点每 tick 更新
 *  修复（卡片点击失效）：静态消息同样输出「带 cardAt 的段列表」——此前合并为单段 HTML
 *  丢失 cardAt，导致历史/已结束消息的工具卡片点击在 handleToolCardClick 定位环节被静默忽略 */
const buildThinkingSegmentsFor = (msg: ChatMessage): ThinkSegment[] => {
  const t = msg.thinking
  const trs = msg.toolResults
  if (!t && (!trs || trs.length === 0)) return []
  if (!msg.isStreaming) {
    const stateSig = (msg.expandedCards && msg.expandedCards.size > 0
        ? '|x' + Array.from(msg.expandedCards).sort((a, b) => a - b).join(',')
        : '')
      + (msg.fullCards && msg.fullCards.size > 0
        ? '|f' + Array.from(msg.fullCards).sort((a, b) => a - b).join(',')
        : '')
    const cacheKey = 'st:' + (t || '').length + ':' + (t || '').slice(0, 32) + ':' + (t || '').slice(-32) + ':' + JSON.stringify(trs?.map(x => ({ at: x.at, len: x.content.length, pending: x.pending, tn: x.toolName }))) + stateSig
    const cached = thinkingCache.get(cacheKey)
    if (cached !== undefined) return cached
    const segs = buildThinkingSegments(t, trs, msg)
    if (thinkingCache.size >= 200) {
      const firstKey = thinkingCache.keys().next().value
      if (firstKey) thinkingCache.delete(firstKey)
    }
    thinkingCache.set(cacheKey, segs)
    return segs
  }
  return buildThinkingSegments(t, trs, msg, true)
}

// ===== M1b-1：content 段级渲染（2026-09-15 性能改造）=====
// DOM 段级增量：把 content 拆为「稳定段 + 尾段」，各段独立 v-html 渲染；
// Vue diff 时稳定段（key 与 html 均不变）DOM 完全复用，仅尾段节点每 tick 更新。
// 与 M1a 段缓存联动：稳定段 HTML 直接命中 segmentCache，只有尾段增量解析。
// 设计原则（详见 findings/F4）：content 为唯一事实源，段列表为纯派生、无跨调用状态。

/** 构建 content 的渲染段列表（纯派生：无安全切点则单尾段）
 *  M4：streaming=true 时尾段走降级渲染（跳过 hljs/katex），封段/消息结束后由完整渲染补齐 */
const buildContentSegments = (content: string, streaming = false): { key: string; html: string }[] => {
  if (!content) return []
  const stableEnd = findStablePrefixEnd(content)
  const segs: { key: string; html: string }[] = []
  if (stableEnd <= 0) {
    segs.push({ key: 'tail', html: streaming ? renderMarkdownLight(content) : renderMarkdown(content) })
    return segs
  }
  const parts = content.slice(0, stableEnd).split('\n\n')
  for (let i = 0; i < parts.length; i++) {
    const p = parts[i]
    if (p) segs.push({ key: 'c-' + i, html: renderSegmentCached(p) })
  }
  const tail = content.slice(stableEnd)
  if (tail) segs.push({ key: 'tail', html: streaming ? renderMarkdownLight(tail) : renderMarkdown(tail) })
  return segs
}

const formatMessage = (content: string) => {
  if (!content) return ''
  // 缓存命中直接返回，避免重复解析 markdown（核心性能优化）
  const cached = markdownCache.get(content)
  if (cached !== undefined) return cached
  // ★ M1a 分段渲染：稳定段走段级缓存（渲染一次永久命中）+ 尾段增量渲染，拼接为完整 HTML
  const html = buildContentSegments(content).map(s => s.html).join('')
  // LRU 淘汰：缓存超过上限时删除最早条目
  if (markdownCache.size >= 200) {
    const firstKey = markdownCache.keys().next().value
    if (firstKey) markdownCache.delete(firstKey)
  }
  markdownCache.set(content, html)
  return html
}

/** 消息正文渲染段列表（模板调用）：
 *  - 静态消息（非流式）→ markdownCache 整体缓存，输出单段（不随渲染重复计算）
 *  - 流式消息 → 多段输出：稳定段 DOM 复用，仅尾段节点每 tick 更新（M1b 核心收益）；
 *    M4：尾段降级渲染（跳过 hljs/katex），段封段/消息结束后由完整渲染补齐 */
const buildMessageSegments = (msg: { content?: string; isStreaming?: boolean }): { key: string; html: string }[] => {
  const content = msg.content
  if (!content) return []
  if (!msg.isStreaming) {
    return [{ key: 'main', html: formatMessage(content) }]
  }
  return buildContentSegments(content, true)
}

// 渲染 ask_user 问题的 markdown（预处理字面量 \n → 真正换行）
const formatQuestion = (question: string) => {
  if (!question) return ''
  // 将字面量 \n 转为真正的换行符（处理 AI 可能产生的双重转义）
  const normalized = question.replace(/\\n/g, '\n')
  return formatMessage(normalized)
}

// HTML 转义辅助函数
const escapeHtml = (text: string) => {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** M3：点击「显示全部」→ 记录完整展开状态（解除截断） */
const handleToolCardExpandAll = (e: MouseEvent, msg: ChatMessage) => {
  const segEl = (e.target as HTMLElement).closest('.think-seg') as HTMLElement | null
  const atStr = segEl?.dataset.cardAt
  if (atStr === undefined || atStr === '') return
  const at = Number(atStr)
  if (!Number.isFinite(at)) return
  const f = msg.fullCards ? new Set(msg.fullCards) : new Set<number>()
  f.add(at)
  msg.fullCards = f  // 响应式更新 → 触发重渲染 → 卡片切换为完整内容
}

// 工具结果卡片点击折叠/展开（M1b-2：数据驱动，状态存于 msg.expandedCards）
const handleToolCardClick = (e: MouseEvent, msg: ChatMessage) => {
  const target = e.target as HTMLElement
  // M3：「显示全部」入口在卡片正文内，先行拦截
  if (target.closest('.tr-expand-all')) {
    handleToolCardExpandAll(e, msg)
    return
  }
  // 2026-09-21：委托卡片「前往 B 会话」入口（先于折叠判断，点击不触发折叠）
  const jumpBtn = target.closest('.jump-to-session-btn') as HTMLElement | null
  if (jumpBtn) {
    const sid = jumpBtn.dataset.sessionId
    if (sid) selectConversation(sid)
    return
  }
  const header = target.closest('.tool-result-header')
  if (!header) return
  // 通过段 wrapper 的 data-card-at 定位卡片（pending 卡片无标记 → 不响应折叠）
  const segEl = header.closest('.think-seg') as HTMLElement | null
  const atStr = segEl?.dataset.cardAt
  if (atStr === undefined || atStr === '') return
  const at = Number(atStr)
  if (!Number.isFinite(at)) return
  const s = msg.expandedCards ? new Set(msg.expandedCards) : new Set<number>()
  if (s.has(at)) s.delete(at)
  else s.add(at)
  msg.expandedCards = s  // 响应式更新 → 触发重渲染 → 卡片折叠态刷新
}

// ===== 自动滚动到底部控制 =====
// 默认开启自动滚动。流式输出时若用户手动向上滚动，则关闭自动滚动；点击"回到底部"按钮或发送新消息时重置为开启。
const autoScrollToBottom = ref(true)
const showScrollToBottomBtn = ref(false)
// DynamicScroller 大小更新节拍器：每个 scheduleMessageUpdate 周期递增
// 说明：vue-virtual-scroller 2.x 在浏览器支持 ResizeObserver 时会忽略 size-dependencies，
//   高度由内部 ResizeObserver 上报；此 tick 仅作为「无 ResizeObserver 环境」的兼容依赖保留。
//   实际的重测兜底见 v-track-msg-size / scheduleScrollerSelfHeal。
const streamingUpdateTick = ref(0)

// ===== 思考过程（thinking-content）自动滚动控制 =====
const thinkAutoScroll = ref(true)
const thinkShowScrollBtn = ref(false)

// 滚动到底部（多层保障：nextTick + rAF + setTimeout 兜底，适配 DynamicScroller 首次挂载及虚拟渲染时机）
// DynamicScroller 是虚拟滚动组件，首次挂载时需要异步测量每个 item 大小，
// 仅靠 nextTick + rAF 无法保证 scrollHeight 已更新为最终值，因此增加延时重试。
let scrollToBottomTimer: ReturnType<typeof setTimeout> | null = null
const scrollToBottom = () => {
  if (!autoScrollToBottom.value) return
  // 清除上一次未完成的延时滚动，避免重复触发
  if (scrollToBottomTimer !== null) {
    clearTimeout(scrollToBottomTimer)
    scrollToBottomTimer = null
  }
  const doScroll = () => {
    if (scrollerRef.value) {
      const el = scrollerRef.value.$el as HTMLElement
      if (el) {
        el.scrollTop = el.scrollHeight
      }
    }
  }
  nextTick(() => {
    requestAnimationFrame(() => {
      doScroll()
      // DynamicScroller 虚拟渲染可能需要额外一帧才能算出正确 scrollHeight
      requestAnimationFrame(() => {
        doScroll()
        // ★ 兜底重试：DynamicScroller 首次挂载时内部异步测量 item 大小，
        //   可能超过 2 帧的等待时间，通过 setTimeout 在更长间隔后重试
        scrollToBottomTimer = setTimeout(() => {
          // 先强制刷新让 DynamicScroller 重新测量布局
          forceRefreshScroller()
          nextTick(() => {
            requestAnimationFrame(() => {
              doScroll()
              // 再次兜底：极慢渲染场景下再等一轮
              scrollToBottomTimer = setTimeout(() => {
                doScroll()
                scrollToBottomTimer = null
              }, 200)
            })
          })
        }, 150)
      })
    })
  })
}

// ===== 强制滚动到底部（切换会话 / 一键到底专用）=====
// 与 scrollToBottom 的区别：
// 1) 不受 autoScrollToBottom 限制——切会话、点「一键到底」必须生效；
// 2) 逐帧贴底，直到「scrollHeight 稳定 + 已在底部」连续 3 帧才停：虚拟列表 item 高度是异步测量的，
//    只设一次 scrollTop 会在后续测量把内容撑高后又离开底部（消息越多越明显，这是一键到底失效的主因）；
// 3) token 取消上一次未完成的滚动，避免连续点击/频繁切会话互相打架；
// 4) forceScrolling 期间屏蔽「是否离开底部」的状态切换，避免按钮闪烁。
let forceScrolling = false
let forceScrollToken = 0
const scrollToBottomForce = (convId?: string) => {
  const targetConv = convId || currentConversationId.value
  autoScrollToBottom.value = true
  showScrollToBottomBtn.value = false
  forceScrolling = true
  const token = ++forceScrollToken
  const getEl = () => (scrollerRef.value?.$el as HTMLElement | undefined)
  let prevHeight = -1
  let stableFrames = 0
  let frames = 0
  const finish = () => {
    if (token === forceScrollToken) forceScrolling = false
  }
  const step = () => {
    if (token !== forceScrollToken) return
    // 已切到别的会话 → 放弃，避免把新会话拽到底部
    if (targetConv && currentConversationId.value !== targetConv) {
      finish()
      return
    }
    const el = getEl()
    if (!el) {
      finish()
      return
    }
    el.scrollTop = el.scrollHeight
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight <= 2
    if (atBottom && el.scrollHeight === prevHeight) stableFrames++
    else stableFrames = 0
    prevHeight = el.scrollHeight
    frames++
    // 连续 3 帧稳定到底 = 完成；最多追 120 帧（约 2s）防极端场景空转
    if (stableFrames < 3 && frames < 120) {
      requestAnimationFrame(step)
    } else {
      finish()
    }
  }
  nextTick(() => {
    if (token !== forceScrollToken) return
    // 触发一次重测：让尚未测量的长消息尽快拿到真实高度，避免「滚到估算底部后又变高」
    scrollerRef.value?.forceUpdate?.()
    requestAnimationFrame(step)
  })
  // 兜底：极慢渲染（大量超长消息）时补两次贴底
  window.setTimeout(() => {
    if (token !== forceScrollToken) return
    const el = getEl()
    if (el) el.scrollTop = el.scrollHeight
  }, 300)
  window.setTimeout(() => {
    if (token !== forceScrollToken) return
    const el = getEl()
    if (el) el.scrollTop = el.scrollHeight
    finish()
  }, 900)
}

// ★ M2 统一刷新入口（2026-09-15 性能改造）：递增节拍器 + 替换数组引用，触发 DynamicScroller 重测。
//   50ms 去重窗：同一瞬间的多路调用（tick 刷新与滚动兜底刷新）只执行一次，消除重复的强制布局。
let lastScrollerRefreshAt = 0
const refreshScrollerData = (convId: string) => {
  const now = Date.now()
  if (now - lastScrollerRefreshAt < 50) return
  lastScrollerRefreshAt = now
  streamingUpdateTick.value++
  if (messages.value[convId]) {
    messages.value[convId] = [...messages.value[convId]]
  }
}

// 强制刷新 DynamicScroller：使最新内容可见并重新计算布局（经统一入口，50ms 去重）
const forceRefreshScroller = () => {
  const convId = currentConversationId.value
  if (!convId) return
  refreshScrollerData(convId)
}

// 监听 DynamicScroller 的 scroll 事件：
// 1) 滚动到顶部 → 懒加载更早的历史（不受下方 80ms 防抖影响，避免快速滚到顶时事件被丢弃）；
// 2) 检测用户是否手动滚动离开底部（控制流式自动滚动与「一键到底」按钮）。
let lastScrollHandleTime = 0
const handleMessageListScroll = () => {
  const el = scrollerRef.value?.$el as HTMLElement | undefined
  if (!el) return

  // ★ 顶部懒加载：loadMoreMessages 内部有 hasMore / loading / cursor 三重防重入
  if (el.scrollTop <= 80) {
    void loadMoreMessages(currentConversationId.value)
  }

  // 强制到底的逐帧贴底期间不做底部状态切换，否则按钮会在贴底过程中反复闪烁
  if (forceScrolling) return

  // 80ms 防抖：持续滚动时减少状态切换频率，降低跳动
  const now = Date.now()
  if (now - lastScrollHandleTime < 80) return
  lastScrollHandleTime = now

  const threshold = 50 // 距离底部小于 50px 视为在底部
  const distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight
  if (distanceToBottom < threshold) {
    // 用户在底部附近 → 恢复自动滚动，同时触发刷新使最新内容可见
    if (!autoScrollToBottom.value) {
      autoScrollToBottom.value = true
      showScrollToBottomBtn.value = false
      forceRefreshScroller()
    }
  } else {
    // 用户已离开底部 → 关闭自动滚动，显示回到底部按钮
    autoScrollToBottom.value = false
    showScrollToBottomBtn.value = true
  }
}

// 用户点击"回到底部"按钮
const scrollToBottomManually = () => {
  // 一键到底：不依赖流式自动滚动状态；逐帧贴底以吸收虚拟列表的异步高度测量
  // （消息越多，异步测量把内容撑高的幅度越大，只设一次 scrollTop 就会"看起来没到底"）
  scrollToBottomForce()
}

// 思考过程回到底部
const thinkScrollToBottom = () => {
  thinkAutoScroll.value = true
  thinkShowScrollBtn.value = false
  const msgs = currentMessages.value
  const streamingMsg = msgs.find(m => m.isStreaming)
  if (!streamingMsg) return
  nextTick(() => {
    const el = document.querySelector(`[data-think-scroll="${streamingMsg.id}"]`) as HTMLElement
    if (el) el.scrollTop = el.scrollHeight
  })
}

// 监听思考过程 .thinking-content 的 scroll 事件
let lastThinkScrollTime = 0
const handleThinkScroll = (e: Event) => {
  const now = Date.now()
  if (now - lastThinkScrollTime < 80) return
  lastThinkScrollTime = now
  
  const el = e.target as HTMLElement
  const msgId = el.dataset.thinkScroll
  if (!msgId) return
  const msgs = currentMessages.value
  const msg = msgs.find(m => m.id === msgId)
  if (!msg || !msg.isStreaming) return
  const threshold = 30
  const distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight
  if (distanceToBottom < threshold) {
    thinkAutoScroll.value = true
    thinkShowScrollBtn.value = false
  } else {
    thinkAutoScroll.value = false
    thinkShowScrollBtn.value = true
  }
}

// 监听消息数量变化自动滚动（替代 deep:true 深度监听，避免每次渲染触发全量比较）
watch(() => currentMessages.value.length + '|' + (currentMessages.value[currentMessages.value.length - 1]?.content?.length || 0), () => {
  // 上滑加载更早历史同样会改变 length：此时不能把用户从历史位置拽回底部。
  // 注意：loadMoreMessages 的 finally 会先于 watcher 回调（同一 microtask）清掉 loading 标记，
  // 只用 loadingMoreConvIds 判断会漏判，故再叠加「最近一次头部插入」的时间窗。
  if (loadingMoreConvIds.has(currentConversationId.value)) return
  if (Date.now() - lastHistoryPrependAt < 500) return
  scrollToBottom()
})

onMounted(() => {
  scrollToBottomForce()
  // 仅当当前 Agent 配置了工作目录时才自动加载文件树
  // 不自动使用 settingsStore.projectRoot（需要用户主动点击「选择目录」）
  if (agentRuntime.value.workDir) {
    fileTreeLoadPath.value = agentRuntime.value.workDir
  }
  // 点击外部关闭任务下拉
  document.addEventListener('click', (e) => {
    const target = e.target as HTMLElement
    if (!target.closest('.task-trigger') && !target.closest('.task-dropdown')) {
      showTaskDropdown.value = false
    }
    if (!target.closest('.changes-trigger') && !target.closest('.changes-panel')) {
      showChangesPanel.value = false
    }
    if (!target.closest('.more-settings-trigger') && !target.closest('.more-settings-popover') && !target.closest('.ant-select-dropdown')) {
      showMoreSettings.value = false
    }
  })
})

onUnmounted(() => {
  disposeLogXterm()
  // 清理拖拽事件
  document.removeEventListener('mousemove', onGlobalDragMove)
  document.removeEventListener('mouseup', onGlobalDragUp)
  // 清理虚拟列表高度自愈资源
  if (sizeSelfHealTimer !== null) {
    clearTimeout(sizeSelfHealTimer)
    sizeSelfHealTimer = null
  }
  scrollerSizeObserver?.disconnect()
})

// 当首次加载完会话列表且选中了会话后，检查活跃任务
// ===== 任务清单解析（P2：按会话隔离——不同 Agent 的 T1/T2 不再互相覆盖） =====
const parseTaskManagerResult = (content: string, convId?: string) => {
  // 无 convId 时退回当前会话（兼容旧调用）
  const targetConvId = convId ?? currentConversationId.value
  if (!targetConvId) return

  // 优先解析结构化 JSON（---TASK_JSON--- 分隔）
  const jsonMarkerIdx = content.indexOf('---TASK_JSON---')
  if (jsonMarkerIdx !== -1) {
    const jsonStr = content.substring(jsonMarkerIdx + '---TASK_JSON---'.length).trim()
    try {
      const parsed = JSON.parse(jsonStr)
      if (Array.isArray(parsed) && parsed.length > 0) {
        const existing = getTaskItems(targetConvId)
        for (const item of parsed) {
          const idx = existing.findIndex(t => t.id === item.id)
          const st = item.status === 'completed' ? 'completed' as const : 'pending' as const
          if (idx !== -1) {
            existing[idx].status = st
            if (item.priority) existing[idx].priority = item.priority
            if (item.depends_on) existing[idx].depends_on = item.depends_on
          } else {
            existing.push({
              id: item.id,
              description: item.description,
              status: st,
              priority: item.priority,
              depends_on: item.depends_on
            })
          }
        }
        setTaskItems(targetConvId, [...existing])
        return
      }
    } catch (e) {
      // JSON 解析失败，回退到文本解析
    }
  }

  // 兼容旧格式：处理 complete 操作: "✅ 任务 T1 已完成！"
  const completeMatch = content.match(/✅\s*任务\s+(T\d+)\s*已完成/)
  if (completeMatch) {
    const tid = completeMatch[1]
    const existing = getTaskItems(targetConvId)
    const found = existing.find(t => t.id === tid)
    if (found) { found.status = 'completed'; setTaskItems(targetConvId, [...existing]) }
    return
  }
  // 处理 reopen 操作: "🔄 任务 T1 已重新打开"
  const reopenMatch = content.match(/🔄\s*任务\s+(T\d+)\s*已重新打开/)
  if (reopenMatch) {
    const tid = reopenMatch[1]
    const existing = getTaskItems(targetConvId)
    const found = existing.find(t => t.id === tid)
    if (found) { found.status = 'pending'; setTaskItems(targetConvId, [...existing]) }
    return
  }
  // 处理批量操作结果（兼容）
  const batchMatch = content.match(/✅\s*批量完成任务：(.+)/)
  if (batchMatch) {
    const ids = batchMatch[1].split(',').map(s => s.trim())
    const existing = getTaskItems(targetConvId)
    for (const tid of ids) {
      const found = existing.find(t => t.id === tid)
      if (found) found.status = 'completed'
    }
    setTaskItems(targetConvId, [...existing])
    return
  }
  const batchReopenMatch = content.match(/🔄\s*批量重开任务：(.+)/)
  if (batchReopenMatch) {
    const ids = batchReopenMatch[1].split(',').map(s => s.trim())
    const existing = getTaskItems(targetConvId)
    for (const tid of ids) {
      const found = existing.find(t => t.id === tid)
      if (found) found.status = 'pending'
    }
    setTaskItems(targetConvId, [...existing])
    return
  }
  if (!content.includes('任务清单') && !content.includes('任务 ')) return
  const lines = content.split('\n')
  const parsedTasks: TaskItem[] = []
  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed) continue
    const parts = trimmed.split('-')
    if (parts.length < 2) continue
    const firstPart = parts[0].trim()
    const idMatch = firstPart.match(/(T\d+)/)
    if (!idMatch) continue
    const id = idMatch[1]
    const desc = parts.slice(1).join('-').trim()
    const cleaned = desc.replace(/[[✅⏳].*?]/g, '').trim()
    if (!cleaned) continue
    const isCompleted = trimmed.includes('✅')
    parsedTasks.push({ id, description: cleaned, status: isCompleted ? 'completed' : 'pending' })
  }
  if (parsedTasks.length > 0) {
    const existing = getTaskItems(targetConvId)
    if (existing.length > 0) {
      for (const newTask of parsedTasks) {
        const idx = existing.findIndex(t => t.id === newTask.id)
        if (idx !== -1) existing[idx].status = newTask.status
        else existing.push(newTask)
      }
      setTaskItems(targetConvId, [...existing])
    } else {
      setTaskItems(targetConvId, parsedTasks)
    }
  }
}

watch(currentConversationId, (newId) => {
  if (newId && !newId.startsWith('local-')) {
    saveConversationId(newId)
    checkAndReconnect(newId)
  } else if (!newId) {
    clearSavedConversationId()
  }
})
</script>

<style scoped>
/* ============================================================
   Code Assistant v4 — 现代AI助手 · Bento卡片 · 紫色主题 · 暗色增强 · 微交互
   设计语言对齐 P2P Panel v3 / Layout Sidebar v2
   ============================================================ */

/* ---------- CSS 变量 ---------- */
.chat-container {
  --accent: #8b5cf6;
  --accent-lt: rgba(139, 92, 246, 0.08);
  --accent-md: rgba(139, 92, 246, 0.18);
  --accent-dk: #7c3aed;
  --accent-glow: rgba(139, 92, 246, 0.3);

  --bg-root: #f5f3fa;
  --bg-card: #ffffff;
  --bg-chat: #faf9fc;
  --bg-hover: rgba(139, 92, 246, 0.04);
  --text-1: #1a1a2e;
  --text-2: #5c5c78;
  --text-3: #9696aa;
  --text-4: #b8b8c8;
  --border: #e8e5f0;
  --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.04);
  --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.06);
  --shadow-lg: 0 8px 30px rgba(0, 0, 0, 0.08);
  --green: #10b981;
  --red: #ef4444;
  --radius: 14px;
  --radius-sm: 10px;
  --radius-xs: 6px;

  display: flex;
  height: 100%;
  background: var(--bg-root);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  text-align: left;
}

/* ===== 侧边栏 ===== */
.sidebar {
  width: 280px;
  background: var(--bg-card);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  height: 100%;
  transition: width 0.3s cubic-bezier(0.16, 1, 0.3, 1);
  overflow: hidden;
  flex-shrink: 0;
  box-shadow: var(--shadow-sm);
  z-index: 10;
}
.sidebar.collapsed { width: 80px; }

.sidebar-header {
  padding: 14px 10px;
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

/* 简洁标题（无子Agent时） */
.sidebar-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  padding: 2px 6px;
}

.sidebar-title-icon {
  font-size: 18px;
  line-height: 1;
  flex-shrink: 0;
}

.sidebar-title-text {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-1);
  white-space: nowrap;
}

/* 折叠后标题适配 */
.collapsed .sidebar-title {
  justify-content: center;
  padding: 0;
}
.collapsed .sidebar-title-text {
  display: none;
}
.collapsed .sidebar-title-icon {
  font-size: 22px;
}

.sidebar-tabs {
  display: flex;
  background: var(--bg-root);
  border-radius: var(--radius-xs);
  padding: 3px;
  flex: 1;
  min-width: 0;
}

.tab-btn {
  flex: 1;
  border: none;
  background: transparent;
  padding: 5px 0;
  font-size: 12px;
  color: var(--text-3);
  cursor: pointer;
  border-radius: 4px;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  font-family: inherit;
  font-weight: 500;
}
.tab-btn:hover {
  color: var(--accent);
  background: var(--accent-lt);
}
.tab-btn.active {
  background: var(--bg-card);
  color: var(--accent);
  font-weight: 600;
  box-shadow: var(--shadow-sm);
}

.tab-full { display: inline; }
.tab-short { display: none; }

.collapsed .sidebar-tabs {
  flex: 1;
  flex-direction: column;
  gap: 4px;
  background: transparent;
  padding: 6px;
  overflow-y: auto;
}
.collapsed .tab-btn {
  flex: 0 0 auto;
  width: 52px;
  height: 44px;
  margin: 0 auto;
  padding: 0;
  font-size: 11px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  line-height: 1.2;
  border: 1px solid transparent;
}
.collapsed .tab-btn:hover {
  border-color: var(--accent-md);
  box-shadow: var(--shadow-sm);
}
.collapsed .tab-btn.active {
  background: linear-gradient(135deg, var(--accent), var(--accent-dk)) !important;
  color: #fff !important;
  font-weight: 700;
  box-shadow: 0 4px 14px var(--accent-glow);
  border-color: transparent;
}
.collapsed .tab-full { display: none; }
.collapsed .tab-short { display: inline; }

.sidebar-collapse-btn {
  cursor: pointer;
  font-size: 17px;
  color: var(--text-3);
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: var(--radius-xs);
  flex-shrink: 0;
  transition: all 0.2s;
}
.sidebar-collapse-btn:hover {
  color: var(--accent);
  background: var(--accent-lt);
}

/* ===== 侧边栏内容区通用 ===== */
.conversation-list,
.filetree-panel,
.git-panel,
.skill-panel,
.agent-panel-wrapper {
  flex: 1;
  overflow-y: auto;
  background: var(--bg-card);
}

/* 自定义滚动条 */
.conversation-list::-webkit-scrollbar,
.filetree-panel::-webkit-scrollbar,
.git-panel::-webkit-scrollbar,
.skill-panel::-webkit-scrollbar,
.agent-panel-wrapper::-webkit-scrollbar {
  width: 4px;
}
.conversation-list::-webkit-scrollbar-thumb,
.filetree-panel::-webkit-scrollbar-thumb,
.git-panel::-webkit-scrollbar-thumb,
.skill-panel::-webkit-scrollbar-thumb,
.agent-panel-wrapper::-webkit-scrollbar-thumb {
  background: #dcd8ea;
  border-radius: 2px;
}

/* ===== 会话列表项 ===== */
.conversation-list { padding: 10px; }

.conversation-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  margin-bottom: 4px;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  border: 1px solid transparent;
  background: var(--bg-card);
  position: relative;
  margin-right: 2px;
}
/* 左侧指示条 */
.conversation-item::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: transparent;
  transition: background 0.2s ease;
}
.conversation-item:hover {
  background: var(--bg-hover);
  border-color: var(--accent-md);
  box-shadow: var(--shadow-sm);
}
.conversation-item:hover::before {
  background: var(--accent);
}
.conversation-item.active {
  background: linear-gradient(135deg, var(--accent-lt), rgba(99, 102, 241, 0.06));
  border-color: var(--accent-md);
  box-shadow: var(--shadow-md);
}
.conversation-item.active::before {
  background: var(--accent);
}
.conv-icon {
  margin-right: 10px;
  color: var(--accent);
  font-size: 17px;
  flex-shrink: 0;
}
.conv-info {
  flex: 1;
  min-width: 0;
}
.conv-title {
  font-weight: 600;
  color: var(--text-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12.5px;
  cursor: pointer;
}
.conv-title:hover { color: var(--accent); }

.conv-title-editing { margin: -2px 0; }

.conv-title-editing .ant-input {
  height: 28px;
  font-size: 12px;
  border-radius: var(--radius-xs);
}
.conv-time {
  font-size: 10px;
  color: var(--text-3);
  margin-top: 2px;
}
.conv-actions {
  opacity: 0;
  transition: opacity 0.15s;
  flex-shrink: 0;
}
.conversation-item:hover .conv-actions { opacity: 1; }

/* 折叠后会话项：显示标题首字方块 */
.collapsed .conv-icon { display: none; }
.collapsed .conv-actions { display: none; }
.collapsed .conv-info { display: flex !important; align-items: center; justify-content: center; flex: 1; }
.collapsed .conv-title {
  width: 1.1em;
  overflow: hidden;
  white-space: nowrap;
  font-size: 16px;
  font-weight: 700;
  color: var(--accent);
}
.collapsed .conv-time { display: none; }
.collapsed .conversation-item {
  padding: 0;
  justify-content: center;
  width: 44px;
  height: 44px;
  margin: 2px auto;
  border-radius: 12px;
  border: 1px solid transparent;
  display: flex;
  align-items: center;
}
.collapsed .conversation-item::before {
  display: none;
  content: none;
}
.collapsed .conversation-item:hover {
  border-color: var(--accent-md);
  box-shadow: var(--shadow-sm);
  background: var(--bg-hover);
}
.collapsed .conversation-item.active {
  background: linear-gradient(135deg, var(--accent), var(--accent-dk));
  border-color: transparent;
  box-shadow: 0 4px 14px var(--accent-glow);
}
.collapsed .conversation-item.active .conv-title { color: #fff; }
.collapsed .conversation-list {
  padding: 6px 4px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

/* ===== 新建会话按钮 ===== */
.new-chat-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: 10px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  color: var(--accent);
  background: var(--accent-lt);
  border: 1.5px dashed var(--accent-md);
  transition: all 0.25s ease;
}
.new-chat-btn:hover {
  background: linear-gradient(135deg, var(--accent-lt), rgba(99, 102, 241, 0.12));
  border-color: var(--accent);
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}
/* 折叠后只显示 + */
.collapsed .new-chat-btn {
  justify-content: center;
  padding: 0;
  width: 44px;
  height: 44px;
  margin: 4px auto 8px;
  border-radius: 12px;
  font-size: 22px;
  gap: 0;
  position: relative;
}
.collapsed .new-chat-btn > :not(.anticon) { display: none; }
.collapsed .new-chat-btn > .anticon { font-size: 22px; margin: 0; }

/* ===== 空状态 ===== */
.loading-conversations {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 30px 12px;
  color: var(--text-3);
  gap: 8px;
}
.empty-conversations {
  text-align: center;
  padding: 30px 12px;
  color: var(--text-3);
}
.empty-conversations p { margin: 0; font-size: 13px; }
.empty-hint { margin-top: 6px !important; font-size: 11px !important; color: var(--text-4); }
.collapsed .loading-conversations span,
.collapsed .loading-conversations,
.collapsed .empty-conversations p,
.collapsed .empty-conversations { display: none; }

/* ===== 折叠后：侧边栏头部优化 ===== */
.collapsed .sidebar-header {
  padding: 8px 4px;
  flex-direction: column;
  gap: 4px;
}
.collapsed .sidebar-collapse-btn {
  order: -1;
  margin: 0 auto;
}

/* ===== 折叠后：各面板适配 ===== */
/* 文件树 / Git / 技能面板折叠后隐藏 */
.collapsed .project-root-bar,
.collapsed .filetree-panel > :not(:first-child),
.collapsed .git-panel > *,
.collapsed .skill-panel > * { display: none; }
.collapsed .agent-selector-wrapper { display: none; }

/* ===== 项目根目录栏 ===== */
.project-root-bar {
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-card);
}
.project-root-label {
  font-size: 11px;
  font-weight: 700;
  color: var(--text-3);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 6px;
  display: block;
}
.project-root-input { font-size: 12px; }
.project-root-btn {
  border-radius: var(--radius-xs);
  transition: all 0.2s;
}

/* ===== 主聊天区域 ===== */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-root);
}

/* ===== 标签栏 ===== */
.tab-bar {
  display: flex;
  background: var(--bg-card);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
  overflow-x: auto;
  overflow-y: hidden;
  min-height: 38px;
  align-items: stretch;
  box-shadow: var(--shadow-sm);
}
.tab-bar::-webkit-scrollbar { height: 3px; }
.tab-bar::-webkit-scrollbar-thumb { background: #dcd8ea; border-radius: 2px; }

.tab-item {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 0 14px;
  font-size: 12.5px;
  color: var(--text-3);
  cursor: pointer;
  border-right: 1px solid var(--border);
  background: transparent;
  white-space: nowrap;
  user-select: none;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  min-width: 0;
  position: relative;
  font-weight: 500;
}
.tab-item:hover {
  background: var(--bg-hover);
  color: var(--text-1);
}
.tab-item.active {
  background: var(--bg-root);
  color: var(--accent);
  font-weight: 700;
  border-bottom: 2px solid var(--accent);
  margin-bottom: -1px;
}
.tab-item.dirty {
  color: #f59e0b;
}
.tab-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 160px;
}
.tab-dirty-dot {
  font-size: 10px;
  color: #f59e0b;
}
.tab-close {
  font-size: 15px;
  line-height: 1;
  color: var(--text-4);
  padding: 2px 4px;
  border-radius: var(--radius-xs);
  transition: all 0.15s;
  flex-shrink: 0;
  margin-left: 4px;
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.tab-close:hover {
  background: var(--red);
  color: #fff;
}

/* 分屏按钮 */
.tab-split-btn {
  font-size: 12px;
  line-height: 1;
  color: var(--text-4);
  padding: 2px 4px;
  border-radius: var(--radius-xs);
  transition: all 0.15s;
  flex-shrink: 0;
  margin-left: 2px;
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}
.tab-split-btn:hover {
  background: var(--accent-lt);
  color: var(--accent);
}
.tab-unsplit-btn {
  font-size: 12px;
  line-height: 1;
  color: var(--accent);
  padding: 2px 4px;
  border-radius: var(--radius-xs);
  transition: all 0.15s;
  flex-shrink: 0;
  margin-left: 2px;
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  background: var(--accent-lt);
}
.tab-unsplit-btn:hover {
  background: var(--red);
  color: #fff;
}

/* 拖拽中的 tab 样式 */
.tab-item.dragging {
  opacity: 0.4;
}

/* 文件编辑空状态 */
.file-tab-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-3, #999);
  font-size: 15px;
  user-select: none;
}

/* ===== 标签内容区 ===== */
.tab-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
  background: var(--bg-chat);
}
.tab-content > .chat-messages-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
  position: relative;
}

/* ===== 网页标签布局 ===== */
.web-tab-layout {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
}
.web-tab-toolbar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--border, #e8e5f0);
  background: var(--bg-secondary, #f7f6fb);
}
.web-tab-url {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--text-secondary, #666);
}
.web-tab-actions {
  flex: 0 0 auto;
  display: flex;
  gap: 6px;
}
.web-tab-body {
  flex: 1;
  min-height: 0;
  position: relative;
  display: flex;
}
/* webview 需显式 display:flex 才能撑满高度（Electron 已知表现） */
.web-tab-frame {
  flex: 1;
  width: 100%;
  height: 100%;
  border: 0;
  display: flex;
}

/* ===== 图片标签布局 ===== */
.image-tab-layout {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
}
.image-tab-toolbar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--border, #e8e5f0);
  background: var(--bg-secondary, #f7f6fb);
}
.image-tab-path {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--text-secondary, #666);
}
.image-tab-actions {
  flex: 0 0 auto;
}
.image-tab-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 12px;
}
.image-tab-img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  border-radius: 6px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.12);
}
.image-tab-hint {
  color: var(--text-secondary, #888);
  font-size: 13px;
}
.image-tab-hint.error {
  color: #e5484d;
}

/* ===== 文件标签布局 ===== */
.file-tab-layout {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
}
.file-tab-editor {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.console-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #6a9955;
  display: inline-block;
}
.console-dot.running {
  background: var(--green);
  animation: consolePulse 1.5s ease-in-out infinite;
}
@keyframes consolePulse {
  0%, 100% { opacity: 1; box-shadow: 0 0 4px var(--green); }
  50% { opacity: 0.5; box-shadow: 0 0 8px var(--green); }
}
/* 标签操作按钮组 */
.tab-items {
  display: flex;
  flex: 1;
  overflow-x: auto;
  overflow-y: hidden;
  align-items: stretch;
}
.tab-items::-webkit-scrollbar { height: 3px; }
.tab-items::-webkit-scrollbar-thumb { background: #dcd8ea; border-radius: 2px; }
.tab-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  flex-shrink: 0;
  background: var(--bg-card);
  border-left: 1px solid var(--border);
}
.tb-btn {
  font-size: 12px !important;
  height: 26px !important;
  padding: 0 10px !important;
  border-radius: var(--radius-xs) !important;
  font-weight: 500 !important;
  transition: all 0.2s ease !important;
}
.tb-btn:hover {
  transform: translateY(-1px);
  box-shadow: var(--shadow-sm);
}

/* ===== 差异对比标签 ===== */
.diff-tab-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-chat);
}
.diff-tab-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  background: var(--bg-card);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.diff-tab-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-1);
}
.diff-tab-actions {
  display: flex;
  gap: 8px;
}
.diff-tab-body {
  flex: 1;
  overflow: auto;
  padding: 16px;
}

/* ===== 消息列表 ===== */
.message-list {
  flex: 1;
  overflow-y: auto;
  overflow-anchor: none;
  padding: 20px 24px;
  background: var(--bg-chat);
}
.message-list::-webkit-scrollbar { width: 5px; }
.message-list::-webkit-scrollbar-thumb { background: #dcd8ea; border-radius: 3px; }
.message-list::-webkit-scrollbar-track { background: transparent; }

/* ===== 顶部懒加载提示（上滑到顶加载更早历史）===== */
.loading-more-tip {
  position: absolute;
  top: 10px;
  left: 50%;
  transform: translateX(-50%);
  padding: 4px 12px;
  font-size: 12px;
  line-height: 20px;
  color: var(--text-2);
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 999px;
  box-shadow: var(--shadow-sm);
  z-index: 6;
  pointer-events: none;
  white-space: nowrap;
}
[data-theme="dark"] .loading-more-tip {
  background: #1e1d2c;
  border-color: #2a2838;
  color: var(--text-2);
}

/* ===== 回到底部浮动按钮 ===== */
.scroll-to-bottom-btn {
  position: absolute;
  bottom: 20px;
  right: 40px;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--bg-card);
  border: 1px solid var(--border);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.1);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--accent);
  font-size: 16px;
  z-index: 20;
  transition: all 0.25s ease;
  user-select: none;
}
.scroll-to-bottom-btn:hover {
  background: var(--accent-lt);
  border-color: var(--accent);
  box-shadow: 0 4px 18px var(--accent-glow);
  transform: translateY(-2px);
}
.scroll-to-bottom-btn:active {
  transform: scale(0.92);
}

/* 回到底部按钮淡入淡出 */
.scroll-btn-fade-enter-active {
  animation: scrollBtnIn 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}
.scroll-btn-fade-leave-active {
  animation: scrollBtnIn 0.18s cubic-bezier(0.16, 1, 0.3, 1) reverse;
}
@keyframes scrollBtnIn {
  from { opacity: 0; transform: translateY(12px) scale(0.85); }
  to   { opacity: 1; transform: translateY(0) scale(1); }
}

/* 暗色模式适配 */
[data-theme="dark"] .scroll-to-bottom-btn {
  background: #1a1925;
  border-color: #2a2838;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.3);
}
[data-theme="dark"] .scroll-to-bottom-btn:hover {
  background: rgba(139, 92, 246, 0.12);
  border-color: var(--accent);
}

.message-list-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
.loading-messages {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  color: var(--text-3);
  gap: 10px;
  font-size: 14px;
}

/* ===== 无 Provider 引导界面 ===== */
.no-provider-guide {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 60px 40px;
  text-align: center;
}
.guide-icon {
  font-size: 64px;
  line-height: 1;
  margin-bottom: 8px;
}
.guide-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--text-1);
}
.guide-desc {
  margin: 0;
  font-size: 14px;
  color: var(--text-3);
  max-width: 400px;
  line-height: 1.6;
}
.guide-btn {
  margin-top: 8px;
  border-radius: 10px !important;
  font-weight: 700 !important;
  padding: 0 28px !important;
  height: 44px !important;
  font-size: 15px !important;
}
[data-theme="dark"] .guide-title { color: #e4e2f0; }
[data-theme="dark"] .guide-desc { color: #7a7898; }

/* ===== 消息项 ===== */
.message-item {
  display: flex;
  padding-bottom: 24px;
  gap: 14px;
  /* 注意：虚拟滚动下每条消息是会复用的绝对定位视图，入场动画（translateY+scale）
     会随视图复用反复重播，视觉上表现为消息「错位/滑入」，故此处不再使用入场动画 */
}
@keyframes msgSlideIn {
  from { opacity: 0; transform: translateY(12px) scale(0.98); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}

/* ===== 用户消息：右对齐，紫色渐变气泡 ===== */
.message-item.user {
  justify-content: flex-end;
}
.message-item.user .message-content {
  background: linear-gradient(135deg, var(--accent), var(--accent-dk));
  color: #fff;
  border: none;
  border-bottom-right-radius: 6px;
  box-shadow: 0 4px 16px var(--accent-glow);
}
.message-item.user .message-sender {
  color: rgba(255, 255, 255, 0.85);
}
.message-item.user .message-time,
.message-item.user .message-token {
  color: rgba(255, 255, 255, 0.6);
}
.message-item.user .message-text {
  color: rgba(255, 255, 255, 0.95);
}
/* 用户消息中内联代码 */
.message-item.user :deep(.code-message p code),
.message-item.user :deep(.message-text p code) {
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
}
/* 用户消息中的代码块 */
.message-item.user :deep(.code-message pre),
.message-item.user :deep(.message-text pre) {
  background: rgba(0, 0, 0, 0.2);
  border-color: rgba(255, 255, 255, 0.15);
  color: #e4e2f0;
}

/* ===== AI 消息：左对齐，浅色卡片 ===== */
.message-item.assistant {
  justify-content: flex-start;
}
.message-item.assistant .message-content {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-bottom-left-radius: 6px;
  box-shadow: var(--shadow-sm);
}

/* ===== 头像 ===== */
.message-avatar {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  order: 0;
}
/* 用户消息头像放右边 */
.message-item.user .message-avatar {
  order: 2;
}

.avatar {
  width: 38px;
  height: 38px;
  border-radius: 12px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 14px;
  box-shadow: 0 3px 10px rgba(0,0,0,0.08);
}
.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.user-avatar {
  background: linear-gradient(135deg, var(--accent), var(--accent-dk));
  color: white;
  box-shadow: 0 3px 10px var(--accent-glow);
}
.ai-avatar {
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  color: white;
  font-size: 18px;
  box-shadow: 0 3px 10px rgba(59, 130, 246, 0.3);
}

/* 回滚按钮 */
.rollback-btn {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: rgba(255,255,255,0.2);
  border: 1px solid rgba(255,255,255,0.3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: 11px;
  color: rgba(255,255,255,0.7);
  transition: all 0.2s;
  user-select: none;
  flex-shrink: 0;
  margin-left: 6px;
}
.rollback-btn:hover {
  background: var(--red);
  border-color: var(--red);
  color: #fff;
  box-shadow: 0 2px 8px rgba(239, 68, 68, 0.3);
}

/* 消息卡片 */
.message-content {
  flex: 0 1 auto;
  min-width: 0;
  max-width: 72%;
  border-radius: var(--radius);
  padding: 14px 18px;
  /* 只过渡视觉属性：禁止 transition: all（宽度/高度过渡会与虚拟列表的高度测量打架，
     测量到过渡中的中间高度 → 后续消息位置偏移/重叠） */
  transition: background-color 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
}

.message-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.message-sender {
  font-weight: 700;
  font-size: 13px;
  color: var(--text-1);
}

.message-time {
  font-size: 11px;
  color: var(--text-4);
}

.message-token {
  font-size: 11px;
  color: var(--text-4);
}

.streaming-indicator {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--accent);
  font-weight: 500;
}
.streaming-dot {
  width: 7px;
  height: 7px;
  background: var(--accent);
  border-radius: 50%;
  animation: pulse 1.2s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.message-text {
  font-size: 14px;
  line-height: 1.75;
  color: var(--text-2);
  word-wrap: break-word;
  overflow-wrap: break-word;
  text-align: left;
}

/* ===== 任务错误（消息区：LLM 接口错误停止的详情展示） ===== */
.message-task-error {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid #ffccc7;
  border-left: 3px solid #ff4d4f;
  border-radius: 8px;
  background: rgba(255, 241, 240, 0.7);
}
.task-error-title {
  font-size: 13px;
  font-weight: 600;
  color: #cf1322;
}
.task-error-detail {
  margin-top: 4px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-2);
  white-space: pre-line;
  word-break: break-word;
}
[data-theme="dark"] .message-task-error {
  background: rgba(70, 30, 32, 0.55);
  border-color: #5c2224;
}
[data-theme="dark"] .task-error-title {
  color: #ff7875;
}

/* ===== 思考过程 ===== */
.thinking-section {
  margin-bottom: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--bg-root);
}
.thinking-header {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 5px;
  padding: 8px 14px;
  cursor: pointer;
  user-select: none;
  background: #f0edf6;
  font-size: 12px;
  transition: background 0.2s;
}
.thinking-header:hover { background: #e8e2f2; }
.thinking-title {
  font-weight: 700;
  color: var(--text-1);
  flex-shrink: 0;
}
.thinking-summary {
  font-size: 11px;
  color: var(--text-3);
  margin-right: 6px;
}
.thinking-hint {
  font-size: 11px;
  color: var(--text-4);
}
/* 命中技能标签（动态展示：LLM 真正执行 skill action=load 时出现） */
.skill-hit-tags {
  display: flex;
  gap: 4px;
  margin-right: 8px;
  overflow: hidden;
  flex: 1;
  min-width: 0;
}
.skill-hit-tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 10px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
  color: #7c3aed;
  background: rgba(139, 92, 246, 0.10);
  border: 1px solid rgba(139, 92, 246, 0.35);
  animation: skillHitIn 0.35s cubic-bezier(0.16, 1, 0.3, 1);
}
@keyframes skillHitIn {
  from { opacity: 0; transform: translateY(-3px) scale(0.92); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}
.thinking-content {
  padding: 10px 14px;
  max-height: 600px;
  overflow-y: auto;
  overflow-anchor: none;
}
.thinking-content::-webkit-scrollbar { width: 4px; }
.thinking-content::-webkit-scrollbar-thumb { background: #dcd8ea; border-radius: 2px; }
.thinking-content::-webkit-scrollbar-track { background: transparent; }
/* 思考过程回到底部按钮 — sticky 定位粘在可视底部 */
.think-scroll-btn-wrap {
  position: sticky;
  bottom: 0;
  display: flex;
  justify-content: flex-end;
  padding: 4px 14px 8px;
  pointer-events: none;
  background: linear-gradient(to top, var(--bg-card) 60%, transparent);
}
.think-scroll-btn {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--bg-card);
  border: 1px solid var(--border);
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--accent);
  font-size: 12px;
  transition: all 0.2s ease;
  pointer-events: auto;
}
.think-scroll-btn:hover {
  background: var(--accent-lt);
  transform: translateY(-1px);
}
[data-theme="dark"] .think-scroll-btn-wrap {
  background: linear-gradient(to top, #1a1925 60%, transparent);
}
[data-theme="dark"] .think-scroll-btn {
  background: #1a1925;
  border-color: #2a2838;
}
[data-theme="dark"] .think-scroll-btn:hover {
  background: rgba(139,92,246,0.12);
}
.thinking-text {
  font-size: 13px;
  line-height: 1.65;
  color: var(--text-2);
  text-align: left;
}

/* ===== 思考时间线 ===== */
.thinking-timeline {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
/* M1b-2：段 wrapper 不参与布局（穿透），使各段内的块成为 flex/gap 的直接子项，视觉与原结构等效 */
.think-seg {
  display: contents;
}
.thinking-text-block {
  padding: 4px 0;
  white-space: pre-wrap;
  word-wrap: break-word;
  overflow-wrap: break-word;
}
.thinking-text-block p { margin: 4px 0; }
.thinking-text-block code {
  font-size: 12px;
  padding: 2px 5px;
  background: #eef2f6;
  border-radius: 3px;
}

/* ===== 输入区域 ===== */
.chat-input-area {
  padding: 14px 24px 18px;
  background: var(--bg-card);
  border-top: 1px solid var(--border);
  position: relative;
  backdrop-filter: blur(10px);
}

/* ===== 任务中断横幅（输入区：错误详情 + 一键继续任务） ===== */
.task-interrupted-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  padding: 10px 12px;
  border: 1px solid #ffccc7;
  border-radius: 10px;
  background: rgba(255, 241, 240, 0.6);
}
.interrupted-info {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}
.interrupted-icon {
  font-size: 15px;
  line-height: 1.5;
  flex-shrink: 0;
}
.interrupted-texts {
  min-width: 0;
}
.interrupted-title {
  font-size: 13px;
  font-weight: 600;
  color: #cf1322;
}
.interrupted-message {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-2);
  white-space: pre-line;
  word-break: break-word;
}
.continue-task-btn {
  flex-shrink: 0;
}
[data-theme="dark"] .task-interrupted-banner {
  background: rgba(70, 30, 32, 0.5);
  border-color: #5c2224;
}
[data-theme="dark"] .interrupted-title {
  color: #ff7875;
}

/* ===== 附件 ===== */
.attachment-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
  padding: 0 2px;
}
.attachment-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  background: var(--accent-lt);
  border: 1px solid var(--accent-md);
  border-radius: var(--radius-xs);
  font-size: 12px;
  line-height: 22px;
  transition: all 0.2s;
}
.attachment-tag:hover {
  background: rgba(139, 92, 246, 0.14);
  border-color: var(--accent);
}
.attachment-tag.attachment-error {
  background: #fef2f2;
  border-color: #fca5a5;
}
.attachment-icon { font-size: 13px; flex-shrink: 0; }
.attachment-name {
  color: var(--text-1);
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.attachment-size { color: var(--text-3); font-size: 11px; flex-shrink: 0; }
.attachment-uploading { color: var(--accent); font-size: 12px; }
.attachment-error-msg {
  color: var(--red);
  font-size: 11px;
  cursor: help;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.attachment-remove {
  cursor: pointer;
  color: var(--text-4);
  font-size: 15px;
  line-height: 1;
  padding: 0 3px;
  transition: color 0.15s;
  flex-shrink: 0;
}
.attachment-remove:hover { color: var(--red); }

/* M4：图片附件缩略图（附件区） */
.attachment-tag.attachment-image { padding: 3px 10px 3px 4px; }
.attachment-thumb {
  width: 32px;
  height: 32px;
  border-radius: 6px;
  overflow: hidden;
  flex-shrink: 0;
  cursor: zoom-in;
  background: rgba(0, 0, 0, 0.05);
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--accent-md);
}
.attachment-thumb img { width: 100%; height: 100%; object-fit: cover; }
.thumb-loading { font-size: 14px; opacity: 0.6; }

/* M4：消息气泡图片（本轮发送 / 历史回显） */
.message-images {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.message-image-thumb {
  width: 96px;
  height: 96px;
  border-radius: 10px;
  overflow: hidden;
  cursor: zoom-in;
  background: rgba(0, 0, 0, 0.04);
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border);
  transition: transform 0.15s, box-shadow 0.15s;
}
.message-image-thumb:hover {
  transform: scale(1.03);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.12);
}
.message-image-thumb img { width: 100%; height: 100%; object-fit: cover; }
/* M7：消息气泡文档卡片（文件格式图标 + 文件名，点击下载） */
.message-doc-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 240px;
  padding: 7px 12px;
  border-radius: 8px;
  cursor: pointer;
  background: rgba(0, 0, 0, 0.04);
  border: 1px solid var(--border);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.message-doc-chip:hover {
  border-color: var(--accent);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.12);
}
.doc-chip-icon { font-size: 16px; flex-shrink: 0; }
.doc-chip-name {
  font-size: 12px;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* M4：大图预览遮罩 */
.big-preview-mask {
  position: fixed;
  inset: 0;
  z-index: 2100;
  background: rgba(0, 0, 0, 0.78);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: zoom-out;
}
.big-preview-mask img {
  max-width: 92vw;
  max-height: 92vh;
  border-radius: 8px;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.5);
}

/* ===== 附件入口下拉菜单 + 历史图片选择弹窗 ===== */
.attach-menu-icon { margin-right: 6px; font-size: 13px; }
.history-picker-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.history-picker-search { flex: 1; }
.history-picker-count { font-size: 12px; color: var(--text-3); white-space: nowrap; }
.history-picker-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(118px, 1fr));
  gap: 10px;
  max-height: 400px;
  overflow-y: auto;
  padding: 2px;
}
.history-picker-item {
  border: 2px solid transparent;
  border-radius: var(--radius-xs);
  padding: 4px;
  cursor: pointer;
  background: rgba(0, 0, 0, 0.03);
  transition: border-color 0.15s, background 0.15s;
}
.history-picker-item:hover { border-color: var(--accent-md); }
.history-picker-item--selected { border-color: var(--accent); background: var(--accent-lt); }
.history-picker-item--attached { opacity: 0.55; cursor: not-allowed; }
.history-picker-thumb {
  position: relative;
  width: 100%;
  height: 96px;
  border-radius: 6px;
  overflow: hidden;
  background: rgba(0, 0, 0, 0.05);
  display: flex;
  align-items: center;
  justify-content: center;
}
.history-picker-thumb img { width: 100%; height: 100%; object-fit: cover; }
.history-picker-thumb-placeholder { font-size: 20px; opacity: 0.6; }
.history-picker-doc-icon { font-size: 34px; opacity: 0.9; }
.history-picker-check {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}
.history-picker-attached-badge {
  position: absolute;
  top: 4px;
  left: 4px;
  padding: 1px 6px;
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 10px;
}
.history-picker-name {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.history-picker-size { font-size: 11px; color: var(--text-4); }
.history-picker-empty {
  padding: 40px 0;
  text-align: center;
  color: var(--text-4);
  font-size: 13px;
}

/* 附件上传按钮 */
.attach-btn {
  height: 42px;
  width: 42px;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
  border: 2px solid var(--border);
  color: var(--text-3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.25s;
}
.attach-btn:hover:not(:disabled) {
  color: var(--accent);
  border-color: var(--accent);
  background: var(--accent-lt);
}
.attach-btn:disabled {
  color: var(--text-4);
  border-color: var(--border);
  background: transparent;
  opacity: 0.5;
}

/* 优化按钮 */
.optimize-btn {
  height: 42px;
  width: 42px;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
  border: 2px solid var(--accent);
  color: var(--accent);
  background: var(--accent-lt);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  transition: all 0.25s ease;
}
.optimize-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px var(--accent-glow);
  background: var(--accent);
  color: #fff;
}
.optimize-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ===== 输入框包装 ===== */
.input-wrapper {
  display: flex;
  gap: 8px;
  align-items: center;
}
.input-wrapper :deep(.ant-input) {
  border-radius: var(--radius-sm);
  resize: none;
  font-size: 14px;
  min-height: 42px;
  padding: 9px 12px;
  line-height: 22px;
  border-color: var(--border);
  transition: all 0.2s;
}
.input-wrapper :deep(.ant-input:focus) {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-lt);
}

/* 发送按钮 */
.send-btn {
  height: 42px;
  width: 42px;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
  background: linear-gradient(135deg, var(--accent), var(--accent-dk)) !important;
  border: none !important;
  box-shadow: 0 3px 10px var(--accent-glow);
  transition: all 0.25s ease;
}
.send-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 5px 16px var(--accent-glow);
}
.send-btn:disabled {
  background: #dcd8ea !important;
  box-shadow: none;
}

/* 停止按钮 */
.stop-btn {
  height: 42px;
  width: 42px;
  border-radius: 50%;
  flex-shrink: 0;
  background: rgba(0, 0, 0, 0.55);
  border: none;
  color: white;
  font-size: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.25s ease;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
}
.stop-btn:hover {
  background: var(--red);
  box-shadow: 0 4px 14px rgba(239, 68, 68, 0.35);
  transform: scale(1.06);
}
.stop-btn:active { transform: scale(0.95); }

/* ===== 输入底部 ===== */
.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
  flex-wrap: nowrap;
  gap: 8px;
  position: relative;
  min-width: 0;
}

.footer-left {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: nowrap;
  position: relative;
  flex: 1;
  min-width: 0;
  overflow: visible;
}
.footer-right {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  white-space: nowrap;
}
.context-tokens {
  color: var(--text-3);
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}
.balance-chip {
  margin-right: 10px;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
  color: #d48806;
  background: rgba(250, 173, 20, 0.12);
  border: 1px solid rgba(250, 173, 20, 0.32);
  white-space: nowrap;
  cursor: default;
}
.context-mode-badge {
  margin-left: 6px;
  padding: 1px 7px;
  border-radius: 10px;
  font-size: 10px;
  font-weight: 600;
  background: rgba(139, 92, 246, 0.12);
  color: #8b5cf6;
  border: 1px solid rgba(139, 92, 246, 0.25);
}

/* ===== 模式选择器 / Chip 标签 ===== */
.mode-selector {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius-xs);
  height: 30px;
  transition: all 0.2s ease;
  backdrop-filter: blur(4px);
}
.mode-selector:hover {
  border-color: var(--accent-md);
  background: var(--accent-lt);
  box-shadow: 0 1px 4px rgba(139, 92, 246, 0.08);
}

.mode-emoji {
  font-size: 13px;
  line-height: 1;
  flex-shrink: 0;
}
.mode-icon {
  font-size: 12px;
  color: var(--text-3);
  flex-shrink: 0;
}
.mode-label {
  font-size: 12px;
  color: var(--text-2);
  white-space: nowrap;
  font-weight: 600;
}

.mode-select {
  width: 68px;
}
.model-select {
  width: 130px;
}
.mode-select :deep(.ant-select-selection-item),
.model-select :deep(.ant-select-selection-item) {
  font-size: 12px !important;
  font-weight: 600 !important;
  color: var(--text-1) !important;
}
.mode-select :deep(.ant-select-selector),
.model-select :deep(.ant-select-selector) {
  background: transparent !important;
  border: none !important;
  box-shadow: none !important;
  padding: 0 !important;
  min-height: auto !important;
}
.mode-select :deep(.ant-select-arrow),
.model-select :deep(.ant-select-arrow) {
  color: var(--text-4);
  font-size: 10px;
}

/* ===== 任务进度触发器 ===== */
.task-trigger {
  cursor: pointer;
  position: relative;
}
.task-trigger:hover {
  background: var(--accent-lt);
  border-color: var(--accent-md);
}
.task-trigger.active {
  background: var(--accent-lt);
  border-color: var(--accent);
}
.task-trigger.loading .task-trigger-icon { color: var(--accent); }
.task-trigger-icon { font-size: 14px; }
.task-trigger-text {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-2);
  white-space: nowrap;
}

/* ===== 更多设置折叠区 ===== */
.more-settings-trigger {
  cursor: pointer;
  position: relative;
}
.more-settings-trigger:hover {
  background: var(--accent-lt);
  border-color: var(--accent-md);
}
.more-settings-trigger.active {
  background: var(--accent-lt);
  border-color: var(--accent);
}
.more-settings-arrow {
  font-size: 10px;
  color: var(--text-3);
  margin-left: 2px;
}
.more-settings-popover {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 0;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 200;
  min-width: 280px;
  animation: panelSlideUp 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.more-settings-item {
  display: flex;
  align-items: center;
  gap: 6px;
}
.more-settings-item .mode-emoji {
  font-size: 13px;
  flex-shrink: 0;
}
.more-settings-item .mode-label {
  font-size: 12px;
  color: var(--text-2);
  white-space: nowrap;
  font-weight: 600;
  min-width: 60px;
}

/* ===== 任务下拉面板 ===== */
.task-dropdown {
  position: absolute;
  bottom: 100%;
  left: 12px;
  right: 12px;
  margin-bottom: 10px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
  z-index: 100;
  max-height: 260px;
  display: flex;
  flex-direction: column;
  animation: panelSlideUp 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
@keyframes panelSlideUp {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
.task-dropdown-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  background: var(--accent-lt);
  border-bottom: 1px solid var(--border);
  font-size: 13px;
  font-weight: 700;
  color: var(--text-1);
  flex-shrink: 0;
}
.task-dropdown-progress {
  font-size: 12px;
  color: var(--accent);
  font-weight: 600;
}
.task-dropdown-body {
  padding: 4px 0;
  overflow-y: auto;
  flex: 1;
}
.task-dropdown-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  font-size: 12px;
  line-height: 1.5;
  transition: background 0.15s;
}
.task-dropdown-item:hover { background: var(--bg-hover); }
.task-dropdown-item--completed { opacity: 0.55; }
.task-dropdown-item--executing {
  background: var(--accent-lt);
}
.task-dropdown-icon { flex-shrink: 0; font-size: 13px; }
.task-dropdown-id {
  flex-shrink: 0;
  font-family: 'SF Mono', 'Monaco', 'Fira Code', monospace;
  font-size: 11px;
  color: var(--accent);
  font-weight: 700;
  min-width: 22px;
}
.task-dropdown-desc {
  color: var(--text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.task-dropdown-priority-high {
  flex-shrink: 0;
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  background: #fef2f2;
  color: var(--red);
  font-weight: 700;
  line-height: 16px;
}
.task-dropdown-deps {
  flex-shrink: 0;
  font-size: 10px;
  color: var(--text-4);
  font-family: 'SF Mono', 'Monaco', 'Fira Code', monospace;
}

/* ===== 文件改动触发器 ===== */
.changes-trigger {
  cursor: pointer;
  position: relative;
}
.changes-trigger:hover {
  background: var(--accent-lt);
  border-color: var(--accent-md);
}
.changes-trigger.active {
  background: var(--accent-lt);
  border-color: var(--accent);
}
.changes-trigger-icon { font-size: 14px; }
.changes-trigger-text {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-2);
  white-space: nowrap;
}
.changes-trigger-badge {
  font-size: 11px;
  color: var(--green);
  font-weight: 700;
  margin-left: 2px;
  white-space: nowrap;
}

/* ===== 文件改动面板 ===== */
.changes-panel {
  position: absolute;
  bottom: 100%;
  left: 12px;
  right: 12px;
  margin-bottom: 10px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
  z-index: 100;
  max-height: 340px;
  display: flex;
  flex-direction: column;
  animation: panelSlideUp 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.changes-panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  background: var(--bg-root);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.changes-panel-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-1);
}
.changes-panel-summary {
  font-size: 12px;
  color: var(--text-3);
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 4px;
}
.changes-summary-divider { color: var(--border); margin: 0 2px; }
.changes-panel-body { overflow-y: auto; flex: 1; }
.changes-panel-item {
  border-bottom: 1px solid var(--border);
  transition: background 0.15s;
  cursor: pointer;
}
.changes-panel-item:last-child { border-bottom: none; }
.changes-panel-item:hover { background: var(--bg-hover); }
.changes-panel-item.is-rolled { opacity: 0.5; }
.changes-file-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  gap: 12px;
}
.changes-file-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  flex: 1;
}
.changes-file-path-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.changes-file-icon { flex-shrink: 0; font-size: 12px; line-height: 1; }
.changes-file-path {
  color: var(--text-1);
  font-family: 'SF Mono', 'Monaco', 'Fira Code', monospace;
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.4;
}
.changes-file-meta {
  display: flex;
  align-items: center;
  gap: 6px;
}
.changes-badge {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 600;
  line-height: 18px;
  display: inline-block;
}
.changes-badge-new { background: #f0fdf4; color: var(--green); border: 1px solid #86efac; }
.changes-badge-rolled { background: #fef2f2; color: var(--red); border: 1px solid #fca5a5; }
.changes-file-stats {
  font-size: 12px;
  font-weight: 700;
  font-family: 'SF Mono', 'Monaco', 'Fira Code', monospace;
}
.changes-add { color: var(--green); }
.changes-del { color: var(--red); margin-left: 4px; }
.changes-file-actions { flex-shrink: 0; }
.changes-rollback-btn {
  font-size: 12px;
  color: var(--text-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-xs);
  padding: 0 10px;
  height: 28px;
  line-height: 26px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  transition: all 0.2s;
}
.changes-rollback-btn:not(:disabled):hover {
  color: var(--red) !important;
  border-color: var(--red);
  background: #fef2f2;
}
.changes-rollback-btn--rolled {
  color: var(--text-4) !important;
  border-color: var(--border);
  cursor: not-allowed;
}
.changes-empty {
  padding: 36px 14px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: var(--text-4);
  font-size: 13px;
}
.changes-empty-icon { font-size: 28px; opacity: 0.5; }
.changes-panel-footer {
  display: flex;
  justify-content: center;
  padding: 10px 14px;
  border-top: 1px solid var(--border);
  flex-shrink: 0;
  background: var(--bg-root);
}
.changes-rollback-all-btn {
  font-size: 12px;
  height: 28px;
  padding: 0 16px;
  border-radius: var(--radius-xs);
  border-color: var(--red);
  color: var(--red);
}
.changes-rollback-all-btn:not(:disabled):hover {
  color: #fff !important;
  background: var(--red);
  border-color: var(--red);
}
.changes-rollback-all-btn:disabled {
  border-color: var(--border);
  color: var(--text-4);
}

/* ===== 旧模式选择器样式（保留兼容） ===== */
.footer-left {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
  overflow: visible;
}
.footer-right {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  white-space: nowrap;
}

/* Git 侧边栏 / 技能面板（已在侧边栏通用样式中定义背景） */
.git-panel,
.skill-panel {
  flex: 1;
  overflow-y: auto;
}

/* ===== 流式加载指示器 ===== */
.stream-indicator {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 24px;
  flex-shrink: 0;
  /* 背景透明：与聊天区背景融为一体（透出背景图 / 页面遮罩），
     只保留圆点 + 状态文字 + 用时，不再形成实心横条挡在背景上 */
  background: transparent;
}
.stream-pulse-dot {
  width: 9px;
  height: 9px;
  background: var(--accent);
  border-radius: 50%;
  animation: streamPulse 1.2s ease-in-out infinite;
  flex-shrink: 0;
}
.stream-indicator-text {
  font-size: 13px;
  color: var(--accent);
  font-weight: 600;
}
@keyframes streamPulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(0.75); }
}
.stream-elapsed {
  font-size: 13px;
  color: var(--accent);
  font-weight: 700;
  margin-left: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* ===== ask_user 问答面板 ===== */
.ask-user-panel {
  margin: 0 18px 14px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 20px 22px;
  box-shadow: var(--shadow-md);
  animation: panelSlideUp 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  text-align: left;
  align-self: flex-start;
  /* 2026-09-21 修复：问题内容（LLM 输出）过长时面板会撑出聊天区，被 .chat-messages-area 的
     overflow:hidden 裁剪底部回答按钮/输入框，导致用户无法回答问题。
     限制面板最大高度（至多占聊天区 70% 且不超过 640px），配合内部滚动链：
     「标题 + 内容滚动区 + 操作区」始终完整可见 */
  display: flex;
  flex-direction: column;
  min-height: 0;
  max-height: min(70%, 640px);
}
.ask-user-header {
  font-weight: 700;
  font-size: 15px;
  color: var(--text-1);
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0; /* 滚动链（2026-09-21）：标题始终可见，不参与压缩 */
}
.ask-user-header-icon { font-size: 18px; line-height: 1; }
/* P3：后台授权等待角标 */
.bg-permission-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #d48806;
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: var(--radius-sm);
  padding: 6px 12px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: opacity 0.2s;
}
.bg-permission-tip-icon { font-size: 14px; }
/* P3：授权归属 Agent 标识 */
.ask-user-agent-tag {
  font-size: 12px;
  font-weight: 500;
  color: var(--primary);
  background: color-mix(in srgb, var(--primary) 12%, transparent);
  padding: 2px 8px;
  border-radius: 10px;
  margin-left: auto;
}
.ask-user-question {
  font-size: 14px;
  color: var(--text-2);
  margin-bottom: 16px;
  line-height: 1.65;
  background: var(--bg-root);
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  border-left: 3px solid var(--accent);
  /* 滚动链（2026-09-21）：内容超长时在内部滚动，不撑破面板（flex 收缩 + 独立滚动） */
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
}
/* 滚动链配套：滚动条样式对齐项目规范（2026-09-21） */
.ask-user-question::-webkit-scrollbar { width: 5px; height: 5px; }
.ask-user-question::-webkit-scrollbar-thumb { background: #dcd8ea; border-radius: 3px; }
.ask-user-question::-webkit-scrollbar-track { background: transparent; }
/* markdown 渲染后的块级元素间距优化 */
.ask-user-question p { margin: 0 0 8px; }
.ask-user-question p:last-child { margin-bottom: 0; }
.ask-user-question ul, .ask-user-question ol { margin: 0 0 8px; padding-left: 20px; }
.ask-user-question ul:last-child, .ask-user-question ol:last-child { margin-bottom: 0; }
.ask-user-question code { background: var(--bg-hover); padding: 1px 6px; border-radius: 3px; font-size: 13px; }
.ask-user-input-row {
  display: flex;
  gap: 8px;
  flex-shrink: 0; /* 滚动链（2026-09-21）：回答输入框始终可见 */
}
.ask-user-input-row .a-input { flex: 1; }

/* ===== 任务迭代确认面板（评委否决 / 累计扩容达上限 → 用户决定是否继续） ===== */
.iteration-confirm-panel {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
}
.iteration-confirm-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  flex-shrink: 0; /* 滚动链：按钮区始终可见 */
}
.iteration-confirm-btn {
  height: 40px !important;
  font-size: 13px !important;
  border-radius: var(--radius-sm) !important;
  font-weight: 600 !important;
  flex: 0 0 auto;
}
.iteration-confirm-hint {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-3);
  flex-shrink: 0;
}

/* ===== 权限面板 ===== */
.permission-panel {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
}
/* 询问（clarification）分支容器：与 permission-panel 同构，支撑内容区滚动链（2026-09-21） */
.ask-user-clarify-panel {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
}

/* ===== 权限授权按钮 ===== */
.permission-btn-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 0;
  justify-content: flex-start;
  flex-shrink: 0; /* 滚动链（2026-09-21）：授权按钮始终可见 */
}
.permission-btn {
  height: 40px !important;
  font-size: 13px !important;
  border-radius: var(--radius-sm) !important;
  font-weight: 600 !important;
  transition: all 0.25s ease !important;
  flex: 0 0 auto;
  min-width: 90px;
}
.permission-btn:hover {
  transform: translateY(-1px);
}
.permission-btn-approve {
  background: linear-gradient(135deg, var(--accent), var(--accent-dk)) !important;
  border-color: transparent !important;
  color: #fff !important;
  box-shadow: 0 3px 10px var(--accent-glow);
}
.permission-btn-approve:hover {
  box-shadow: 0 5px 16px var(--accent-glow);
}
.permission-btn-approve-all {
  color: var(--accent) !important;
  border-color: var(--accent-md) !important;
  background: var(--bg-card) !important;
}
.permission-btn-approve-all:hover {
  color: var(--accent-dk) !important;
  border-color: var(--accent) !important;
  background: var(--accent-lt) !important;
}
.permission-btn-reject {
  color: var(--red) !important;
  border-color: rgba(239,68,68,0.3) !important;
  background: var(--bg-card) !important;
}
.permission-btn-reject:hover {
  color: #dc2626 !important;
  border-color: var(--red) !important;
  background: #fef2f2 !important;
}
.permission-btn-custom {
  color: var(--text-2) !important;
  border-color: var(--border) !important;
  background: var(--bg-card) !important;
}
.permission-btn-custom:hover {
  color: var(--accent) !important;
  border-color: var(--accent) !important;
  background: var(--accent-lt) !important;
}
.permission-btn-custom.active {
  color: var(--accent) !important;
  border-color: var(--accent) !important;
  background: var(--accent-lt) !important;
}
.permission-custom-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  flex-shrink: 0; /* 滚动链（2026-09-21）：自定义回答输入行始终可见 */
}
.permission-custom-row .a-input { flex: 1; }
.custom-send-btn { flex-shrink: 0; }

/* 展开/收起动画 */
.custom-fade-enter-active { animation: customFadeIn 0.2s ease-out; }
.custom-fade-leave-active { animation: customFadeIn 0.15s ease-in reverse; }
@keyframes customFadeIn {
  from { opacity: 0; transform: translateY(-6px); max-height: 0; }
  to { opacity: 1; transform: translateY(0); max-height: 80px; }
}

/* ===== 补充需求面板 ===== */
.supplement-panel {
  margin: 0 18px 10px;
  flex-shrink: 0;
}
.supplement-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: var(--bg-card);
  border: 1px dashed var(--accent-md);
  border-radius: 20px;
  cursor: pointer;
  font-size: 13px;
  color: var(--accent);
  font-weight: 600;
  transition: all 0.25s ease;
  user-select: none;
}
.supplement-trigger:hover {
  background: var(--accent-lt);
  border-color: var(--accent);
  border-style: solid;
  transform: translateY(-1px);
  box-shadow: 0 3px 10px var(--accent-glow);
}
.supplement-icon {
  font-size: 16px;
  line-height: 1;
}
.supplement-input-row {
  display: flex;
  gap: 8px;
  align-items: center;
  background: var(--bg-card);
  border: 1px solid var(--accent-md);
  border-radius: var(--radius-sm);
  padding: 10px 14px;
  animation: panelSlideUp 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.supplement-input-row :deep(.ant-input) {
  flex: 1;
  font-size: 13px;
}

/* ===== 底部运行日志面板 ===== */
.run-log-panel {
  flex-shrink: 0;
  background: var(--bg-card);
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  min-height: 32px;
  max-height: 50vh;
  position: relative;
}
.run-log-panel.minimized {
  flex: 0 0 auto !important;
  height: auto !important;
}
.log-resize-handle {
  position: absolute;
  top: -4px;
  left: 0;
  right: 0;
  height: 8px;
  cursor: ns-resize;
  z-index: 10;
}
.log-resize-handle:hover { background: rgba(139, 92, 246, 0.25); }

/* 日志面板 Tab 栏 */
.log-tab-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--bg-root);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
  min-height: 34px;
  user-select: none;
}
.log-tab {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 14px;
  height: 34px;
  font-size: 12px;
  color: var(--text-1);
  border-bottom: 2px solid var(--accent);
  background: var(--bg-card);
}

/* 日志内容 — xterm.js 终端 */
.log-xterm-container {
  flex: 1;
  overflow: hidden;
  min-height: 60px;
}

/* ===== 暗色模式 ===== */
[data-theme="dark"] .chat-container {
  --bg-root: #121117;
  --bg-card: #1a1925;
  --bg-chat: #15141d;
  --bg-hover: rgba(139, 92, 246, 0.06);
  --text-1: #e4e2f0;
  --text-2: #a09eb8;
  --text-3: #6a6880;
  --text-4: #525070;
  --border: #2a2838;
  --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.3);
  --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.4);
  --shadow-lg: 0 8px 30px rgba(0, 0, 0, 0.5);
  --accent-glow: rgba(139, 92, 246, 0.25);
}

[data-theme="dark"] .thinking-section { background: #1a1925; border-color: #2a2838; }
[data-theme="dark"] .thinking-header { background: #1e1d2c; }
[data-theme="dark"] .thinking-header:hover { background: #252438; }

[data-theme="dark"] .message-item.assistant .message-content {
  background: #1a1925;
  border-color: #2a2838;
}

  .sidebar { box-shadow: 1px 0 12px rgba(0, 0, 0, 0.3); }

  /* 折叠：暗色 tab 按钮 */
  .collapsed .tab-btn:hover {
    border-color: rgba(139, 92, 246, 0.35);
    background: rgba(139, 92, 246, 0.08);
  }
  .collapsed .tab-btn.active {
    box-shadow: 0 4px 16px rgba(139, 92, 246, 0.5);
  }

[data-theme="dark"] .code-message pre,
[data-theme="dark"] .thinking-text pre {
  background: #1a1925;
  border-color: #2a2838;
}

/* 文件改动Badge */
[data-theme="dark"] .changes-badge-new {
  background: rgba(16,185,129,0.12);
  border-color: rgba(16,185,129,0.25);
}
[data-theme="dark"] .changes-badge-rolled {
  background: rgba(239,68,68,0.12);
  border-color: rgba(239,68,68,0.25);
}

/* 任务下拉优先级标签 */
[data-theme="dark"] .task-dropdown-priority-high {
  background: rgba(239,68,68,0.12);
}

/* 发送按钮 disabled — 暗色 */
[data-theme="dark"] .send-btn:disabled {
  background: #2a2838 !important;
  box-shadow: none;
  color: #525070 !important;
}

/* 权限按钮 hover */
[data-theme="dark"] .permission-btn-reject:hover {
  background: rgba(239,68,68,0.1) !important;
}

/* 非折叠 tab 按钮补齐 */
[data-theme="dark"] .tab-btn.active {
  background: var(--bg-card);
  color: var(--accent);
}
[data-theme="dark"] .tab-btn:hover {
  background: var(--accent-lt);
}

/* 命中技能标签（暗色） */
[data-theme="dark"] .skill-hit-tag {
  color: #c4b5fd;
  background: rgba(139, 92, 246, 0.16);
  border-color: rgba(139, 92, 246, 0.4);
}

/* ===== 暗色：发送面板 ===== */
[data-theme="dark"] .mode-selector {
  background: #1a1925;
  border-color: #2a2838;
}
[data-theme="dark"] .balance-chip {
  color: #fbbf24;
  background: rgba(251, 191, 36, 0.12);
  border-color: rgba(251, 191, 36, 0.3);
}
[data-theme="dark"] .mode-selector:hover {
  border-color: rgba(139, 92, 246, 0.35);
  background: rgba(139, 92, 246, 0.06);
}
[data-theme="dark"] .attach-btn {
  border-color: #2a2838;
  color: #6a6880;
}
[data-theme="dark"] .attach-btn:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}
[data-theme="dark"] .input-wrapper :deep(.ant-input) {
  background: #1a1925;
  border-color: #2a2838;
  color: #e4e2f0;
}
[data-theme="dark"] .input-wrapper :deep(.ant-input::placeholder) {
  color: #525070;
}
[data-theme="dark"] .input-wrapper :deep(.ant-input:focus) {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px rgba(139, 92, 246, 0.15);
}

/* ===== 暗色模式 - 滚动条适配 ===== */
[data-theme="dark"] .conversation-list::-webkit-scrollbar-thumb,
[data-theme="dark"] .filetree-panel::-webkit-scrollbar-thumb,
[data-theme="dark"] .git-panel::-webkit-scrollbar-thumb,
[data-theme="dark"] .skill-panel::-webkit-scrollbar-thumb,
[data-theme="dark"] .agent-panel-wrapper::-webkit-scrollbar-thumb {
  background: #3a3850;
}
[data-theme="dark"] .tab-bar::-webkit-scrollbar-thumb,
[data-theme="dark"] .tab-items::-webkit-scrollbar-thumb {
  background: #3a3850;
}
/* 暗色分屏按钮 */
[data-theme="dark"] .tab-split-btn:hover {
  background: rgba(139, 92, 246, 0.15);
  color: #a78bfa;
}
[data-theme="dark"] .tab-unsplit-btn {
  background: rgba(139, 92, 246, 0.15);
  color: #a78bfa;
}
[data-theme="dark"] .tab-unsplit-btn:hover {
  background: rgba(239, 68, 68, 0.2);
  color: #f87171;
}
[data-theme="dark"] .file-tab-empty {
  color: #6b6b80;
}
[data-theme="dark"] .message-list::-webkit-scrollbar {
  width: 5px;
}
[data-theme="dark"] .message-list::-webkit-scrollbar-thumb {
  background: #3a3850;
  border-radius: 3px;
}
[data-theme="dark"] .message-list::-webkit-scrollbar-track {
  background: transparent;
}
/* 滚动链配套（2026-09-21）：ask_user 问题内容区滚动条（深色主题） */
[data-theme="dark"] .ask-user-question::-webkit-scrollbar {
  width: 5px;
  height: 5px;
}
[data-theme="dark"] .ask-user-question::-webkit-scrollbar-thumb {
  background: #3a3850;
  border-radius: 3px;
}
[data-theme="dark"] .ask-user-question::-webkit-scrollbar-track {
  background: transparent;
}
[data-theme="dark"] .thinking-content::-webkit-scrollbar {
  width: 4px;
}
[data-theme="dark"] .thinking-content::-webkit-scrollbar-thumb {
  background: #3a3850;
  border-radius: 2px;
}
[data-theme="dark"] .thinking-content::-webkit-scrollbar-track {
  background: transparent;
}
[data-theme="dark"] .diff-tab-body::-webkit-scrollbar-thumb,
[data-theme="dark"] .task-dropdown-body::-webkit-scrollbar-thumb,
[data-theme="dark"] .changes-panel-body::-webkit-scrollbar-thumb,
[data-theme="dark"] .console-log-body::-webkit-scrollbar-thumb,
[data-theme="dark"] .tool-result-body::-webkit-scrollbar-thumb {
  background: #3a3850;
  border-radius: 2px;
}
[data-theme="dark"] .diff-tab-body::-webkit-scrollbar,
[data-theme="dark"] .task-dropdown-body::-webkit-scrollbar,
[data-theme="dark"] .changes-panel-body::-webkit-scrollbar,
[data-theme="dark"] .console-log-body::-webkit-scrollbar,
[data-theme="dark"] .tool-result-body::-webkit-scrollbar {
  width: 4px;
}
</style>

<!-- 非 scoped 样式：markdown 内容排版（v-html 内元素不受 scoped 影响） -->
<style>
/* ============================================================
   全局组件样式（非 scoped，v-html 内元素不受 scoped 影响）
   设计语言对齐 P2P Panel v3 / Layout Sidebar v2
   ============================================================ */

/* ===== 工具调用结果卡片 ===== */
.tool-result-card {
  border: 1px solid var(--border, #e8e5f0);
  border-radius: 8px;
  overflow: hidden;
  background: var(--bg-root, #f5f3fa);
  font-size: 12px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.tool-result-card.tool-result-error {
  border-color: #fca5a5;
  background: #fef2f2;
}
.tool-result-card.tool-result-collapsed .tool-result-body { display: none; }
.tool-result-card.tool-result-collapsed .tr-toggle { transform: rotate(0deg); }
/* pending 卡片始终展开，不响应折叠 */
.tool-result-pending .tool-result-body { display: block !important; }

.tool-result-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  cursor: pointer;
  user-select: none;
  font-weight: 600;
  color: var(--text-1, #1a1a2e);
  background: #f0edf6;
  border-bottom: 1px solid var(--border, #e8e5f0);
  transition: background 0.15s;
}
.tool-result-header:hover { background: #e8e2f2; }
.tool-result-error .tool-result-header {
  background: #fee;
  border-bottom-color: #fca5a5;
}
.tool-result-error .tool-result-header:hover { background: #fdd; }
.tr-toggle {
  font-size: 10px;
  color: #94a3b8;
  transition: transform 0.2s;
  transform: rotate(90deg);
  flex-shrink: 0;
}
.tr-icon {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 10px;
  font-weight: 700;
}
.tr-icon-success { background: #dcfce7; color: #16a34a; }
.tr-icon-error { background: #fef2f2; color: #dc2626; }
.tr-label { font-weight: 700; flex-shrink: 0; }
.tr-summary {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-3, #9696aa);
  font-weight: 400;
  font-size: 11px;
}
.tool-result-body { max-height: 400px; overflow-y: auto; }
.tool-result-body pre {
  margin: 0;
  padding: 10px 12px;
  font-family: 'SF Mono', 'Monaco', 'Fira Code', monospace;
  font-size: 12px;
  line-height: 1.45;
  color: var(--text-2, #5c5c78);
  white-space: pre-wrap;
  word-break: break-all;
  text-align: left;
}
.tool-result-error .tool-result-body pre { color: #991b1b; }

/* ===== M3：超长内容截断提示行 ===== */
.tool-result-truncated {
  padding: 6px 12px;
  font-size: 11px;
  color: #9696aa;
  background: #faf9fd;
  border-top: 1px dashed var(--border, #e8e5f0);
  text-align: center;
}
.tr-expand-all { color: #7c3aed; cursor: pointer; font-weight: 600; }
.tr-expand-all:hover { text-decoration: underline; }

/* ===== 2026-09-21：委托卡片「前往 B 会话」按钮 ===== */
.jump-to-session-btn {
  flex-shrink: 0;
  margin-left: 6px;
  padding: 2px 10px;
  font-size: 11px;
  font-weight: 600;
  color: #7c3aed;
  background: #f3e8ff;
  border: 1px solid #c4b5fd;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}
.jump-to-session-btn:hover {
  background: #ede9fe;
  border-color: #a78bfa;
}

/* ===== 工具调用加载中（pending）卡片 ===== */
.tool-result-pending {
  border-color: #a78bfa !important;
  background: #faf5ff !important;
  animation: tool-card-pulse 2s ease-in-out infinite;
}
@keyframes tool-card-pulse {
  0%, 100% { border-color: #a78bfa; }
  50% { border-color: #c4b5fd; }
}
.tool-result-pending .tool-result-header {
  background: #f3e8ff;
  border-bottom-color: #a78bfa;
  cursor: default;
}
.tool-result-pending .tool-result-header:hover {
  background: #f3e8ff;
}
.tr-toggle-spin {
  animation: tr-spin 1.5s linear infinite;
  display: inline-block;
}
@keyframes tr-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.tr-icon-pending {
  background: #ede9fe;
  color: #7c3aed;
  position: relative;
  overflow: hidden;
}
.tr-pending-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  background: #7c3aed;
  border-radius: 50%;
  animation: pending-dot-bounce 1.2s ease-in-out infinite;
}
@keyframes pending-dot-bounce {
  0%, 100% { transform: scale(0.6); opacity: 0.4; }
  50% { transform: scale(1.2); opacity: 1; }
}
.tool-result-loading {
  padding: 10px 12px;
}
.tool-loading-bar {
  height: 3px;
  background: #e9d5ff;
  border-radius: 2px;
  overflow: hidden;
  margin-bottom: 6px;
}
.tool-loading-bar-inner {
  height: 100%;
  width: 40%;
  background: linear-gradient(90deg, #a78bfa, #7c3aed, #a78bfa);
  background-size: 200% 100%;
  border-radius: 2px;
  animation: loading-bar-slide 1.8s ease-in-out infinite;
}
@keyframes loading-bar-slide {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(350%); }
}
.tool-loading-hint {
  font-size: 11px;
  color: #7c3aed;
  font-weight: 500;
  text-align: center;
}

/* ===== 技能使用卡片 ===== */
.skill-usage-card { border-radius: 8px; overflow: hidden; font-size: 12px; }
.skill-usage-card.skill-usage-success { border: 1px solid #86efac; background: #f0fdf4; }
.skill-usage-card.skill-usage-fail { border: 1px solid #fca5a5; background: #fef2f2; }
.skill-usage-card .tool-result-header { border-bottom: none; }
.skill-usage-card.skill-usage-success .tool-result-header { background: #dcfce7; }
.skill-usage-card.skill-usage-fail .tool-result-header { background: #fee; }
.skill-usage-badge {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 11px;
  font-weight: 700;
}
.skill-usage-badge-ok { background: #16a34a; color: #fff; }
.skill-usage-badge-fail { background: #dc2626; color: #fff; }
.skill-usage-conf {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 10px;
  background: #e0e7ff;
  color: #4338ca;
}

/* ===== 代码块命令卡片（:deep(.code-block-cmd) 移到此处作为全局样式） ===== */
.code-block-cmd {
  border: 1px solid var(--border, #e8e5f0);
  border-radius: 8px;
  overflow: hidden;
  font-family: 'SF Mono', 'Monaco', 'Fira Code', monospace;
  font-size: 12px;
  margin: 8px 0;
  background: var(--bg-card, #fff);
  color: var(--text-1, #1a1a2e);
}
.cbc-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: #f0edf6;
  border-bottom: 1px solid var(--border, #e8e5f0);
}
.cbc-title { font-weight: 700; font-size: 11px; color: var(--text-1, #1a1a2e); }
.cbc-status { margin-left: auto; font-size: 10px; font-weight: 700; }
.cbc-status.success { color: #16a34a; }
.cbc-status.fail { color: #dc2626; }
.cbc-body { padding: 6px 0; }
.cbc-command {
  display: flex;
  gap: 6px;
  padding: 2px 12px 8px;
  border-bottom: 1px solid var(--border, #e8e5f0);
  margin-bottom: 4px;
}
.cbc-prompt { color: #16a34a; font-weight: 700; user-select: none; }
.cbc-output {
  padding: 0 12px;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
  color: var(--text-2, #5c5c78);
}

/* ===== 文件列表卡片 ===== */
.code-block-filelist {
  border: 1px solid var(--border, #e8e5f0);
  border-radius: 8px;
  overflow: hidden;
  font-size: 12px;
  margin: 8px 0;
  background: var(--bg-card, #fff);
}
.cbf-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: #f0edf6;
  border-bottom: 1px solid var(--border, #e8e5f0);
}
.cbf-title { font-weight: 700; color: var(--text-1, #1a1a2e); font-size: 11px; }
.cbf-count { margin-left: auto; color: var(--text-3, #9696aa); font-size: 10px; }
.cbf-body { padding: 4px 0; }
.cbf-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  font-family: 'SF Mono', 'Monaco', 'Fira Code', monospace;
}
.cbf-badge {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  font-weight: 700;
  flex-shrink: 0;
  line-height: 16px;
}
.cbf-item.add .cbf-badge { background: #f0fdf4; color: #16a34a; }
.cbf-item.mod .cbf-badge { background: #fffbeb; color: #d97706; }
.cbf-item.del .cbf-badge { background: #fef2f2; color: #dc2626; }
.cbf-path { color: var(--text-1, #1a1a2e); }
.cbf-summary { color: var(--text-3, #9696aa); }

/* ===== 代码块 ===== */
.code-message pre {
  background: var(--bg-root, #f5f3fa);
  border-radius: 8px;
  padding: 14px 18px;
  font-size: 13px;
  line-height: 1.55;
  margin: 8px 0;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-x: hidden;
  border: 1px solid var(--border, #e8e5f0);
  text-align: left;
}
.code-message code {
  font-family: 'SF Mono', 'Monaco', 'Fira Code', monospace;
  font-size: 0.9em;
}
.code-message p code {
  background: #f0edf6;
  padding: 2px 6px;
  border-radius: 4px;
  color: var(--accent, #8b5cf6);
}
.thinking-text pre {
  background: var(--bg-root, #f5f3fa);
  border: 1px solid var(--border, #e8e5f0);
  border-radius: 8px;
  padding: 12px 16px;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-x: hidden;
  text-align: left;
}

/* ===== highlight.js 主题 ===== */
.code-message pre code.hljs,
.thinking-text pre code.hljs {
  background: transparent;
  color: var(--text-1, #1a1a2e);
}
.code-message .hljs-keyword,
.thinking-text .hljs-keyword { color: #cf222e; }
.code-message .hljs-string,
.thinking-text .hljs-string { color: #0a3069; }
.code-message .hljs-number,
.thinking-text .hljs-number { color: #0550ae; }
.code-message .hljs-comment,
.thinking-text .hljs-comment { color: #6e7781; font-style: italic; }
.code-message .hljs-title,
.thinking-text .hljs-title { color: var(--accent, #8250df); }
.code-message .hljs-built_in,
.thinking-text .hljs-built_in { color: #0550ae; }
.code-message .hljs-type,
.thinking-text .hljs-type { color: #0550ae; }
.code-message .hljs-literal,
.thinking-text .hljs-literal { color: #0550ae; }
.code-message .hljs-attr,
.thinking-text .hljs-attr { color: #0550ae; }
.code-message .hljs-selector-class,
.thinking-text .hljs-selector-class { color: #0550ae; }
.code-message .hljs-meta,
.thinking-text .hljs-meta { color: var(--accent, #8250df); }
.code-message .hljs-tag,
.thinking-text .hljs-tag { color: #116329; }
.code-message .hljs-name,
.thinking-text .hljs-name { color: #116329; }
.code-message .hljs-attribute,
.thinking-text .hljs-attribute { color: #0550ae; }

/* ===== Diff 高亮 ===== */
.code-message .hljs-addition,
.thinking-text .hljs-addition {
  background: #e6ffed;
  color: #1a7f37;
  display: inline-block;
  width: 100%;
}
.code-message .hljs-deletion,
.thinking-text .hljs-deletion {
  background: #ffebe9;
  color: #cf222e;
  display: inline-block;
  width: 100%;
}
.code-message .hljs-section,
.thinking-text .hljs-section { color: var(--accent, #8b5cf6); font-weight: 700; }

/* ===== 列表排版 ===== */
.code-message ol,
.code-message ul,
.thinking-text ol,
.thinking-text ul {
  padding-left: 28px;
  margin: 6px 0;
  text-align: left;
}
.code-message li,
.thinking-text li {
  margin-bottom: 4px;
  line-height: 1.75;
  text-align: left;
}
.code-message li p,
.thinking-text li p { margin: 0; display: inline; }
.code-message p,
.thinking-text p {
  margin: 6px 0;
  line-height: 1.75;
  text-align: left;
}
.code-message h1, .code-message h2, .code-message h3, .code-message h4,
.thinking-text h1, .thinking-text h2, .thinking-text h3, .thinking-text h4 {
  margin: 14px 0 6px;
  color: var(--text-1, #1a1a2e);
  text-align: left;
}
.code-message blockquote,
.thinking-text blockquote {
  margin: 8px 0;
  padding: 6px 14px;
  border-left: 3px solid var(--accent, #8b5cf6);
  color: var(--text-2, #5c5c78);
  background: var(--bg-root, #f5f3fa);
  border-radius: 0 6px 6px 0;
}
.code-message table,
.thinking-text table {
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 13px;
}
.code-message th, .code-message td,
.thinking-text th, .thinking-text td {
  border: 1px solid var(--border, #e8e5f0);
  padding: 6px 10px;
  text-align: left;
}
.code-message th,
.thinking-text th {
  background: #f0edf6;
  font-weight: 700;
}

/* ===== 暗色模式适配（非scoped） ===== */
[data-theme="dark"] .code-message pre,
[data-theme="dark"] .thinking-text pre {
  background: #1a1925;
  border-color: #2a2838;
}
[data-theme="dark"] .code-message p code {
  background: #2a2838;
  color: #a78bfa;
}
[data-theme="dark"] .tool-result-card {
  border-color: #2a2838;
  background: #1a1925;
}
[data-theme="dark"] .tool-result-header {
  background: #1e1d2c;
  border-bottom-color: #2a2838;
  color: #e4e2f0;
}
[data-theme="dark"] .tool-result-header:hover { background: #252438; }
[data-theme="dark"] .tool-result-error .tool-result-header { background: #3b1a1a; border-bottom-color: #dc2626; }
[data-theme="dark"] .tool-result-error .tool-result-header:hover { background: #4a2020; }
[data-theme="dark"] .tool-result-body pre { color: #a09eb8; }
[data-theme="dark"] .tool-result-error .tool-result-body pre { color: #fca5a5; }
[data-theme="dark"] .tool-result-truncated { background: #201f30; color: #7e7c96; border-top-color: #2e2d42; }
[data-theme="dark"] .tr-expand-all { color: #b39ddb; }
[data-theme="dark"] .code-block-cmd,
[data-theme="dark"] .code-block-filelist {
  background: #1a1925;
  border-color: #2a2838;
  color: #e4e2f0;
}
[data-theme="dark"] .cbc-header,
[data-theme="dark"] .cbf-header {
  background: #1e1d2c;
  border-bottom-color: #2a2838;
}
[data-theme="dark"] .cbc-prompt { color: var(--green); }
[data-theme="dark"] .cbc-output { color: #a09eb8; }
[data-theme="dark"] .cbf-item.add .cbf-badge { background: rgba(16,185,129,0.15); }
[data-theme="dark"] .cbf-item.mod .cbf-badge { background: rgba(245,158,11,0.15); }
[data-theme="dark"] .cbf-item.del .cbf-badge { background: rgba(239,68,68,0.15); }
[data-theme="dark"] .code-message blockquote,
[data-theme="dark"] .thinking-text blockquote {
  background: #1a1925;
  color: #a09eb8;
}
[data-theme="dark"] .code-message th,
[data-theme="dark"] .thinking-text th { background: #1e1d2c; }
[data-theme="dark"] .code-message td,
[data-theme="dark"] .thinking-text td {
  background: #1a1925;
  color: #a09eb8;
}
[data-theme="dark"] .code-message th,
[data-theme="dark"] .code-message td,
[data-theme="dark"] .thinking-text th,
[data-theme="dark"] .thinking-text td { border-color: #2a2838; }
[data-theme="dark"] .code-message table,
[data-theme="dark"] .thinking-text table {
  background: #1a1925;
}

/* hljs diff 高亮 — 暗色 */
[data-theme="dark"] .code-message .hljs-addition,
[data-theme="dark"] .thinking-text .hljs-addition {
  background: rgba(16,185,129,0.12);
  color: #4ade80;
}
[data-theme="dark"] .code-message .hljs-deletion,
[data-theme="dark"] .thinking-text .hljs-deletion {
  background: rgba(239,68,68,0.12);
  color: #f87171;
}

/* hljs 语法高亮 — 暗色适配 */
[data-theme="dark"] .code-message .hljs-keyword,
[data-theme="dark"] .thinking-text .hljs-keyword { color: #f87171; }
[data-theme="dark"] .code-message .hljs-string,
[data-theme="dark"] .thinking-text .hljs-string { color: #a5d6ff; }
[data-theme="dark"] .code-message .hljs-number,
[data-theme="dark"] .thinking-text .hljs-number { color: #79c0ff; }
[data-theme="dark"] .code-message .hljs-comment,
[data-theme="dark"] .thinking-text .hljs-comment { color: #6a6880; }
[data-theme="dark"] .code-message .hljs-built_in,
[data-theme="dark"] .thinking-text .hljs-built_in { color: #79c0ff; }
[data-theme="dark"] .code-message .hljs-type,
[data-theme="dark"] .thinking-text .hljs-type { color: #79c0ff; }
[data-theme="dark"] .code-message .hljs-literal,
[data-theme="dark"] .thinking-text .hljs-literal { color: #79c0ff; }
[data-theme="dark"] .code-message .hljs-attr,
[data-theme="dark"] .thinking-text .hljs-attr { color: #79c0ff; }
[data-theme="dark"] .code-message .hljs-tag,
[data-theme="dark"] .thinking-text .hljs-tag { color: #7ee787; }
[data-theme="dark"] .code-message .hljs-name,
[data-theme="dark"] .thinking-text .hljs-name { color: #7ee787; }
[data-theme="dark"] .code-message .hljs-attribute,
[data-theme="dark"] .thinking-text .hljs-attribute { color: #79c0ff; }
[data-theme="dark"] .code-message .hljs-selector-class,
[data-theme="dark"] .thinking-text .hljs-selector-class { color: #79c0ff; }
[data-theme="dark"] .code-message pre code.hljs,
[data-theme="dark"] .thinking-text pre code.hljs {
  color: #e4e2f0;
}

/* 文件列表 & 路径 — 暗色 */
[data-theme="dark"] .cbf-header {
  background: #1e1d2c;
}
[data-theme="dark"] .cbf-title {
  color: #e4e2f0;
}
[data-theme="dark"] .cbf-count {
  color: #6a6880;
}
[data-theme="dark"] .cbf-path {
  color: #e4e2f0;
}
[data-theme="dark"] .cbf-summary {
  color: #a09eb8;
}
[data-theme="dark"] .cbf-item:hover {
  background: rgba(139,92,246,0.06);
}

/* 工具结果卡片补充 */
[data-theme="dark"] .tool-result-card.tool-result-error {
  background: #1f1215;
  border-color: #dc2626;
}
[data-theme="dark"] .tool-result-pending {
  background: #1a1525 !important;
  border-color: #7c3aed !important;
}
[data-theme="dark"] .tool-result-pending .tool-result-header {
  background: #1e1830;
  border-bottom-color: #7c3aed;
}
[data-theme="dark"] .tool-result-pending .tool-result-header:hover {
  background: #1e1830;
}
[data-theme="dark"] .tr-icon-pending {
  background: rgba(124,58,237,0.15);
}
[data-theme="dark"] .tool-loading-bar {
  background: #2a2040;
}
[data-theme="dark"] .tool-loading-hint {
  color: #a78bfa;
}
[data-theme="dark"] .tr-toggle {
  color: #6a6880;
}
[data-theme="dark"] .tr-icon-success {
  background: rgba(16,185,129,0.15);
  color: #4ade80;
}
[data-theme="dark"] .tr-icon-error {
  background: rgba(239,68,68,0.15);
  color: #f87171;
}
[data-theme="dark"] .tr-summary {
  color: #6a6880;
}
[data-theme="dark"] .tr-label {
  color: #e4e2f0;
}
/* 2026-09-21：委托卡片「前往 B 会话」按钮（深色主题） */
[data-theme="dark"] .jump-to-session-btn {
  color: #c4b5fd;
  background: rgba(124,58,237,0.15);
  border-color: rgba(124,58,237,0.45);
}
[data-theme="dark"] .jump-to-session-btn:hover {
  background: rgba(124,58,237,0.28);
  border-color: #a78bfa;
}

/* 技能使用卡片 */
[data-theme="dark"] .skill-usage-card.skill-usage-success {
  background: rgba(16,185,129,0.08);
  border-color: rgba(16,185,129,0.25);
}
[data-theme="dark"] .skill-usage-card.skill-usage-fail {
  background: rgba(239,68,68,0.08);
  border-color: rgba(239,68,68,0.25);
}
[data-theme="dark"] .skill-usage-card.skill-usage-success .tool-result-header {
  background: rgba(16,185,129,0.12);
}
[data-theme="dark"] .skill-usage-card.skill-usage-fail .tool-result-header {
  background: rgba(239,68,68,0.12);
}
[data-theme="dark"] .skill-usage-conf {
  background: rgba(99,102,241,0.12);
  color: #a5b4fc;
}

/* 行内代码（通用覆盖，含 p/li/td 内） */
[data-theme="dark"] .code-message code {
  background: #2a2838;
  color: #a78bfa;
}
[data-theme="dark"] .code-message pre code {
  background: transparent;
  color: inherit;
}
[data-theme="dark"] .thinking-text code,
[data-theme="dark"] .thinking-text-block code {
  background: #2a2838;
  color: #a78bfa;
}
[data-theme="dark"] .thinking-text pre code {
  background: transparent;
  color: inherit;
}
</style>
