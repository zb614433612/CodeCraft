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
          <div class="chat-messages-area">
            <!-- 无消息时的占位区（撑满弹性空间，使输入框始终在底部） -->
      <div v-if="currentMessages.length === 0" class="message-list-empty">
        <div v-if="isLoadingMessages" class="loading-messages">
          <a-spin size="small" />
          <span>加载消息中...</span>
        </div>
      </div>

      <!-- 虚拟滚动消息列表 -->
      <DynamicScroller
        v-if="currentMessages.length > 0"
        class="message-list"
        ref="scrollerRef"
        :items="currentMessages"
        :min-item-size="80"
        key-field="id"
        v-slot="{ item: msg, active }"
        @scroll.passive="handleMessageListScroll"
      >
        <DynamicScrollerItem :item="msg" :active="active" :size-dependencies="msg.isStreaming ? [streamingUpdateTick] : [msg.content?.length || 0, msg.thinking?.length || 0, msg.snapshotId || '']">
        <div :class="['message-item', msg.role]">
          <div class="message-avatar">
            <div v-if="msg.role === 'user'" class="avatar user-avatar">
              {{ userStore.userInfo?.username?.charAt(0).toUpperCase() || 'U' }}
            </div>
            <div v-else class="avatar ai-avatar">
              <CodeOutlined />
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
            <div v-if="msg.role === 'assistant' && (msg.thinking || msg.toolResults?.length || msg.matchedSkills?.length)" class="thinking-section">
              <div class="thinking-header" @click="toggleThinkingVisibility(msg.id)">
                <span class="thinking-title">
                  <DownOutlined v-if="!getThinkingVisible(msg.id)" />
                  <UpOutlined v-else />
                  思考过程
                </span>
                <span v-if="msg.matchedSkills?.length" class="skill-match-tags">
                  <span v-for="skill in msg.matchedSkills" :key="skill.name" class="skill-match-tag" :title="skill.name" :class="skill.confidence >= 0.7 ? 'skill-tag-high' : skill.confidence >= 0.4 ? 'skill-tag-mid' : 'skill-tag-low'">
                    🔧 {{ skill.name }}
                    <span class="skill-tag-conf">{{ (skill.confidence * 100).toFixed(0) }}%</span>
                  </span>
                </span>
                <span class="thinking-summary" v-if="msg.toolResults?.length">
                  · {{ msg.toolResults.length }} 次工具调用
                </span>
                <span class="thinking-hint">点击{{ getThinkingVisible(msg.id) ? '收起' : '展开' }}</span>
              </div>
              <div v-if="getThinkingVisible(msg.id)" class="thinking-content" :data-think-scroll="msg.id" @scroll.passive="handleThinkScroll">
                <div class="thinking-text" v-html="formatThinking(msg.thinking, msg.toolResults)" @click="handleToolCardClick"></div>
                <div v-if="msg.isStreaming && thinkShowScrollBtn" class="think-scroll-btn-wrap">
                  <div class="think-scroll-btn" @click.stop="thinkScrollToBottom" title="回到底部">
                    <DownOutlined />
                  </div>
                </div>
              </div>
            </div>
            <div class="message-text code-message" v-html="formatMessage(msg.content)" v-if="msg.content"></div>
          </div>
        </div>
        </DynamicScrollerItem>
      </DynamicScroller>

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

      <!-- 流式加载指示器 -->
      <div v-if="isSending &amp;&amp; streamStatus" class="stream-indicator">
        <span class="stream-pulse-dot"></span>
        <span class="stream-indicator-text">{{ streamStatus }}</span>
        <span class="stream-elapsed" v-if="elapsedTime > 0">{{ formatElapsed(elapsedTime) }}</span>
      </div>

      <!-- 补充需求面板（仅在流式输出中且无待审批问题时显示） -->
      <div v-if="isSending &amp;&amp; !pendingQuestion" class="supplement-panel">
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

      <!-- ask_user 问答面板 -->
      <div v-if="pendingQuestion" class="ask-user-panel">
        <!-- 权限授权面板（askType=permission） -->
        <div v-if="pendingQuestion.askType === 'permission'" class="permission-panel">
          <div class="ask-user-header">
            <span class="ask-user-header-icon">🔒</span>
            需要授权
          </div>
          <div class="ask-user-question" v-html="formatQuestion(pendingQuestion.question)"></div>
          <div class="permission-btn-grid">
            <a-button type="primary" class="permission-btn permission-btn-approve" @click="handlePermissionAction('approve')">同意</a-button>
            <a-button type="primary" ghost class="permission-btn permission-btn-approve-all" @click="handlePermissionAction('approve_all')">本轮对话全部同意</a-button>
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
        <div v-else>
          <div class="ask-user-header">
            <span class="ask-user-header-icon">💬</span>
            需要确认
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
      </div>

      <!-- ===== 全局控制台（终端 + 运行日志） ===== -->
      <div class="console-panel" :class="{ minimized: terminalMinimized }">
        <!-- 拖拽调整大小的手柄 -->
        <div
          class="console-resize-handle"
          @mousedown.prevent="startTerminalResize"
        ></div>
        <!-- Tab 栏 -->
        <div class="console-tab-bar">
          <div class="console-tabs">
            <div
              class="console-tab"
              :class="{ active: consoleActiveTab === 'terminal' }"
              @click="consoleActiveTab = 'terminal'"
            >
              <span class="console-tab-icon">&gt;_</span>
              <span>终端</span>
            </div>
            <div
              v-if="showBuildRunLog"
              class="console-tab"
              :class="{ active: consoleActiveTab === 'log' }"
              @click="consoleActiveTab = 'log'"
            >
              <span class="console-dot" :class="{ running: isServiceRunning }"></span>
              <span>运行日志</span>
              <span
                v-if="!isServiceRunning"
                class="console-tab-close"
                @click.stop="closeLogTab"
              >×</span>
            </div>
          </div>
          <div class="console-tab-actions">
            <span class="console-tab-cwd" :title="displayCwd">{{ displayCwd }}</span>
            <a-button size="small" type="text" class="console-tab-btn" @click="clearActiveConsole">
              <ClearOutlined /> 清屏
            </a-button>
            <DownOutlined
              v-if="!terminalMinimized"
              class="console-tab-toggle"
              @click="toggleTerminalMinimize"
            />
            <UpOutlined
              v-else
              class="console-tab-toggle"
              @click="toggleTerminalMinimize"
            />
          </div>
        </div>
        <!-- 终端内容 -->
        <div v-show="!terminalMinimized && consoleActiveTab === 'terminal'" class="console-panel-body" ref="terminalBodyRef">
          <div
            class="terminal-output"
            ref="terminalOutputRef"
            tabindex="0"
            @keydown="handleTerminalKeydown"
            @click="focusTerminal"
          >
            <div v-for="(line, i) in terminalLines" :key="i" class="terminal-line">{{ line }}</div>
            <div v-if="isTerminalExecuting" class="terminal-line terminal-executing">
              <LoadingOutlined spin /> 执行中...
            </div>
            <div class="terminal-line terminal-prompt-line" v-if="!isTerminalExecuting">
              <span class="prompt-sign">{{ formatTerminalPrompt() }}</span>
              <span class="prompt-input">{{ terminalCurrentInput }}</span>
              <span class="prompt-cursor" :class="{ blink: terminalFocused }">|</span>
            </div>
          </div>
        </div>
        <!-- 运行日志内容 -->
        <div v-show="!terminalMinimized && consoleActiveTab === 'log'" class="console-panel-body console-log-body">
          <div
            v-for="(line, i) in consoleLines"
            :key="i"
            :class="['log-line', {
              'log-error': line.includes('[ERROR]') || line.includes('ERROR'),
              'log-warn': line.includes('[WARNING]') || line.includes('WARN'),
              'log-success': line.includes('BUILD SUCCESS') || line.includes('SUCCESS')
            }]"
          >{{ line }}</div>
          <div v-if="isBuilding" class="log-loading">
            <LoadingOutlined spin /> 编译中...
          </div>
        </div>
      </div>

      <!-- ===== 输入区域（仅聊天标签时显示） ===== -->
      <footer v-if="activeTab?.type === 'chat'" class="chat-input-area">
        <!-- 隐藏的文件选择器 -->
        <input
          ref="fileInputRef"
          type="file"
          style="display: none"
          @change="handleFileSelected"
        />
        <!-- 附件标签区 -->
        <div v-if="attachedFiles.length > 0" class="attachment-tags">
          <div
            v-for="att in attachedFiles"
            :key="att.id"
            :class="['attachment-tag', { 'attachment-error': att.error }]"
          >
            <span class="attachment-icon">{{ getAttachmentIcon(att.type) }}</span>
            <span class="attachment-name" :title="att.fileName">{{ att.fileName }}</span>
            <span class="attachment-size">{{ formatFileSize(att.size) }}</span>
            <span v-if="att.uploading" class="attachment-uploading">
              <LoadingOutlined spin />
            </span>
            <span v-else-if="att.error" class="attachment-error-msg" :title="att.error">上传失败</span>
            <span v-else class="attachment-remove" @click="removeAttachment(att.id)">×</span>
          </div>
        </div>
        <div class="input-wrapper">
          <a-button
            class="attach-btn"
            @click="triggerFileUpload"
            :disabled="isSending"
            title="上传附件"
          >
            <PaperClipOutlined />
          </a-button>
          <a-textarea
            v-model:value="inputMessage"
            placeholder="描述你的编码需求...（Shift+Enter换行，Enter发送）"
            :auto-size="{ minRows: 1, maxRows: 8 }"
            :disabled="isSending || isOptimizing"
            @keydown.enter.exact.prevent="sendMessage"
            @keydown.shift.enter="handleShiftEnter"
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
            v-if="isSending"
            class="stop-btn"
            @click="stopStreaming"
          >
            <CloseOutlined />
          </a-button>
          <a-button
            v-else
            type="primary"
            class="send-btn"
            @click="sendMessage"
            :disabled="!inputMessage.trim() && attachedFiles.length === 0"
          >
            <SendOutlined />
          </a-button>
        </div>
        <div class="input-footer">
          <div class="footer-left">
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
            <div class="mode-selector">
              <span class="mode-emoji">⚡</span>
              <a-select
                :value="agentRuntime.model"
                @change="(v: string) => { updateAgentRuntime('model', v) }"
                size="small"
                class="model-select"
                dropdown-class-name="mode-dropdown"
              >
                <a-select-option value="deepseek-v4-flash">Flash</a-select-option>
                <a-select-option value="deepseek-v4-pro">Pro</a-select-option>
              </a-select>
            </div>
            <div class="mode-selector">
              <span class="mode-emoji">💡</span>
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
            <div class="mode-selector">
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
            <!-- 任务进度触发器 -->
            <div class="mode-selector task-trigger" @click="toggleTaskDropdown" :class="{ active: showTaskDropdown, loading: isSending }">
              <span class="mode-icon task-trigger-icon">
                <LoadingOutlined v-if="isSending" spin />
                <span v-else>📋</span>
              </span>
              <span class="task-trigger-text" v-if="taskItems.length === 0">任务</span>
              <span class="task-trigger-text" v-else>{{ completedTaskCount }}/{{ taskItems.length }}</span>
            </div>
            <!-- 文件改动触发器 -->
            <div class="mode-selector changes-trigger" @click="toggleChangesPanel" :class="{ active: showChangesPanel }" :style="{ display: currentConversationId && !currentConversationId.startsWith('local-') ? '' : 'none' }">
              <span class="mode-icon changes-trigger-icon">
                <span>📄</span>
              </span>
              <span class="changes-trigger-text">改动</span>
              <span v-if="sessionChanges" class="changes-trigger-badge">
                +{{ sessionChanges.totalLinesAdded }}/-{{ sessionChanges.totalLinesDeleted }}
              </span>
            </div>
          </div>
          <div class="footer-right" v-if="currentMessages.length > 0">
            <span class="context-tokens">
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
import { message, Modal } from 'ant-design-vue'
import { useUserStore } from '@/store/user'
import { getConversationList, mapConversationResponseToConversation, getConversationMessages, processMessageGroups, deleteConversation as deleteConversationApi, updateConversationName } from '@/api/conversation'
import { streamChat, checkActiveTask, taskStream, cancelTask, uploadAttachment, supplementRequest, optimizePrompt } from '@/api/chat'
import { setConfig } from '@/api/config'
import type { SkillMatchInfo } from '@/utils/sse-client'
import type { AgentStreamEvent } from '@/utils/sse-client'
import { submitAnswer } from '@/api/askUser'
import { listSnapshots, previewRollback, executeRollback, getSessionChanges, rollbackFile, rollbackAllFiles, getSnapshotFileContent, type SessionChanges } from '@/api/snapshot'
import { useSettingsStore } from '@/store/settings'
import { renderMarkdown } from '@/utils/markdown'
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
  FolderOpenOutlined,
  UndoOutlined,
  PaperClipOutlined,
  PlaySquareOutlined,
  CaretRightOutlined,
  PoweroffOutlined,
  ClearOutlined
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
import { readProjectFile, buildProject, runProject, stopProject, getRunStatus, getRunOutput, execCommand } from '@/api/project'
import { getGitDiff, gitRestore, gitShowFile } from '@/api/git'
import AgentSelector from '@/components/AgentSelector.vue'

