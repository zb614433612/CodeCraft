<template>
  <div class="chat-container">
    <!-- 侧边栏: 会话列表 / 文件树 -->
    <aside class="sidebar" :class="{ collapsed }">
      <div class="sidebar-header">
        <div class="sidebar-tabs">
          <button
            :class="['tab-btn', { active: sidebarTab === 'conversation' }]"
            @click="sidebarTab = 'conversation'"
          ><span class="tab-full">会话</span><span class="tab-short">会话</span></button>
          <button
            :class="['tab-btn', { active: sidebarTab === 'files' }]"
            @click="sidebarTab = 'files'"
          ><span class="tab-full">文件</span><span class="tab-short">文件</span></button>
          <button
            :class="['tab-btn', { active: sidebarTab === 'git' }]"
            @click="sidebarTab = 'git'"
          ><span class="tab-full">Git</span><span class="tab-short">Git</span></button>
          <button
            :class="['tab-btn', { active: sidebarTab === 'skills' }]"
            @click="sidebarTab = 'skills'"
          ><span class="tab-full">技能</span><span class="tab-short">技能</span></button>
          <button
            v-if="agentInfos.length > 0"
            :class="['tab-btn', { active: sidebarTab === 'agents' }]"
            @click="sidebarTab = 'agents'"
          ><span class="tab-full">子Agent</span><span class="tab-short">Agent</span></button>
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
        <AgentSelector @change="onAgentChange" ref="agentSelectorRef" />
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

      <!-- Git 面板 -->
      <div v-show="sidebarTab === 'git'" class="git-panel">
        <GitSidebar :project-root="settingsStore.projectRoot || ''" @file-dblclick="onGitFileDblClick" />
      </div>

      <!-- 技能面板 -->
      <div v-show="sidebarTab === 'skills'" class="skill-panel">
        <SkillList :agent-config-id="currentAgentConfigId" />
      </div>

      <!-- 子Agent面板 -->
      <div v-show="sidebarTab === 'agents'" class="agent-panel-wrapper">
        <AgentPanel :agents="agentInfos" />
      </div>

      <!-- 文件树 -->
      <div v-show="sidebarTab === 'files'" class="filetree-panel">
        <div class="project-root-bar">
          <span class="project-root-label">工作目录</span>
          <div class="project-root-row">
            <a-input
              :value="agentRuntime.workDir"
              placeholder="点击右侧按钮选择目录"
              size="small"
              class="project-root-input"
              readonly
            />
            <a-button size="small" type="primary" @click="selectProjectRoot" class="project-root-btn">
              <FolderOpenOutlined />
            </a-button>
          </div>
        </div>
        <FileTree :root-path="fileTreeLoadPath" @select="onFileSelect" @dblclick="onFileDblClick" />
        <DirectoryBrowser :visible="showDirBrowser" @select="onDirSelected" @close="showDirBrowser = false" />
      </div>
    </aside>

    <!-- 主聊天区域（标签页系统） -->
    <main class="chat-main">
      <!-- 标签栏 -->
      <div class="tab-bar" v-if="tabs.length > 0">
        <div class="tab-items">
          <div
            v-for="tab in tabs"
            :key="tab.id"
            :class="['tab-item', { active: activeTabId === tab.id, dirty: tab.isDirty }]"
            @click="activeTabId = tab.id"
            @mousedown.middle.prevent="closeTab(tab.id)"
          >
            <span class="tab-title">{{ tab.title }}</span>
            <span v-if="tab.isDirty" class="tab-dirty-dot">●</span>
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
      >
        <DynamicScrollerItem :item="msg" :active="active" :size-dependencies="msg.isStreaming ? [] : [msg.content?.length || 0, msg.thinking?.length || 0, msg.snapshotId || '']">
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
                  <span v-for="skill in msg.matchedSkills" :key="skill.name" class="skill-match-tag" :class="skill.confidence >= 0.7 ? 'skill-tag-high' : skill.confidence >= 0.4 ? 'skill-tag-mid' : 'skill-tag-low'">
                    🔧 {{ skill.name }}
                    <span class="skill-tag-conf">{{ (skill.confidence * 100).toFixed(0) }}%</span>
                  </span>
                </span>
                <span class="thinking-summary" v-if="msg.toolResults?.length">
                  · {{ msg.toolResults.length }} 次工具调用
                </span>
                <span class="thinking-hint">点击{{ getThinkingVisible(msg.id) ? '收起' : '展开' }}</span>
              </div>
              <div v-if="getThinkingVisible(msg.id)" class="thinking-content" :data-think-scroll="msg.id">
                <div class="thinking-text" v-html="formatThinking(msg.thinking, msg.toolResults)" @click="handleToolCardClick"></div>
              </div>
            </div>
            <div class="message-text code-message" v-html="formatMessage(msg.content)" v-if="msg.content"></div>
          </div>
        </div>
        </DynamicScrollerItem>
      </DynamicScroller>

      <!-- 流式加载指示器 -->
      <div v-if="isSending &amp;&amp; streamStatus" class="stream-indicator">
        <span class="stream-pulse-dot"></span>
        <span class="stream-indicator-text">{{ streamStatus }}</span>
        <span class="stream-elapsed" v-if="elapsedTime > 0">{{ formatElapsed(elapsedTime) }}</span>
      </div>

      <!-- ask_user 问答面板 -->
      <div v-if="pendingQuestion" class="ask-user-panel">
        <!-- 权限授权面板（askType=permission）：显示4个按钮，grid两列布局 -->
        <div v-if="pendingQuestion.askType === 'permission'">
          <div class="ask-user-header">
            <span class="ask-user-header-icon">🔒</span>
            需要授权
          </div>
          <div class="ask-user-question">{{ pendingQuestion.question }}</div>
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
          <div class="ask-user-question">{{ pendingQuestion.question }}</div>
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
              <DiffView :content="activeTab.diffContent || ''" :title="'Diff: ' + (activeTab.filePath || '')" />
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
            <span class="attachment-icon">{{ att.image ? '🖼️' : '📄' }}</span>
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
            @keydown.enter.exact.prevent="sendMessage"
            @keydown.shift.enter="handleShiftEnter"
          />
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
              <SettingOutlined class="mode-icon" />
              <span class="mode-label">执行</span>
              <a-select
                :value="agentRuntime.executionMode"
                @change="(v: string) => { updateAgentRuntime('executionMode', v) }"
                size="small"
                class="mode-select"
                dropdown-class-name="mode-dropdown"
              >
                <a-select-option value="manual">手动 - 询问后执行</a-select-option>
                <a-select-option value="auto">自动 - 直接执行</a-select-option>
              </a-select>
            </div>
            <div class="mode-selector">
              <SettingOutlined class="mode-icon" />
              <a-select
                :value="agentRuntime.model"
                @change="(v: string) => { updateAgentRuntime('model', v) }"
                size="small"
                class="model-select"
                dropdown-class-name="mode-dropdown"
              >
                <a-select-option value="deepseek-v4-flash">deepseek-v4-flash</a-select-option>
                <a-select-option value="deepseek-v4-pro">deepseek-v4-pro</a-select-option>
              </a-select>
            </div>
            <div class="mode-selector">
              <SettingOutlined class="mode-icon" />
              <a-select
                :value="agentRuntime.thinkingMode"
                @change="(v: string) => { updateAgentRuntime('thinkingMode', v) }"
                size="small"
                class="model-select"
                dropdown-class-name="mode-dropdown"
              >
                <a-select-option value="non-thinking">关闭思考</a-select-option>
                <a-select-option value="thinking">思考 (high)</a-select-option>
                <a-select-option value="thinking_max">深度思考 (max)</a-select-option>
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
            <span class="context-tokens">上下文 Token: {{ formatTokenCount(totalContextTokens) }}</span>
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
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick, watch, h } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { useUserStore } from '@/store/user'
import { getConversationList, mapConversationResponseToConversation, getConversationMessages, processMessageGroups, deleteConversation as deleteConversationApi, updateConversationName } from '@/api/conversation'
import { streamChat, checkActiveTask, taskStream, cancelTask, uploadAttachment } from '@/api/chat'
import type { SkillMatchInfo } from '@/utils/sse-client'
import type { AgentStreamEvent } from '@/utils/sse-client'
import { submitAnswer } from '@/api/askUser'
import { listSnapshots, previewRollback, executeRollback, getSessionChanges, rollbackFile, rollbackAllFiles, type SessionChanges } from '@/api/snapshot'
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
import DirectoryBrowser from '@/components/DirectoryBrowser.vue'
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'
import { readProjectFile, buildProject, runProject, stopProject, getRunStatus, getRunOutput, execCommand } from '@/api/project'
import { getGitDiff, gitRestore } from '@/api/git'
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
  toolResults?: { at: number; content: string }[]
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
  fileName: string
  content: string
  size: number
  image: boolean
  language: string
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
    fileName: file.name,
    content: '',
    size: file.size,
    image: false,
    language: '',
    uploading: true
  }
  attachedFiles.value.push(attFile)

  try {
    const result = await uploadAttachment(file)
    if (result.success) {
      const found = attachedFiles.value.find(a => a.id === attId)
      if (found) {
        found.content = result.content
        found.image = result.image
        found.language = result.language
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
  const projectRoot = settingsStore.projectRoot
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
  const projectRoot = settingsStore.projectRoot
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
  return terminalCwd.value || settingsStore.projectRoot || '未设置工作目录'
})

const formatTerminalPrompt = () => {
  const cwd = terminalCwd.value || settingsStore.projectRoot || '.'
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

// 确保 terminalCwd 跟随 projectRoot 实时变化
watch(() => settingsStore.projectRoot, (val) => {
  if (val) {
    terminalCwd.value = val
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
  const current = terminalCwd.value || settingsStore.projectRoot || ''

  if (!target || target === '~') {
    // cd 或 cd ~ → 回到 projectRoot
    terminalCwd.value = settingsStore.projectRoot || current
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
  if (!terminalCwd.value && settingsStore.projectRoot) {
    terminalCwd.value = settingsStore.projectRoot
  }

  const projectRoot = settingsStore.projectRoot
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
  language?: string
}

const tabs = ref<EditorTab[]>([
  { id: 'chat', type: 'chat', title: '💬 对话' }
])
const activeTabId = ref('chat')

const activeTab = computed(() => tabs.value.find(t => t.id === activeTabId.value) || tabs.value[0])

// 双击文件树中的文件，打开文件编辑标签
const onFileDblClick = async (path: string, isDirectory: boolean) => {
  if (isDirectory) return
  const existing = tabs.value.find(t => t.type === 'file' && t.filePath === path)
  if (existing) {
    activeTabId.value = existing.id
    return
  }
  try {
    const res = await readProjectFile(path)
    if (res.code === 200 && res.data !== null) {
      const fileName = path.split('/').pop() || path.split('\\').pop() || path
      const tabId = `file-${path}`
      tabs.value.push({
        id: tabId,
        type: 'file',
        title: fileName,
        filePath: path,
        content: res.data,
        originalContent: res.data,
        isDirty: false
      })
      activeTabId.value = tabId
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
  if (activeTabId.value === tabId) {
    const newIdx = Math.min(idx, tabs.value.length - 1)
    activeTabId.value = tabs.value[newIdx]?.id || 'chat'
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

// 撤销文件改动
const handleRevertFile = async (filePath: string) => {
  const projectRoot = settingsStore.projectRoot
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
  if (existing) {
    activeTabId.value = tabId
    return
  }
  try {
    const result = await getGitDiff(projectRoot, filePath)
    if (result.success) {
      tabs.value.push({
        id: tabId,
        type: 'diff',
        title: filePath,
        filePath: filePath,
        diffContent: result.diff
      })
      activeTabId.value = tabId
    }
  } catch (e: any) {
    console.error('获取 diff 失败:', e)
  }
}

const conversations = ref<Conversation[]>([])
const messages = ref<Record<string, ChatMessage[]>>({})
const currentConversationId = ref<string>('')
const inputMessage = ref('')
const isSending = ref(false)
const isLoadingConversations = ref(false)
const isLoadingMessages = ref(false)
const scrollerRef = ref<any>(null)
const collapsed = ref(false)
const sidebarTab = ref<'conversation' | 'files' | 'git' | 'skills' | 'agents'>('conversation')

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
const pendingQuestion = ref<{ uuid: string; question: string; askType?: string } | null>(null)
const pendingQuestionAnswer = ref('')
const pendingShowCustomInput = ref(false)
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
  return currentMessages.value.reduce((sum, msg) => sum + (msg.tokenCount || 0), 0)
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
watch(isLoadingMessages, (loading) => {
  if (!loading) {
    scrollToBottom()
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
    agent.events.push({ type: 'tool_call' as const, toolName: tool_name || '', filePath: file_path || '', result: result || '' })
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
  } else {
    settingsStore.projectRoot = path
  }
  fileTreeLoadPath.value = path
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
    message.success('回答已发送')
  } catch (e: any) {
    message.error('回答发送失败: ' + (e.message || '未知错误'))
  }
}

// 处理权限授权按钮（4种 action）
const permissionLock = ref(false)
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
}

// 节流渲染：将流式更新聚合并限制在每 150ms 一次，避免高频 chunk 导致卡死
let updateTimer: number | null = null

const scheduleMessageUpdate = (convId: string, thinkingMsgId?: string | number) => {
  if (updateTimer !== null) return
  updateTimer = window.setTimeout(() => {
    updateTimer = null
    if (messages.value[convId]) {
      // 只替换数组引用触发 DynamicScroller 更新，不替换消息对象（避免切断响应式连接导致流式内容不更新）
      messages.value[convId] = [...messages.value[convId]]
    }
    scrollToBottom()
    // 等 DOM 实际更新后再滚动思考过程容器到底部
    if (thinkingMsgId !== undefined) {
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
  if (messages.value[convId]) {
    messages.value[convId] = [...messages.value[convId]]
  }
  scrollToBottom()
  if (thinkingMsgId !== undefined) {
    nextTick(() => {
      const el = document.querySelector(`[data-think-scroll="${thinkingMsgId}"]`)
      if (el) el.scrollTop = el.scrollHeight
    })
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

  // 构建最终消息：将附件内容拼接到用户消息前
  let finalMessage = text
  if (attachedFiles.value.length > 0) {
    const attachmentBlocks: string[] = []
    for (const att of attachedFiles.value) {
      if (att.content) {
        attachmentBlocks.push(`--- 附件: ${att.fileName} ---\n${att.content}\n--- 附件结束 ---`)
      } else if (att.image) {
        attachmentBlocks.push(`[用户上传了图片: ${att.fileName}（${formatFileSize(att.size)}）]`)
      }
    }
    if (attachmentBlocks.length > 0) {
      finalMessage = attachmentBlocks.join('\n\n') + '\n\n' + text
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
  isSending.value = true
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
      agentConfigId: currentAgentConfigId.value
    }, abortCtrl)) {
      // 每个事件都检查 sessionId —— 后端在第一个 SSE 事件中就返回了真实会话 ID，
      // 但不在流式过程中迁移 convId（避免数组引用变动导致内容重复），
      // 流式结束后在 finally 中统一迁移。
      // 这里只保存 realSessionId，确保用户过早停止时 finally 中也能正确迁移。
      if (convId.startsWith('local-') && event.sessionId && !realSessionId) {
        realSessionId = String(event.sessionId)
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
            assistantMsg.toolResults!.push({
              at: thinkingContent.length,
              content: toolContent
            })
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
          pendingQuestion.value = { uuid: event.data.uuid, question: event.data.question, askType: event.data.askType || 'clarification' }
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
        pendingQuestion.value = { uuid: event.data.uuid, question: event.data.question, askType: event.data.askType || 'clarification' }
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
 * 渲染技能使用卡片（report_skill_result 工具结果专用）
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

const formatThinking = (thinking: string | undefined, toolResults?: { at: number; content: string }[]) => {
  if (!thinking && (!toolResults || toolResults.length === 0)) return ''
  thinking = thinking || ''
  const cacheKey = thinking + '|' + JSON.stringify(toolResults?.map(t => ({ at: t.at, len: t.content.length })))
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
        // 工具调用结果卡片（含工具名称解析）
        const { toolName, cleanContent } = parseToolNameLine(tr.content)
        const displayContent = cleanContent || tr.content

        // 技能使用卡片：report_skill_result 特殊渲染
        if (toolName === 'report_skill_result') {
          const skillUsageHtml = renderSkillUsageCard(displayContent)
          if (skillUsageHtml) {
            parts.push(skillUsageHtml)
            lastPos = tr.at
            continue
          }
        }

        const escaped = escapeHtml(displayContent)
        const isError = /^错误[：:]|【参数(?:缺失|错误)】|【权限(?:不足|拒绝)】|【会话上下文丢失】|\bCannot\b/i.test(displayContent.substring(0, 300))
        const firstLine = displayContent.split('\n')[0].substring(0, 80)
        const labelText = isError ? ('执行出错' + (toolName ? ' ' + toolName : '')) : ('工具' + (toolName || '') + '执行')
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

// 滚动（双重保障：nextTick + rAF + 延时兜底，适配 DynamicScroller 渲染时机）
const scrollToBottom = () => {
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
      requestAnimationFrame(() => doScroll())
    })
  })
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
.chat-container {
  display: flex;
  height: 100%;
  background-color: #f7f9fc;
}

/* ===== 侧边栏 ===== */
.sidebar {
  width: 280px;
  background: #fafbfc;
  border-right: 1px solid #eef2f7;
  display: flex;
  flex-direction: column;
  height: 100%;
  transition: width 0.25s ease;
  overflow: hidden;
  flex-shrink: 0;
}
.sidebar.collapsed { width: 80px; }

.sidebar-header {
  padding: 12px 8px;
  border-bottom: 1px solid #eef2f7;
  background: #f8fafc;
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.sidebar-tabs {
  display: flex;
  background: #f0f2f5;
  border-radius: 6px;
  padding: 2px;
  flex: 1;
  min-width: 0;
}

.tab-btn {
  flex: 1;
  border: none;
  background: transparent;
  padding: 4px 0;
  font-size: 12px;
  color: #666;
  cursor: pointer;
  border-radius: 4px;
  transition: all 0.2s;
  font-family: inherit;
}
.tab-btn.active {
  background: white;
  color: #1a202c;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0,0,0,0.08);
}

.tab-full { display: inline; }
.tab-short { display: none; }

.collapsed .sidebar-tabs { flex: 1; }
.collapsed .tab-full { display: none; }
.collapsed .tab-short { display: inline; }

.sidebar-collapse-btn {
  cursor: pointer;
  font-size: 18px;
  color: #8c8c8c;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: color 0.2s;
}
.sidebar-collapse-btn:hover { color: #1890ff; }

.conversation-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
  background: #fafbfc;
}
.filetree-panel {
  flex: 1;
  overflow-y: auto;
  background: #fafbfc;
}

.conversation-item {
  display: flex;
  align-items: center;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: all 0.15s;
  border: 1px solid transparent;
  background: white;
  box-shadow: 0 1px 2px rgba(0,0,0,0.04);
}
.conversation-item:hover {
  background-color: #f8fafc;
  border-color: #e1e8f0;
}
.conversation-item.active {
  background-color: #f0f9ff;
  border-color: #bae6fd;
}
.conv-icon {
  margin-right: 10px;
  color: #1677ff;
  font-size: 16px;
  flex-shrink: 0;
}
.conv-info {
  flex: 1;
  min-width: 0;
}
.conv-title {
  font-weight: 500;
  color: #1a202c;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
  cursor: pointer;
}

.conv-title:hover {
  color: #1890ff;
}

.conv-title-editing {
  margin: -2px 0;
}

.conv-title-editing .ant-input {
  height: 28px;
  font-size: 12px;
  border-radius: 4px;
}
.conv-time {
  font-size: 10px;
  color: #8c8c8c;
  margin-top: 1px;
}
.conv-actions {
  opacity: 0;
  transition: opacity 0.15s;
  flex-shrink: 0;
}
.conversation-item:hover .conv-actions { opacity: 1; }

.collapsed .conv-info,
.collapsed .conv-actions { display: none; }
.collapsed .conversation-item {
  padding: 8px 0;
  justify-content: center;
}
.collapsed .conv-icon { margin-right: 0; font-size: 18px; }
.collapsed .conversation-list { padding: 8px 4px; }

.new-chat-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  margin-bottom: 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  color: #1677ff;
  background: #f0f5ff;
  border: 1px dashed #91caff;
  transition: all 0.2s;
}
.new-chat-btn:hover {
  background: #e6f4ff;
  border-color: #1677ff;
}
.collapsed .new-chat-btn {
  justify-content: center;
  padding: 6px 0;
}
.collapsed .new-chat-btn span { display: none; }

.loading-conversations {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 30px 12px;
  color: #8c8c8c;
  gap: 8px;
}
.empty-conversations {
  text-align: center;
  padding: 30px 12px;
  color: #8c8c8c;
}
.empty-conversations p { margin: 0; font-size: 13px; }
.empty-hint { margin-top: 6px !important; font-size: 11px !important; color: #bbb; }
.collapsed .loading-conversations span,
.collapsed .empty-conversations p { display: none; }

/* ===== 主聊天区域 ===== */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #f7f9fc;
}

/* ===== 标签栏 ===== */
.tab-bar {
  display: flex;
  background: #f0f0f0;
  border-bottom: 1px solid #d9d9d9;
  flex-shrink: 0;
  overflow-x: auto;
  overflow-y: hidden;
  min-height: 32px;
  align-items: stretch;
}
.tab-bar::-webkit-scrollbar { height: 2px; }
.tab-bar::-webkit-scrollbar-thumb { background: #ccc; border-radius: 1px; }

.tab-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 12px;
  font-size: 12px;
  color: #666;
  cursor: pointer;
  border-right: 1px solid #d9d9d9;
  background: #f0f0f0;
  white-space: nowrap;
  user-select: none;
  transition: all 0.15s;
  min-width: 0;
  position: relative;
}
.tab-item:hover {
  background: #e6e6e6;
  color: #333;
}
.tab-item.active {
  background: white;
  color: #1a202c;
  font-weight: 600;
  border-bottom: 2px solid #1677ff;
  margin-bottom: -1px;
}
.tab-item.dirty {
  color: #faad14;
}
.tab-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 160px;
}
.tab-dirty-dot {
  font-size: 10px;
  color: #faad14;
}
.tab-close {
  font-size: 14px;
  line-height: 1;
  color: #999;
  padding: 0 2px;
  border-radius: 3px;
  transition: all 0.15s;
  flex-shrink: 0;
  margin-left: 2px;
}
.tab-close:hover {
  background: #ff4d4f;
  color: white;
}

/* ===== 标签内容区 ===== */
.tab-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
}
.tab-content > .chat-messages-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
  min-height: 0;
}

/* ===== 文件标签布局（编辑器+控制台分栏） ===== */
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
  background: #4ec9b0;
  animation: consolePulse 1.5s ease-in-out infinite;
}
@keyframes consolePulse {
  0%, 100% { opacity: 1; box-shadow: 0 0 4px #4ec9b0; }
  50% { opacity: 0.5; box-shadow: 0 0 8px #4ec9b0; }
}
/* 标签操作按钮组 */
.tab-items {
  display: flex;
  flex: 1;
  overflow-x: auto;
  overflow-y: hidden;
  align-items: stretch;
}
.tab-items::-webkit-scrollbar { height: 2px; }
.tab-items::-webkit-scrollbar-thumb { background: #ccc; border-radius: 1px; }
.tab-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 8px;
  flex-shrink: 0;
  background: #f0f0f0;
  border-left: 1px solid #d9d9d9;
}
.tb-btn {
  font-size: 11px !important;
  height: 24px !important;
  padding: 0 8px !important;
  border-radius: 4px !important;
}

/* ===== 差异对比标签 ===== */
.diff-tab-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #f7f9fc;
}
.diff-tab-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
  flex-shrink: 0;
}
.diff-tab-title {
  font-size: 13px;
  font-weight: 600;
  color: #262626;
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

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
  background: #f7f9fc;
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
  padding: 30px;
  color: #8c8c8c;
  gap: 8px;
}

.message-item {
  display: flex;
  margin-bottom: 20px;
  gap: 12px;
}

.message-avatar {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: bold;
  font-size: 14px;
}
.user-avatar {
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: white;
}
.ai-avatar {
  background: linear-gradient(135deg, #1677ff, #0958d9);
  color: white;
  font-size: 18px;
}

/* 回滚按钮 */
.rollback-btn {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #fff0f0;
  border: 1px solid #ffccc7;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: 10px;
  color: #ff4d4f;
  transition: all 0.2s;
  user-select: none;
  flex-shrink: 0;
  margin-left: 4px;
}
.rollback-btn:hover {
  background: #ff4d4f;
  border-color: #ff4d4f;
  color: #fff;
}

.message-content {
  flex: 1;
  min-width: 0;
}

.message-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}

.message-sender {
  font-weight: 600;
  font-size: 13px;
  color: #262626;
}

.message-time {
  font-size: 11px;
  color: #bbb;
}

.message-token {
  font-size: 11px;
  color: #bbb;
}

.streaming-indicator {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: #1677ff;
}
.streaming-dot {
  width: 6px;
  height: 6px;
  background: #1677ff;
  border-radius: 50%;
  animation: pulse 1.2s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.message-text {
  font-size: 14px;
  line-height: 1.7;
  color: #262626;
  word-wrap: break-word;
  overflow-wrap: break-word;
}

/* thinking section */
.thinking-section {
  margin-bottom: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
  background-color: #f8fafc;
}
.thinking-header {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  cursor: pointer;
  user-select: none;
  background: #f1f5f9;
  font-size: 12px;
}
.thinking-header:hover { background: #e9edf2; }
.thinking-title { font-weight: 600; color: #475569; flex: 1; }
.thinking-summary { font-size: 11px; color: #64748b; margin-right: 4px; }
.thinking-hint { font-size: 11px; color: #94a3b8; }
.skill-match-tags { display: flex; gap: 4px; flex-wrap: wrap; margin-right: 8px; }
.skill-match-tag {
  display: inline-flex; align-items: center; gap: 3px;
  padding: 1px 8px; border-radius: 10px;
  font-size: 11px; font-weight: 500;
}
.skill-tag-high { background: #f6ffed; color: #389e0d; border: 1px solid #b7eb8f; }
.skill-tag-mid  { background: #fffbe6; color: #d48806; border: 1px solid #ffe58f; }
.skill-tag-low  { background: #fff2f0; color: #cf1322; border: 1px solid #ffccc7; }
.skill-tag-conf { font-size: 10px; opacity: 0.8; }
.thinking-content { padding: 8px 12px; max-height: 600px; overflow-y: auto; }
.thinking-text {
  font-size: 13px;
  line-height: 1.6;
  color: #475569;
}

/* thinking timeline layout */
.thinking-timeline {
  display: flex;
  flex-direction: column;
  gap: 8px;
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
  padding: 1px 4px;
  background: #eef2f6;
  border-radius: 3px;
}

/* ===== 输入区域 ===== */
.chat-input-area {
  padding: 12px 24px 16px;
  background: white;
  border-top: 1px solid #e8e8e8;
  position: relative;
}
/* ===== 附件上传 ===== */
.attachment-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
  padding: 0 2px;
}
.attachment-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  background: #f0f5ff;
  border: 1px solid #d6e4ff;
  border-radius: 4px;
  font-size: 12px;
  line-height: 22px;
  transition: all 0.15s;
}
.attachment-tag:hover {
  background: #e6f0ff;
}
.attachment-tag.attachment-error {
  background: #fff2f0;
  border-color: #ffccc7;
}
.attachment-icon {
  font-size: 13px;
  flex-shrink: 0;
}
.attachment-name {
  color: #262626;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.attachment-size {
  color: #8c8c8c;
  font-size: 11px;
  flex-shrink: 0;
}
.attachment-uploading {
  color: #1677ff;
  font-size: 12px;
}
.attachment-error-msg {
  color: #ff4d4f;
  font-size: 11px;
  cursor: help;
}
.attachment-remove {
  cursor: pointer;
  color: #8c8c8c;
  font-size: 14px;
  line-height: 1;
  padding: 0 2px;
  transition: color 0.15s;
  flex-shrink: 0;
}
.attachment-remove:hover {
  color: #ff4d4f;
}
/* 附件上传按钮 */
.attach-btn {
  height: 40px;
  width: 40px;
  border-radius: 8px;
  flex-shrink: 0;
  border: 1px dashed #d9d9d9;
  color: #8c8c8c;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}
.attach-btn:hover {
  color: #1677ff;
  border-color: #1677ff;
  background: #f0f5ff;
}
.attach-btn:disabled {
  color: #d9d9d9;
  border-color: #d9d9d9;
  background: transparent;
}


.input-wrapper {
  display: flex;
  gap: 8px;
  align-items: center;
}
.input-wrapper :deep(.ant-input) {
  border-radius: 8px;
  resize: none;
  font-size: 14px;
  min-height: 40px;
  padding: 8px 11px;
  line-height: 22px;
}
.send-btn {
  height: 40px;
  width: 40px;
  border-radius: 8px;
  flex-shrink: 0;
}

.stop-btn {
  height: 40px;
  width: 40px;
  border-radius: 50%;
  flex-shrink: 0;
  background: rgba(0, 0, 0, 0.5);
  border: none;
  color: white;
  font-size: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.08);
}
.stop-btn:hover {
  background: #ff4d4f;
  color: white;
  box-shadow: 0 4px 12px rgba(255, 77, 79, 0.3);
  transform: scale(1.05);
}
.stop-btn:active {
  transform: scale(0.95);
}

.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
  flex-wrap: wrap;
  gap: 8px;
}

/* 任务进度触发器（与 mode-selector 风格一致） */
.task-trigger {
  cursor: pointer;
  position: relative;
  transition: all 0.2s;
}
.task-trigger:hover {
  background: #e6f7ff;
  border-color: #91d5ff;
}
.task-trigger.active {
  background: #e6f7ff;
  border-color: #1890ff;
}
.task-trigger.loading .task-trigger-icon {
  color: #1890ff;
}
.task-trigger-icon {
  font-size: 14px;
}
.task-trigger-text {
  font-size: 12px;
  font-weight: 500;
  color: #595959;
  white-space: nowrap;
}

/* 任务下拉面板 */
.task-dropdown {
  position: absolute;
  bottom: 100%;
  left: 12px;
  right: 12px;
  margin-bottom: 8px;
  background: #fff;
  border: 1px solid #d9d9d9;
  border-radius: 8px;
  box-shadow: 0 -4px 16px rgba(0, 0, 0, 0.12);
  overflow: hidden;
  z-index: 100;
  max-height: 240px;
  display: flex;
  flex-direction: column;
}
.task-dropdown-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: #f0f5ff;
  border-bottom: 1px solid #e8e8e8;
  font-size: 13px;
  font-weight: 600;
  color: #1a202c;
  flex-shrink: 0;
}
.task-dropdown-progress {
  font-size: 12px;
  color: #667eea;
  font-weight: 500;
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
  padding: 5px 12px;
  font-size: 12px;
  line-height: 1.5;
  transition: background 0.15s;
}
.task-dropdown-item--completed {
  opacity: 0.55;
}
.task-dropdown-item--executing {
  background: #e6f7ff;
}
.task-dropdown-icon {
  flex-shrink: 0;
  font-size: 13px;
}
.task-dropdown-id {
  flex-shrink: 0;
  font-family: 'SF Mono', 'Monaco', monospace;
  font-size: 11px;
  color: #667eea;
  font-weight: 600;
  min-width: 22px;
}
.task-dropdown-desc {
  color: #334155;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.task-dropdown-priority-high {
  flex-shrink: 0;
  font-size: 10px;
  padding: 0 4px;
  border-radius: 2px;
  background: #fff2f0;
  color: #ff4d4f;
  font-weight: 600;
  line-height: 16px;
}
.task-dropdown-deps {
  flex-shrink: 0;
  font-size: 10px;
  color: #8c8c8c;
  font-family: 'SF Mono', 'Monaco', monospace;
}

/* 文件改动触发器 */
.changes-trigger {
  cursor: pointer;
  position: relative;
  transition: all 0.2s;
}
.changes-trigger:hover {
  background: #e6f7ff;
  border-color: #91d5ff;
}
.changes-trigger.active {
  background: #fff7e6;
  border-color: #ffa940;
}
.changes-trigger-icon {
  font-size: 14px;
}
.changes-trigger-text {
  font-size: 12px;
  font-weight: 500;
  color: #595959;
  white-space: nowrap;
}
.changes-trigger-badge {
  font-size: 11px;
  color: #52c41a;
  font-weight: 600;
  margin-left: 2px;
  white-space: nowrap;
}

/* 文件改动面板 */
.changes-panel {
  position: absolute;
  bottom: 100%;
  left: 12px;
  right: 12px;
  margin-bottom: 8px;
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  box-shadow: 0 -4px 20px rgba(0, 0, 0, 0.10);
  overflow: hidden;
  z-index: 100;
  max-height: 320px;
  display: flex;
  flex-direction: column;
}
.changes-panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  background: #fafafa;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}
.changes-panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #1a202c;
}
.changes-panel-summary {
  font-size: 12px;
  color: #8c8c8c;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 4px;
}
.changes-summary-divider {
  color: #d9d9d9;
  margin: 0 2px;
}
.changes-panel-body {
  overflow-y: auto;
  flex: 1;
}
.changes-panel-item {
  border-bottom: 1px solid #f5f5f5;
  transition: background 0.15s;
}
.changes-panel-item:last-child {
  border-bottom: none;
}
.changes-panel-item:hover {
  background: #fafafa;
}
.changes-panel-item.is-rolled {
  opacity: 0.6;
}
.changes-file-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px;
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
.changes-file-icon {
  flex-shrink: 0;
  font-size: 12px;
  line-height: 1;
}
.changes-file-path {
  color: #262626;
  font-family: 'SF Mono', 'Monaco', 'Inconsolata', monospace;
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
  padding: 0 6px;
  border-radius: 3px;
  font-weight: 500;
  line-height: 18px;
  display: inline-block;
}
.changes-badge-new {
  background: #f6ffed;
  color: #52c41a;
  border: 1px solid #b7eb8f;
}
.changes-badge-rolled {
  background: #fff1f0;
  color: #ff4d4f;
  border: 1px solid #ffa39e;
}
.changes-file-stats {
  font-size: 12px;
  font-weight: 600;
  font-family: 'SF Mono', 'Monaco', 'Inconsolata', monospace;
}
.changes-add {
  color: #52c41a;
}
.changes-del {
  color: #ff4d4f;
  margin-left: 4px;
}
.changes-file-actions {
  flex-shrink: 0;
}
.changes-rollback-btn {
  font-size: 12px;
  color: #8c8c8c;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 0 8px;
  height: 26px;
  line-height: 24px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  transition: all 0.2s;
}
.changes-rollback-btn:not(:disabled):hover {
  color: #ff4d4f !important;
  border-color: #ff4d4f;
  background: #fff1f0;
}
.changes-rollback-btn--rolled {
  color: #bfbfbf !important;
  border-color: #f0f0f0;
  cursor: not-allowed;
}
.changes-empty {
  padding: 32px 14px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: #bfbfbf;
  font-size: 13px;
}
.changes-empty-icon {
  font-size: 24px;
}
.changes-panel-footer {
  display: flex;
  justify-content: center;
  padding: 10px 14px;
  border-top: 1px solid #f0f0f0;
  flex-shrink: 0;
  background: #fafafa;
}
.changes-rollback-all-btn {
  font-size: 12px;
  height: 28px;
  padding: 0 14px;
  border-radius: 4px;
  border-color: #ff4d4f;
  color: #ff4d4f;
}
.changes-rollback-all-btn:not(:disabled):hover {
  color: #fff !important;
  background: #ff4d4f;
  border-color: #ff4d4f;
}
.changes-rollback-all-btn:disabled {
  border-color: #d9d9d9;
  color: #bfbfbf;
}

.footer-left {
  display: flex;
  align-items: center;
}

.footer-right {
  display: flex;
  align-items: center;
}

.context-tokens {
  color: #8c8c8c;
  font-size: 11px;
}

/* 模式选择器 */
.mode-selector {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 10px 2px 8px;
  background: #f5f5f5;
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  height: 26px;
}

.mode-icon {
  font-size: 12px;
  opacity: 0.55;
}

.mode-label {
  font-size: 11px;
  color: #8c8c8c;
  white-space: nowrap;
}

.mode-select {
  width: 130px;
}

.model-select {
  width: 150px;
}

.model-select :deep(.ant-select-selection-item) {
  font-size: 12px !important;
}

.mode-select :deep(.ant-select-selection-item) {
  font-size: 12px !important;
}

/* Git 侧边栏面板 */
.git-panel {
  flex: 1;
  overflow-y: auto;
  background: #fafbfc;
}

/* 技能面板 */
.skill-panel {
  flex: 1;
  overflow-y: auto;
  background: #fafbfc;
}

/* ===== 流式加载指示器（消息框左下角） ===== */
.stream-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 24px;
  flex-shrink: 0;
}
.stream-pulse-dot {
  width: 8px;
  height: 8px;
  background: #1677ff;
  border-radius: 50%;
  animation: streamPulse 1.2s ease-in-out infinite;
  flex-shrink: 0;
}
.stream-indicator-text {
  font-size: 13px;
  color: #1677ff;
  font-weight: 500;
}
@keyframes streamPulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(0.8); }
}

/* 深色模式适配 */
[data-theme='dark'] .mode-selector {
  background: #1f1f1f;
  border-color: #333;
}

[data-theme='dark'] .mode-selector .mode-label {
  color: #666;
}

[data-theme='dark'] .mode-selector .mode-icon {
  opacity: 0.4;
}
:deep(.code-block-cmd) {
  border: 1px solid #d0d7de;
  border-radius: 6px;
  overflow: hidden;
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', monospace;
  font-size: 12px;
  margin: 8px 0;
  background: #f6f8fa;
  color: #24292f;
}
:deep(.cbc-header) {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  background: #eef1f5;
  border-bottom: 1px solid #d0d7de;
}
:deep(.cbc-title) { font-weight: 600; font-size: 11px; color: #24292f; }
:deep(.cbc-status) { margin-left: auto; font-size: 10px; font-weight: 600; }
:deep(.cbc-status.success) { color: #1a7f37; }
:deep(.cbc-status.fail) { color: #cf222e; }
:deep(.cbc-body) { padding: 6px 0; }
:deep(.cbc-command) {
  display: flex;
  gap: 6px;
  padding: 2px 10px 6px;
  border-bottom: 1px solid #d0d7de;
  margin-bottom: 4px;
}
:deep(.cbc-prompt) { color: #1a7f37; font-weight: 600; user-select: none; }
:deep(.cbc-output) {
  padding: 0 10px;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
  color: #656d76;
}

:deep(.code-block-filelist) {
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  overflow: hidden;
  font-size: 12px;
  margin: 8px 0;
  background: #fff;
}
:deep(.cbf-header) {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  background: #fafafa;
  border-bottom: 1px solid #e8e8e8;
}
:deep(.cbf-title) { font-weight: 600; color: #262626; font-size: 11px; }
:deep(.cbf-count) { margin-left: auto; color: #8c8c8c; font-size: 10px; }
:deep(.cbf-body) { padding: 4px 0; }
:deep(.cbf-item) {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 10px;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
}
:deep(.cbf-badge) {
  font-size: 9px;
  padding: 0 4px;
  border-radius: 2px;
  font-weight: 600;
  flex-shrink: 0;
  line-height: 16px;
}
:deep(.cbf-item.add .cbf-badge) { background: #f6ffed; color: #52c41a; }
:deep(.cbf-item.mod .cbf-badge) { background: #fff7e6; color: #fa8c16; }
:deep(.cbf-item.del .cbf-badge) { background: #fff2f0; color: #ff4d4f; }
:deep(.cbf-path) { color: #262626; }
:deep(.cbf-summary) { color: #8c8c8c; }

/* ask_user 问答面板 — 全新样式 */
.ask-user-panel {
  margin: 0 16px 12px;
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 10px;
  padding: 18px 20px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
}
.ask-user-header {
  font-weight: 600;
  font-size: 15px;
  color: #262626;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.ask-user-header-icon {
  font-size: 16px;
  line-height: 1;
}
.ask-user-question {
  font-size: 14px;
  color: #434343;
  margin-bottom: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  background: #fafafa;
  padding: 10px 12px;
  border-radius: 6px;
  border-left: 3px solid #1677ff;
}
.ask-user-input-row {
  display: flex;
  gap: 8px;
}
.ask-user-input-row .a-input {
  flex: 1;
}

/* 权限授权按钮 — grid 两列等宽布局 */
.permission-btn-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 0;
}
.permission-btn {
  height: 38px !important;
  font-size: 13px !important;
  border-radius: 6px !important;
  font-weight: 500 !important;
  transition: all 0.2s ease !important;
}
.permission-btn-approve {
  background: #1677ff !important;
  border-color: #1677ff !important;
  color: #fff !important;
}
.permission-btn-approve:hover {
  background: #4096ff !important;
  border-color: #4096ff !important;
}
.permission-btn-approve-all {
  color: #1677ff !important;
  border-color: #1677ff !important;
  background: #fff !important;
}
.permission-btn-approve-all:hover {
  color: #4096ff !important;
  border-color: #4096ff !important;
  background: #f0f5ff !important;
}
.permission-btn-reject {
  color: #ff4d4f !important;
  border-color: #ff4d4f !important;
  background: #fff !important;
}
.permission-btn-reject:hover {
  color: #ff7875 !important;
  border-color: #ff7875 !important;
  background: #fff2f0 !important;
}
.permission-btn-custom {
  color: #595959 !important;
  border-color: #d9d9d9 !important;
  background: #fff !important;
}
.permission-btn-custom:hover {
  color: #1677ff !important;
  border-color: #1677ff !important;
  background: #f0f5ff !important;
}
.permission-btn-custom.active {
  color: #1677ff !important;
  border-color: #1677ff !important;
  background: #e6f4ff !important;
}

/* 自定义消息输入行 — 带过渡动画 */
.permission-custom-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.permission-custom-row .a-input {
  flex: 1;
}
.custom-send-btn {
  flex-shrink: 0;
}

/* 展开/收起动画 */
.custom-fade-enter-active {
  animation: customFadeIn 0.2s ease-out;
}
.custom-fade-leave-active {
  animation: customFadeIn 0.15s ease-in reverse;
}
@keyframes customFadeIn {
  from {
    opacity: 0;
    transform: translateY(-6px);
    max-height: 0;
  }
  to {
    opacity: 1;
    transform: translateY(0);
    max-height: 80px;
  }
}

.stream-elapsed {
  font-size: 13px;
  color: #1677ff;
  font-weight: 600;
  margin-left: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
/* ===== 控制台面板（终端 + 运行日志 Tabs） ===== */
.console-panel {
  flex-shrink: 0;
  background: #1e1e1e;
  border-top: 1px solid #3c3c3c;
  border-bottom: 1px solid #3c3c3c;
  display: flex;
  flex-direction: column;
  min-height: 32px;
  position: relative;
}
.console-panel.minimized {
  flex: 0 0 auto !important;
  height: auto !important;
}
/* 拖拽调整手柄 */
.console-resize-handle {
  position: absolute;
  top: -4px;
  left: 0;
  right: 0;
  height: 8px;
  cursor: ns-resize;
  z-index: 10;
}
.console-resize-handle:hover {
  background: rgba(78, 201, 176, 0.3);
}
/* Tab 栏 */
.console-tab-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #252526;
  border-bottom: 1px solid #3c3c3c;
  flex-shrink: 0;
  min-height: 32px;
  user-select: none;
}
.console-tabs {
  display: flex;
  align-items: center;
  gap: 0;
}
.console-tab {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 0 12px;
  height: 32px;
  font-size: 12px;
  color: #858585;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.15s;
}
.console-tab:hover {
  color: #cccccc;
  background: #2d2d2d;
}
.console-tab.active {
  color: #cccccc;
  border-bottom-color: #4ec9b0;
  background: #1e1e1e;
}
.console-tab-icon {
  font-size: 12px;
  font-weight: 700;
  color: #4ec9b0;
  font-family: 'SF Mono', 'Monaco', 'Consolas', monospace;
}
.console-tab-close {
  font-size: 14px;
  line-height: 1;
  color: #858585;
  padding: 0 2px;
  border-radius: 3px;
  margin-left: 2px;
  transition: all 0.15s;
}
.console-tab-close:hover {
  background: #ff4d4f;
  color: white;
}
.console-tab-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
}
.console-tab-cwd {
  font-size: 11px;
  color: #858585;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.console-tab-btn {
  font-size: 11px !important;
  color: #858585 !important;
  height: 22px !important;
  padding: 0 6px !important;
}
.console-tab-btn:hover {
  color: #cccccc !important;
}
.console-tab-toggle {
  font-size: 11px;
  color: #858585;
  cursor: pointer;
  padding: 2px;
  border-radius: 3px;
  transition: all 0.15s;
}
.console-tab-toggle:hover {
  background: #3c3c3c;
  color: #cccccc;
}
.console-panel-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.console-log-body {
  overflow-y: auto;
  padding: 8px 12px;
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', monospace;
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
  padding: 6px 12px;
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', monospace;
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
.terminal-executing {
  color: #858585;
  font-style: italic;
}
.terminal-prompt-line {
  display: flex;
  align-items: center;
  gap: 0;
  min-height: 18px;
}
.prompt-sign {
  color: #4ec9b0;
  white-space: pre;
  flex-shrink: 0;
}
.prompt-input {
  color: #d4d4d4;
  white-space: pre;
}
.prompt-cursor {
  color: #d4d4d4;
  font-weight: 100;
  animation: terminalBlink 1s step-end infinite;
}
.prompt-cursor.blink {
  visibility: visible;
}
@keyframes terminalBlink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

/* ===== 运行日志内容样式 ===== */
.log-line {
  min-height: 18px;
  padding: 0;
  text-align: left;
}
.log-line.log-error {
  color: #f48771;
}
.log-line.log-warn {
  color: #dcdcaa;
}
.log-line.log-success {
  color: #4ec9b0;
}
.log-loading {
  color: #858585;
  padding: 4px 0;
  font-size: 12px;
}
</style>

<!-- 非 scoped 样式：markdown 内容排版（v-html 内元素不受 scoped 影响） -->
<style>
/* ===== 工具调用结果卡片 ===== */
.tool-result-card {
  border: 1px solid #d4dce8;
  border-radius: 6px;
  overflow: hidden;
  background: #f8fafc;
  font-size: 12px;
}
.tool-result-card.tool-result-error {
  border-color: #fca5a5;
  background: #fef2f2;
}
.tool-result-card.tool-result-collapsed .tool-result-body {
  display: none;
}
.tool-result-card.tool-result-collapsed .tr-toggle {
  transform: rotate(0deg);
}
.tool-result-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  cursor: pointer;
  user-select: none;
  font-weight: 500;
  color: #374151;
  background: #eef2f8;
  border-bottom: 1px solid #d4dce8;
  transition: background 0.15s;
}
.tool-result-header:hover { background: #e4eaf2; }
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
  width: 16px;
  height: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 9px;
  font-weight: 700;
}
.tr-icon-success {
  background: #dcfce7;
  color: #16a34a;
}
.tr-icon-error {
  background: #fef2f2;
  color: #dc2626;
}
.tr-label {
  font-weight: 600;
  flex-shrink: 0;
}
.tr-summary {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #64748b;
  font-weight: 400;
  font-size: 11px;
}
.tool-result-body {
  max-height: 400px;
  overflow-y: auto;
}
.tool-result-body pre {
  margin: 0;
  padding: 8px 10px;
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 12px;
  line-height: 1.4;
  color: #334155;
  white-space: pre-wrap;
  word-break: break-all;
}
.tool-result-error .tool-result-body pre { color: #991b1b; }

/* ===== 技能使用卡片（report_skill_result 专用） ===== */
.skill-usage-card {
  border-radius: 6px;
  overflow: hidden;
  font-size: 12px;
}
.skill-usage-card.skill-usage-success {
  border: 1px solid #86efac;
  background: #f0fdf4;
}
.skill-usage-card.skill-usage-fail {
  border: 1px solid #fca5a5;
  background: #fef2f2;
}
.skill-usage-card .tool-result-header {
  border-bottom: none;
}
.skill-usage-card.skill-usage-success .tool-result-header {
  background: #dcfce7;
}
.skill-usage-card.skill-usage-fail .tool-result-header {
  background: #fee;
}
.skill-usage-badge {
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
.skill-usage-badge-ok {
  background: #16a34a;
  color: #fff;
}
.skill-usage-badge-fail {
  background: #dc2626;
  color: #fff;
}
.skill-usage-conf {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 8px;
  background: #e0e7ff;
  color: #4338ca;
}

/* ===== 代码块 ===== */
.code-message pre {
  background: #f6f8fa;
  border-radius: 6px;
  padding: 12px 16px;
  font-size: 13px;
  line-height: 1.5;
  margin: 8px 0;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-x: hidden;
  border: 1px solid #e1e4e8;
}
.code-message code {
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', monospace;
  font-size: 0.9em;
}
.code-message p code {
  background: #f0f2f5;
  padding: 1px 5px;
  border-radius: 3px;
  color: #1677ff;
}
/* 思考过程中代码块 */
.thinking-text pre {
  background: #f6f8fa;
  border: 1px solid #e1e4e8;
  border-radius: 6px;
  padding: 10px 14px;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-x: hidden;
}

/* ===== highlight.js 浅色主题 ===== */
.code-message pre code.hljs,
.thinking-text pre code.hljs {
  background: transparent;
  color: #24292f;
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
.thinking-text .hljs-title { color: #8250df; }
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
.thinking-text .hljs-meta { color: #8250df; }
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
.thinking-text .hljs-section { color: #1677ff; font-weight: 600; }

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
  line-height: 1.7;
  text-align: left;
}
.code-message li p,
.thinking-text li p {
  margin: 0;
  display: inline;
}
.code-message p,
.thinking-text p {
  margin: 6px 0;
  line-height: 1.7;
  text-align: left;
}
.code-message h1,
.code-message h2,
.code-message h3,
.code-message h4,
.thinking-text h1,
.thinking-text h2,
.thinking-text h3,
.thinking-text h4 {
  margin: 14px 0 6px;
  color: #1a202c;
  text-align: left;
}
.code-message blockquote,
.thinking-text blockquote {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid #d0d7de;
  color: #57606a;
  background: #f6f8fa;
}
.code-message table,
.thinking-text table {
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 13px;
}
.code-message th,
.code-message td,
.thinking-text th,
.thinking-text td {
  border: 1px solid #d0d7de;
  padding: 6px 10px;
  text-align: left;
}
.code-message th,
.thinking-text th {
  background: #f6f8fa;
  font-weight: 600;
}

</style>