const PROMPT_FILE = 'code_agent_prompt.txt'

const userStore = useUserStore()
const settingsStore = useSettingsStore()

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
  }[]
  matchedSkills?: { name: string; confidence: number; triggerWords: string }[]
  timestamp: number
  isStreaming?: boolean
  tokenCount?: number
  turnId?: string
  snapshotId?: string
}

// ===== 附件系统 =====
interface AttachedFile {
  id: string
  attachmentId: string
  fileName: string
  size: number
  type: string      // text/pdf/word/excel/image
  extension: string
  uploading: boolean
  error?: string
}

const attachedFiles = ref<AttachedFile[]>([])
const fileInputRef = ref<HTMLInputElement | null>(null)

// Agent 选择器相关
const agentSelectorRef = ref<InstanceType<typeof AgentSelector> | null>(null)
const currentAgentConfigId = ref<number | null>(null)
const currentAgentWorkDir = ref<string>('')
const skillsRefreshKey = ref(0)

// Agent 运行时配置（computed，确保响应式追踪）
const agentRuntime = computed(() => ({
  model: agentSelectorRef.value?.runtime?.model || 'deepseek-v4-flash',
  thinkingMode: agentSelectorRef.value?.runtime?.thinkingMode || 'non-thinking',
  executionMode: agentSelectorRef.value?.runtime?.executionMode || 'manual',
  workDir: agentSelectorRef.value?.runtime?.workDir || ''
}))

const updateAgentRuntime = (key: string, value: string) => {
  const rt = agentSelectorRef.value?.runtime
  if (rt) {
    (rt as any)[key] = value
    agentSelectorRef.value?.saveRuntime()
  }
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
const onStreamComplete = (convId: string, agentId: number | null) => {
  // 从后台流式列表中移除
  backgroundStreams.value = backgroundStreams.value.filter(s => s.convId !== convId)
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
  isSending.value = false
  stopAbortController.value = null
  streamStatus.value = ''
  pendingQuestion.value = null

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
  clearSavedConversationId()
  skillsRefreshKey.value++

  // 第六步：文件树
  if (agent?.workDir) {
    fileTreeLoadPath.value = agent.workDir
  } else {
    fileTreeLoadPath.value = ''
  }

  // 第七步：恢复流式状态（如果新 Agent 有后台流式在运行）
  if (agentId && backgroundStreams.value.some(s => s.agentConfigId === agentId)) {
    isSending.value = true
    streamStatus.value = '后台输出中...'
  }
}

const triggerFileUpload = () => {
  fileInputRef.value?.click()
}

const handleFileSelected = async (event: Event) => {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  const attId = `att-${Date.now()}`
  const attFile: AttachedFile = {
    id: attId,
    attachmentId: '',
    fileName: file.name,
    size: file.size,
    type: '',
    extension: '',
    uploading: true
  }
  attachedFiles.value.push(attFile)

  try {
    const result = await uploadAttachment(file)
    if (result.success) {
      const found = attachedFiles.value.find(a => a.id === attId)
      if (found) {
        found.attachmentId = result.attachmentId
        found.type = result.type
        found.extension = result.extension
        found.uploading = false
      }
    } else {
      const found = attachedFiles.value.find(a => a.id === attId)
      if (found) {
        found.error = result.error || '上传失败'
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

  // 清空 input 以便重复选择同一个文件
  target.value = ''
}

const removeAttachment = (id: string) => {
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
  consoleActiveTab.value = 'log'
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
  consoleActiveTab.value = 'log'
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

// ===== 终端命令行（Shell 风格） =====
const terminalCurrentInput = ref('')
const terminalLines = ref<string[]>([])
const terminalMinimized = ref(true) // 默认折叠
const isTerminalExecuting = ref(false)
const showBuildRunLog = ref(false)
const consoleActiveTab = ref<'terminal' | 'log'>('terminal')
const terminalOutputRef = ref<HTMLElement | null>(null)
const terminalBodyRef = ref<HTMLElement | null>(null)
const terminalFocused = ref(false)
// 拖拽调整大小
const terminalResizeStartY = ref(0)
const terminalResizeStartHeight = ref(0)
const terminalPanelRef = ref<HTMLElement | null>(null)
// 当前工作目录（前端维护，支持 cd 命令）
const terminalCwd = ref('')

const displayCwd = computed(() => {
  return terminalCwd.value || agentRuntime.value.workDir || '未设置工作目录'
})

const formatTerminalPrompt = () => {
  const cwd = terminalCwd.value || agentRuntime.value.workDir || '.'
  return cwd.replace(/\\/g, '/') + ' $'
}

const closeLogTab = () => {
  showBuildRunLog.value = false
  if (consoleActiveTab.value === 'log') {
    consoleActiveTab.value = 'terminal'
  }
}

const clearActiveConsole = () => {
  if (consoleActiveTab.value === 'terminal') {
    terminalLines.value = []
  } else {
    consoleLines.value = []
  }
}

// 确保 terminalCwd 和 settingsStore 跟随 workDir 实时变化
watch(() => agentRuntime.value.workDir, (val) => {
  if (val) {
    terminalCwd.value = val
    settingsStore.projectRoot = val
    fileTreeLoadPath.value = val
  }
}, { immediate: true })

const focusTerminal = () => {
  terminalFocused.value = true
  nextTick(() => {
    terminalOutputRef.value?.focus()
  })
}

const handleTerminalKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Enter') {
    e.preventDefault()
    executeTerminalCommand()
    return
  }
  if (e.key === 'Backspace') {
    e.preventDefault()
    terminalCurrentInput.value = terminalCurrentInput.value.slice(0, -1)
    return
  }
  // Ctrl+C 清空当前输入
  if (e.key === 'c' && e.ctrlKey) {
    e.preventDefault()
    terminalCurrentInput.value = ''
    terminalLines.value.push(`${formatTerminalPrompt()} ${terminalCurrentInput.value}^C`)
    return
  }
  // Tab 键阻止焦点离开
  if (e.key === 'Tab') {
    e.preventDefault()
    return
  }
  // 可打印字符
  if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
    e.preventDefault()
    terminalCurrentInput.value += e.key
    return
  }
}

/**
 * 处理 cd 命令 — 前端直接修改 cwd，不请求后端
 * 支持: cd ..  cd .  cd ~  cd D:  cd /d D:  cd path\to\dir
 */
const handleCdCommand = (target: string) => {
  const current = terminalCwd.value || agentRuntime.value.workDir || ''

  if (!target || target === '~') {
    // cd 或 cd ~ → 回到 workDir
    terminalCwd.value = agentRuntime.value.workDir || current
  } else if (target === '.') {
    // cd . → 不变
    // no-op
  } else if (target === '..') {
    // cd .. → 上一级
    const parent = current.replace(/\\/g, '/').replace(/\/[^/]*$/, '')
    terminalCwd.value = parent || current
  } else if (target.match(/^[a-zA-Z]:/) || target.match(/^\/\//) || target.match(/^\\\\/)) {
    // 绝对路径（如 D:\dir 或 \\server\share）
    terminalCwd.value = target
  } else {
    // 相对路径：在当前路径后追加
    const separator = current.endsWith('\\') || current.endsWith('/') ? '' : '\\'
    const resolved = current + separator + target
    terminalCwd.value = resolved
  }

  terminalLines.value.push(`${formatTerminalPrompt().slice(0, -2)}$ cd ${target}`)
}

const executeTerminalCommand = async () => {
  const cmd = terminalCurrentInput.value.trim()
  if (!cmd) return

  // 确保 cwd 已初始化
  const workDir = agentRuntime.value.workDir
  if (!terminalCwd.value && workDir) {
    terminalCwd.value = workDir
  }

  const projectRoot = workDir
  if (!projectRoot) {
    message.warning('请先设置工作目录')
    return
  }

  // 拦截 cd 命令（前端处理，不请求后端）
  if (cmd === 'cd') {
    handleCdCommand('')
    terminalCurrentInput.value = ''
    return
  }
  if (cmd.startsWith('cd ')) {
    handleCdCommand(cmd.substring(3).trim())
    terminalCurrentInput.value = ''
    return
  }

  terminalLines.value.push(`${formatTerminalPrompt()} ${cmd}`)
  terminalCurrentInput.value = ''
  isTerminalExecuting.value = true

  try {
    const cwd = terminalCwd.value || projectRoot
    const result = await execCommand(cmd, cwd)
    if (result.output) {
      const outputLines = result.output.split('\n')
      for (const line of outputLines) {
        terminalLines.value.push(line)
      }
    }
    if (result.timedOut) {
      terminalLines.value.push('[命令执行超时]')
    } else if (result.exitCode !== 0) {
      terminalLines.value.push(`[进程退出码: ${result.exitCode}]`)
    }
  } catch (e: any) {
    terminalLines.value.push(`[错误] ${e.message}`)
  } finally {
    isTerminalExecuting.value = false
    scrollTerminalToBottom()
    focusTerminal()
  }
}

const scrollTerminalToBottom = () => {
  nextTick(() => {
    if (terminalOutputRef.value) {
      terminalOutputRef.value.scrollTop = terminalOutputRef.value.scrollHeight
    }
  })
}

const toggleTerminalMinimize = () => {
  terminalMinimized.value = !terminalMinimized.value
  if (!terminalMinimized.value) {
    // 展开后聚焦
    nextTick(() => {
      focusTerminal()
      scrollTerminalToBottom()
    })
  }
}

// 拖拽调整终端高度
const startTerminalResize = (e: MouseEvent) => {
  const panel = terminalBodyRef.value?.parentElement
  if (!panel) return
  terminalPanelRef.value = panel
  terminalResizeStartY.value = e.clientY
  terminalResizeStartHeight.value = panel.offsetHeight

  document.addEventListener('mousemove', onTerminalResize)
  document.addEventListener('mouseup', stopTerminalResize)
}

const onTerminalResize = (e: MouseEvent) => {
  const panel = terminalPanelRef.value
  if (!panel) return
  const delta = terminalResizeStartY.value - e.clientY // 向上拖拽增大
  const newHeight = terminalResizeStartHeight.value + delta
  const minH = 100
  const maxH = window.innerHeight * 0.7
  panel.style.height = Math.max(minH, Math.min(maxH, newHeight)) + 'px'
}

const stopTerminalResize = () => {
  document.removeEventListener('mousemove', onTerminalResize)
  document.removeEventListener('mouseup', stopTerminalResize)
}

// 页面离开时停止轮询
onMounted(() => {
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
  type: 'chat' | 'file' | 'diff'
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

// 双击文件树中的文件，打开文件编辑标签
const onFileDblClick = async (path: string, isDirectory: boolean) => {
  if (isDirectory) return
  // 将相对路径转为绝对路径：文件树返回的 path 是相对于 fileTreeLoadPath 的相对路径
  // 直接传给后端 /api/project/read 时，后端用 user.dir 拼接可能路径不对（Electron桌面端 user.dir 是jar目录）
  const resolvedPath = (!isAbsolutePath(path) && fileTreeLoadPath.value)
    ? fileTreeLoadPath.value.replace(/[/\\]$/, '') + '/' + path
    : path
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

// 关闭标签
const closeTab = (tabId: string) => {
  const idx = tabs.value.findIndex(t => t.id === tabId)
  if (idx === -1 || tabs.value[idx].type === 'chat') return
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
  const isAbsPath = /^[a-zA-Z]:[/\\]/.test(filePath) || filePath.startsWith('/')
  const absFilePath = isAbsPath
    ? filePath
    : projectRoot.replace(/[/\\]$/, '') + '/' + filePath.replace(/\\/g, '/')

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

  // 构建绝对文件路径（readProjectFile 需要绝对路径）
  const isAbsPath = /^[a-zA-Z]:[/\\]/.test(filePath) || filePath.startsWith('/')
  const absFilePath = isAbsPath
    ? filePath
    : projectRoot.replace(/[/\\]$/, '') + '/' + filePath.replace(/\\/g, '/')

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
const isSending = ref(false)
const isOptimizing = ref(false)
const isLoadingConversations = ref(false)
const isLoadingMessages = ref(false)
const scrollerRef = ref<any>(null)
const collapsed = ref(false)
const sidebarTab = ref<'conversation' | 'agents'>('conversation')

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
const pendingQuestion = ref<{ uuid: string; question: string; askType?: string; toolName?: string; filePath?: string; fullDetail?: string } | null>(null)
const pendingQuestionAnswer = ref('')
const pendingShowCustomInput = ref(false)
// 补充需求状态
const showSupplementInput = ref(false)
const supplementMessage = ref('')
const supplementSending = ref(false)
const supplementInputRef = ref<InstanceType<typeof HTMLInputElement> | null>(null)
const streamStatus = ref('')
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
const taskItems = ref<TaskItem[]>([])
const completedTaskCount = computed(() => taskItems.value.filter(t => t.status === 'completed').length)
const showTaskDropdown = ref(false)
// 「执行中」的任务：当 isSending 或 activeTask 时，第一个 pending 任务视为 executing
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
// 清除任务清单（新消息或刷新页面时调用）
const clearTaskList = () => { taskItems.value = [] }
const activeTask = ref<{ taskId: number; status: string; iteration: number; eventCount: number; pendingQuestionUuid?: string; pendingQuestionText?: string } | null>(null)

// Markdown 渲染缓存（避免历史消息重复解析 markdown，性能优化）
// key=content原文, value=渲染后的HTML，最大缓存200条
const markdownCache = new Map<string, string>()
const thinkingCache = new Map<string, string>()

const toggleCollapsed = () => { collapsed.value = !collapsed.value }

// 文件树选择
const onFileSelect = (_path: string, _isDirectory: boolean) => {
  // 可以后续扩展为预览文件内容等
}

// 会话相关
const currentMessages = computed(() => messages.value[currentConversationId.value] || [])

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

const fetchMessages = async (conversationId: string, force = false) => {
  if (!conversationId) return
  if (!force && messages.value[conversationId]) return // 已缓存（首次加载免重复请求）
  isLoadingMessages.value = true
  try {
    const response = await getConversationMessages(parseInt(conversationId))
    if (response.code === 200 && response.data) {
      const groups = processMessageGroups(response.data)
      messages.value[conversationId] = groups as ChatMessage[]
      // 历史消息加载后，匹配回滚快照
      matchSnapshotsForMessages(conversationId)
      // 加载会话改动统计
      loadSessionChanges(conversationId)
    }
  } catch (error) {
    console.error('获取消息失败:', error)
  } finally {
    isLoadingMessages.value = false
  }
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
watch(isLoadingMessages, (loading) => {
  if (!loading) {
    nextTick(() => {
      scrollToBottom()
    })
  }
})

const selectConversation = (id: string) => {
  currentConversationId.value = id
  fetchMessages(id)
  // 检查是否有正在运行的后台任务
  checkAndReconnect(id)
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

// 停止流式响应（只停止当前Agent的流式，其他Agent的后台流式不受影响）
const stopStreaming = () => {
  const currentAgentId = currentAgentConfigId.value
  const currentConvId = currentConversationId.value

  // 收集要取消的后端任务ID
  const cancelIds: number[] = []

  // 停止当前会话的流式（stopAbortController 中的流式）
  if (stopAbortController.value) {
    stopAbortController.value.abort()
    stopAbortController.value = null
  }

  // 停止后台流式中属于当前 Agent 的流式
  backgroundStreams.value = backgroundStreams.value.filter(bg => {
    if (bg.agentConfigId === currentAgentId) {
      bg.abortController.abort()
      if (bg.realSessionId) {
        cancelIds.push(bg.realSessionId)
      }
      return false
    }
    return true
  })

  // 从 currentStreamSessionId 获取（最直接的路径）
  if (currentStreamSessionId.value) {
    cancelIds.push(currentStreamSessionId.value)
    currentStreamSessionId.value = null
  }

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

  // 立即重置当前 UI 状态
  isSending.value = false
  streamStatus.value = ''
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

  // ★ 始终优先使用 SSE 流返回的真实 sessionId（currentStreamSessionId）
  // currentConversationId 在流式过程中可能不是最新的真实 ID：
  //   - new session: currentConversationId = "local-xxx", 真实 ID 在 currentStreamSessionId
  //   - existing session: currentConversationId 可能是旧 ID，后端可能创建了新会话返回新 ID
  const convId = currentStreamSessionId.value
    ? String(currentStreamSessionId.value)
    : currentConversationId.value
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
    pendingQuestion.value = null
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
  permissionLock.value = true
  pendingQuestion.value = null
  // 对于 custom 类型，需要用户输入了内容
  if (action === 'custom') {
    if (!pendingQuestionAnswer.value.trim()) {
      message.warning('请输入您的消息')
      permissionLock.value = false
      pendingQuestion.value = q
      return
    }
  }
  const answer = action === 'custom' ? pendingQuestionAnswer.value.trim() : action
  try {
    await submitAnswer(q.uuid, answer, action)
    pendingQuestionAnswer.value = ''
    pendingShowCustomInput.value = false
    if (action === 'approve_all') {
      message.success('已同意本轮所有操作')
    } else if (action === 'approve') {
      message.success('已同意')
    } else if (action === 'reject') {
      message.success('已拒绝')
    } else if (action === 'custom') {
      message.success('消息已发送')
    }
  } catch (e: any) {
    message.error('操作失败: ' + (e.message || '未知错误'))
    pendingQuestion.value = q
  } finally {
    permissionLock.value = false
  }
  // ★ 清理：关闭文件 tab + 取消分屏
  cleanupPermissionPanel()
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
  const projectRoot = agentRuntime.value.workDir
  if (!projectRoot) return
  const isAbs = /^[a-zA-Z]:[/\\]/.test(filePath) || filePath.startsWith('/')
  const absPath = isAbs ? filePath : projectRoot.replace(/[/\\]$/, '') + '/' + filePath.replace(/\\/g, '/')
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
      // 自动滚动模式：递增节拍器 + 触发 DynamicScroller 更新 + 滚动到底部
      streamingUpdateTick.value++
      if (messages.value[convId]) {
        messages.value[convId] = [...messages.value[convId]]
      }
      scrollToBottom()
    }
    // autoScrollToBottom = false 时：完全冻结，不递增 tick、不替换数组、不滚动
    // tick 不变 → size-dependencies 不变 → DynamicScroller 不重新测量 → 零跳动
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
    streamingUpdateTick.value++
    if (messages.value[convId]) {
      messages.value[convId] = [...messages.value[convId]]
    }
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
const sendMessage = async () => {
  const text = inputMessage.value.trim()
  if (!text || isSending.value) return

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

  // 发送新消息时重置自动滚动状态：重新固定到底部
  autoScrollToBottom.value = true
  showScrollToBottomBtn.value = false
  thinkAutoScroll.value = true
  thinkShowScrollBtn.value = false

  // 关闭补充需求面板
  showSupplementInput.value = false
  supplementMessage.value = ''

  // 构建最终消息：附件不再拼接到消息中，由 LLM 通过 chat_attachment 工具读取
  let finalMessage = text

  // 收集附件ID列表，传给后端
  const attachmentIds: string[] = []
  if (attachedFiles.value.length > 0) {
    for (const att of attachedFiles.value) {
      if (att.attachmentId && !att.error) {
        attachmentIds.push(att.attachmentId)
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
    id: `user-${Date.now()}`,
    role: 'user',
    content: finalMessage,
    timestamp: Date.now(),
    tokenCount: estimateTokenCount(finalMessage),
    turnId
  }

  // 清空附件列表
  attachedFiles.value = []

  if (!messages.value[convId]) {
    messages.value[convId] = []
  }
  messages.value[convId].push(userMsg)
  inputMessage.value = ''
  // ★ 关键：先让 Vue 完成 DOM 更新（清空输入框），再禁用 textarea
  //    否则 Ant Design Vue 的 a-textarea 在 disabled 状态下不响应 v-model 变化，
  //    导致输入框直到流式结束后（isSending=false）才显示为空
  await nextTick()
  isSending.value = true
  // ★ 重置 currentStreamSessionId，确保新会话的 SSE 流能设置新的真实 sessionId
  // 否则上一次流式残留的值会导致补充需求发到旧的会话
  currentStreamSessionId.value = null
  startElapsedTimer()
  streamStatus.value = '正在连接...'

  // 添加占位的助手消息
  const assistantMsg: ChatMessage = {
    id: `assistant-${Date.now()}`,
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

  try {
    const sessionId = convId.startsWith('local-') ? undefined : parseInt(convId)

    // 构建thinking内容
    let thinkingContent = ''

    for await (const event of streamChat(finalMessage, sessionId, {
      promptFileName: PROMPT_FILE,
      executionMode: agentRuntime.value.executionMode,
      projectRoot: agentRuntime.value.workDir || settingsStore.projectRoot || undefined,
      model: agentRuntime.value.model,
      thinkingMode: agentRuntime.value.thinkingMode,
      turnId,
      agentConfigId: currentAgentConfigId.value,
      contextMode: settingsStore.contextMode,
      attachmentIds: attachmentIds.length > 0 ? attachmentIds : undefined
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
        streamStatus.value = '思考分析中...'
        // 检测工具调用结果标记，分离存储
        const markerIdx = event.data.indexOf('----工具调用:----')
        if (markerIdx !== -1) {
          streamStatus.value = '执行工具中...'
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
            // 解析 task_manager 工具的任务清单
            console.log('[TaskList] toolContent:', toolContent.substring(0, 200))
            parseTaskManagerResult(toolContent)
          }
        } else {
          thinkingContent += event.data
        }
        assistantMsg.thinking = thinkingContent
        // 节流刷新（携带思考消息ID，更新 DOM 后自动滚动到底部）
        scheduleMessageUpdate(convId, assistantMsg.id)
      } else if (event.type === 'tool_call_start') {
        // 工具调用开始事件：创建 pending 条目，显示加载动画
        streamStatus.value = '执行工具中...'
        const tcData = event.data as { tools: string[]; summaries: string[] }
        const tools = tcData.tools || []
        const summaries = tcData.summaries || []
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
      } else if (event.type === 'content') {
        streamStatus.value = '正在生成回答...'
        // 实时更新消息内容
        if (event.data) {
          assistantMsg.content += event.data
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
          pendingQuestion.value = { uuid: event.data.uuid, question: event.data.question, askType: event.data.askType || 'clarification', toolName: event.data.toolName, filePath: event.data.filePath, fullDetail: event.data.fullDetail }
          pendingQuestionAnswer.value = ''
        } catch (e) {
          console.warn('解析 ask_user 事件失败:', e)
        }
        } else if (event.type === 'skill_match') {
          const skills = event.data as SkillMatchInfo[]
          if (skills.length > 0) {
            assistantMsg.matchedSkills = skills.map(s => ({
              name: s.name,
              confidence: s.confidence,
              triggerWords: s.triggerWords
            }))
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
    streamStatus.value = ''
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
  } catch (error: any) {
    if (error.message === '__USER_ABORT__') {
      console.log('用户中断流式响应')
      assistantMsg.isStreaming = false
      streamStatus.value = ''
      flushMessageUpdate(convId, assistantMsg.id)
      // 如果已经获取到真实会话ID，提前迁移 convId，避免 finally 中迁移触发 checkAndReconnect 重连
      if (realSessionId) {
        messages.value[realSessionId] = messages.value[convId]
        delete messages.value[convId]
        currentConversationId.value = realSessionId
        convId = realSessionId
      }
      return
    }
    console.error('发送消息失败:', error)
    assistantMsg.content = error.message || '发送失败，请重试'
    assistantMsg.isStreaming = false
    streamStatus.value = ''
    flushMessageUpdate(convId, assistantMsg.id)
    message.error('发送失败: ' + (error.message || '未知错误'))
  } finally {
    isSending.value = false
    // ★ 兜底清空输入框：确保消息发送后输入条一定被清空
    //    （第 2257 行已有 inputMessage.value = ''，但若发送过程中出现异常提前 return，
    //      或 Vue 响应式更新时序问题导致视图未更新，这里再次清空确保万无一失）
    if (inputMessage.value !== '') {
      inputMessage.value = ''
    }
    stopElapsedTimer()
    streamStatus.value = ''
    stopAbortController.value = null
    // 清除 activeStreamInfo（流式已结束，可能是正常结束或被 stopStreaming 提前清掉）
    if (activeStreamInfo.value?.convId === convId) {
      activeStreamInfo.value = null
    }
    // 通知后台流式管理：当前流式已结束
    onStreamComplete(convId, currentAgentConfigId.value)
    scrollToBottom()
    // 流式结束后统一迁移 convId（避免流式过程中数组引用变动导致内容重复）
    if (convId.startsWith('local-') && realSessionId) {
      messages.value[realSessionId] = messages.value[convId]
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
  }
}

const handleShiftEnter = () => {
  // Shift+Enter 会由 textarea 自动处理为换行
}

// ===== 后台任务重连 =====

const reconnectToTaskStream = async (convId: number) => {
  streamStatus.value = '任务恢复中...'
  isSending.value = true
  startElapsedTimer()
  const stringConvId = String(convId)

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
      id: `reconnect-${Date.now()}`,
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

  const abortCtrl = new AbortController()
  stopAbortController.value = abortCtrl

  try {
    for await (const event of taskStream(convId, abortCtrl)) {
      if (event.type === 'thinking') {
        const data = typeof event.data === 'string' ? event.data : ''
        const markerIdx = data.indexOf('----工具调用:----')
        if (markerIdx !== -1) {
          streamStatus.value = '执行工具中...'
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
            parseTaskManagerResult(toolContent)
          }
        } else {
          // 普通 thinking 内容：Replay Sink 重放的历史内容已在 thinkingContent 中，跳过重复追加
          if (!thinkingContent.endsWith(data)) {
            thinkingContent += data
          }
        }
        targetMsg.thinking = thinkingContent
        // 携带思考消息ID，更新 DOM 后自动滚动到底部
        scheduleMessageUpdate(stringConvId, targetMsg.id)
      } else if (event.type === 'content') {
        streamStatus.value = '正在生成回答...'
        targetMsg.content = (targetMsg.content || '') + event.data
        scheduleMessageUpdate(stringConvId)
      } else if (event.type === 'ask_user') {
        streamStatus.value = '等待用户授权...'
        pendingQuestion.value = { uuid: event.data.uuid, question: event.data.question, askType: event.data.askType || 'clarification', toolName: event.data.toolName, filePath: event.data.filePath, fullDetail: event.data.fullDetail }
        pendingQuestionAnswer.value = ''
      } else if (event.type === 'resume') {
        streamStatus.value = '继续执行中...'
      } else if (event.type === 'complete') {
        break
      }
    }

    // 流结束，取消 streaming 状态
    if (targetMsg) {
      targetMsg.isStreaming = false
    }
    streamStatus.value = ''
    flushMessageUpdate(stringConvId, targetMsg.id)
  } catch (error: any) {
    if (error?.name === 'AbortError') {
      console.log('重连任务流被用户终止')
    } else {
      console.warn('重连任务流失败:', error)
    }
  } finally {
    streamStatus.value = ''
    isSending.value = false
    stopElapsedTimer()
    activeTask.value = null
    stopAbortController.value = null
    // 任务可能已完成或有新消息，从 DB 强制刷新最新消息列表（绕过缓存）
    await fetchMessages(stringConvId, true)
  }
}

const checkAndReconnect = async (convId: string) => {
  if (convId.startsWith('local-')) return
  // 如果该会话已经在后台流式运行中，跳过重连（避免双流式冲突）
  if (backgroundStreams.value.some(s => s.convId === convId)) return
  const task = await checkActiveTask(parseInt(convId))
  if (task.active) {
    activeTask.value = task as any
    // 如果有待审批问题，立即展示审批对话框（页面刷新后重连）
    if (task.pendingQuestionUuid) {
      pendingQuestion.value = { uuid: task.pendingQuestionUuid, question: task.pendingQuestionText || '请确认是否执行以上操作', askType: 'permission' }
      pendingQuestionAnswer.value = ''
      message.info('检测到有待审批的操作，请确认')
    }
    if (task.status === 'running') {
      // 任务仍在运行，连接事件流追踪进度
      reconnectToTaskStream(parseInt(convId))
    } else {
      // 任务已完成/失败/取消，清除 activeTask（数据已在 DB 中）
      activeTask.value = null
    }
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
          const map: Record<string, string> = { add: '暂存', commit: '提交', push: '推送' }
          return map[act] || act
        }
        return 'Git'
      }
      if (/📌|暂存/.test(firstLines)) return '暂存'
      if (/📦|提交|commit/.test(firstLines)) return '提交'
      if (/⬆️|推送|push/.test(firstLines)) return '推送'
      const gsMatch = content.match(/action=(\w+)/)
      if (gsMatch) {
        const act = gsMatch[1]
        const map: Record<string, string> = { add: '暂存', commit: '提交', push: '推送' }
        return map[act] || act
      }
      return 'Git'
    }

    // ======== git_query ========
    case 'git_query': {
      if (isError) return '查询'
      if (/状态|Status|status/.test(firstLines)) return '状态'
      if (/差异|diff|Diff/.test(firstLines)) return '差异'
      if (/历史|log|提交|commit/.test(firstLines)) return '日志'
      return '查询'
    }

    // ======== git_branch ========
    case 'git_branch': {
      if (isError) {
        if (/action=(\w+)/.test(content)) {
          const act = content.match(/action=(\w+)/)![1]
          const map: Record<string, string> = { create: '创建分支', switch: '切换分支', delete: '删除分支', list: '列出分支' }
          return map[act] || act
        }
        return '分支管理'
      }
      if (/创建分支/.test(firstLines)) return '创建分支'
      if (/切换分支/.test(firstLines)) return '切换分支'
      if (/删除分支/.test(firstLines)) return '删除分支'
      const gbMatch = content.match(/action=(\w+)/)
      if (gbMatch) {
        const act = gbMatch[1]
        const map: Record<string, string> = { create: '创建分支', switch: '切换分支', delete: '删除分支', list: '列出分支' }
        return map[act] || act
      }
      return '分支管理'
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

/**
 * 渲染技能使用卡片（skill action=report 工具结果专用）
 * 解析格式：
 *   技能「名称」(ID:N) 执行结果：成功/失败
 *   当前置信度：XX%（共使用 N 次，成功 N / 失败 N）
 */
function renderSkillUsageCard(content: string): string | null {
  const nameMatch = content.match(/技能「(.+?)」/)
  if (!nameMatch) return null
  const skillName = nameMatch[1]
  const success = /执行结果[：:]\s*(成功)/.test(content)
  const confMatch = content.match(/当前置信度[：:]\s*(\d+%?)%/)
  const confidence = confMatch ? confMatch[1].replace('%', '') + '%' : ''
  const statusText = success ? '技能使用成功' : '技能使用失败'
  const statusIcon = success ? '&#10003;' : '&#10007;'
  const cardClass = success ? 'skill-usage-success' : 'skill-usage-fail'
  const firstLine = content.split('\n')[0].substring(0, 80)
  const escaped = escapeHtml(content)

  return `<div class="skill-usage-card tool-result-collapsed ${cardClass}">
    <div class="tool-result-header">
      <span class="tr-toggle">▶</span>
      <span class="skill-usage-badge ${success ? 'skill-usage-badge-ok' : 'skill-usage-badge-fail'}">${statusIcon}</span>
      <span class="tr-label">${escapeHtml(statusText)}：${escapeHtml(skillName)}</span>
      ${confidence ? `<span class="skill-usage-conf">${escapeHtml(confidence)}</span>` : ''}
      <span class="tr-summary">${escapeHtml(firstLine)}</span>
    </div>
    <div class="tool-result-body"><pre>${escaped}</pre></div>
  </div>`
}

const formatThinking = (thinking: string | undefined, toolResults?: { at: number; content: string; toolName?: string; pending?: boolean; operationSummary?: string }[]) => {
  if (!thinking && (!toolResults || toolResults.length === 0)) return ''
  thinking = thinking || ''
  const cacheKey = thinking + '|' + JSON.stringify(toolResults?.map(t => ({ at: t.at, len: t.content.length, pending: t.pending, tn: t.toolName })))
  const cached = thinkingCache.get(cacheKey)
  if (cached !== undefined) return cached

  const renderResult = (() => {
    if (toolResults && toolResults.length > 0) {
      const sorted = [...toolResults].sort((a, b) => a.at - b.at)
      const parts: string[] = ['<div class="thinking-timeline">']
      let lastPos = 0
      for (const tr of sorted) {
        // 思考过程片段（到工具调用之间的推理）
        if (tr.at > lastPos) {
          const textSegment = thinking.slice(lastPos, tr.at)
          if (textSegment.trim()) {
            parts.push(`<div class="thinking-text-block">${renderMarkdown(textSegment)}</div>`)
          }
        }
        // ★ pending 条目：工具正在执行中，显示加载动画
        if (tr.pending) {
          const toolLabel = tr.toolName || '工具'
          const summaryText = tr.operationSummary || `正在调用 ${toolLabel}...`
          // 提取操作摘要的第一行（去掉 markdown 标记后截取前80字符）
          const cleanSummary = summaryText.replace(/^>\s*/, '').replace(/[*`]/g, '').substring(0, 80)
          parts.push(`<div class="tool-result-card tool-result-pending">
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
          </div>`)
          lastPos = tr.at
          continue
        }
        // 工具调用结果卡片（含工具名称解析）
        const { toolName, cleanContent } = parseToolNameLine(tr.content)
        const displayContent = cleanContent || tr.content

        // 技能使用卡片：skill action=report 特殊渲染
        if (toolName === 'skill') {
          const skillUsageHtml = renderSkillUsageCard(displayContent)
          if (skillUsageHtml) {
            parts.push(skillUsageHtml)
            lastPos = tr.at
            continue
          }
        }

        const escaped = escapeHtml(displayContent)
        // ⚠️ 只检查第一行！工具报错永远在第一行，避免读取文件内容中的「错误关键词」导致误判
        const firstLineOfResult = displayContent.split('\n')[0]
        const isError = /^(错误[：:]|【[^】]+】|❌\s*错误)/.test(firstLineOfResult) || /\bCannot\b/.test(firstLineOfResult)
        const firstLine = displayContent.split('\n')[0].substring(0, 80)
        // 提取操作类型并构建更有信息的标签
        const actionLabel = toolName ? extractActionFromToolContent(displayContent, toolName) : ''
        const labelText = isError
          ? ('执行出错' + (toolName ? ' ' + toolName : ''))
          : (toolName ? (toolName + ' · ' + actionLabel) : '工具执行')
        parts.push(`<div class="tool-result-card tool-result-collapsed ${isError ? 'tool-result-error' : ''}">
          <div class="tool-result-header">
            <span class="tr-toggle">▶</span>
            <span class="tr-icon ${isError ? 'tr-icon-error' : 'tr-icon-success'}">${isError ? '&#10007;' : '&#10003;'}</span>
            <span class="tr-label">${labelText}</span>
            <span class="tr-summary">${escapeHtml(firstLine)}</span>
          </div>
          <div class="tool-result-body"><pre>${escaped}</pre></div>
        </div>`)
        lastPos = tr.at
      }
      // 剩余思考过程
      if (thinking && lastPos < thinking.length) {
        const remaining = thinking.slice(lastPos)
        if (remaining.trim()) {
          parts.push(`<div class="thinking-text-block">${renderMarkdown(remaining)}</div>`)
        }
      }
      parts.push('</div>')
      return parts.join('\n')
    }
    // 无工具调用，纯渲染思考文本
    return thinking.trim() ? renderMarkdown(thinking) : ''
  })()

  if (thinkingCache.size >= 200) {
    const firstKey = thinkingCache.keys().next().value
    if (firstKey) thinkingCache.delete(firstKey)
  }
  thinkingCache.set(cacheKey, renderResult)
  return renderResult
}

const formatMessage = (content: string) => {
  if (!content) return ''
  // 缓存命中直接返回，避免重复解析 markdown（核心性能优化）
  const cached = markdownCache.get(content)
  if (cached !== undefined) return cached
  const html = renderMarkdown(content)
  // LRU 淘汰：缓存超过上限时删除最早条目
  if (markdownCache.size >= 200) {
    const firstKey = markdownCache.keys().next().value
    if (firstKey) markdownCache.delete(firstKey)
  }
  markdownCache.set(content, html)
  return html
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

// 工具结果卡片点击折叠/展开
const handleToolCardClick = (e: MouseEvent) => {
  const header = (e.target as HTMLElement).closest('.tool-result-header')
  if (header) {
    header.parentElement?.classList.toggle('tool-result-collapsed')
  }
}

// ===== 自动滚动到底部控制 =====
// 默认开启自动滚动。流式输出时若用户手动向上滚动，则关闭自动滚动；点击"回到底部"按钮或发送新消息时重置为开启。
const autoScrollToBottom = ref(true)
const showScrollToBottomBtn = ref(false)
// DynamicScroller 大小更新节拍器：每个 scheduleMessageUpdate 周期递增
// 用于 size-dependencies，让 DynamicScroller 感知 streaming 消息的大小变化
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

// 强制刷新 DynamicScroller：递增 tick + 替换数组引用，使最新内容可见并重新计算布局
const forceRefreshScroller = () => {
  streamingUpdateTick.value++
  const convId = currentConversationId.value
  if (convId && messages.value[convId]) {
    messages.value[convId] = [...messages.value[convId]]
  }
}

// 监听 DynamicScroller 的 scroll 事件，检测用户是否手动滚动离开底部
let lastScrollHandleTime = 0
const handleMessageListScroll = () => {
  // 80ms 防抖：持续滚动时减少状态切换频率，降低跳动
  const now = Date.now()
  if (now - lastScrollHandleTime < 80) return
  lastScrollHandleTime = now
  
  const el = scrollerRef.value?.$el as HTMLElement | undefined
  if (!el) return
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
  autoScrollToBottom.value = true
  showScrollToBottomBtn.value = false
  // 先刷新让最新内容渲染出来，再滚动到底部
  forceRefreshScroller()
  nextTick(() => {
    const doScroll = () => {
      if (scrollerRef.value) {
        const el = scrollerRef.value.$el as HTMLElement
        if (el) el.scrollTop = el.scrollHeight
      }
    }
    requestAnimationFrame(() => {
      doScroll()
      requestAnimationFrame(() => doScroll())
    })
  })
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
  scrollToBottom()
})

onMounted(() => {
  scrollToBottom()
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
  })
})

onUnmounted(() => {
  // 清理拖拽事件
  document.removeEventListener('mousemove', onGlobalDragMove)
  document.removeEventListener('mouseup', onGlobalDragUp)
})

// 当首次加载完会话列表且选中了会话后，检查活跃任务
// ===== 任务清单解析 =====
const parseTaskManagerResult = (content: string) => {
  // 优先解析结构化 JSON（---TASK_JSON--- 分隔）
  const jsonMarkerIdx = content.indexOf('---TASK_JSON---')
  if (jsonMarkerIdx !== -1) {
    const jsonStr = content.substring(jsonMarkerIdx + '---TASK_JSON---'.length).trim()
    try {
      const parsed = JSON.parse(jsonStr)
      if (Array.isArray(parsed) && parsed.length > 0) {
        const existing = taskItems.value
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
        taskItems.value = [...existing]
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
    const existing = taskItems.value
    const found = existing.find(t => t.id === tid)
    if (found) { found.status = 'completed'; taskItems.value = [...existing] }
    return
  }
  // 处理 reopen 操作: "🔄 任务 T1 已重新打开"
  const reopenMatch = content.match(/🔄\s*任务\s+(T\d+)\s*已重新打开/)
  if (reopenMatch) {
    const tid = reopenMatch[1]
    const existing = taskItems.value
    const found = existing.find(t => t.id === tid)
    if (found) { found.status = 'pending'; taskItems.value = [...existing] }
    return
  }
  // 处理批量操作结果（兼容）
  const batchMatch = content.match(/✅\s*批量完成任务：(.+)/)
  if (batchMatch) {
    const ids = batchMatch[1].split(',').map(s => s.trim())
    const existing = taskItems.value
    for (const tid of ids) {
      const found = existing.find(t => t.id === tid)
      if (found) found.status = 'completed'
    }
    taskItems.value = [...existing]
    return
  }
  const batchReopenMatch = content.match(/🔄\s*批量重开任务：(.+)/)
  if (batchReopenMatch) {
    const ids = batchReopenMatch[1].split(',').map(s => s.trim())
    const existing = taskItems.value
    for (const tid of ids) {
      const found = existing.find(t => t.id === tid)
      if (found) found.status = 'pending'
    }
    taskItems.value = [...existing]
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
    const existing = taskItems.value
    if (existing.length > 0) {
      for (const newTask of parsedTasks) {
        const idx = existing.findIndex(t => t.id === newTask.id)
        if (idx !== -1) existing[idx].status = newTask.status
        else existing.push(newTask)
      }
      taskItems.value = [...existing]
    } else {
      taskItems.value = parsedTasks
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

/* ===== 消息项 ===== */
.message-item {
  display: flex;
  padding-bottom: 24px;
  gap: 14px;
  animation: msgSlideIn 0.35s cubic-bezier(0.16, 1, 0.3, 1);
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
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 14px;
  box-shadow: 0 3px 10px rgba(0,0,0,0.08);
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
  transition: all 0.2s ease;
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
.skill-match-tags {
  display: flex;
  gap: 4px;
  margin-right: 8px;
  overflow: hidden;
  flex: 1;
  min-width: 0;
}
.skill-match-tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 9px;
  border-radius: 12px;
  font-size: 11px;
  font-weight: 600;
  max-width: 160px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.skill-tag-high { background: #f0fdf4; color: #16a34a; border: 1px solid #86efac; }
.skill-tag-mid  { background: #fffbeb; color: #d97706; border: 1px solid #fcd34d; }
.skill-tag-low  { background: #fef2f2; color: #dc2626; border: 1px solid #fca5a5; }
.skill-tag-conf { font-size: 10px; opacity: 0.75; }
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
}

/* ===== 思考时间线 ===== */
.thinking-timeline {
  display: flex;
  flex-direction: column;
  gap: 10px;
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
.attachment-error-msg { color: var(--red); font-size: 11px; cursor: help; }
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
  flex-wrap: wrap;
  gap: 8px;
}

.footer-left {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.footer-right {
  display: flex;
  align-items: center;
}
.context-tokens {
  color: var(--text-3);
  font-size: 11px;
  font-weight: 500;
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
  width: 90px;
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
}
.footer-right {
  display: flex;
  align-items: center;
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
  background: var(--bg-chat);
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
}
.ask-user-header {
  font-weight: 700;
  font-size: 15px;
  color: var(--text-1);
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.ask-user-header-icon { font-size: 18px; line-height: 1; }
.ask-user-question {
  font-size: 14px;
  color: var(--text-2);
  margin-bottom: 16px;
  line-height: 1.65;
  background: var(--bg-root);
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  border-left: 3px solid var(--accent);
}
/* markdown 渲染后的块级元素间距优化 */
.ask-user-question p { margin: 0 0 8px; }
.ask-user-question p:last-child { margin-bottom: 0; }
.ask-user-question ul, .ask-user-question ol { margin: 0 0 8px; padding-left: 20px; }
.ask-user-question ul:last-child, .ask-user-question ol:last-child { margin-bottom: 0; }
.ask-user-question code { background: var(--bg-hover); padding: 1px 6px; border-radius: 3px; font-size: 13px; }
.ask-user-input-row {
  display: flex;
  gap: 8px;
}
.ask-user-input-row .a-input { flex: 1; }

/* ===== 权限面板 ===== */
.permission-panel {
  display: flex;
  flex-direction: column;
}

/* ===== 权限授权按钮 ===== */
.permission-btn-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 0;
  justify-content: flex-start;
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

/* ===== 控制台面板（终端 + 运行日志） ===== */
.console-panel {
  flex-shrink: 0;
  background: #1a1925;
  border-top: 1px solid #2a2838;
  display: flex;
  flex-direction: column;
  min-height: 32px;
  position: relative;
}
.console-panel.minimized {
  flex: 0 0 auto !important;
  height: auto !important;
}
.console-resize-handle {
  position: absolute;
  top: -4px;
  left: 0;
  right: 0;
  height: 8px;
  cursor: ns-resize;
  z-index: 10;
}
.console-resize-handle:hover { background: rgba(139, 92, 246, 0.25); }

/* 控制台 Tab 栏 */
.console-tab-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #1e1d2c;
  border-bottom: 1px solid #2a2838;
  flex-shrink: 0;
  min-height: 34px;
  user-select: none;
}
.console-tabs { display: flex; align-items: center; }
.console-tab {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 14px;
  height: 34px;
  font-size: 12px;
  color: #7a7898;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
}
.console-tab:hover {
  color: #c4b5fd;
  background: rgba(139, 92, 246, 0.06);
}
.console-tab.active {
  color: #e4e2f0;
  border-bottom-color: var(--accent);
  background: #1a1925;
}
.console-tab-icon {
  font-size: 13px;
  font-weight: 700;
  color: var(--accent);
  font-family: 'SF Mono', 'Monaco', 'Fira Code', monospace;
}
.console-tab-close {
  font-size: 14px;
  line-height: 1;
  color: #6a6880;
  padding: 2px 4px;
  border-radius: 3px;
  margin-left: 2px;
  transition: all 0.15s;
}
.console-tab-close:hover { background: var(--red); color: #fff; }
.console-tab-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 12px;
}
.console-tab-cwd {
  font-size: 11px;
  color: #6a6880;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.console-tab-btn {
  font-size: 11px !important;
  color: #7a7898 !important;
  height: 24px !important;
  padding: 0 8px !important;
}
.console-tab-btn:hover { color: #c4b5fd !important; }
.console-tab-toggle {
  font-size: 11px;
  color: #6a6880;
  cursor: pointer;
  padding: 2px 4px;
  border-radius: 3px;
  transition: all 0.15s;
}
.console-tab-toggle:hover { background: #2a2838; color: #c4b5fd; }

/* 控制台内容 */
.console-panel-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.console-log-body {
  overflow-y: auto;
  padding: 10px 14px;
  font-family: 'SF Mono', 'Monaco', 'Fira Code', monospace;
  font-size: 12px;
  line-height: 1.5;
  color: #d4d4d4;
  white-space: pre-wrap;
  word-break: break-all;
  text-align: left;
}
.terminal-output {
  flex: 1;
  overflow-y: auto;
  padding: 8px 14px;
  font-family: 'SF Mono', 'Monaco', 'Fira Code', monospace;
  font-size: 12px;
  line-height: 1.5;
  color: #d4d4d4;
  white-space: pre-wrap;
  word-break: break-all;
  outline: none;
  cursor: text;
  text-align: left;
}
.terminal-line {
  min-height: 18px;
  padding: 0;
  white-space: pre-wrap;
  word-break: break-all;
  text-align: left;
}
.terminal-executing { color: #7a7898; font-style: italic; }
.terminal-prompt-line {
  display: flex;
  align-items: center;
  gap: 0;
  min-height: 18px;
}
.prompt-sign {
  color: var(--accent);
  white-space: pre;
  flex-shrink: 0;
}
.prompt-input { color: #d4d4d4; white-space: pre; }
.prompt-cursor {
  color: #d4d4d4;
  font-weight: 100;
  animation: terminalBlink 1s step-end infinite;
}
.prompt-cursor.blink { visibility: visible; }
@keyframes terminalBlink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

/* ===== 运行日志内容 ===== */
.log-line { min-height: 18px; padding: 0; text-align: left; }
.log-line.log-error { color: #f48771; }
.log-line.log-warn { color: #dcdcaa; }
.log-line.log-success { color: var(--green); }
.log-loading { color: #7a7898; padding: 4px 0; font-size: 12px; }

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

[data-theme="dark"] .console-panel { background: #0e0d16; border-color: #1e1d2c; }
[data-theme="dark"] .console-tab-bar { background: #11101a; border-color: #1e1d2c; }
[data-theme="dark"] .console-tab.active { background: #0e0d16; }
[data-theme="dark"] .console-tab:hover { background: rgba(139, 92, 246, 0.08); }
[data-theme="dark"] .console-resize-handle:hover { background: rgba(139, 92, 246, 0.2); }

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

/* 技能标签 */
[data-theme="dark"] .skill-tag-high {
  background: rgba(16,185,129,0.12);
  color: #4ade80;
  border-color: rgba(16,185,129,0.25);
}
[data-theme="dark"] .skill-tag-mid {
  background: rgba(245,158,11,0.12);
  color: #fbbf24;
  border-color: rgba(245,158,11,0.25);
}
[data-theme="dark"] .skill-tag-low {
  background: rgba(239,68,68,0.12);
  color: #f87171;
  border-color: rgba(239,68,68,0.25);
}

/* ===== 暗色：发送面板 ===== */
[data-theme="dark"] .mode-selector {
  background: #1a1925;
  border-color: #2a2838;
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
[data-theme="dark"] .terminal-output::-webkit-scrollbar-thumb,
[data-theme="dark"] .tool-result-body::-webkit-scrollbar-thumb {
  background: #3a3850;
  border-radius: 2px;
}
[data-theme="dark"] .diff-tab-body::-webkit-scrollbar,
[data-theme="dark"] .task-dropdown-body::-webkit-scrollbar,
[data-theme="dark"] .changes-panel-body::-webkit-scrollbar,
[data-theme="dark"] .console-log-body::-webkit-scrollbar,
[data-theme="dark"] .terminal-output::-webkit-scrollbar,
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
}
.tool-result-error .tool-result-body pre { color: #991b1b; }

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
