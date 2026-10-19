<!--
  应用入口（v2 示例装配）
  ----------------------------------------------------------------------------
  本文件演示「AppShell + ChatView」如何组装，以及所有数据接缝的位置。
  对接真实后端时：
    1) 删除下方 DEMO_* 常量，改为调用接口（api/chat.ts、api/conversation.ts …）；
    2) 保留事件处理函数签名，内部替换为真实请求；
    3) 流式消息通过 SSE 增量更新 messages 中最后一条 assistant 的 content/contentHtml。
  样式引入：把 assets/tokens.css 与 assets/app.css 拷贝到 src/styles/ 后，
           在 main.ts 里 `import './styles/tokens.css'; import './styles/app.css'`。
-->
<template>
  <!--
    Electron 窗口拖拽条
    ----------------------------------------------------------------------------
    必须放在登录页/主界面的分支**之外**：登录页（/login）是独立全屏布局，不套
    AppShell，若只把拖拽条写进 AppShell，用户在登录页就拖不动窗口（桌面壳是
    `titleBarStyle: 'hidden'` 的无边框窗口，没有原生标题栏）。
    浏览器环境 isElectron 为 false，这个 div 不会渲染。
  -->
  <div v-if="isElectron" class="cc-titlebar" />

  <!--
    登录页：独立全屏，不套 AppShell。
    登录页没有侧栏 / 会话列表 / 状态栏，套进去会漏出「当前用户」「退出」这类还不存在的东西。
  -->
  <router-view v-if="isLoginPage" />

  <template v-else>
  <AppShell
    :active-key="activeKey"
    :groups="conversationGroups"
    :active-conv-id="activeConversationId"
    :user-name="userName"
    :user-avatar="userAvatar"
    :is-admin="isAdmin"
    :agents="agentPickerItems"
    :current-agent-id="currentAgentId"
    @select-menu="onSelectMenu"
    @select-conv="onSelectConversation"
    @rename-conv="onConversationRename"
    @export-conv="onConversationExport"
    @delete-conv="onConversationDelete"
    @new-chat="onNewChat"
    @switch-agent="onSwitchAgent"
    @open-profile="onOpenProfile"
    @logout="onLogout"
    @switch-version="onSwitchVersion"
  >
    <!--
      页面容器（L5：v-if 链 → <router-view>）
      ----------------------------------------------------------------------------
      1) 页面组件由路由懒加载提供，App.vue 不再静态 import 任何 views，
         构建期那 16 条 INEFFECTIVE_DYNAMIC_IMPORT 警告随之消失；
      2) 每页的 props / 事件绑定集中在 script 内的 PAGE_BINDINGS，此处只做透传；
      3) 页面随路由真实挂载 / 卸载，因此「进入页面才拉数据」下沉为各 View 的
         onMounted 调用父级注入的 props.load（见 PAGE_BINDINGS 中的 load 字段）。
    -->
    <router-view v-slot="{ Component }">
      <component
        :is="Component"
        v-if="Component"
        ref="chatViewRef"
        v-bind="pageView.props"
        v-on="pageView.on"
      >
        <!--
          文件 / Git / 终端 抽屉内容（仅聊天页使用）
          三个组件都以「当前 Agent 工作目录」为根，切换 Agent 时由 chatProjectRoot 驱动重载
        -->
        <template #drawer="{ tab }">
          <ProjectFileTree v-if="tab === 'files'" :root="chatProjectRoot" />
          <GitChangePanel v-else-if="tab === 'git'" :root="chatProjectRoot" />
          <TerminalPanel v-else :root="chatProjectRoot" />
        </template>
      </component>
    </router-view>

  </AppShell>

  <!-- 聊天页附属浮层（Teleport 到 body / 隐藏 input，放在 AppShell 之外避免破坏 v-if 链） -->
  <ChatModelPicker
    v-model:open="chatModelPickerOpen"
    :providers="chatProviderOptions"
    :provider-code="chatProviderCode"
    :models="availableModels"
    :model="chatModel"
    :thinking-mode="chatThinkingMode"
    :execution-mode="chatExecutionMode"
    :context-mode="chatContextMode"
    @apply="onChatModelApply"
  />
  <input
    ref="chatFileInput"
    type="file"
    multiple
    style="display: none"
    @change="onChatFileChange"
  />
  <!-- 改动回滚面板：由概览条「N 个文件改动」或状态栏「改动/回滚」打开，回滚成功后刷新统计 -->
  <ChangeReview
    v-model:open="chatChangesOpen"
    :session-id="chatSessionId"
    :turn-hints="chatSnapshotHints"
    :project-root="chatProjectRoot"
    @changed="loadChatChanges()"
  />

  <!-- 历史文件选择器：从聊天输入区的「附件 → 用历史文件」打开，选中后并入附件区 -->
  <FilePickerDialog
    v-model:open="chatFilePickerOpen"
    :attached-ids="chatAttachedIds"
    @pick="onFilePickerPick"
  />

  </template>

  <!--
    全局反馈层：Toast / Confirm / Prompt / 图片灯箱均 Teleport 到 body，不参与 AppShell 的栅格布局。
    放在 v-if / v-else 之外：登录页也要用（登录失败提示、退出确认框）。
  -->
  <ToastHost />
  <ConfirmHost />
  <PromptHost />
  <!-- F14：全站图片放大（调用方只需 import { openLightbox }） -->
  <LightboxHost />
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, isRef, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from './layouts/AppShell.vue'
import ToastHost from './components/ToastHost.vue'
import ConfirmHost from './components/ConfirmHost.vue'
import { toast } from './utils/toast'
import { confirm, confirmDelete } from './utils/confirm'
import { prompt } from './utils/prompt'
import PromptHost from './components/PromptHost.vue'
import LightboxHost from './components/LightboxHost.vue'
import { useUserStore } from './store/user'
import { useSettingsStore } from './store/settings'
import { useFileRefStore } from './store/file-ref'
import { clearSession } from './utils/session'
import { switchToLegacy } from './utils/ui-version'
import { logout as apiLogout } from './api/user'
import type { ApprovalPayload, ConversationDetail } from './views/ChatView.vue'
import ChatModelPicker from './components/ChatModelPicker.vue'
import FilePickerDialog from './components/FilePickerDialog.vue'
import type { AgentPickerItem } from './components/ChatAgentPicker.vue'
/* 聊天页右侧抽屉三件套：文件树 / Git 变更 / 终端（均以当前 Agent 工作目录为根） */
import ProjectFileTree from './components/ProjectFileTree.vue'
import GitChangePanel from './components/GitChangePanel.vue'
/*
 * 终端面板按需加载：它引入了 xterm.js（约 300KB），而终端只是抽屉里的一个 tab。
 * 静态 import 会让 xterm 进入主 chunk、拖慢首屏；改成异步组件后只在第一次点「终端」时才加载。
 */
const TerminalPanel = defineAsyncComponent(() => import('./components/TerminalPanel.vue'))
/* 聊天页改动回滚面板（快照 / 逐文件 / 全量回滚） */
import ChangeReview from './components/ChangeReview.vue'
import { executeRollback, getSessionChanges, listSnapshots, previewRollback } from './api/snapshot'
import type { SessionChanges } from './api/snapshot'
/* 上下文模式的镜像持久化（sys_config.context_mode，后端作为请求未带该参数时的兜底默认值） */
import { setConfig } from './api/config'
import type { AgentItem } from './components/AgentCard.vue'
import type { SkillItem } from './components/SkillRow.vue'
import type { McpServerItem } from './components/McpRow.vue'
import type { ScheduleTaskItem } from './components/ScheduleTaskRow.vue'
import type { LessonItem } from './components/LessonRow.vue'
import type { LessonClusterItem } from './views/LessonListView.vue'
import type { DbConnectionItem, DbTestState } from './components/DbConnectionRow.vue'
import type { SqlExecuteResult } from './components/DbExecuteDrawer.vue'
import type { FileItem } from './components/FileRow.vue'
import type { CloudFileListModel } from './components/CloudFilesDrawer.vue'
import type { P2pPeer } from './components/P2pPeerRow.vue'
import type { AgentAuthItem } from './components/P2pPeerDrawer.vue'
import type { P2pStatus, P2pAddressInfo, P2pSignalingResult } from './components/P2pSelfDrawer.vue'
import type { ProviderItem } from './components/ProviderRow.vue'
import type { ProviderBalanceModel, ProviderSavePayload } from './components/ProviderEditDrawer.vue'
import type { LogLineItem } from './components/LogRow.vue'
import type { LogSearchQuery } from './views/LogListView.vue'
import type { DataImportResult } from './utils/data-transfer'
import type { UserItem } from './components/UserRow.vue'
import type { UserSavePayload } from './components/UserEditDrawer.vue'
import type { MenuItem as PermMenuItem } from './components/MenuRow.vue'
import type { MenuSavePayload } from './components/MenuEditDrawer.vue'
import type { RoleItem, RoleSavePayload } from './components/RoleEditDrawer.vue'
import type { ThemeMode } from './views/AppearanceView.vue'
import { DATA_CATEGORIES, backupFileName, parseBackupJson } from './utils/data-transfer'
import { hasCharacterProfile } from './utils/character-profile'
import type { ChatMessageModel } from './components/ChatMessage.vue'
/* 任务清单类型与统计口径统一收敛在 utils/task-list（F12：补优先级 / 依赖 / 阻塞） */
import type { TaskItem } from './utils/task-list'
import type { Attachment } from './components/ChatComposer.vue'
import { groupConversationsByTime } from './menu.config'
/* ---- L1 数据层：真实接口（Providers 页已切到真实数据，其余页面仍为 DEMO） ---- */
import {
  listProviders,
  createProvider,
  updateProvider,
  deleteProvider,
  getProviderBalance,
  parseModelList
} from './api/llm-provider'
/* ---- L4 逐页接线：运行日志（只读，无需管理员） ---- */
import { getLogDates, searchLogs, tailLogs, exportLogFile } from './api/logs'
/* ---- 外观设置：背景图上传（唯一走后端的设置项，其余存 localStorage） ---- */
import { uploadBackground } from './api/appearance'
/* ---- L4 逐页接线：智能体（CRUD） ---- */
import {
  listAgentConfigs,
  createAgentConfig,
  updateAgentConfig,
  updateAgentRuntime,
  deleteAgentConfig,
  setDefaultAgentConfig
} from './api/agent-config'
import type { AgentConfig } from './api/agent-config'
import type { AgentProviderOption } from './components/AgentEditDrawer.vue'
/* ---- P2 / F18：工具注册表（智能体「工具集」的真实数据源，替代写死的假 key） ---- */
import { listToolRegistry } from './api/tools'
import { parseToolNames, toToolGroups } from './utils/tool-catalog'
import type { ToolGroup } from './utils/tool-catalog'
/* ---- L4 逐页接线：聊天页（SSE 流式 + 审批回执 + 会话管理） ---- */
import {
  streamChat,
  taskStream,
  checkActiveTask,
  cancelTask,
  uploadAttachment,
  optimizePrompt,
  supplementRequest
} from './api/chat'
import type { StreamChatEvent } from './api/chat'
import {
  getConversationList,
  getConversationMessages,
  getConversationMessagesPage,
  processMessageGroups,
  deleteConversation,
  deleteMessagesFromTurn,
  updateConversationName
} from './api/conversation'
import { submitAnswer } from './api/askUser'
/* uploadFileAsset 已由「文件资产」段统一导入（图片粘贴复用同一条通道） */
import {
  toChatMessages,
  parseTaskManagerOutput,
  toApprovalPayload,
  abbreviate
} from './utils/chat-mapper'
import type { AskUserEventData } from './utils/chat-mapper'
import type { ProcessItem, ToolCallItem } from './components/ChatMessage.vue'
import { renderMarkdown, renderMarkdownLight } from './utils/markdown'
/* 工具输出截断：保头也保尾（尾部带 [TOOL_RESULT] 增删行数与 ---TASK_JSON--- 任务清单） */
import { clipToolDetail } from './utils/tool-render'
import { estimateTokenCount } from './utils/tokenCalculator'
/* ---- F05 子Agent面板：fork 记录读库 + 委托调用心跳 ---- */
import { listSubAgents } from './api/sub-agent'
import {
  mayForkSubAgent,
  mergeDelegateCall,
  toDelegateCall,
  toSubAgentRuns,
  type DelegateCall,
  type SubAgentRun
} from './utils/agent-event'
/* ---- L4 逐页接线：技能（按 userId 隔离的 CRUD） ---- */
import { listSkills, createSkill, updateSkill, deleteSkillApi } from './api/skill'
import type { SkillData } from './api/skill'
/* ---- L4 逐页接线：MCP 服务器（配置 + 实时连接状态） ---- */
import {
  listMcpServers,
  createMcpServer,
  updateMcpServer,
  deleteMcpServer,
  connectMcpServer,
  disconnectMcpServer,
  refreshMcpServer
} from './api/mcp'
import type { McpServerConfig, McpServerVO } from './api/mcp'
/* ---- L4 逐页接线：踩坑经验（列表 + 反馈 + 归并） ---- */
import {
  pageLessons,
  listLessonClusters,
  updateLesson,
  feedbackLesson,
  deleteLesson,
  mergeLessonGroup
} from './api/lesson'
import type { LessonCluster, LessonData } from './api/lesson'
/* ---- L4 逐页接线：数据库连接（CRUD + 测试连接 + 执行 SQL） ---- */
import {
  listDbConnections,
  createDbConnection,
  updateDbConnection,
  deleteDbConnection,
  testDbConnection,
  testSavedDbConnection,
  executeDbConnectionSql
} from './api/db-connection'
import type { DbConnectionConfig, DbConnectionVO } from './api/db-connection'
/* ---- L4 逐页接线：文件资产（分页列表 + 上传 + 重命名 + 删除 + 云端清理） ---- */
import {
  listFileAssetsPaged,
  uploadFileAsset,
  renameFileAsset,
  deleteFileAsset,
  listCloudFiles,
  deleteCloudFile,
  getFilePreviewBlobUrl,
  invalidatePreviewCache,
  formatAssetSize,
  fileAssetDisplayName,
  isImageMime,
  docTypeOf,
  docTypeIcon
} from './api/file-asset'
import type { CloudFileListData, FileAssetData } from './api/file-asset'
/* ---- 身份预热：技能页依赖 userId，首屏直达时身份可能还在异步拉取中 ---- */
import { ensureIdentity, refreshProfile } from './utils/session'
/* ---- L4 逐页接线：定时任务（CRUD + 启用/禁用） ---- */
import {
  getTaskList,
  createTask,
  updateTask,
  deleteTask,
  enableTask,
  disableTask,
  runTaskNow
} from './api/schedule-task'
import type { ScheduleTaskItem as ApiScheduleTask } from './api/schedule-task'
/* ---- L4 逐页接线：设备互联（状态/地址/二维码/节点/授权） ---- */
import {
  getP2pStatus,
  getP2pAddress,
  getP2pName,
  setP2pName,
  generateQrCode,
  parseQrCode,
  connectPeer,
  disconnectPeer,
  deletePeer,
  reconnectPeer,
  getPeers,
  setRemark,
  grantAgentAuth,
  cancelAgentAuth,
  getMyAuthToPeer,
  getPeerAuthToMe
} from './api/p2p'
/* ---- L4 逐页接线：数据管理（统计 / 导出 / 导入 / 重置） ---- */
import { fetchDataStats, exportSystemData, importSystemData, resetAllData } from './api/system'
/* F06：桌面端「重置所有数据」后顺带清理升级备份 / 已下载安装包 / 检测缓存（浏览器环境返回 null，无副作用） */
import { clearLocalAppData } from './utils/update'
/* ---- L4 逐页接线：用户管理（分页列表 + CRUD） ---- */
import { getUserList, createUser, updateUser, deleteUser } from './api/user-admin'
import type { UserItem as ApiUserItem } from './api/user-admin'
/* ---- L4 逐页接线：菜单权限（菜单 + 角色 + 角色菜单） ---- */
import {
  getMenuTree,
  createMenu,
  updateMenu,
  deleteMenu,
  getRoleList,
  getRoleMenuIds,
  assignMenusToRole,
  createRole,
  updateRole
} from './api/menu'

/* ============================ 通用工具 ============================ */
/**
 * 从调用异常中读取可读错误消息
 *
 * <p>`request()` 内部已把后端 `message` / Spring 错误页压成中文，因此直接取 `err.message` 即可；
 * 其余类型回落到各调用点给定的兜底文案。</p>
 */
function readErrorMessage(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message) return err.message
  if (typeof err === 'string' && err) return err
  return fallback
}

/**
 * 本地时间格式化（`yyyy-MM-dd HH:mm:ss`）
 * ----------------------------------------------------------------------------
 * 原定义在「DEMO：定时任务」段里，L4 第四批把该段换成真实接口后仍需保留：
 * `daysAgoStr()` 以及聊天页的 DEMO 时间都依赖它。
 */
function toLocalStr(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:00`
}

/** 相对今天 N 天的本地时间串（仅 DEMO 数据用；真实数据的时间由后端返回） */
function daysAgoStr(days: number): string {
  return toLocalStr(new Date(Date.now() - days * 86_400_000))
}

/**
 * 后端时间串 → `YYYY-MM-DD HH:mm:ss`
 * ----------------------------------------------------------------------------
 * 后端实体用 `LocalDateTime`，Jackson 序列化为 ISO-8601（`2026-10-17T10:59:45.462346`），
 * 而列表行组件（DbConnectionRow / FileRow）的 `formatTime` 走 `new Date(v.replace(/-/g, '/'))`
 * —— 斜杠换成 `/` 后 ISO 的 `T` 仍在，部分浏览器会解析失败并原样显示长串。
 * 这里统一压成人类可读的本地时间，组件只需按普通日期解析。
 */
function normalizeTimeText(value?: string | null): string | undefined {
  if (!value) return undefined
  const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})/.exec(value)
  if (m) return `${m[1]}-${m[2]}-${m[3]} ${m[4]}:${m[5]}:${m[6]}`
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/* ============================ 全局 UI 状态 ============================ */
const route = useRoute()
const router = useRouter()

/**
 * 当前页面 = 当前路由 path（L0 装配时打通）
 * ----------------------------------------------------------------------------
 * 骨架原本是 `const activeKey = ref('/chat')` + `v-if` 切页面，地址栏完全不参与，导致：
 *   · 刷新后永远回到聊天页，无法保持当前位置；
 *   · 无法用 URL 直达某个管理页（/logs、/users …），router/index.ts 的路由表形同虚设；
 *   · 侧栏「更多」里的一级导航与浏览器前进/后退失效。
 * 改成与 route.path 双向绑定后，`activeKey.value = '/logs'` 等价于 `router.push('/logs')`，
 * 因此 App.vue 内原有的赋值写法（onSelectMenu / onSettingsNavigate / onAgentSelect …）无需改动。
 */
const activeKey = computed({
  get: () => route.path,
  set: (path: string) => {
    if (path !== route.path) void router.push(path)
  }
})

/**
 * 当前是否停在登录页。
 *
 * <p>登录页要脱离 AppShell（无侧栏 / 会话列表 / 状态栏 / 用户菜单），
 * 模板据此在两套布局之间切换。用判等而非前缀匹配：/login 是唯一入口，没有子路由。</p>
 */
const isLoginPage = computed(() => route.path === '/login')

/**
 * 是否运行在 Electron 桌面壳里（`preload.js` 注入的 `window.electronAPI`）。
 *
 * <p>模板据此渲染窗口拖拽条 `.cc-titlebar`。桌面壳是 `titleBarStyle: 'hidden'`
 * 的无边框窗口：没有拖拽条就拖不动窗口，且右上角内容会被原生窗口按钮压住。
 * 拖拽条放在登录页/主界面分支之外，两套布局都能拖。</p>
 *
 * <p>`<html>` 上的 `cc-electron` 类由 index.html 的早期脚本先挂一次（避免首帧闪动），
 * 这里只做渲染判定，不重复操作 DOM。</p>
 */
const isElectron = typeof window !== 'undefined' && Boolean((window as any).electronAPI)
/* ============================ 身份（L3 路由权限） ============================ */
/**
 * 全局用户状态：userInfo 由 `utils/session.ts` 的 `ensureIdentity()` 拉取
 * （`GET /api/user/current`）并持久化，因此刷新后侧栏权限不会闪变。
 */
const userStore = useUserStore()

/**
 * 全局外观状态（主题 / 背景图 / 透明度 / 模糊）
 * ----------------------------------------------------------------------------
 * 由 `store/settings.ts` 统一持有并持久化到 localStorage；
 * 只有背景图文件本身走后端（`POST /api/appearance/background`），其余设置不入库
 * （与旧前端 `frontend/src/store/settings.ts` 一致）。
 * `layouts/AppShell.vue` 直接读同一个 store 应用主题与背景，因此这里改设置界面立刻生效。
 */
const settings = useSettingsStore()

/**
 * 跨页文件引用（文件管理页「引用到聊天」→ 聊天附件区）。
 * 轻量、无持久化：只在一次页面跳转之间传递，取走即清空。
 */
const fileRefStore = useFileRefStore()

/** 是否管理员：侧栏菜单过滤与设置页入口共用，与路由守卫 meta.adminOnly 同源 */
const isAdmin = computed(() => userStore.isAdmin)

/** 用户名：真实身份优先；后端不可用时回落演示名，避免顶部信息空白 */
const userName = computed(
  () => userStore.userInfo?.nickname || userStore.userInfo?.username || '程序员小王'
)

/**
 * 当前用户头像（图片 URL / emoji / 单字）。
 *
 * <p>数据来源：`ensureIdentity()` 在拿到 `/user/current` 后会补一次 `/user/profile`，
 * 把 avatar / nickname 一并写进 pinia（已持久化，刷新不闪）。空串时由组件用昵称首字兜底。</p>
 */
const userAvatar = computed(() => userStore.userInfo?.avatar || '')

/**
 * 当前登录用户 id
 * ----------------------------------------------------------------------------
 * `GET /api/skills` 的 `userId` 是**必填**参数（后端按 user_id 隔离技能），
 * 且 `PUT/DELETE /skills/{id}` 会校验技能归属，传错会抛 SecurityException。
 * 身份由 `utils/session.ts` 的 `ensureIdentity()`（`GET /api/user/current`）写入 store，
 * 隐形登录默认账号为 admin（userId=1）。
 */
const currentUserId = computed(() =>
  Number(userStore.userInfo?.id ?? userStore.userInfo?.userId ?? 0)
)

/* ============================ 聊天页：真实接口 ============================ */
/**
 * 数据接缝（`/api/conversation` + `/api/deepseek`）：
 *   GET    /conversation/list?agentConfigId=      → 会话列表（侧栏分组）
 *   GET    /conversation/{id}/messages/page       → 按轮次分页的历史消息（游标 beforeId）
 *   DELETE /conversation/{id}                     → 删除会话（连带消息）
 *   POST   /deepseek/chat/stream                  → SSE 流式对话（content/thinking/tool_call_start/ask_user/task_error）
 *   POST   /deepseek/answer                       → ask_user 回执（approve / approve_all / reject / custom）
 *   GET    /deepseek/task/{id}                    → 后台任务状态（刷新页面后重连）
 *   GET    /deepseek/task/{id}/stream?cursor=     → 后台任务增量事件流
 *   POST   /deepseek/task/{id}/cancel             → 取消后台任务
 *   POST   /deepseek/upload                       → 附件暂存（LLM 用 chat_attachment 工具读取）
 *
 * 与旧项目的差异（有意简化）：
 *  1) 只维护「当前会话」的消息数组，不做多会话并行流（旧项目 backgroundStreams）；
 *  2) 历史消息里的「文件改动统计」需要快照接口，本轮未接 → 概览条只显示工具调用与上下文；
 *  3) 会话重命名未做（旧项目走 Input 弹窗），仅提供删除。
 */
type ChatAttachment = Attachment & {
  /** /deepseek/upload 返回的附件 ID（文档走 chat_attachment 工具） */
  attachmentId?: string
  /** /files 上传后的资产 ID（图片走 vision 内容块） */
  fileAssetId?: number
  uploading?: boolean
}

/** 历史消息分页轮数（一轮 = 一次提问 + 该轮全部回复） */
const CHAT_PAGE_ROUNDS = 5

/**
 * 当前页面组件实例（动态组件 + 同名 ref）
 * ----------------------------------------------------------------------------
 * 只有聊天页真正实现 getDraft / setDraft（见 ChatView 的 defineExpose），
 * 其余页面拿到的是自身实例，调用处一律用可选链兜底。
 */
const chatViewRef = ref<{
  getDraft?: () => string
  setDraft?: (value: string) => void
} | null>(null)
const chatFileInput = ref<HTMLInputElement | null>(null)

/** 侧栏会话列表（真实数据） */
const conversationList = ref<
  Array<{
    id: string
    title: string
    updatedAt: number
    running?: boolean
    pending?: boolean
    /** 该会话所属 Agent（后端 Conversation.agentConfigId）：历史消息头据此还原 Agent 名与头像 */
    agentConfigId?: number | null
  }>
>([])
const conversationLoading = ref(false)
const conversationError = ref('')
/**
 * 会话 → 所属 Agent 的登记表（跨 Agent 保留）。
 *
 * <p>侧栏列表只装「当前 Agent」的会话，切换 Agent 后旧会话就不在列表里了。
 * 但多任务并行时，任务浮层要展示别处（可能属于另一个 Agent）的进度，
 * 点进去还得先切回那个 Agent —— 所以归属关系单独记一份，不随列表清空。</p>
 */
const conversationAgentMap = ref<Record<string, number>>({})
/**
 * 会话标题登记表（跨 Agent 保留）。
 * 切到别的 Agent 后旧会话不在列表里，但任务浮层、后台完成提示仍要显示它的名字。
 */
const conversationTitleMap = ref<Record<string, string>>({})

/**
 * 侧栏分组：在真实会话列表上叠加「运行中 / 等待确认 / 任务进度」三个实时标记。
 *
 * <p>running 表示该会话的 SSE 流正在本页后台消费；pending 表示它在等 ask_user 回执；
 * taskDone/taskTotal 是它的 task_manager 清单进度。三者都由下面的分片状态派生，
 * 不写进 conversationList（避免刷新列表时被覆盖）。</p>
 *
 * <p>多任务并行时，用户在当前会话里看不到后台会话的任务进度，侧栏这个
 * `3/5` 徽章是唯一的全局视图（点开任务浮层还能看到分组明细）。</p>
 */
const conversationGroups = computed(() =>
  groupConversationsByTime(
    conversationList.value.map(item => {
      const list = taskMap.value[item.id] ?? []
      return {
        ...item,
        running: !!streamingMap.value[item.id],
        pending: !!pendingMap.value[item.id],
        taskDone: list.filter(t => t.status === 'done').length,
        taskTotal: list.length
      }
    })
  )
)

const activeConversationId = ref('')

/* ------------------------------------------------------------------ *
 * 多会话并行流（C 档）
 * ------------------------------------------------------------------
 * 早期实现是单例：messages / isStreaming / approval / chatAbort 各只有一份，
 * 切会话必须先 abort 前端流，否则两条会话的增量会写进同一个数组。
 *
 * 现在按「会话 key」分片存放，切走**不再 abort**：后台继续消费 SSE，
 * 切回来直接看到已经长出来的内容（与旧项目 backgroundStreams 同思路）。
 *
 * 会话 key 有两种形态：
 *   1) 已落库会话 → `String(conversationId)`，如 "2"；
 *   2) 新会话（后端尚未在首个 SSE 事件返回 id）→ `draft-<时间戳>`，
 *      首事件带回真实 id 后整体迁移（见 migrateConversationKey）。
 */
/** 新会话草稿 key 前缀 */
const DRAFT_PREFIX = 'draft-'

/** 每个会话的消息数组（key 见上） */
const messagesMap = ref<Record<string, ChatMessageModel[]>>({})
/** 当前会话的消息（模板与 ChatView 只关心这个派生值） */
const messages = computed<ChatMessageModel[]>(() => messagesMap.value[activeConversationId.value] ?? [])

/** 取某个会话的消息数组（不存在则建空数组，便于直接 push） */
function convMessages(key: string): ChatMessageModel[] {
  if (!messagesMap.value[key]) messagesMap.value[key] = []
  return messagesMap.value[key]
}

/** 会话显示名（用于后台完成 / 等待确认的提示文案） */
function conversationTitleOf(key: string): string {
  return conversationList.value.find(c => c.id === key)?.title || conversationTitleMap.value[key] || '新会话'
}

/**
 * 某个会话所属 Agent 的名称/头像。
 *
 * <p>消息表里不存「这条回复来自哪个 Agent」，只能按会话的 `agentConfigId` 在 Agent 列表里反查。
 * 不直接用「当前选中的 Agent」：多会话并行后，后台会话可能属于另一个 Agent，用当前 Agent
 * 兜底会把别人的名字/头像挂到它的回复上。</p>
 *
 * <p>反查不到（Agent 列表还没加载完 / 会话指向已删除的 Agent）时才回落到当前 Agent，
 * 最终由 ChatMessage 组件用「编码助手」兜底。</p>
 */
function agentMetaOf(agentConfigId?: number | null): { name?: string; avatar?: string } {
  const id = agentConfigId ? Number(agentConfigId) : currentAgentId.value
  const found = id ? agentList.value.find(a => Number(a.id) === id) : undefined
  return {
    name: found?.name || currentAgent.value?.name,
    avatar: found?.avatar || currentAgent.value?.avatar
  }
}

/** 写某个会话的流式状态 */
function setStreaming(key: string, value: boolean) {
  if (!key) return
  streamingMap.value = { ...streamingMap.value, [key]: value }
}

/** 写某个会话的审批卡片（同时维护「等待确认」标记与 uuid） */
function setApproval(key: string, payload: ApprovalPayload | null) {
  if (!key) return
  approvalMap.value = { ...approvalMap.value, [key]: payload }
  pendingMap.value = { ...pendingMap.value, [key]: !!payload }
  if (!payload) pendingUuidMap.delete(key)
}

/** 覆盖某个会话的消息数组（重新生成 / 全量刷新用） */
function setConvMessages(key: string, list: ChatMessageModel[]) {
  messagesMap.value[key] = list
}

/** 删除某个会话的全部分片状态（会话被删 / 草稿废弃时调用） */
function dropConversationState(key: string) {
  if (!key) return
  const dropKey = <T>(map: Record<string, T>) => {
    if (!(key in map)) return
    const next = { ...map }
    delete next[key]
    return next
  }
  const m1 = dropKey(messagesMap.value)
  if (m1) messagesMap.value = m1 as Record<string, ChatMessageModel[]>
  const m2 = dropKey(streamingMap.value)
  if (m2) streamingMap.value = m2
  const m3 = dropKey(pendingMap.value)
  if (m3) pendingMap.value = m3
  const m4 = dropKey(approvalMap.value)
  if (m4) approvalMap.value = m4
  const m5 = dropKey(contextTokensMap.value)
  if (m5) contextTokensMap.value = m5
  const m6 = dropKey(changesMap.value)
  if (m6) changesMap.value = m6
  const m7 = dropKey(supplementMap.value)
  if (m7) supplementMap.value = m7
  const m8 = dropKey(taskMap.value)
  if (m8) taskMap.value = m8
  const m9 = dropKey(subAgentMap.value)
  if (m9) subAgentMap.value = m9
  const m10 = dropKey(delegateMap.value)
  if (m10) delegateMap.value = m10
  streamAborts.delete(key)
  pendingUuidMap.delete(key)
  // 会话都没了，针对它的子Agent轮询也必须停（否则定时器会一直空转）
  stopSubAgentPolling(key)
}

/**
 * 会话 key 迁移：新会话拿到真实 id 后，把草稿 key 下的一切搬到 `String(id)`。
 *
 * <p>必须整体迁移而不是「继续用草稿 key」：切走再切回时用户点的是侧栏的真实会话 id，
 * 消息、审批卡片、改动统计都要能从那个 key 取到，否则后台跑完的内容看起来凭空消失。</p>
 */
function migrateConversationKey(from: string, to: string) {
  if (!from || !to || from === to) return
  const move = <T>(map: Record<string, T>): Record<string, T> | null => {
    if (!(from in map)) return null
    const next = { ...map }
    next[to] = next[from]
    delete next[from]
    return next
  }
  const m1 = move(messagesMap.value)
  if (m1) messagesMap.value = m1 as Record<string, ChatMessageModel[]>
  const m2 = move(streamingMap.value)
  if (m2) streamingMap.value = m2
  const m3 = move(pendingMap.value)
  if (m3) pendingMap.value = m3
  const m4 = move(approvalMap.value)
  if (m4) approvalMap.value = m4
  const m5 = move(contextTokensMap.value)
  if (m5) contextTokensMap.value = m5
  const m6 = move(changesMap.value)
  if (m6) changesMap.value = m6
  const m7 = move(supplementMap.value)
  if (m7) supplementMap.value = m7
  const m8 = move(taskMap.value)
  if (m8) taskMap.value = m8
  const m9 = move(subAgentMap.value)
  if (m9) subAgentMap.value = m9
  const m10 = move(delegateMap.value)
  if (m10) delegateMap.value = m10
  // 轮询定时器跟着会话 key 迁移，否则草稿 key 下的定时器会失去归属
  if (subAgentTimers.has(from)) {
    window.clearInterval(subAgentTimers.get(from)!)
    subAgentTimers.delete(from)
    startSubAgentPolling(to)
  }
  const abort = streamAborts.get(from)
  if (abort) {
    streamAborts.delete(from)
    streamAborts.set(to, abort)
  }
  const uuid = pendingUuidMap.get(from)
  if (uuid) {
    pendingUuidMap.delete(from)
    pendingUuidMap.set(to, uuid)
  }
  // 用户正在看这个会话：把「当前会话」也一起指过去，否则输入框与消息区会突然空掉
  if (activeConversationId.value === from) activeConversationId.value = to
}
/**
 * 任务清单按会话隔离（key = 会话 id 字符串）。
 *
 * <p>原先是全局单 ref：切到另一个会话时会看到上一个会话的任务进度，
 * 刷新页面后清单直接丢（任务清单只存在于工具输出里，没有独立的持久化表）。</p>
 */
const taskMap = ref<Record<string, TaskItem[]>>({})
/** 当前会话的任务清单（模板与 ChatView 只关心这个派生值） */
const tasks = computed<TaskItem[]>(() => {
  const key = activeConversationId.value
  return key ? taskMap.value[key] ?? [] : []
})

/* ============================ F05 子Agent面板 ============================ */
/**
 * 子Agent 数据源说明（很重要，决定了为什么这里是「读库 + 轮询」而不是「收事件」）：
 *
 * <p>后端 {@code AgentEventBus} 只发 ask_user / tool_call_start / task_error，
 * {@code AgentInvokeServiceImpl} 另发 delegate_progress（跨智能体委托），
 * 而 {@code AgentForkManager}（fork 子Agent）<b>从不发任何 SSE 事件</b> ——
 * 它把子Agent结果以纯文本写进主Agent思考流（「📋 主Agent已完成，正在等待 N 个子Agent结果…」）。
 * 所以 sse-client 里那条 `agent_*` 解析分支永远收不到数据，旧版 AgentPanel 一直是空面板。</p>
 *
 * <p>真实数据在 {@code sub_agent_log} 表：fork 时 INSERT（status=running），
 * 结束时 UPDATE（completed/failed/timeout + 摘要 + 文件变更）。前端按会话 key 分片缓存，
 * 流式期间轮询 3s 一次看状态流转，切回会话时拉一次覆盖历史回放。</p>
 */
const subAgentMap = ref<Record<string, SubAgentRun[]>>({})
/** 当前会话的子Agent列表 */
const chatSubAgents = computed<SubAgentRun[]>(() => subAgentMap.value[activeConversationId.value] ?? [])

/**
 * 委托调用（`delegate_progress` 心跳）。
 *
 * <p>不落库、不持久化：每次心跳刷新已等待秒数，超过 {@link DELEGATE_STALE_MS} 视为已结束。
 * 刷新页面后靠新的心跳重建，历史委托不再回显（后端也没有对应的查询接口）。</p>
 */
const delegateMap = ref<Record<string, DelegateCall[]>>({})
const chatDelegates = computed<DelegateCall[]>(() => delegateMap.value[activeConversationId.value] ?? [])

/** 子Agent轮询定时器（会话 key → timer）。只要有 running 记录就保持轮询，全部终态自动停 */
const subAgentTimers = new Map<string, number>()
/** 轮询次数计数：防止后端残留 running 记录导致无限轮询 */
const subAgentPollCount = new Map<string, number>()
const SUB_AGENT_POLL_MS = 3000
const SUB_AGENT_MAX_POLLS = 200

function stopSubAgentPolling(key: string) {
  const timer = subAgentTimers.get(key)
  if (timer !== undefined) {
    window.clearInterval(timer)
    subAgentTimers.delete(key)
  }
  subAgentPollCount.delete(key)
}

function startSubAgentPolling(key: string) {
  if (subAgentTimers.has(key)) return
  subAgentPollCount.set(key, 0)
  const timer = window.setInterval(() => {
    const count = (subAgentPollCount.get(key) ?? 0) + 1
    subAgentPollCount.set(key, count)
    if (count > SUB_AGENT_MAX_POLLS) {
      stopSubAgentPolling(key)
      return
    }
    void loadSubAgents(key)
  }, SUB_AGENT_POLL_MS)
  subAgentTimers.set(key, timer)
}

/**
 * 拉取某个会话的子Agent列表。
 *
 * <p>失败静默：子Agent面板是辅助信息，拉不到就保持上一次结果，
 * 不该为此弹 toast 打断正在进行的对话。</p>
 */
async function loadSubAgents(key: string = activeConversationId.value) {
  const sessionId = keyToSessionId(key)
  if (!sessionId) return
  try {
    const res = await listSubAgents(sessionId)
    const runs = toSubAgentRuns(res.data ?? [])
    subAgentMap.value = { ...subAgentMap.value, [key]: runs }
    if (runs.some(run => run.status === 'running')) startSubAgentPolling(key)
    else stopSubAgentPolling(key)
  } catch {
    /* 静默：见方法注释 */
  }
}

/** 记录一条委托进度心跳（同一 callId 只保留最新一条） */
function noteDelegateProgress(key: string, data: { callId?: string; targetSessionId?: number; targetAgent?: string; status?: string; elapsedSec?: number }) {
  if (!key) return
  const call = toDelegateCall(data)
  if (!call) return
  const current = delegateMap.value[key] ?? []
  delegateMap.value = { ...delegateMap.value, [key]: mergeDelegateCall(current, call) }
}
/** 各会话的待审批卡片（按会话 key 分片，切走再切回不会丢） */
const approvalMap = ref<Record<string, ApprovalPayload | null>>({})
/** 当前会话的审批卡片 */
const approval = computed<ApprovalPayload | null>(() => approvalMap.value[activeConversationId.value] ?? null)
const attachments = ref<ChatAttachment[]>([])
/** 历史文件选择器开关（附件 → 用历史文件） */
const chatFilePickerOpen = ref(false)
/** 附件区已有的文件资产 id：选择器里置灰去重，避免同一个文件挂两遍 */
const chatAttachedIds = computed(() => attachments.value.filter(a => a.fileAssetId).map(a => a.fileAssetId!))

/** 哪些会话正在流式生成（后台流也计入：侧栏圆点与 composer 状态都读它） */
const streamingMap = ref<Record<string, boolean>>({})
/** 当前会话是否在生成中 */
const isStreaming = computed(() => !!streamingMap.value[activeConversationId.value])
/** 哪些会话在等 ask_user 回执（侧栏黄色感叹号） */
const pendingMap = ref<Record<string, boolean>>({})
/**
 * 运行中的 SSE 中断器（按会话 key）。
 *
 * <p>用普通 Map 而非 ref：AbortController 不参与渲染，塞进响应式代理反而会因为
 * 深度代理导致 `abort()` 内部 this 指向代理对象。</p>
 */
const streamAborts = new Map<string, AbortController>()

const chatLoading = ref(false)
const chatError = ref('')
/**
 * 当前会话的真实 id（新会话草稿态为 null）。
 * 由 activeConversationId 派生：草稿 key 不是数字，取不到 id。
 */
const chatSessionId = computed<number | null>(() => keyToSessionId(activeConversationId.value))

/** 会话 key → 落库 id（草稿 key 返回 null） */
function keyToSessionId(key: string): number | null {
  const id = Number(key)
  return Number.isFinite(id) && id > 0 ? id : null
}

/**
 * 会话改动统计（GET /api/snapshots/session-changes）。
 * 用于两处：概览条的「N 个文件改动 +A -D」，以及改动回滚面板的初始数据。
 */
const changesMap = ref<Record<string, SessionChanges | null>>({})
const chatSessionChanges = computed<SessionChanges | null>(
  () => changesMap.value[activeConversationId.value] ?? null
)
/** 改动回滚面板显隐 */
const chatChangesOpen = ref(false)
/**
 * 会话 → 可回滚的快照轮次数（运行状态栏「改动/回滚」按钮的徽章数据源）。
 *
 * <p>为什么需要它：回滚按钮挂在「本轮产生过快照」的 AI 消息上，而最近几轮常常没有文件改动，
 * 于是页面上一个按钮都看不到，用户会以为功能没做。这个计数让状态栏能如实说明
 * 「本会话历史上有 N 轮改动可回滚」，把入口暴露在常驻位置上。</p>
 */
const snapshotCountMap = ref<Record<string, number>>({})
const chatSnapshotCount = computed<number>(
  () => snapshotCountMap.value[activeConversationId.value] ?? 0
)
/**
 * 会话 → { turnId: 该轮用户提问摘要 }。
 *
 * <p>「改动与回滚」面板的按快照列表用它标注每一轮聊的是什么 —— 只有时间戳和文件数
 * 根本认不出是哪一轮。摘要来自已加载进内存的消息，更早的轮次翻页后才补齐（缺失时显示占位文案）。</p>
 */
const snapshotHintMap = ref<Record<string, Record<string, string>>>({})
const chatSnapshotHints = computed<Record<string, string>>(
  () => snapshotHintMap.value[activeConversationId.value] ?? {}
)
/**
 * 「补充需求」发送状态（ChatView 只负责展示，真实请求与结果都在这里）。
 * done 会让 ChatView 清空并收起输入条；error 保留输入内容，方便直接重试。
 * 按会话分片：后台会话的 error 不该污染当前会话的输入条。
 */
const supplementMap = ref<Record<string, 'idle' | 'sending' | 'done' | 'error'>>({})
const chatSupplementState = computed<'idle' | 'sending' | 'done' | 'error'>(
  () => supplementMap.value[activeConversationId.value] ?? 'idle'
)
/** 写某个会话的补充需求状态（默认当前会话） */
function setSupplementState(value: 'idle' | 'sending' | 'done' | 'error', key = activeConversationId.value) {
  if (!key) return
  supplementMap.value = { ...supplementMap.value, [key]: value }
}

/**
 * 后端遗留「运行中」标记的会话（僵尸任务）。
 *
 * <p>后端因重启 / 进程异常退出可能残留 `status=running` 但早已没有事件的 task 记录。
 * 前端一旦据此判定 running，就会把输入框永久锁死（用户看到的是「聊天框坏了」）。
 * 这里登记被判定为遗留的会话 key：这些会话不锁输入，状态栏给一条「清理标记」的出口。</p>
 */
const staleTaskKeys = ref<Record<string, boolean>>({})
/** 当前会话是否为遗留运行标记 */
const staleTask = computed(() => !!staleTaskKeys.value[activeConversationId.value])

/** 历史消息分页游标：本页最早一条消息 id */
const chatCursor = ref<number | null>(null)
const chatHasMore = ref(false)
const chatLoadingMore = ref(false)

/**
 * 待回执的 ask_user uuid（按会话 key）。
 * 用普通 Map：只在提交回执时读取，不需要驱动渲染。
 */
const pendingUuidMap = new Map<string, string>()

/** 当前智能体 id：决定 systemPrompt / 工具集 / 工作目录 / 模型 */
const currentAgentId = ref(0)
/** 模型与运行模式（可从「模型与运行模式」弹层临时切换，并持久化到 Agent） */
const chatModel = ref('')
const chatThinkingMode = ref('')
const chatExecutionMode = ref('auto')
const chatModelPickerOpen = ref(false)

/** 当前智能体（未加载时退回默认 / 第一个） */
const currentAgent = computed<AgentRow | undefined>(() => {
  const list = agentList.value
  if (!list.length) return undefined
  return (
    list.find(a => Number(a.id) === currentAgentId.value) ??
    list.find(a => a.isDefault) ??
    list[0]
  )
})

/** 当前智能体名（审批卡片「来自 xx」与消息头展示） */
function chatAgentName(): string {
  return currentAgent.value?.name || '编码助手'
}

/**
 * 消息里本地路径的解析基准：当前 Agent 的工作目录。
 * 相对路径（如 `src/utils/markdown.ts`）经后端 `/project/resolve` 按后缀匹配，
 * 命中后与这个根目录拼成绝对路径再读取。
 */
const chatProjectRoot = computed(() => currentAgent.value?.workDir || '')

/** 当前 Agent 头像：聊天页助手消息的兜底（消息自身记录 agentAvatar 时优先用它） */
const agentAvatar = computed(() => currentAgent.value?.avatar || '')

/** 除当前会话外，还有几个会话在后台跑流（状态栏「N 个会话后台运行中」） */
const backgroundCount = computed(() => {
  const current = activeConversationId.value
  return Object.keys(streamingMap.value).filter(key => streamingMap.value[key] && key !== current).length
})

/**
 * 其他会话的任务清单摘要（多任务并行时的任务面板分组）。
 *
 * <p>为什么单独派生：任务浮层原本只渲染 `tasks`（当前会话），并行跑多个会话时
 * 用户看不到别处的进度，只能一个个点进去翻。这里把「有清单且不是当前会话」的会话
 * 汇总成 `{ 标题, 已完成/总数, 是否仍在跑 }`，浮层里以只读分组展示，点击可跳过去。</p>
 *
 * <p>排序：仍在跑的排前面，其次按会话 id 倒序（越新的越靠前）。</p>
 */
const backgroundTasks = computed<
  Array<{ key: string; title: string; done: number; total: number; running: boolean }>
>(() => {
  const current = activeConversationId.value
  return Object.keys(taskMap.value)
    .filter(key => key !== current && (taskMap.value[key]?.length ?? 0) > 0)
    .map(key => {
      const list = taskMap.value[key]
      const conv = conversationList.value.find(c => c.id === key)
      return {
        key,
        title: conv?.title || conversationTitleMap.value[key] || `会话 #${key}`,
        done: list.filter(t => t.status === 'done').length,
        total: list.length,
        running: !!streamingMap.value[key]
      }
    })
    .sort((a, b) => {
      if (a.running !== b.running) return a.running ? -1 : 1
      return Number(b.key) - Number(a.key)
    })
})

/**
 * 切到后台运行中的会话（状态栏提示的点击行为）。
 * 取「最近开始的那一个」而不是随便一个：多个后台会话时，用户更可能想看刚发起的那条。
 */
function onSwitchBackground() {
  const current = activeConversationId.value
  const keys = Object.keys(streamingMap.value).filter(key => streamingMap.value[key] && key !== current)
  if (!keys.length) return
  const target = keys.sort((a, b) => Number(b) - Number(a))[0]
  void onSelectConversation(target)
}

/**
 * 切到指定的「其他会话」（任务浮层里的其他会话分组）。
 * 与 onSwitchBackground 的区别：那个只能挑最近一个在跑的，这个由用户点名，
 * 目标可能已经跑完（只是任务清单还在），所以不校验 streamingMap。
 *
 * <p>多智能体并行时，目标会话可能属于另一个 Agent —— 那种情况下侧栏列表里根本没有它，
 * 直接 onSelectConversation 会加载失败。所以先按登记的归属切 Agent（会重拉列表），
 * 再选中会话。</p>
 */
async function onSwitchBackgroundTask(key: string) {
  if (!key || key === activeConversationId.value) return
  const ownerAgent = conversationAgentMap.value[key]
  if (ownerAgent && ownerAgent !== currentAgentId.value) {
    currentAgentId.value = ownerAgent
    syncChatRuntime()
    conversationList.value = []
    await loadConversations()
    toast.info(`已切换到「${currentAgent.value?.name || `#${ownerAgent}`}」`)
  }
  await onSelectConversation(key)
}

/**
 * 子Agent面板里点「前往会话」：切到被委托智能体的会话。
 *
 * <p>目标会话可能根本不在当前智能体的会话列表里（委托给另一个 Agent 时），
 * 所以先刷新列表再定位；仍然找不到就明确提示，而不是静默失败让用户以为是没反应。</p>
 */
async function onOpenSubagentSession(sessionId: number) {
  const key = String(sessionId)
  if (!key || key === activeConversationId.value) return
  if (!conversationList.value.some(item => item.id === key)) {
    await loadConversations()
    if (!conversationList.value.some(item => item.id === key)) {
      toast.warning('该会话不在当前智能体的会话列表里', {
        description: '可在侧栏切换到发起委托的那个智能体后再查看'
      })
      return
    }
  }
  await onSelectConversation(key)
}

/** 可选模型：各启用 Provider 的 modelList 合集（去重，保持 Provider 顺序） */
const availableModels = computed<string[]>(() => {
  const seen = new Set<string>()
  const list: string[] = []
  for (const provider of providerList.value) {
    for (const model of provider.parsedModelList ?? []) {
      if (!model || seen.has(model)) continue
      seen.add(model)
      list.push(model)
    }
  }
  return list
})

/** token 数格式化：1.2K / 1.2M */
function formatTokens(count: number): string {
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1)}M`
  if (count >= 1000) return `${(count / 1000).toFixed(1)}K`
  return String(count)
}

/**
 * 用户在「模型与运行模式」弹层里临时切换的 Provider。
 *
 * <p>与旧版 `CodeAssistantView.onProviderSwitch` 的语义一致：切换后写回 Agent 运行时
 * （`PUT /agent-configs/{id}/runtime { providerCode }`），所以正常情况下这个 ref 会一直是空串，
 * Provider 由 Agent 的 `providerCode` 决定。</p>
 *
 * <p>为什么还要保留它：写回是异步的，且 `loadAgents()` 重新拉列表也可能失败
 * （后端不可用、权限不足）。此时用本 ref 兜住用户的选择，保证「本次会话立刻按新 Provider 发请求」，
 * 避免出现「弹层里选了 A、实际仍在用 B」的错觉。</p>
 */
const chatProviderOverride = ref('')

/**
 * 当前会话使用的 Provider code。
 *
 * <p>优先级：本次临时切换 > Agent 显式指定的 providerCode > isDefault 项 > 列表第一项。
 * 后三档与后端 `LLMClientManager` 的选型兜底顺序一致 —— 空串在这里会被解析成一个具体 code，
 * 但真正发给后端时仍按「是否与 Agent 绑定值相同」决定传不传（见 onSend），
 * 以免把「跟随默认」这一语义固化成某个具体 Provider。</p>
 */
const chatProviderCode = computed(() => {
  if (chatProviderOverride.value) return chatProviderOverride.value
  const explicit = currentAgent.value?.providerCode || ''
  if (explicit) return explicit
  const fallback = providerList.value.find(p => p.isDefault === 1) ?? providerList.value[0]
  return fallback?.code || ''
})

/**
 * 上下文模式（全量 / 精简）。
 *
 * <p>唯一真相源是 `settingsStore.contextMode`（pinia persist → localStorage），
 * 同时镜像一份到后端 `sys_config.context_mode`：后端 `DeepSeekServiceImpl` 的选择顺序是
 * 「请求参数 > sys_config > 默认 full」，请求里已显式带上，sys_config 只是
 * 给「绕过前端直连接口」的场景兜底（对齐旧版 `saveContextModeToServer`）。</p>
 */
const chatContextMode = computed(() => settings.contextMode || 'full')

/** 聊天页余额文案（空串表示不显示：未查、查询失败或不支持余额查询） */
const chatBalanceText = ref('')
/** 已查询过余额的 Provider code，避免每次进聊天页都重复请求 */
const chatBalanceCode = ref('')

/**
 * 上下文 Token 估算值（输入区下方「上下文 xx」）。
 *
 * <p>为什么不用 computed 直接算：流式期间 messages 每帧都在变，而估算要遍历
 * 全部消息 + 思考全文 + 工具返回（一个长会话可达数百万字符），每帧重算会明显掉帧。
 * 改为「时机驱动」——消息加载完 / 一轮结束 / 重新生成后各算一次。</p>
 *
 * <p>口径与旧项目 `totalContextTokens` 对齐：**正文 + 思考过程 + 工具返回** 全部计入。
 * 早期 v3 只拼了 `content`，导致一个上千条消息、617 次工具调用的会话只显示 7.3K，
 * 而真正的上下文大头恰恰是思考与工具输出。</p>
 *
 * <p>局限：仍是前端字符估算（非后端真实 prompt_tokens，后端未透传 usage），
 * 且只覆盖「已加载的消息」（分页每次 5 轮），因此 UI 上标注为「已载入」。</p>
 */
const contextTokensMap = ref<Record<string, number>>({})
/** 当前会话的上下文估算值 */
const chatContextTokens = computed(() => contextTokensMap.value[activeConversationId.value] ?? 0)

/**
 * 重新估算某个会话的上下文 Token（正文 + 思考 + 工具明细）。
 *
 * <p>口径随上下文模式变化（对齐后端 `ContextBuilder.compactHistoryMessages` 的实际行为）：</p>
 * <ul>
 *   <li><b>full</b>：全部消息的正文 + 思考 + 工具返回都计入；</li>
 *   <li><b>compact</b>：只有「本轮」（最后一条 user 消息之后）完整计入，
 *       更早轮次移除思考过程、工具调用降级为约 30 token 的摘要，正文保留。</li>
 * </ul>
 *
 * <p>与旧项目 `totalContextTokens` 的差别：旧版对非本轮 assistant 粗按 `tokenCount * 0.2` 打折，
 * 因为它的思考与正文混在一个字段里没法拆分；v3 的 ChatMessageModel 把思考（`process`）
 * 与工具（`tools`）外置了，可以直接按「保留正文 / 丢弃思考 / 工具算摘要」精确估算。</p>
 *
 * @param key 会话 key，默认当前会话（后台流收尾时传它自己的 key）
 */
function refreshContextTokens(key = activeConversationId.value) {
  if (!key) return
  const list = messagesMap.value[key] ?? []
  const compact = chatContextMode.value === 'compact'
  // compact 模式「本轮」起点：最后一条 user 消息的下标（找不到就全按历史轮次处理）
  let lastUserIdx = -1
  if (compact) {
    for (let i = list.length - 1; i >= 0; i--) {
      if (list[i].role === 'user') {
        lastUserIdx = i
        break
      }
    }
  }
  let total = 0
  for (let i = 0; i < list.length; i++) {
    const msg = list[i]
    total += estimateTokenCount(msg.content || '')
    // 本轮或全量模式：思考与工具明细都进上下文
    if (!compact || i >= lastUserIdx) {
      for (const part of msg.process ?? []) total += estimateTokenCount(part.text || '')
      for (const tool of msg.tools ?? []) total += estimateTokenCount(tool.detail || tool.summary || '')
    } else {
      // 历史轮次：思考被移除，工具只留一条摘要（后端按 tool 消息整条降级）
      total += (msg.tools?.length ?? 0) * 30
    }
  }
  contextTokensMap.value = { ...contextTokensMap.value, [key]: total }
}

/** 币种符号：后端返回 CNY/USD 之类的 ISO code */
function currencySymbol(currency?: string): string {
  const code = (currency || '').toUpperCase()
  if (!code) return '¥'
  if (code === 'CNY' || code === 'RMB') return '¥'
  if (code === 'USD') return '$'
  return `${code} `
}

/**
 * 把 ProviderBalanceVO 压成一行小字（输入区右下角只放得下一个短语）。
 *
 * <p>金额类取 totalBalance（火山另提供 cashBalance），额度类取剩余百分比或剩余次数，
 * 都取不到就返回空串让模板隐藏，绝不显示「余额 --」这种占位噪音。</p>
 */
function formatBalanceText(model: ProviderBalanceModel): string {
  if (!model.isAvailable) return ''
  const info = model.balanceInfos?.[0]
  if (!info) return ''
  if (model.balanceType === 'quota') {
    if (typeof info.remainingPercent === 'number') return `剩余 ${Math.round(info.remainingPercent)}%`
    if (typeof info.remainingCount === 'number' && typeof info.totalCount === 'number') {
      return `剩余 ${info.remainingCount}/${info.totalCount}`
    }
    return ''
  }
  const amount = info.totalBalance ?? info.cashBalance
  if (!amount) return ''
  return `${currencySymbol(info.currency)}${amount}`
}

/**
 * 静默查询当前 Provider 余额。
 *
 * <p>聊天页的余额只是辅助信息，失败一律吞掉（不弹 toast、不写错误条）：
 * 否则后端未配 AK/SK 的 Provider 会让每次进聊天页都蹦一个错误提示。</p>
 */
async function loadChatBalance(force = false) {
  const code = chatProviderCode.value
  if (!code) return
  if (!force && chatBalanceCode.value === code && chatBalanceText.value) return
  try {
    const res = await getProviderBalance(code)
    const data = res.data
    chatBalanceCode.value = code
    chatBalanceText.value = data
      ? formatBalanceText({
          providerCode: data.providerCode || code,
          balanceType:
            data.balanceType === 'balance' || data.balanceType === 'quota' ? data.balanceType : undefined,
          isAvailable: data.isAvailable !== false,
          balanceInfos: (data.balanceInfos || []).map(info => ({ ...info }))
        })
      : ''
  } catch {
    // 静默失败：标记已尝试，避免同一 Provider 反复请求
    chatBalanceCode.value = code
    chatBalanceText.value = ''
  }
}

/** 会话详情：全部由当前状态派生（模板只读） */
const conversation = computed<ConversationDetail>(() => {
  const item = conversationList.value.find(c => c.id === activeConversationId.value)
  const toolCalls = messages.value.reduce((sum, msg) => sum + (msg.tools?.length ?? 0), 0)
  const changeStat = chatSessionChanges.value
  return {
    id: activeConversationId.value,
    title: item?.title || '新会话',
    model: chatModel.value || availableModels.value[0] || '',
    executionMode: chatExecutionMode.value === 'manual' ? '手动确认' : '自动执行',
    // 上下文模式：ChatComposer 底部据此给「⚡ 精简」徽章（compact 时上下文口径也随之变化）
    contextMode: chatContextMode.value,
    // 余额来自 GET /llm-providers/{code}/balance，静默查询，查不到就不显示（见 loadChatBalance）
    balance: chatBalanceText.value || undefined,
    updatedAt: item ? new Date(item.updatedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '',
    stats: messages.value.length
      ? {
          toolCalls,
          // 没有改动时不传字段，概览条整段隐藏（显示「0 个文件改动 +0 -0」只是噪音）
          changedFiles: changeStat && changeStat.totalFiles > 0 ? changeStat.totalFiles : undefined,
          added: changeStat && changeStat.totalFiles > 0 ? changeStat.totalLinesAdded : undefined,
          deleted: changeStat && changeStat.totalFiles > 0 ? changeStat.totalLinesDeleted : undefined,
          // 上下文 = 正文 + 思考 + 工具返回（见 refreshContextTokens）；
          // 早期只拼 content，会让重工具会话严重低估（实测 7.3K vs 实际十余万）
          contextTokens: formatTokens(chatContextTokens.value)
        }
      : undefined
  }
})

/* ============================ 聊天页：会话数据（真实接口） ============================ */
/**
 * 写入某个会话的任务清单。
 * @param key 会话 key，默认当前会话 —— 后台流必须显式传自己的 key，
 *            否则会把别的会话的任务进度写到用户正在看的这一条上。
 */
function setSessionTasks(list: TaskItem[], key = activeConversationId.value) {
  if (!key) return
  taskMap.value = { ...taskMap.value, [key]: list }
}

/** 清掉某个会话的任务清单（默认当前会话） */
function clearSessionTasks(key?: string) {
  const target = key ?? activeConversationId.value
  if (!target || !taskMap.value[target]) return
  const next = { ...taskMap.value }
  delete next[target]
  taskMap.value = next
}

/**
 * 历史回放：从已加载的消息里回溯最近一次 task_manager 清单。
 *
 * <p>任务清单没有独立落库，只存在于工具输出的 `---TASK_JSON---` 段落里，
 * 所以刷新/切回会话只能从消息内容还原。倒序找最近一条能解析出清单的消息，
 * 找到就停 —— 更早的清单已被后续调用覆盖，回放它只会显示过时进度。</p>
 *
 * <p>优先取 `msg.taskSnapshot`：由 chat-mapper 用**未截断**的工具输出原文解析得来。
 * 下面的兜底通道只扫正文字符串，而工具卡片 detail 被截断到 4000 字符，长输出会把清单切掉，
 * 实测会出现「只捞到几百轮之前的旧清单」或干脆空面板。</p>
 */
function restoreTasksFromMessages(conversationId: number, key = String(conversationId)) {
  const target = key || String(conversationId)
  if (taskMap.value[target]?.length) return
  const list = messagesMap.value[target] ?? []
  for (let i = list.length - 1; i >= 0; i -= 1) {
    const msg = list[i]
    if (msg.role !== 'assistant') continue
    // 主通道：mapper 阶段解析好的快照（未截断原文 → 不会因超长丢 JSON）
    if (msg.taskSnapshot?.length) {
      taskMap.value = { ...taskMap.value, [target]: msg.taskSnapshot.map(t => ({ ...t })) }
      return
    }
    // 兜底通道：老数据/流式期间未写快照时，扫正文与工具摘要
    const sources: string[] = [msg.content || '']
    for (const part of msg.process ?? []) sources.push(part.text)
    for (const tool of msg.tools ?? []) sources.push(tool.detail || '')
    const parsed = parseTaskManagerOutput(sources.join('\n'))
    if (parsed) {
      taskMap.value = { ...taskMap.value, [target]: parsed }
      return
    }
  }
}

/** 历史消息游标分页：滚动到顶部时加载更早的 5 轮 */
async function loadConversationMessages(conversationId: string, before?: number | null) {
  const convId = Number(conversationId)
  if (!convId || Number.isNaN(convId)) return
  const key = String(conversationId)
  chatLoading.value = true
  chatError.value = ''
  try {
    const res = await getConversationMessagesPage(convId, before ?? null, CHAT_PAGE_ROUNDS)
    const page = res.data
    if (!page) return
    // Agent 身份按「会话所属 Agent」反查：否则刷新/切回会话后消息头会从「圆圆」退化成「编码助手」
    const convAgentId = conversationList.value.find(c => c.id === key)?.agentConfigId ?? null
    const mapped = toChatMessages(page.messages || [], chatModel.value, agentMetaOf(convAgentId))
    if (before) {
      const previous = messagesMap.value[key] ?? []
      const existing = new Set(previous.map(m => m.id))
      setConvMessages(key, [...mapped.filter(m => !existing.has(m.id)), ...previous])
    } else {
      setConvMessages(key, mapped)
    }
    chatHasMore.value = !!page.hasMore
    chatCursor.value = page.nextBeforeId ?? null
    // 任务清单没有独立存储：从刚加载的消息里回溯最近一次 task_manager 输出
    restoreTasksFromMessages(convId, key)
    // 上下文估算随消息变化，加载完重算一次（正文 + 思考 + 工具返回）
    refreshContextTokens(key)
    // 历史消息里的「回滚改动」按钮要靠快照列表点亮（异步，不阻塞消息渲染）
    void matchSnapshotsForMessages(convId, key)
    // 子Agent（F05）：历史会话也要能看到 fork 出来的子Agent，读库一次即可
    void loadSubAgents(key)
  } catch (err) {
    chatError.value = readErrorMessage(err, '聊天记录加载失败')
  } finally {
    chatLoading.value = false
  }
}

/**
 * 侧栏会话列表。
 * 并发调用复用同一次请求：原先「加载中直接 return」会让首轮结束后触发的那次刷新被丢弃，
 * 表现为「刚发出的新会话在侧栏消失」。
 */
let conversationsInFlight: Promise<void> | null = null
async function loadConversations() {
  if (conversationsInFlight) return conversationsInFlight
  conversationsInFlight = (async () => {
    conversationLoading.value = true
    conversationError.value = ''
    try {
      const res = await getConversationList(undefined, currentAgentId.value || undefined)
      conversationList.value = (res.data ?? []).map(item => ({
        id: String(item.id),
        title: item.name,
        updatedAt: new Date(item.updatedAt).getTime() || Date.now(),
        agentConfigId: item.agentConfigId ?? null
      }))
      // 登记归属与标题：切到别的 Agent 后这些会话不在当前列表里，任务浮层跳转仍要知道它属于谁
      for (const item of conversationList.value) {
        if (item.agentConfigId) conversationAgentMap.value[item.id] = item.agentConfigId
        conversationTitleMap.value[item.id] = item.title
      }
      // 首次进入没有选中会话时，自动打开最新一个，避免右侧空白
      if (!activeConversationId.value && conversationList.value.length) {
        void onSelectConversation(conversationList.value[0].id)
      }
    } catch (err) {
      conversationError.value = readErrorMessage(err, '会话列表加载失败')
    } finally {
      conversationLoading.value = false
      conversationsInFlight = null
    }
  })()
  return conversationsInFlight
}

/* 任务清单 / 审批 / 附件 / 流式状态均已在上面的「聊天页：真实接口」段声明 */

/* ============================ 智能体管理页：真实接口 ============================ */
/**
 * 数据接缝（`/api/agent-configs`）：
 *   GET    /list?userId=   → AgentConfig[]（userId 可选，为空返回全部）
 *   POST   /create         → AgentConfig
 *   PUT    /{id}           → AgentConfig
 *   PUT    /{id}/default   → AgentConfig（设为默认，独立接口）
 *   DELETE /{id}
 *
 * 字段差异（前端 AgentItem ↔ 后端 AgentConfig）：
 *   model      ← modelName（后端没有 model 字段）
 *   toolsCount ← toolNames 逗号串拆分后的数量；toolNames 为空串 = 「全部工具」
 *   isDefault / isBuiltin 后端是 0|1，前端收成 boolean
 *
 * 抽屉回填需要 systemPrompt / thinkingMode / temperature / tools，AgentItem 没有这些字段，
 * 因此用 AgentRow 扩展一层（结构上是 AgentItem 的子类型，传给子组件无需断言）。
 */
type AgentRow = AgentItem & {
  systemPrompt?: string
  thinkingMode?: string
  temperature?: number
  tools?: string[]
  /** 角色性格 JSON 字符串（原样保留，抽屉内解析） */
  characterProfile?: string
}

const agentList = ref<AgentRow[]>([])
const agentLoading = ref(false)

/* ---- 工具注册表（F18）：智能体抽屉「工具集」的真实选项 ---- */
/** 工具分组；空数组时抽屉回退内置快照（见 utils/tool-catalog） */
const toolGroups = ref<ToolGroup[]>([])
const toolGroupsLoading = ref(false)
const toolGroupsError = ref('')

/**
 * 拉取工具注册表
 *
 * <p>只在智能体页首次进入时拉一次：注册表随后端版本变化，不需要每次进页都刷新。
 * 失败不弹 toast —— 抽屉里有内置快照兜底，静默降级比打断用户更合适。</p>
 */
async function loadToolGroups() {
  if (toolGroupsLoading.value) return
  toolGroupsLoading.value = true
  toolGroupsError.value = ''
  try {
    const res = await listToolRegistry()
    toolGroups.value = toToolGroups(res.data)
    if (!toolGroups.value.length) toolGroupsError.value = '工具注册表为空'
  } catch (err) {
    toolGroupsError.value = readErrorMessage(err, '工具注册表加载失败')
  } finally {
    toolGroupsLoading.value = false
  }
}

/** 后端实体 → 卡片行模型 */
function toAgentRow(cfg: AgentConfig): AgentRow {
  // tool_names 是 JSON 数组字符串（历史数据可能是逗号串）；空值 = 全部工具
  const names = parseToolNames(cfg.toolNames)
  return {
    id: cfg.id as number,
    name: cfg.name,
    description: cfg.description,
    avatar: cfg.avatar,
    model: cfg.modelName || '',
    providerCode: cfg.providerCode || '',
    executionMode: cfg.executionMode,
    toolsCount: names.length,
    workDir: cfg.workDir,
    isDefault: cfg.isDefault === 1,
    isBuiltin: cfg.isBuiltin === 1,
    hasCharacter: hasCharacterProfile(cfg.characterProfile),
    systemPrompt: cfg.systemPrompt,
    thinkingMode: cfg.thinkingMode,
    temperature: cfg.temperature,
    tools: names,
    characterProfile: cfg.characterProfile
  }
}

/** 拉取 Agent 列表（首屏 / 增删改后复用） */
async function loadAgents() {
  agentLoading.value = true
  try {
    const res = await listAgentConfigs()
    agentList.value = (res.data || []).map(toAgentRow)
    // 聊天页依赖「当前 Agent」：未选中或选中项已被删除时，回落到默认 Agent / 第一个
    if (!currentAgentId.value || !agentList.value.some(a => Number(a.id) === currentAgentId.value)) {
      const fallback = agentList.value.find(a => a.isDefault) ?? agentList.value[0]
      currentAgentId.value = fallback ? Number(fallback.id) : 0
    }
    syncChatRuntime()
  } catch (err) {
    agentList.value = []
    toast.error('加载智能体失败', { description: readErrorMessage(err, '请检查后端是否已启动') })
  } finally {
    agentLoading.value = false
  }
}
/* ============================ 技能管理页：真实接口 ============================ */
/** 相对时间/天数换算基准（定时任务页与经验库时间展示复用） */
const DAY = 86_400_000

/** 列表行 = SkillItem（展示字段）+ 抽屉编辑所需的隐藏字段 */
type SkillRow = SkillItem & { instructions?: string; tools?: string[] }

const skillList = ref<SkillRow[]>([])
const skillLoading = ref(false)
const skillError = ref('')

/** 作用域下拉选项：由 Agent 列表派生 */
const agentOptions = computed(() => agentList.value.map(a => ({ id: Number(a.id), name: a.name })))

/** 安全解析 JSON 数组字符串（后端 tool_names / trigger_words 均按 JSON 数组存储） */
function parseJsonArray(raw?: string | null): string[] {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.map(String).filter(Boolean) : []
  } catch {
    // 兜底：早期数据可能是逗号分隔
    return String(raw).split(/[,，]/).map(s => s.trim()).filter(Boolean)
  }
}

/** 技能图标：后端不存图标字段，按名称关键词挑一个语义接近的 symbol */
function guessSkillIcon(name: string): string {
  const n = (name || '').toLowerCase()
  if (/样式|设计|配色|token|css|ui/.test(n)) return 'i-brush'
  if (/打包|构建|部署|jre|dmg|release/.test(n)) return 'i-term'
  if (/数据库|sql|索引|慢查询/.test(n)) return 'i-db'
  if (/测试|排查|bug|修复|异常/.test(n)) return 'i-bug'
  if (/文档|规范|说明/.test(n)) return 'i-book'
  return 'i-skill'
}

/** 后端 Skill → 列表行：解析 JSON 字符串字段 + 反查作用域 Agent 名 */
function toSkillRow(data: SkillData): SkillRow {
  const agentConfigId = data.agentConfigId ?? null
  return {
    id: data.id ?? 0,
    name: data.name,
    description: data.description,
    confidence: data.confidence ?? 0.5,
    usageCount: data.usageCount ?? 0,
    updatedAt: data.updatedAt,
    agentConfigId,
    agentName: agentConfigId
      ? agentList.value.find(a => Number(a.id) === agentConfigId)?.name
      : undefined,
    triggerWords: data.triggerWords,
    icon: guessSkillIcon(data.name),
    instructions: data.instructions,
    tools: parseJsonArray(data.toolNames)
  }
}

/**
 * 拉取技能列表
 * ----------------------------------------------------------------------------
 * `userId` 是后端必填参数（`GET /api/skills?userId=`），按 user_id 隔离技能；
 * 不传会直接返回 400（Spring 缺参），传错则只能看到别人的/空的列表。
 */
async function loadSkills() {
  // 身份是异步预热的（main.ts 里 `void ensureIdentity()`），首屏直达本页时可能还没就绪；
  // 这里补一次 await（内部有并发去重与失败冷却），否则会误报「未获取到身份」且不再重试。
  if (!currentUserId.value) await ensureIdentity()
  const uid = currentUserId.value
  if (!uid) {
    skillError.value = '未获取到当前用户身份，无法加载技能列表'
    return
  }
  skillLoading.value = true
  skillError.value = ''
  try {
    const list = (await listSkills(uid)).data || []
    // 作用域显示名依赖 Agent 列表，还没拉过就顺带补一次（失败不影响技能展示）
    if (!agentList.value.length && !agentLoading.value) void loadAgents()
    skillList.value = list.map(toSkillRow)
  } catch (err) {
    skillList.value = []
    skillError.value = readErrorMessage(err, '请确认后端服务已启动（端口 8084）')
  } finally {
    skillLoading.value = false
  }
}

/* ============================ 事件处理（对接后端时替换内部实现） ============================ */

function onSelectMenu(key: string) {
  // activeKey 已与 route.path 双向绑定，此处等价于 router.push(key)
  activeKey.value = key
}

/** 打开个人资料页（侧栏用户菜单的第一项） */
function onOpenProfile() {
  activeKey.value = '/profile'
}

/**
 * 退出登录：后端作废 token → 清本地会话 → 回登录页。
 *
 * <p>顺序不能反：必须先带着 token 调 {@code POST /user/logout}，再清本地 token。
 * 反过来后端收到的是「无 Authorization 的注销请求」，它对该分支直接返回成功，
 * 等于 token 没被作废（虽然前端已不可用，但服务端仍认为那次登录有效）。</p>
 *
 * <p>后端调不通也继续本地清理 —— 用户点了退出就必须退出去，
 * 不能因为服务异常把人卡在界面里（本地 token 已是唯一凭据）。</p>
 */
async function onLogout() {
  const ok = await confirm({
    title: '退出登录',
    content: '退出后需要重新输入用户名和密码才能继续使用。',
    okText: '退出登录',
    type: 'danger'
  })
  if (!ok) return

  try {
    await apiLogout()
  } catch {
    // 后端不可用 / token 已失效：忽略，本地照样清
  }

  clearSession()
  userStore.clearUserInfo()
  // 会话分片状态留在内存里没意义：下一个登录用户不该看到上一个人的对话与任务
  // （chatSupplementState 是按会话派生的 computed，不能直接赋值，这里清它的底层 map）
  supplementMap.value = {}
  chatError.value = ''
  toast.success('已退出登录')
  void router.replace('/login')
}

/**
 * 切换到经典版（v2 页面）。
 *
 * <p>两套前端产物同源部署：新版在 `/v3/`，经典版在 `/`。切换必须整页跳转
 * （两个独立 SPA，无法用前端路由互跳）。偏好写入 `cc.uiVersion='v2'`，
 * 经典版 `index.html` 的注入脚本读到它就不再把人弹回新版。</p>
 *
 * <p>登录态不用迁移：经典版读自己的 pinia `user` 键，新版从未写过它；
 * 而新版登录时用的 token 是经典版签发的那份（或独立登录得到的新 token），
 * 两者在同一个后端 TokenStore 里都有效。</p>
 */
async function onSwitchVersion() {
  const ok = await confirm({
    title: '切换到经典版',
    content:
      '将打开旧版界面（页面会整体刷新）。\n' +
      '两版共用同一份后端数据，登录状态、主题与背景图都会保留。\n' +
      '想换回来时，点旧版右下角的「体验新版」按钮即可。',
    okText: '切换到经典版'
  })
  if (!ok) return
  switchToLegacy()
}

/** 附件体积展示（KB / MB） */
function formatAttachmentSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

/**
 * 切换会话：加载历史消息，并检查是否有后台任务需要重连。
 *
 * <p>**不再 abort 前端流**：C 档下切走即转后台，原会话的 SSE 继续消费并写入它自己的
 * 消息分片，切回来直接看到已经长出来的内容（旧实现必须先 abort，否则两条会话的增量
 * 会写进同一个数组）。</p>
 */
async function onSelectConversation(id: string) {
  if (!id) return
  // 侧栏会话在任意页面都可点：不在聊天页时先跳回聊天页
  // （路由切换会触发旧页面卸载 / 聊天页挂载，随后再加载目标会话消息）
  if (route.path !== '/chat') {
    await router.push('/chat')
  }
  const previous = activeConversationId.value
  // 离开一个「没发过消息的空草稿」时顺手回收，避免反复切换堆一堆空数组
  if (previous && previous !== id && previous.startsWith(DRAFT_PREFIX) && !streamingMap.value[previous]) {
    dropConversationState(previous)
  }
  activeConversationId.value = id
  attachments.value = []
  chatError.value = ''
  chatChangesOpen.value = false
  // 任务清单按会话隔离：tasks 是 computed，跟着 activeConversationId 走
  chatCursor.value = null
  chatHasMore.value = false
  // 该会话正在本页后台跑流：内存里就是最新态，用 DB 覆盖反而会丢正在写的增量
  if (streamingMap.value[id] || streamAborts.has(id)) return
  await loadConversationMessages(id)
  void checkChatActiveTask(Number(id))
  // 改动统计与消息分开拉：快照接口慢且不影响首屏渲染
  void loadChatChanges(Number(id))
}

/**
 * 会话重命名：输入弹窗 → `PUT /api/conversation/{id}?name=`
 * 后端名称为空会被拒绝，前端先做空值/长度校验，避免一次无效请求。
 */
async function onConversationRename(id: string) {
  const item = conversationList.value.find(c => c.id === id)
  const current = item?.title || ''
  const name = await prompt({
    title: '重命名会话',
    label: '仅修改侧栏显示名称，不会改动聊天记录内容。',
    value: current,
    placeholder: '请输入会话名称',
    okText: '保存',
    validate: value => {
      if (!value) return '名称不能为空'
      if (value.length > 60) return '名称不能超过 60 个字符'
      return ''
    }
  })
  if (name === null || name === current) return
  try {
    await updateConversationName(Number(id), name)
    // 本地即时更新，避免整表重拉导致列表闪动
    if (item) item.title = name
    toast.success('会话已重命名')
  } catch (err) {
    toast.error('重命名失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

/** 会话「删除」：二次确认后删除会话及其全部聊天记录 */
async function onConversationDelete(id: string) {
  const item = conversationList.value.find(c => c.id === id)
  const ok = await confirmDelete('会话', item?.title || `#${id}`, {
    hint: '会话及其全部聊天记录将被删除，不可撤销。'
  })
  if (!ok) return
  try {
    await deleteConversation(Number(id))
    toast.success('会话已删除')
    // 该会话可能还在后台跑流：先掐掉再清分片状态，否则流会继续往已删除的数组里写
    const abort = streamAborts.get(id)
    if (abort) {
      abort.abort()
      streamAborts.delete(id)
    }
    dropConversationState(id)
    if (activeConversationId.value === id) onNewChat()
    await loadConversations()
  } catch (err) {
    toast.error('删除失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

/**
 * 拉取会话改动统计。
 *
 * <p>`getSessionChanges` 内部已 try/catch（失败返回 null），这里不需要再包一层错误处理；
 * 拿不到就当作「本会话没有改动记录」，概览条对应片段自然隐藏。</p>
 */
async function loadChatChanges(sessionId?: number | null, key?: string) {
  const sid = sessionId ?? chatSessionId.value
  const target = key ?? (sid ? String(sid) : activeConversationId.value)
  if (!sid) {
    if (target) changesMap.value = { ...changesMap.value, [target]: null }
    return
  }
  const data = await getSessionChanges(sid)
  changesMap.value = { ...changesMap.value, [target || String(sid)]: data }
}

/**
 * 用快照列表点亮「回滚改动」按钮（挂在每条 AI 消息的操作区上）。
 *
 * <p>数据接缝：`GET /api/snapshots/list?sessionId=` → `SnapshotSummary[]`（含 turnId / rolledBack）。</p>
 *
 * <p>为什么按 turnId 匹配：后端只给 **user** 消息落了 `turn_id`，assistant / tool 行为空；
 * 而按钮在 AI 消息上。chat-mapper 已让 assistant 继承前一条 user 的 turnId，
 * 这里两边对上即可一一对应（旧项目也是这个思路，只是按钮当时挂在 user 消息上）。</p>
 *
 * <p>两个保护：① 已回滚（rolledBack）的快照不再点亮，避免重复回滚；
 * ② 消息上已有 snapshotId 就不覆盖 —— 否则用户刚回滚完，下一次匹配又把按钮点亮。</p>
 */
async function matchSnapshotsForMessages(sessionId?: number | null, key?: string) {
  const sid = sessionId ?? chatSessionId.value
  if (!sid) return
  const target = key ?? String(sid)
  const snapshots = await listSnapshots(sid)

  /*
    先记「可回滚轮次数」，再判断是否要点亮按钮。
    顺序很关键：最近几轮往往没有文件改动（本轮没动代码），此时一条按钮都不该出现，
    但会话历史上可能仍有十几个快照可回滚 —— 计数如实上报，状态栏才有东西可提示。
  */
  snapshotCountMap.value = {
    ...snapshotCountMap.value,
    [target]: snapshots.filter(s => !s.rolledBack).length
  }

  if (!snapshots.length) return

  // turnId → snapshotId；同一轮若存在多个快照，取列表里靠后的（更新的）那个
  const byTurn = new Map<string, string>()
  for (const snap of snapshots) {
    if (!snap.turnId || snap.rolledBack) continue
    byTurn.set(snap.turnId, snap.snapshotId)
  }

  /*
    轮次提问摘要：快照只有 turnId / 时间 / 文件数，改动面板里根本认不出是哪一轮。
    把已加载消息里的用户提问按 turnId 归档传过去，面板即可显示「本轮聊的是什么」。
    只统计 role=user（assistant 也继承了 turnId，不能重复计入）。
  */
  const hints: Record<string, string> = { ...(snapshotHintMap.value[target] ?? {}) }
  for (const msg of messagesMap.value[target] ?? []) {
    if (msg.role !== 'user' || !msg.turnId || hints[msg.turnId]) continue
    const text = (msg.content || '').replace(/\s+/g, ' ').trim()
    if (text) hints[msg.turnId] = text.length > 40 ? `${text.slice(0, 40)}…` : text
  }
  snapshotHintMap.value = { ...snapshotHintMap.value, [target]: hints }

  if (!byTurn.size) return

  let changed = false
  for (const msg of messagesMap.value[target] ?? []) {
    if (msg.role !== 'assistant' || msg.snapshotId || !msg.turnId) continue
    const hit = byTurn.get(msg.turnId)
    if (!hit) continue
    msg.snapshotId = hit
    changed = true
  }
  // 按钮状态变了说明快照有增删，顺带刷新顶部「N 个文件改动」统计
  if (changed) void loadChatChanges(sid, target)
}

/**
 * 回滚某一轮的代码改动（AI 消息操作区的「回滚改动」）。
 *
 * <p>数据接缝：
 *   GET  /api/snapshots/preview?snapshotId=       本次要动哪些文件（restore / delete）
 *   POST /api/snapshots/rollback { snapshotId }   执行回滚</p>
 *
 * <p>破坏性操作，必须二次确认并列出文件清单（对齐旧项目 `handleRollback` 的交互）。
 * 成功后清掉 `msg.snapshotId` 让按钮消失 —— 同一轮重复回滚没有意义，还会覆盖用户后续改动。</p>
 */
async function onRollbackMessage(msg: ChatMessageModel) {
  const snapshotId = msg.snapshotId
  if (!snapshotId) return

  const previewToast = toast.loading('正在读取回滚预览…')
  const preview = await previewRollback(snapshotId)
  toast.dismiss(previewToast)
  if (!preview) {
    toast.error('无法获取回滚预览', { description: '快照可能已被清理，刷新会话后再试' })
    return
  }

  const files = preview.files || []
  // 清单只列前 12 个：确认弹窗里塞几十上百行没人看得完，剩下的用一行汇总
  // 注意 ConfirmHost 的 content 是 white-space: pre-line（保留换行、压缩连续空格），
  // 所以不要用空格做对齐，改成「动作 · 路径」的单行结构。
  const lines = files
    .slice(0, 12)
    .map(f => `${f.action === 'delete' ? '删除' : '恢复'} · ${f.relativePath}`)
  if (files.length > 12) lines.push(`…另有 ${files.length - 12} 个文件`)

  const ok = await confirm({
    title: '回滚这一轮的代码改动',
    content: files.length
      ? `将把 ${files.length} 个文件恢复到本轮修改前的状态：\n\n${lines.join('\n')}`
      : '该快照没有记录到文件，回滚不会有任何变化。',
    okText: '确认回滚',
    type: 'danger'
  })
  if (!ok) return

  const busyToast = toast.loading('正在回滚…')
  const success = await executeRollback(snapshotId)
  toast.dismiss(busyToast)
  if (!success) {
    toast.error('回滚失败', { description: '快照可能已失效，或目标文件被其他程序占用' })
    return
  }

  msg.snapshotId = undefined
  toast.success('已回滚本轮代码改动', {
    description: files.length ? `${files.length} 个文件已恢复` : '本轮没有文件变化'
  })
  if (chatSessionId.value) void loadChatChanges(chatSessionId.value)
}

/** 概览条 / 运行状态栏的「改动」点击：打开改动与回滚面板 */
function onChatOpenChanges() {
  if (!chatSessionId.value) {
    toast.info('当前会话还没有可回滚的改动')
    return
  }
  // 打开前刷新一次：面板本身也会拉，但这里先同步概览条徽章，避免显示过期数字
  void loadChatChanges()
  chatChangesOpen.value = true
}

/**
 * 生成过程中补充需求（POST /api/deepseek/supplement）。
 *
 * 语义：消息被塞进后端 SupplementStore 队列，正在跑的 ToolLoop 下一轮迭代自动取用 ——
 * 不中断当前任务、不新开一轮对话，所以前端**不插入任何消息**（正式消息由后端随后落库）。
 *
 * 后端只做软校验（拿不到 activeTask 也仅记 warn 仍入队），因此这里只挡住
 * 「会话还没建立（新会话首个事件尚未返回）」这一种前端可判定的情况。
 * 该接口不走统一的 ApiResponse 包装，成功与否只看返回体的 success 字段。
 */
async function onChatSupplement(text: string) {
  const key = activeConversationId.value
  const sid = keyToSessionId(key)
  if (!sid) {
    setSupplementState('error', key)
    toast.warning('会话还没建立，稍等片刻再补充需求')
    return
  }
  setSupplementState('sending', key)
  try {
    const res = await supplementRequest(sid, text)
    if (res?.success) {
      setSupplementState('done', key)
      toast.success('补充需求已发送', { description: 'AI 会在下一轮处理时收到' })
    } else {
      setSupplementState('error', key)
      toast.error(res?.error || '补充需求发送失败')
    }
  } catch (err: any) {
    setSupplementState('error', key)
    toast.error(readErrorMessage(err, '补充需求发送失败'))
  }
}

/**
 * 会话导出（Markdown）
 *
 * <p>走 `GET /api/conversation/{id}/messages`（全量，不是 /page 的最近 5 轮），
 * 再用与聊天页同一套 `processMessageGroups` 聚合轮次，保证导出段落结构与界面对得上。</p>
 *
 * <p>只导出正文与思考过程：工具调用原始返回体量极大（单个会话可上千条），
 * 塞进 Markdown 既不可读也会让浏览器卡住。</p>
 */
async function onConversationExport(id: string) {
  const item = conversationList.value.find(c => c.id === id)
  const title = item?.title || `会话 ${id}`
  const toastId = toast.loading('正在导出会话…')
  try {
    const res = await getConversationMessages(Number(id))
    const list = processMessageGroups(res.data || []) as Array<{
      role: string
      content?: string
      thinking?: string
    }>
    if (!list.length) {
      toast.update(toastId, { type: 'warning', message: '该会话没有可导出的消息' })
      return
    }

    const lines: string[] = [
      `# ${title}`,
      '',
      `> 会话 ID：${id} · 导出时间：${new Date().toLocaleString('zh-CN')} · 共 ${list.length} 条消息`,
      ''
    ]
    for (const msg of list) {
      if (msg.role === 'user') {
        lines.push('## 我', '', String(msg.content || '').trim(), '')
        continue
      }
      lines.push('## AI', '')
      const thinking = String(msg.thinking || '').trim()
      if (thinking) {
        // 折叠块：思考过程默认收起，避免导出的文档一屏全是推理
        lines.push('<details><summary>思考过程</summary>', '', thinking, '', '</details>', '')
      }
      lines.push(String(msg.content || '').trim(), '')
    }

    // 文件名去掉 Windows 非法字符，否则 a[download] 会被浏览器改名成 download
    const safeTitle = title.replace(/[\\/:*?"<>|]/g, '_').slice(0, 60) || 'conversation'
    const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${safeTitle}.md`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)

    toast.update(toastId, {
      type: 'success',
      message: `已导出「${title}」`,
      description: `${list.length} 条消息 · ${a.download}`
    })
  } catch (err) {
    toast.update(toastId, {
      type: 'error',
      message: '导出会话失败',
      description: readErrorMessage(err, '请稍后重试')
    })
  }
}

/**
 * 新建会话：只切一个空草稿 key，首条消息发出后由后端创建真实会话。
 *
 * <p>**不 abort 任何在跑的流**：C 档下切走即转后台，反复点「新建会话」也不会
 * 把上一个会话的生成掐断（旧实现在这里 abort，是全量并行化后最需要拿掉的一处）。</p>
 */
function onNewChat() {
  // 在管理页点「新建会话」时先回到聊天页，否则清空了状态也看不到输入框
  if (route.path !== '/chat') void router.push('/chat')
  const current = activeConversationId.value
  // 上一份是「没有发过消息的空草稿」才回收：正在跑流的草稿必须留着，否则后台写入会丢
  if (current.startsWith(DRAFT_PREFIX) && !streamingMap.value[current]) {
    dropConversationState(current)
  }
  activeConversationId.value = `${DRAFT_PREFIX}${Date.now()}`
  // 新会话没有 id，tasks / 审批 / 改动 / 上下文都由新 key 派生为空，无需逐个清
  attachments.value = []
  chatCursor.value = null
  chatHasMore.value = false
  chatError.value = ''
  chatChangesOpen.value = false
}

/**
 * 消费 SSE 事件流并增量更新一条 assistant 消息。
 *
 * 事件语义（后端 DeepSeekServiceImpl）：
 *  thinking        思考增量；**工具输出也走这个通道**（`----工具调用:----` 标记之后是工具结果）
 *  content         正文增量；thinking→content 切换时补换行（与历史消息渲染保持一致）
 *  tool_call_start 工具开始执行，先建 running 卡片，结果回来再转 done
 *  ask_user        需要用户确认（permission / clarification / iteration_confirm）
 *  task_error      LLM 接口错误导致任务中断（标题 + 原因 + 建议），可据此提示用户重试
 *  complete        本轮结束（sessionId 即新会话 id）
 */
async function consumeChatStream(
  stream: AsyncGenerator<StreamChatEvent, void, unknown>,
  convKey: string,
  assistant: ChatMessageModel
): Promise<string> {
  /**
   * 会话 key 可能在本轮中途变化：新会话的首个 SSE 事件会带回真实 id，
   * 届时把草稿 key 下的一切整体迁移过去（migrateConversationKey），后续写入都用新 key。
   * 返回值即最终 key —— 调用方（onSend / checkChatActiveTask）据此做收尾。
   */
  let key = convKey
  const tools: ToolCallItem[] = assistant.tools ?? []
  const processList: ProcessItem[] = assistant.process ?? []
  assistant.tools = tools
  assistant.process = processList

  /**
   * 续写基线：重连 / 续接场景下 process 里已经有历史思考，
   * 这里接着往后追加，而不是从空串重新开始（否则会把之前的推理过程覆盖掉）。
   */
  const existingThinking = processList.find(p => p.tag === '思考')
  let thinking = existingThinking?.text || ''
  let lastType: 'thinking' | 'content' | null = null

  /** 思考折叠标题：只统计思考字数（工具调用数量由 ChatMessage 自己汇总展示） */
  const refreshLabel = () => {
    assistant.processLabel = thinking ? `思考 ${thinking.length} 字` : undefined
  }

  /**
   * 流式渲染节流器（正文与思考各一个，互不阻塞）
   * ----------------------------------------------------------------------------
   * SSE 的 content / thinking 事件可能逐字下发（一条长回答上千次），
   * 每次都跑 markdown 会明显掉帧：
   *   - 流式期间用「轻量版」渲染（跳过 highlight.js），最多每 80ms 一次；
   *   - 收尾时用完整版渲染一次，补齐代码高亮 / diff 红绿着色。
   */
  function makeThrottle(render: () => void) {
    let lastAt = 0
    let timer: ReturnType<typeof setTimeout> | null = null
    const run = () => {
      timer = null
      lastAt = Date.now()
      render()
    }
    return {
      schedule() {
        const elapsed = Date.now() - lastAt
        if (elapsed >= 80) {
          if (timer) {
            clearTimeout(timer)
            timer = null
          }
          run()
          return
        }
        if (timer) return
        timer = setTimeout(run, 80 - elapsed)
      },
      cancel() {
        if (timer) {
          clearTimeout(timer)
          timer = null
        }
      }
    }
  }

  /** 思考条目（没有就补一个，返回可写的引用） */
  const thinkingItem = (): ProcessItem => {
    const found = processList.find(p => p.tag === '思考')
    if (found) return found
    const item: ProcessItem = { tag: '思考', text: thinking, html: '' }
    processList.unshift(item)
    return item
  }

  const contentRender = makeThrottle(() => {
    assistant.contentHtml = renderMarkdownLight(assistant.content)
  })
  const processRender = makeThrottle(() => {
    thinkingItem().html = renderMarkdownLight(thinking)
  })

  /** 收尾：正文与思考都用完整版渲染一次（补齐代码高亮 / diff 着色） */
  const flushAllRender = () => {
    contentRender.cancel()
    processRender.cancel()
    assistant.contentHtml = assistant.content ? renderMarkdown(assistant.content) : ''
    const item = processList.find(p => p.tag === '思考')
    if (item) item.html = renderMarkdown(item.text)
  }

  try {
    for await (const event of stream) {
      // 首个事件就带真实会话 id（新会话由此建立）：草稿 key 整体迁移到真实 id
      if (event.sessionId) {
        const target = String(event.sessionId)
        if (key !== target) {
          migrateConversationKey(key, target)
          key = target
          /*
           * 立刻刷新侧栏列表。
           * 新会话的 id 只有这里才知道，若等到本轮结束再刷（onSend 的 finally），
           * 流式期间它根本不在侧栏里 —— 用户切走之后就没有入口点回来了，
           * 而「切走 → 后台继续 → 点侧栏切回」正是多会话并行的主路径。
           * loadConversations 内部有并发去重，重复调用安全。
           */
          void loadConversations()
        }
      }

      if (event.type === 'thinking') {
        const data = String(event.data || '')
        if (!data) continue
        const markerIdx = data.indexOf('----工具调用:----')
        if (markerIdx !== -1) {
          const lineEnd = data.indexOf('\n', markerIdx)
          const toolContent = lineEnd !== -1 ? data.slice(lineEnd + 1).trim() : ''
          if (toolContent) {
            const name = toolContent.match(/^>\s*\*\*(.+?)\*\*/)?.[1]?.trim() || '工具'
            const summaryLine =
              toolContent
                .split('\n')
                .map(line => line.trim())
                .find(line => line && !line.startsWith('>')) || ''
            const pending = tools.find(t => t.state === 'running' && t.name === name)
            if (pending) {
              pending.summary = abbreviate(summaryLine, 80)
              // 保头也保尾：尾部常带 [TOOL_RESULT]（增删行数）与 ---TASK_JSON---（任务清单）
              pending.detail = clipToolDetail(toolContent, 4000)
              pending.state = 'done'
            } else {
              tools.push({
                name,
                summary: abbreviate(summaryLine, 80),
                detail: clipToolDetail(toolContent, 4000),
                state: 'done'
              })
            }
            // 工具输出不再写入 process：思考区与工具调用区彻底分离
            // （几十上百次工具调用塞进折叠区会让「思考过程」变得不可读）
            // task_manager 输出尾部附带结构化任务清单（按会话隔离写入）
            const taskItems = parseTaskManagerOutput(toolContent)
            if (taskItems) setSessionTasks(taskItems, key)
          }
        } else {
          thinking += data
          // 存全文（不截断）：折叠态只显示标题，展开即看完整推理过程
          // html 交给节流器写（流式轻量版，收尾补完整版），避免逐字重渲染整段 markdown
          thinkingItem().text = thinking
          processRender.schedule()
          lastType = 'thinking'
        }
        refreshLabel()
        continue
      }

      if (event.type === 'content') {
        const data = String(event.data || '')
        if (!data) continue
        if (lastType === 'thinking' && assistant.content) assistant.content += '\n'
        assistant.content += data
        lastType = 'content'
        // 流式正文节流渲染 markdown（轻量版），收尾时再完整渲染一次
        contentRender.schedule()
        continue
      }

      if (event.type === 'tool_call_start') {
        const payload = event.data as { tools?: string[]; summaries?: string[] }
        const names = payload.tools ?? []
        const summaries = payload.summaries ?? []
        names.forEach((name, index) => {
          tools.push({ name, summary: summaries[index] || '', state: 'running' })
        })
        /*
         * 出现 agent 工具调用 → 可能正在 fork 子Agent，立刻拉一次子Agent列表。
         * tool_call_start 只给工具名、不给参数（无法区分 fork / collect / inspect），
         * 宁可多拉一次只读请求也不要漏掉创建；若拉到 running 记录，
         * loadSubAgents 内部会自动开启 3s 轮询跟踪状态流转。
         */
        if (mayForkSubAgent(names)) void loadSubAgents(key)
        refreshLabel()
        continue
      }

      if (event.type === 'ask_user') {
        pendingUuidMap.set(key, event.data.uuid)
        setApproval(key, toApprovalPayload(event.data as AskUserEventData, chatAgentName()))
        // 后台会话在等确认：当前正看别的会话时给一次提示，否则任务会静静卡在那里
        if (activeConversationId.value !== key) {
          toast.warning('有会话在等待你的确认', { description: conversationTitleOf(key) })
        }
        continue
      }

      if (event.type === 'task_error') {
        const err = event.data as { title?: string; message: string }
        assistant.streaming = false
        if (activeConversationId.value === key) {
          chatError.value = err.title ? `${err.title}：${err.message}` : err.message
        }
        toast.error(err.title || '任务执行中断', {
          description: abbreviate(
            activeConversationId.value === key ? err.message : `「${conversationTitleOf(key)}」：${err.message}`,
            200
          )
        })
        continue
      }

      if (event.type === 'error') {
        const message = String(event.data || '生成失败')
        if (activeConversationId.value === key) chatError.value = message
        toast.error('生成失败', { description: abbreviate(message, 200) })
        continue
      }

      /*
       * 委托调用进度心跳（delegate_progress）：A 委托另一个智能体执行任务期间，
       * 后端每约 5s 推一次。原先这里被整体忽略（后端真实在发，前端白丢）。
       * 子Agent面板据此显示「委托中 · 已等待 Ns · 前往会话」。
       */
      if (event.type === 'delegate_progress') {
        noteDelegateProgress(key, event.data)
        continue
      }
      // complete / resume / agent_event：agent_event 后端从不下发（见 utils/agent-event.ts 注释），忽略
    }
  } catch (err: any) {
    const message = err?.message || '发送失败'
    if (message !== '__USER_ABORT__') {
      if (activeConversationId.value === key) chatError.value = message
      if (!assistant.content) assistant.content = message
      toast.error('发送失败', { description: abbreviate(message, 200) })
    }
  } finally {
    assistant.streaming = false
    // 收尾：正文与思考都用完整版 markdown 渲染一次，补齐代码高亮 / diff 着色 / 表格等
    flushAllRender()
    refreshLabel()
    /*
     * 子Agent收尾刷新：主Agent任务结束不代表子Agent都结束（后端还会 collectPendingAgents 等 300s），
     * 这里补拉一次让最新的 running/completed 落到位；若仍有 running，loadSubAgents 内部会继续轮询。
     */
    void loadSubAgents(key)
  }
  return key
}

/** 发送消息：乐观渲染用户气泡 + 创建 assistant 占位 → SSE 增量填充 */
async function onSend(text: string) {
  const content = text.trim()
  if (!content) return
  // 当前 Agent 决定会话归属（agentConfigId）与工作目录：未加载完成就发消息，
  // 会话会建成「无归属」，随后按 Agent 过滤的侧栏就看不到它
  if (!currentAgentId.value) await loadAgents()
  if (attachments.value.some(a => a.uploading)) {
    toast.warning('附件仍在上传，请稍候再发送')
    return
  }

  /**
   * 目标会话 key。
   * 没有当前会话（刚点「新建会话」/ 首次进入）时开一个草稿 key：
   * 后端在首个 SSE 事件返回真实 id 后，由 consumeChatStream 迁到 `String(id)`。
   */
  let key = activeConversationId.value
  if (!key) {
    key = `${DRAFT_PREFIX}${Date.now()}`
    activeConversationId.value = key
  }
  // 同一会话不允许并发两轮；不同会话之间互不影响 —— 这正是多会话并行流要的效果
  if (streamingMap.value[key]) return

  const fileAssetIds = attachments.value.filter(a => a.fileAssetId).map(a => a.fileAssetId!)
  const attachmentIds = attachments.value.filter(a => a.attachmentId).map(a => a.attachmentId!)

  /**
   * 一轮一个 turnId（后端据此关联代码快照）。
   * 同时挂在本地 user / assistant 两条消息上：历史消息要等刷新后由 chat-mapper 从
   * turn_id 还原，而「刚发完这一轮就点重新生成」只有本地对象上有值。
   */
  const turnId = `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`

  const list = convMessages(key)
  list.push({ id: `u-${Date.now()}`, role: 'user', content, turnId })
  attachments.value = []
  // 新一轮任务从空白开始：清掉本会话上一轮的清单（其余会话的清单不受影响）
  clearSessionTasks(key)
  setApproval(key, null)
  chatError.value = ''
  setStreaming(key, true)

  list.push({
    id: `a-${Date.now()}`,
    role: 'assistant',
    content: '',
    agentName: chatAgentName(),
    // 头像落到消息上：切 Agent 后这条历史回复仍显示当时那个 Agent 的头像
    agentAvatar: agentAvatar.value,
    model: chatModel.value,
    streaming: true,
    process: [],
    tools: [],
    turnId
  })
  // 必须拿响应式代理再改，直接改原始对象不会触发视图更新
  const assistant = list[list.length - 1]

  const abort = new AbortController()
  streamAborts.set(key, abort)
  const sessionId = keyToSessionId(key) ?? undefined
  const wasNewSession = !sessionId
  // 用户点「停止」也走 abort：收尾时靠这个标记区分「完成」与「被停止」
  let finalKey = key

  try {
    finalKey = await consumeChatStream(
      streamChat(
        content,
        sessionId,
        {
          executionMode: chatExecutionMode.value,
          projectRoot: currentAgent.value?.workDir || undefined,
          model: chatModel.value || undefined,
          thinkingMode: chatThinkingMode.value || undefined,
          agentConfigId: currentAgentId.value || undefined,
          /*
            Provider 只在「用户本次手动切换过」时显式下发。

            后端 `LLMClientManager` 的选型优先级是 `ChatRequest.providerCode` > `agentConfig.providerId`
            > `agentConfig.providerCode` > 默认 Provider。Agent 自己绑定的值后端本来就会读到，
            前端再传一遍没有收益；而 `chatProviderCode` 在 Agent 未绑定时会回退成「默认 Provider 的 code」，
            若照抄它下发，就会把「跟随 Agent 绑定 / 跟随默认」这一语义固化成某个具体 Provider，
            反而盖掉后端按 providerId 的选型。
          */
          providerCode: chatProviderOverride.value || undefined,
          // 上下文模式始终显式下发：默认 full 与后端兜底一致，传了不改变行为，但能让设置页的改动立刻生效
          contextMode: chatContextMode.value,
          turnId,
          fileAssetIds: fileAssetIds.length ? fileAssetIds : undefined,
          attachmentIds: attachmentIds.length ? attachmentIds : undefined
        },
        abort
      ),
      key,
      assistant
    )
  } finally {
    assistant.streaming = false
    setStreaming(finalKey, false)
    streamAborts.delete(finalKey)
    streamAborts.delete(key)
    // 本轮正文 / 思考 / 工具返回都已落定，重算上下文估算（流式期间刻意不逐帧算）
    refreshContextTokens(finalKey)
    // 本轮可能产生了代码快照：拉一次快照列表，把「回滚本轮改动」按钮点亮
    const convId = keyToSessionId(finalKey)
    if (convId) {
      void matchSnapshotsForMessages(convId, finalKey)
      /*
        快照落库可能晚于最后一个 SSE 事件（后端在会话收尾阶段才写文件快照）。
        实测：刚让 AI 改完文件的这一轮，首次匹配经常捞不到快照 → 按钮要等刷新才出现。
        这里补一次延时重试，兜住这个时序差；matchSnapshotsForMessages 内部对已点亮的消息
        会跳过（msg.snapshotId 已存在不再覆盖），重复调用无副作用。
      */
      window.setTimeout(() => {
        const again = keyToSessionId(finalKey)
        if (again) void matchSnapshotsForMessages(again, finalKey)
      }, 1500)
    }
    // 新会话首轮结束后侧栏需要出现该会话 → 刷新列表
    if (wasNewSession && convId) await loadConversations()
    // 后台完成：用户已经切到别的会话，补一句提示（主动停止的不提示）
    if (activeConversationId.value !== finalKey && !abort.signal.aborted) {
      toast.success('后台会话已完成', { description: conversationTitleOf(finalKey) })
    }
  }
}

/**
 * 停止生成：只中断「当前会话」的前端流，并取消它在后端的任务。
 *
 * <p>其他会话的后台流不受影响 —— 这是 C 档与旧单例实现的关键差别：
 * 以前点停止会把唯一那条流掐掉，现在停的只是你正在看的这一条。</p>
 */
async function onStop() {
  const key = activeConversationId.value
  const abort = key ? streamAborts.get(key) : null
  if (abort) {
    abort.abort()
    streamAborts.delete(key)
  }
  if (key) setStreaming(key, false)
  const id = keyToSessionId(key)
  if (id) {
    const ok = await cancelTask(id)
    if (!ok) toast.warning('后端任务可能未取消，请稍后刷新确认')
  }
  toast.info('已停止生成')
}

/**
 * 忽略后端遗留的运行标记（僵尸任务的出口）。
 *
 * <p>进入会话时若后端报 running 但 2.5 秒内事件零增长，会被判为遗留标记并解除输入锁定。
 * 但「零增长」也可能只是任务在长时间思考 / 等外部接口（打补丁式的慢任务），
 * 贸然调 cancel 会把用户真正在跑的活儿掐断 —— 本页曾经这么做过，风险太大。</p>
 *
 * <p>所以这里只在前端忽略这条标记：不请求后端、不中断任务。
 * 真要停止，用户在会话里点「停止生成」或重新发送即可，由用户显式决定。</p>
 */
function onClearStaleTask() {
  const key = activeConversationId.value
  if (!key) return
  const next = { ...staleTaskKeys.value }
  delete next[key]
  staleTaskKeys.value = next
  toast.success('已忽略运行标记', {
    description: '只影响本页显示，未中断后端任务（它可能仍在慢速产出）'
  })
}

/**
 * 「未配置 Provider」引导页：去配置 → 跳「模型配置」页。
 *
 * <p>只做导航，不在聊天页重复造一套 Provider 表单：
 * 新增/编辑涉及 API Key、extraConfig JSON、余额查询等一堆字段，
 * 那一页已经做完了，这里再造一份必然两边不一致。</p>
 */
function onChatConfigureProvider() {
  void router.push('/providers')
}

/** 引导页：刷新 → 重新拉一次 Provider 列表（管理员刚配完回来点一下即可） */
function onChatRefreshProviders() {
  void loadProviders()
}

/**
 * 提交审批回执（action：approve / approve_all / reject / custom）
 * @param successText 自定义成功提示（迭代确认等场景文案与通用审批不同）
 */
async function submitChatAnswer(answer: string, action: string, successText = '') {
  const key = activeConversationId.value
  const uuid = pendingUuidMap.get(key) || ''
  if (!uuid) return
  const previous = approvalMap.value[key] ?? null
  setApproval(key, null)
  pendingUuidMap.delete(key)
  try {
    const res = await submitAnswer(uuid, answer, action)
    if (res?.data && res.data.success === false) throw new Error(res.message || '提交失败')
    if (successText) toast.success(successText)
    else if (action === 'reject') toast.success('已拒绝')
    else if (action === 'approve_all') toast.success('已同意本会话后续全部操作')
    else if (action === 'custom') toast.success('回答已发送')
    else toast.success('已同意')
  } catch (err) {
    // 失败时把卡片放回去，避免用户以为已提交
    setApproval(key, previous)
    pendingUuidMap.set(key, uuid)
    toast.error('提交失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

function onApprove() {
  void submitChatAnswer('', 'approve')
}

function onApproveAll() {
  void submitChatAnswer('', 'approve_all')
}

function onReject() {
  // 迭代确认的「结束任务」与工具授权「拒绝」语义不同，提示文案分开
  const isIteration = approval.value?.askType === 'iteration_confirm'
  void submitChatAnswer('', 'reject', isIteration ? '已结束任务' : '')
}

function onAnswer(text: string) {
  void submitChatAnswer(text, 'custom')
}

/**
 * 迭代确认「继续 +N 次」。
 * 后端把 answer 当作「本次扩容的迭代次数」解析，action 仍为 approve
 * （与旧版 handleIterationDecision('continue', n) 的提交语义完全一致）。
 */
function onContinue(additional: number) {
  void submitChatAnswer(String(additional), 'approve', `已继续执行（扩容 ${additional} 次）`)
}

/** 选择附件「上传新文件」→ 打开隐藏的 file input */
function onAttach() {
  chatFileInput.value?.click()
}

/** 选择附件「用历史文件」→ 打开文件库选择器（引用已有资产，不重复上传） */
function onAttachHistory() {
  chatFilePickerOpen.value = true
}

/**
 * 历史文件选择确认：并入附件区。
 *
 * 只记 `fileAssetId`，不重新上传 —— 发送时后端按 file_id 复用同一份资产
 * （图片注入 vision 内容块，文档注入读取提示）。重复选择同一文件直接跳过。
 */
function onFilePickerPick(items: FileAssetData[]) {
  let added = 0
  for (const f of items) {
    if (attachments.value.some(a => a.fileAssetId === f.id)) continue
    const img = isImageMime(f.mimeType)
    const att: ChatAttachment = {
      id: `ref-${f.id}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      name: fileAssetDisplayName(f),
      size: formatAssetSize(f.size),
      icon: img ? '🖼️' : docTypeIcon(docTypeOf(f.filename, f.mimeType)),
      isImage: img,
      fileAssetId: f.id
    }
    attachments.value.push(att)
    // 图片附件异步补缩略图（F14）：带鉴权的 blob 预览，失败保持图标形态
    if (img) void fillAttachmentPreview(att)
    added += 1
  }
  if (added) toast.success(`已引用 ${added} 个历史文件`)
  else toast.info('这些文件已经在附件里了')
}

/**
 * 文件管理页「引用到聊天」：先写入 file-ref store，再跳聊天页。
 *
 * 不直接改 attachments —— 引用发生时聊天页可能还没挂载，且两个页面组件并不共存。
 * store 是跨页一次性传递的载体，聊天页激活时消费（见 tryConsumePendingRefs）。
 */
function onFileReference(file: FileItem) {
  const id = Number(file.id)
  if (!id || Number.isNaN(id)) return
  fileRefStore.addRefs([
    {
      id,
      fileId: file.fileId,
      filename: file.filename || `#${id}`,
      displayName: file.displayName,
      mimeType: file.mimeType,
      storageType: file.storageType,
      size: file.size
    }
  ])
  toast.success('已加入聊天附件', { description: '发送时按 fileAssetId 复用，不会重新上传' })
  void router.push('/chat')
}

/**
 * 消费跨页引用（文件管理页 → 聊天页）。
 *
 * 触发时机：应用启动（用户可能直接从文件页带着引用进来）与每次路由切换。
 * 消费是「取走即清空」语义，所以重复触发不会重复加文件（store 里已空）。
 */
function tryConsumePendingRefs() {
  const refs = fileRefStore.consumeRefs()
  if (!refs.length) return
  let added = 0
  for (const r of refs) {
    if (attachments.value.some(a => a.fileAssetId === r.id)) continue
    const img = isImageMime(r.mimeType)
    const att: ChatAttachment = {
      id: `ref-${r.id}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      name: r.displayName || r.filename || `#${r.id}`,
      size: formatAssetSize(r.size),
      icon: img ? '🖼️' : docTypeIcon(docTypeOf(r.filename, r.mimeType)),
      isImage: img,
      fileAssetId: r.id
    }
    attachments.value.push(att)
    // 图片附件异步补缩略图（走带鉴权的 blob 预览；失败保持图标形态）
    if (img) void fillAttachmentPreview(att)
    added += 1
  }
  if (added) toast.success(`已引用 ${added} 个文件到附件`)
}

// 引用消费时机：① 应用启动（可能带着引用直接落在聊天页）；② 每次切到聊天页。
// 消费语义是取走即清空，重复触发不会重复加文件。
onMounted(() => tryConsumePendingRefs())
watch(
  () => route.path,
  path => {
    if (path === '/chat') tryConsumePendingRefs()
  }
)

/** 附件上传：文档走 /deepseek/upload（LLM 通过 chat_attachment 工具读取） */
async function uploadChatAttachment(file: File) {
  const tempId = `att-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
  const isImage = file.type.startsWith('image/')
  attachments.value.push({
    id: tempId,
    name: file.name,
    size: formatAttachmentSize(file.size),
    icon: isImage ? '🖼️' : '📎',
    // 图片走「文件选择框」而不是粘贴时也会进这个分支：立即用本地 objectURL 出缩略图
    isImage,
    previewUrl: isImage ? URL.createObjectURL(file) : undefined,
    uploading: true
  })
  try {
    const result = await uploadAttachment(file)
    if (!result?.success) throw new Error(result?.error || '上传失败')
    const target = attachments.value.find(a => a.id === tempId)
    if (target) {
      target.attachmentId = result.attachmentId
      target.uploading = false
    }
  } catch (err) {
    attachments.value = attachments.value.filter(a => a.id !== tempId)
    toast.error('附件上传失败', { description: readErrorMessage(err, file.name) })
  }
}

/** 图片上传（粘贴截图）：走文件资产通道，发送时作为 fileAssetIds 注入 vision 内容块 */
async function uploadChatImage(file: File) {
  const tempId = `img-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
  attachments.value.push({
    id: tempId,
    name: file.name || '截图',
    size: formatAttachmentSize(file.size),
    icon: '🖼️',
    isImage: true,
    // 本地 objectURL 先出缩略图（不等上传完成），上传成功后不需要替换：
    // 内容是同一张图，换 URL 反而会让 <img> 闪一下
    previewUrl: URL.createObjectURL(file),
    uploading: true
  })
  try {
    const res = await uploadFileAsset(file, { source: 'paste' })
    if (res.code !== 200 || !res.data) throw new Error(res.message || '上传失败')
    const target = attachments.value.find(a => a.id === tempId)
    if (target) {
      target.fileAssetId = res.data.id
      target.uploading = false
    }
  } catch (err) {
    attachments.value = attachments.value.filter(a => a.id !== tempId)
    toast.error('图片上传失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

/**
 * 给「来自文件库」的图片附件补缩略图（F14）。
 *
 * 文件资产预览需要鉴权头，浏览器无法直接 `<img src>`，只能走
 * `getFilePreviewBlobUrl`（api 层已按 id 缓存 blob）。这里异步补，失败就保持
 * 图标形态 —— 缩略图是锦上添花，不该因为取不到预览就把附件标成错误。
 */
async function fillAttachmentPreview(att: ChatAttachment) {
  if (!att.isImage || att.previewUrl || !att.fileAssetId) return
  try {
    const url = await getFilePreviewBlobUrl(att.fileAssetId)
    if (url) att.previewUrl = url
  } catch {
    /* 预览拿不到 → 保留图标形态 */
  }
}

/**
 * 文件选择框回调：按类型分流。
 *
 * 图片走文件资产通道（`/api/files` → fileAssetId），发送时注入 vision 内容块；
 * 文档走 `/deepseek/upload`（attachmentId），LLM 用 chat_attachment 工具读取。
 * 原实现把所有文件都塞进 /deepseek/upload，图片就拿不到视觉能力了。
 */
async function onChatFileChange(ev: Event) {
  const input = ev.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  for (const file of files) {
    if (file.type.startsWith('image/')) await uploadChatImage(file)
    else await uploadChatAttachment(file)
  }
}

/**
 * 粘贴：同时支持截图与「复制的文件」。
 *
 * - 截图在 `clipboardData.items` 里（`getAsFile()` 才拿得到完整数据）；
 * - 资源管理器里 Ctrl+C 的文件在 `clipboardData.files` 里（items 拿不到）。
 *
 * 两条通道都收，但要过滤掉 files 里的图片：同一张图可能既在 items 又在 files，
 * 不过滤会重复上传同一份文件。
 */
async function onPaste(ev: ClipboardEvent) {
  const dt = ev.clipboardData
  if (!dt) return
  const images = Array.from(dt.items ?? [])
    .filter(item => item.type.startsWith('image/'))
    .map(item => item.getAsFile())
    .filter((file): file is File => !!file)
  const plainFiles = Array.from(dt.files ?? []).filter(file => !file.type.startsWith('image/'))
  if (!images.length && !plainFiles.length) return
  ev.preventDefault()
  for (const file of images) await uploadChatImage(file)
  for (const file of plainFiles) await uploadChatAttachment(file)
}

/** AI 优化提示词：读取 Composer 草稿 → 调接口 → 回填 */
async function onOptimize() {
  const draft = chatViewRef.value?.getDraft?.() ?? ''
  if (!draft.trim()) {
    toast.info('先输入一点内容，再让 AI 优化提示词')
    return
  }
  try {
    const optimized = await optimizePrompt(draft)
    if (!optimized || optimized === draft) {
      toast.info('没有得到更好的表述，保持原样')
      return
    }
    chatViewRef.value?.setDraft?.(optimized)
    toast.success('提示词已优化')
  } catch (err) {
    toast.error('优化失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

function onSelectModel() {
  chatModelPickerOpen.value = true
}

/**
 * 应用「模型与运行模式」弹层的结果。
 *
 * <p>五组设置分两类落地：</p>
 * <ul>
 *   <li><b>Agent 级</b>（Provider / 模型 / 思考模式 / 执行模式）→ 写回当前 Agent 运行时，
 *       与旧版 `onProviderSwitch` + `saveRuntime()` 语义一致，切 Agent 或刷新后仍生效；</li>
 *   <li><b>全局级</b>（上下文模式）→ 写 settingsStore（persist）+ 镜像一份到后端
 *       `sys_config.context_mode`，与旧版 `saveContextModeToServer` 一致，不绑到某个 Agent。</li>
 * </ul>
 */
async function onChatModelApply(payload: {
  providerCode: string
  model: string
  thinkingMode: string
  executionMode: string
  contextMode: string
}) {
  // ---------- 上下文模式：纯前端设置，先落库不依赖网络 ----------
  const nextContext = payload.contextMode === 'compact' ? 'compact' : 'full'
  const contextChanged = nextContext !== chatContextMode.value
  settings.contextMode = nextContext
  if (contextChanged) {
    // 口径变了（思考/工具是否计入），已算好的估算值全部作废，立刻重算当前会话
    refreshContextTokens()
    // 镜像到服务端：失败静默（settingsStore 已生效，请求参数也已显式带上，不影响实际行为）
    void setConfig('context_mode', nextContext).catch(() => {})
  }

  // ---------- 其余四项：先切本地状态，再持久化到当前 Agent ----------
  chatModel.value = payload.model
  chatThinkingMode.value = payload.thinkingMode
  chatExecutionMode.value = payload.executionMode
  // 临时覆盖先兜住：即使后面的写回失败，本次会话也已按新 Provider 生效
  chatProviderOverride.value = payload.providerCode || ''

  const id = currentAgentId.value
  if (!id) {
    toast.info('已切换（当前未绑定智能体，仅本次会话生效）')
    return
  }
  try {
    await updateAgentRuntime(id, {
      modelName: payload.model,
      thinkingMode: payload.thinkingMode,
      executionMode: payload.executionMode,
      // providerCode 必须单独写：AgentConfigServiceImpl.update() 不处理该字段，只有 updateRuntime() 处理
      providerCode: payload.providerCode || ''
    })
    toast.success(
      contextChanged ? '模型与运行模式已更新，上下文改为' + (nextContext === 'compact' ? '精简' : '全量') : '模型与运行模式已更新'
    )
    // 写回成功：Agent 已是新值，清掉本地覆盖，后续完全跟随 Agent（避免两套真相互相漂移）
    chatProviderOverride.value = ''
    await loadAgents()
    // Provider 变了 → 余额要重新查（watch(chatProviderCode) 只在 code 变化时触发，
    // 而这里 loadAgents 后 chatProviderCode 可能回到与覆盖值相同的 code，靠 force 保证刷新）
    void loadChatBalance(true)
  } catch (err) {
    toast.error('保存失败', {
      description: readErrorMessage(err, '已按所选设置应用于本次会话，但未能写回智能体配置')
    })
  }
}

function onRemoveAttachment(id: string) {
  attachments.value = attachments.value.filter(a => a.id !== id)
}

async function onCopy(msg: ChatMessageModel) {
  const text = msg.content || ''
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      // file:// / 非安全上下文下降级
      const el = document.createElement('textarea')
      el.value = text
      document.body.appendChild(el)
      el.select()
      document.execCommand('copy')
      el.remove()
    }
    toast.success('已复制')
  } catch {
    toast.error('复制失败，请手动选择文本复制')
  }
}

/**
 * 重新生成：丢弃该轮（及其后）的消息，用同一条提问重新跑一遍。
 *
 * <p>与早期实现的区别：早期只在前端截断数组就重发，DB 里的旧回复仍在 ——
 * 刷新页面后旧回复又回来，历史里出现两条并存的答案。现在先调
 * `DELETE /api/conversation/{id}/messages?turnId=` 真删，再重发。</p>
 *
 * <p>三种特殊情况：
 *  ① 生成中禁止（后端任务还在写库，删掉会被写回来）；
 *  ② 找不到 turnId（早于 turnId 落库的旧消息）→ 提示「本次重发不会清理历史」，仍允许重发；
 *  ③ 该轮之后还有对话 → 二次确认，说明会一并丢弃。</p>
 */
async function onRegenerate(msg: ChatMessageModel) {
  if (isStreaming.value) {
    toast.warning('正在生成中，请先停止再重新生成')
    return
  }
  const idx = messages.value.findIndex(m => m.id === msg.id)
  if (idx < 0) return

  // 往前找这条 AI 回复对应的用户提问
  let userIdx = -1
  for (let i = idx - 1; i >= 0; i -= 1) {
    if (messages.value[i].role === 'user') {
      userIdx = i
      break
    }
  }
  if (userIdx < 0) {
    toast.info('找不到对应的提问，无法重新生成')
    return
  }

  const question = messages.value[userIdx].content
  // assistant 已在 chat-mapper 里继承了前一条 user 的 turnId，这里双保险各取一次
  const turnId = msg.turnId || messages.value[userIdx].turnId || ''
  const convId = chatSessionId.value
  const laterCount = messages.value.length - idx - 1

  if (laterCount > 0) {
    const ok = await confirm({
      title: '重新生成这一轮',
      content: `该轮之后还有 ${laterCount} 条消息，重新生成会一并丢弃。`,
      okText: '继续',
      type: 'warning'
    })
    if (!ok) return
  }

  if (convId && turnId) {
    const busy = toast.loading('正在丢弃旧回复…')
    try {
      await deleteMessagesFromTurn(convId, turnId)
    } catch (err) {
      toast.dismiss(busy)
      // 后端未重启（接口 404）时会走到这里：明确告知原因，不做静默降级污染历史
      toast.error('重新生成失败', {
        description: readErrorMessage(err, '无法删除旧回复，请确认后端已更新并重启')
      })
      return
    }
    toast.dismiss(busy)
  } else {
    toast.warning('这一轮没有轮次 ID（旧数据），本次重新生成不会清理历史回复')
  }

  // 前端同步截断到提问之前，再原样重发（onSend 会生成新的 turnId）
  setConvMessages(activeConversationId.value, messages.value.slice(0, userIdx))
  refreshContextTokens()
  await onSend(question)
}

/** 滚动到顶部：加载更早的历史消息（游标分页，防重入） */
async function onChatLoadMore() {
  if (chatLoadingMore.value || chatLoading.value) return
  if (!chatHasMore.value || chatCursor.value == null) return
  if (!activeConversationId.value) return
  chatLoadingMore.value = true
  try {
    await loadConversationMessages(activeConversationId.value, chatCursor.value)
  } finally {
    chatLoadingMore.value = false
  }
}

/**
 * 进入会话时检查后台任务：
 *  - 运行中 → 订阅增量事件流继续渲染（cursor = 已消费序号，避免历史重放）
 *  - 有待审批问题 → 恢复审批卡片（页面刷新后不丢授权入口）
 *
 * 续接策略（修复「切回来单开一段会话」）：
 *   cursor 模式下进入之前的事件不会重放，DB 里的内容才是基线。
 *   所以要先拉一次最新消息，再把增量**续写到最后一条 assistant 消息**上，
 *   而不是无脑 push 一条新消息 —— 否则同一轮对话会被切成两条 AI 回复，看起来像断开重开。
 *   只有「最后一条是用户提问」（本轮 AI 还没落库任何内容）时才新建占位消息。
 */
async function checkChatActiveTask(conversationId: number) {
  if (!conversationId || Number.isNaN(conversationId)) return
  const key = String(conversationId)
  // 本页已经在消费这个会话的流：不要重复订阅，否则同一段增量会被写两遍
  if (streamingMap.value[key] || streamAborts.has(key)) return
  try {
    const task = await checkActiveTask(conversationId)
    if (!task?.active) {
      /*
       * 任务已结束：若上次是异常中断（非正常完成），把原因告诉用户。
       * 典型场景：浏览器关掉/刷新后回来，用户只看到「AI 没回复完」，不知道发生了什么。
       * 用 toast 而不是 chatError：ChatView 没有错误条 prop，而这条信息是「一次性提示」性质。
       */
      const last = task?.lastTask
      if (
        last?.errorMessage &&
        last.status !== 'COMPLETED' &&
        activeConversationId.value === String(conversationId)
      ) {
        toast.warning('上次任务已中断', { description: last.errorMessage })
      }
      return
    }

    if (task.pendingQuestionUuid) {
      const text = task.pendingQuestionText || '请确认是否继续执行'
      pendingUuidMap.set(key, task.pendingQuestionUuid)
      setApproval(
        key,
        toApprovalPayload(
          {
            uuid: task.pendingQuestionUuid,
            question: text,
            askType: text.startsWith('【任务迭代确认】') ? 'iteration_confirm' : 'permission'
          },
          chatAgentName()
        )
      )
    }
    if (task.status !== 'running') return

    /*
     * 僵尸任务探测（只在后端报 running 时才走，罕见路径，代价 2.5 秒）：
     * 连续两次查询 seq / eventCount 都不变 = 该任务已经不再产出任何事件，
     * 多半是后端重启或异常退出遗留的标记。此时绝不能锁输入，
     * 否则用户看到的就是「聊天框永久禁用、发不出消息」。
     * 间隔取 2.5 秒：真实任务每轮迭代、每次工具调用都会推进 seq，2.5 秒完全静止概率极低。
     */
    const firstSeq = task.seq ?? -1
    const firstEvents = task.eventCount ?? -1
    await new Promise(resolve => setTimeout(resolve, 2500))
    const probe = await checkActiveTask(conversationId).catch(() => null)
    const stagnant =
      !!probe &&
      probe.active === true &&
      probe.status === 'running' &&
      (probe.seq ?? -2) === firstSeq &&
      (probe.eventCount ?? -2) === firstEvents
    if (stagnant) {
      staleTaskKeys.value = { ...staleTaskKeys.value, [key]: true }
      if (activeConversationId.value === key) {
        toast.warning('该会话有后端遗留的运行标记', {
          description: '2.5 秒内没有任何事件增长，已解除输入锁定；点状态栏「清理标记」可彻底清除'
        })
      }
      return
    }
    // 事件恢复增长：说明是真在跑，撤掉之前可能误判的遗留标记
    if (staleTaskKeys.value[key]) {
      const next = { ...staleTaskKeys.value }
      delete next[key]
      staleTaskKeys.value = next
    }

    // 期间用户可能已切到别的会话：切走就放弃恢复，避免把当前会话的消息覆盖掉
    if (activeConversationId.value !== key) return
    // 先刷新 DB 基线（进入之前已产出的内容都在库里），再决定续接到哪条消息
    await loadConversationMessages(key)
    if (activeConversationId.value !== key) return
    // 刷新期间用户可能又切回来并触发了别的订阅：再判一次，避免双流把增量写两遍
    if (streamingMap.value[key]) return

    const list = convMessages(key)
    const last = list[list.length - 1]
    let assistant: ChatMessageModel
    if (last && last.role === 'assistant') {
      // 续写：直接复用被中断的那条 AI 消息（内容/思考/工具卡片都在里面）
      assistant = last
      assistant.streaming = true
      assistant.model = assistant.model || chatModel.value
      if (!assistant.tools) assistant.tools = []
      if (!assistant.process) assistant.process = []
    } else {
      // 本轮 AI 还没产出任何落库内容（最后一条是用户提问）：新建占位
      list.push({
        id: `a-resume-${Date.now()}`,
        role: 'assistant',
        content: '',
        agentName: chatAgentName(),
        agentAvatar: agentAvatar.value,
        model: chatModel.value,
        streaming: true,
        process: [],
        tools: []
      })
      assistant = list[list.length - 1]
    }

    const abort = new AbortController()
    streamAborts.set(key, abort)
    setStreaming(key, true)
    let finalKey = key
    try {
      finalKey = await consumeChatStream(taskStream(conversationId, abort, task.seq ?? 0), key, assistant)
    } finally {
      setStreaming(finalKey, false)
      streamAborts.delete(finalKey)
      streamAborts.delete(key)
      // 任务收尾后再对齐一次 DB：让分段落库的思考/工具/正文合并成完整的一轮，
      // 避免内存态与持久化内容不一致（下次进来又「看起来少了一段」）
      if (activeConversationId.value === finalKey) {
        await loadConversationMessages(finalKey).catch(() => {})
      }
    }
  } catch {
    // 后端不可达 / 任务已结束：静默忽略，不打扰用户
  }
}

/* ============================ 智能体管理页事件 ============================ */
function onAgentCreate() {
  // 抽屉打开动作由 AgentListView 内部处理，这里保留回调便于埋点/权限校验
}

/**
 * 保存：payload 带 id → 编辑（PUT），否则新建（POST）
 *
 * <p>前端表单字段与后端实体不同名，这里集中做一次转换：
 * `model` → `modelName`；`tools` 数组 → `toolNames` JSON 数组字符串（空数组 = 全部工具，存空串）。</p>
 *
 * <p>providerCode 有个后端坑：`AgentConfigServiceImpl.update()` 只处理 `providerId`，
 * **不处理 `providerCode`**（`updateRuntime()` 才处理）。若只调 PUT /{id}，切换 Provider
 * 后刷新页面会退回旧 providerCode，故编辑分支额外调一次 `PUT /{id}/runtime` 补写。</p>
 *
 * <p>`characterProfile` 必须始终传字符串（空性格传 `"{}"`）：后端用 `!= null` 判断是否覆盖，
 * 传 undefined 等于「不修改」，性格永远清不掉。</p>
 */
async function onAgentSave(payload: Record<string, unknown>) {
  const id = payload.id as number | undefined
  const tools = (payload.tools as string[]) || []
  const providerCode = String(payload.providerCode ?? '').trim()
  // 反查选中 Provider 的 id：后端运行时按 providerId/providerCode 二者择一选型，能传就都传
  const provider = agentProviderOptions.value.find(p => p.code === providerCode)
  const body: AgentConfig = {
    name: String(payload.name ?? '').trim(),
    description: String(payload.description ?? ''),
    // 头像：图片 URL 或 emoji；空串表示清空（后端 create 会给 🤖 兜底，update 会写成空 → 卡片回落名字首字）
    avatar: String(payload.avatar ?? '').trim(),
    modelName: String(payload.model ?? ''),
    executionMode: String(payload.executionMode ?? 'auto'),
    systemPrompt: String(payload.systemPrompt ?? ''),
    thinkingMode: String(payload.thinkingMode ?? 'non-thinking'),
    temperature: Number(payload.temperature ?? 0.3),
    workDir: String(payload.workDir ?? ''),
    // 后端用 objectMapper.readValue(toolNames, List.class) 解析，必须是 JSON 数组字符串；
    // 空数组传空串 = 使用全部工具（与卡片「全部工具」展示语义一致）
    toolNames: tools.length ? JSON.stringify(tools) : '',
    providerCode,
    providerId: provider?.id,
    characterProfile: String(payload.characterProfile ?? '{}')
  }
  if (!body.name) {
    toast.warning('请填写智能体名称')
    return
  }
  try {
    if (id) {
      await updateAgentConfig(id, body)
      // 补写 providerCode（见上方说明）；空串表示「跟随默认 Provider」，需显式写入以清掉旧值
      await updateAgentRuntime(id, { providerCode })
      toast.success('智能体已更新')
    } else {
      await createAgentConfig(body)
      toast.success('智能体已创建')
    }
    await loadAgents()
  } catch (err) {
    toast.error('保存失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

async function onAgentDelete(id: number | string) {
  const target = agentList.value.find(a => a.id === id)
  if (target?.isBuiltin) {
    toast.warning('内置智能体不可删除')
    return
  }
  if (!(await confirmDelete('智能体', target?.name))) return
  try {
    await deleteAgentConfig(Number(id))
    toast.success('智能体已删除')
    await loadAgents()
  } catch (err) {
    toast.error('删除失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

/**
 * 卡片「更多」菜单 → 设为默认
 *
 * <p>后端只有「设为默认」这一个独立接口（PUT /agent-configs/{id}/default），
 * 没有复制、没有启用/停用开关，因此卡片菜单只有「设为默认 / 删除」两项。</p>
 */
async function onAgentSetDefault(agent: AgentItem) {
  if (agent.isDefault) {
    toast.info(`「${agent.name}」已是默认智能体`)
    return
  }
  const yes = await confirm({
    title: '设为默认智能体',
    content: `确定将「${agent.name}」设为默认？新建会话将默认使用该智能体。`,
    okText: '设为默认'
  })
  if (!yes) return
  try {
    await setDefaultAgentConfig(Number(agent.id))
    toast.success('已设为默认智能体')
    await loadAgents()
  } catch (err) {
    toast.error('设置失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

/** 把当前 Agent 的模型 / 思考模式 / 执行模式同步到聊天页运行时 */
function syncChatRuntime() {
  const agent = currentAgent.value
  chatModel.value = agent?.model || availableModels.value[0] || ''
  chatThinkingMode.value = agent?.thinkingMode || ''
  chatExecutionMode.value = agent?.executionMode || 'auto'
  // 切 Agent 时清掉上一轮的 Provider 临时覆盖：新 Agent 有自己的 provider 绑定，沿用旧覆盖会张冠李戴
  chatProviderOverride.value = ''
}

/** 点击智能体卡片 → 切到该 Agent 的会话（聊天页按 agentConfigId 过滤会话列表） */
async function onAgentSelect(id: number | string) {
  const agentId = Number(id)
  if (agentId) currentAgentId.value = agentId
  syncChatRuntime()
  onNewChat()
  conversationList.value = []
  activeKey.value = '/chat'
  await loadConversations()
}

/**
 * 聊天页侧栏的智能体切换器数据。
 *
 * <p>runningCount 统计「该 Agent 名下正在后台跑流的会话数」：多智能体并行时，
 * 用户切到另一个 Agent 前需要知道原来那个是不是还在干活（切走不会中断）。
 * 草稿会话（还没有 id）归属当前 Agent。</p>
 */
const agentPickerItems = computed<AgentPickerItem[]>(() => {
  const runningByAgent: Record<number, number> = {}
  for (const key of Object.keys(streamingMap.value)) {
    if (!streamingMap.value[key]) continue
    const agentId = conversationAgentMap.value[key] ?? currentAgentId.value
    if (!agentId) continue
    runningByAgent[agentId] = (runningByAgent[agentId] ?? 0) + 1
  }
  return agentList.value.map(a => ({
    id: a.id,
    name: a.name,
    avatar: a.avatar,
    model: a.model,
    isDefault: a.isDefault,
    runningCount: runningByAgent[Number(a.id)] ?? 0
  }))
})

/**
 * 切换智能体（聊天页内，不离开当前页面）。
 *
 * <p>与智能体管理页的 onAgentSelect 区别：这里**不 abort 任何后台流**，也不跳页。
 * 切走后会话列表按新 Agent 重拉，旧 Agent 的会话与后台流仍按 key 分片保留；
 * 切回来时侧栏会重新拉到那些会话，消息内容直接从分片状态取（无需重放 SSE）。</p>
 */
async function onSwitchAgent(id: number | string) {
  const agentId = Number(id)
  if (!agentId || agentId === currentAgentId.value) return
  const prevName = currentAgent.value?.name || '原智能体'
  currentAgentId.value = agentId
  syncChatRuntime()
  // 当前会话属于旧 Agent，先切到该 Agent 的空草稿；旧会话的后台流不受影响
  onNewChat()
  conversationList.value = []
  await loadConversations()
  const name = currentAgent.value?.name || `#${agentId}`
  const running = backgroundCount.value
  toast.success(`已切换到「${name}」`, {
    description: running
      ? `${running} 个会话仍在后台运行；切回「${prevName}」可在侧栏继续查看`
      : undefined
  })
}

/* ============================ 技能管理页事件 ============================ */

/**
 * 保存技能：payload 带 id → 编辑（PUT /skills/{id}），否则新建（POST /skills）
 *
 * <p><b>字段映射陷阱</b>：抽屉给的是 `tools: string[]` 与 `triggerWords: string[]`，
 * 而后端 `tool_names` / `trigger_words` 都按 <b>JSON 数组字符串</b> 存储
 * （`DeepSeekServiceImpl` 用 `objectMapper.readValue(toolNames, List.class)` 解析）。
 * 若传逗号串会导致解析失败并静默回落到「全部工具」，等于工具授权失效。</p>
 *
 * <p><b>作用域</b>：`agentConfigId` 传 0 表示全局（后端约定见 SkillController）。</p>
 */
async function onSkillSave(payload: Record<string, unknown>) {
  const id = payload.id as number | undefined
  const uid = currentUserId.value
  if (!uid) {
    toast.warning('未获取到当前用户身份，无法保存技能')
    return
  }
  const tools = (payload.tools as string[]) || []
  const triggerWords = (payload.triggerWords as string[]) || []
  const body: Partial<SkillData> = {
    name: String(payload.name ?? '').trim(),
    description: String(payload.description ?? ''),
    instructions: String(payload.instructions ?? ''),
    toolNames: JSON.stringify(tools),
    triggerWords: JSON.stringify(triggerWords),
    userId: uid,
    // 0 = 全局（后端 > 0 才绑定到具体 Agent）
    agentConfigId: (payload.agentConfigId as number | null | undefined) ?? 0,
    // agent_type 为 NOT NULL 列，管理页固定写默认助手类型（后端也有同值兜底）
    agentType: 'code_assistant'
  }
  if (!body.name) {
    toast.warning('请填写技能名称')
    return
  }
  try {
    if (id) {
      await updateSkill(Number(id), body)
      toast.success('技能已更新')
    } else {
      await createSkill(body)
      toast.success('技能已创建')
    }
    await loadSkills()
  } catch (err) {
    toast.error('保存失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

/** 删除技能（后端校验归属，必须带当前 userId） */
async function onSkillDelete(id: number | string) {
  const target = skillList.value.find(s => s.id === id)
  if (!(await confirmDelete('技能', target?.name))) return
  const uid = currentUserId.value
  if (!uid) {
    toast.warning('未获取到当前用户身份，无法删除技能')
    return
  }
  try {
    await deleteSkillApi(Number(id), uid)
    toast.success('技能已删除')
    await loadSkills()
  } catch (err) {
    toast.error('删除失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

/* ============================ MCP 服务器页：真实接口 ============================ */
/** 列表行 = McpServerItem（展示）+ 请求头是否已配置（列表接口不回显明文） */
type McpRow = McpServerItem & { headersConfigured?: boolean }

const mcpLoading = ref(false)
const mcpError = ref('')
const mcpServers = ref<McpRow[]>([])

/** 后端 McpServerVO → 列表行（状态 CONNECTED / DISABLED / FAILED 由后端按实时连接计算） */
function toMcpRow(vo: McpServerVO): McpRow {
  return {
    id: vo.id,
    name: vo.name,
    type: vo.type,
    url: vo.url,
    command: vo.command,
    toolPrefix: vo.toolPrefix,
    permissionLevel: vo.permissionLevel,
    enabled: (vo.enabled === 1 ? 1 : 0) as 0 | 1,
    autoRegister: (vo.autoRegister === 1 ? 1 : 0) as 0 | 1,
    status: vo.status,
    toolCount: vo.toolCount,
    registeredToolCount: vo.registeredToolCount,
    errorMessage: vo.errorMessage,
    headersConfigured: vo.hasHeaders
  }
}

/**
 * 由列表行还原完整提交体（改单个字段也要传全量）
 * ----------------------------------------------------------------------------
 * 后端 `McpServerService.validate()` 强制校验 name / type / url|command，
 * 只传 `{ enabled }` 会被 400 拒绝，所以这里以当前行数据为基底。
 *
 * `headers` 故意不带上：留空表示保持原值。后端 Mapper 已改为动态 SQL，
 * null 列不会覆盖，避免「改个名字把鉴权头清空」的静默数据丢失。
 */
function mcpConfigOf(server: McpServerItem, patch: Partial<McpServerConfig> = {}): McpServerConfig {
  return {
    name: server.name,
    type: server.type,
    url: server.url,
    command: server.command,
    toolPrefix: server.toolPrefix,
    permissionLevel: server.permissionLevel || 'SAFE',
    enabled: server.enabled ?? 0,
    autoRegister: server.autoRegister ?? 0,
    ...patch
  }
}

/** 拉取 MCP 服务器列表（配置 + 实时连接状态） */
async function loadMcpServers() {
  mcpLoading.value = true
  mcpError.value = ''
  try {
    const list = (await listMcpServers()).data || []
    mcpServers.value = list.map(toMcpRow)
  } catch (err) {
    mcpServers.value = []
    mcpError.value = readErrorMessage(err, '请确认后端服务已启动（端口 8084）')
  } finally {
    mcpLoading.value = false
  }
}

/* ============================ MCP 服务器页事件 ============================ */

async function onMcpReload() {
  await loadMcpServers()
}

/** 连接：后端会连接并注册工具，返回的 VO 里带最新工具数 */
async function onMcpConnect(server: McpServerItem) {
  try {
    const vo = (await connectMcpServer(Number(server.id))).data
    toast.success('连接成功', { description: `已注册 ${vo.registeredToolCount ?? 0} 个工具` })
  } catch (err) {
    toast.error('连接失败', { description: readErrorMessage(err, '请检查地址与请求头配置') })
  } finally {
    await loadMcpServers()
  }
}

/** 断开：已注册的工具会被注销，先二次确认 */
async function onMcpDisconnect(server: McpServerItem) {
  const ok = await confirm({
    title: '断开 MCP 服务器',
    content: `确定断开「${server.name}」吗？已注册的工具会从工具表注销，AI 将无法再调用它们。`,
    okText: '断开',
    type: 'warning'
  })
  if (!ok) return
  try {
    await disconnectMcpServer(Number(server.id))
    toast.success('已断开')
  } catch (err) {
    toast.error('断开失败', { description: readErrorMessage(err, '请稍后重试') })
  } finally {
    await loadMcpServers()
  }
}

/** 刷新工具列表（内部断开重连并按当前配置重新注册） */
async function onMcpRefresh(server: McpServerItem) {
  try {
    const vo = (await refreshMcpServer(Number(server.id))).data
    toast.success('工具列表已刷新', { description: `当前 ${vo.toolCount ?? 0} 个工具` })
  } catch (err) {
    toast.error('刷新失败', { description: readErrorMessage(err, '请稍后重试') })
  } finally {
    await loadMcpServers()
  }
}

/**
 * 保存 MCP 服务器：payload 带 id → 编辑（PUT），否则新增（POST）
 *
 * <p>`headers` 留空 = 不修改（列表接口只回显 hasHeaders，拿不到明文）；
 * 需要清空请求头时填 `{}` 即可（非 null 才会覆盖该列）。</p>
 */
async function onMcpSave(payload: Record<string, unknown>) {
  const id = payload.id as number | undefined
  const body: McpServerConfig = {
    name: String(payload.name ?? '').trim(),
    type: payload.type as 'http' | 'stdio',
    url: payload.url as string | undefined,
    command: payload.command as string | undefined,
    headers: payload.headers as string | undefined,
    toolPrefix: payload.toolPrefix as string | undefined,
    permissionLevel: String(payload.permissionLevel ?? 'SAFE'),
    enabled: Number(payload.enabled ?? 0),
    autoRegister: Number(payload.autoRegister ?? 0)
  }
  if (!body.name) {
    toast.warning('请填写服务器名称')
    return
  }
  try {
    if (id) {
      await updateMcpServer(Number(id), body)
      toast.success('MCP 服务器已更新')
    } else {
      await createMcpServer(body)
      toast.success('MCP 服务器已创建', { description: '启用且自动注册时会立即尝试连接' })
    }
    await loadMcpServers()
  } catch (err) {
    toast.error('保存失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

async function onMcpDelete(id: number | string) {
  const target = mcpServers.value.find(s => s.id === id)
  if (!(await confirmDelete('MCP 服务器', target?.name))) return
  try {
    await deleteMcpServer(Number(id))
    toast.success('MCP 服务器已删除')
    await loadMcpServers()
  } catch (err) {
    toast.error('删除失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

/** 启用/停用：后端在「启用 + 自动注册」时自动重连，否则断开 */
async function onMcpToggleEnabled(payload: { server: McpServerItem; enabled: 0 | 1 }) {
  const target = mcpServers.value.find(s => s.id === payload.server.id)
  if (!target) return
  try {
    await updateMcpServer(Number(target.id), mcpConfigOf(target, { enabled: payload.enabled }))
    toast.success(payload.enabled ? '已启用' : '已停用')
    await loadMcpServers()
  } catch (err) {
    toast.error('切换失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

/* ============================ 定时任务页：真实接口 ============================ */
/**
 * 数据接缝（/api/schedule-task，均需管理员）：
 *   GET    /list          → ScheduleTask[]
 *   POST   /create        → { name, agentType, agentConfigId, instruction, cronExpression, executeTime, maxExecuteCount }
 *   PUT    /update        → 全量覆盖 8 列，必须带齐 agentType / status，缺字段会被写成 NULL
 *   DELETE /delete/{id}
 *   POST   /enable/{id}   → 启用；cron 任务会顺带重算 execute_time（下次执行时间）
 *   POST   /disable/{id}
 *   POST   /run/{id}      → 立即执行一次：建会话 + 异步跑指令，返回会话 id；
 *                           不改 status / execute_time（不打乱调度计划），仅累加执行统计
 * 两个后端语义要点：
 *   1) 调度器每 30s 扫 status='ENABLED' AND (execute_time IS NULL OR execute_time <= NOW())，
 *      新建 cron 任务时 execute_time 为 NULL → 会被立刻执行一次；故保存后追加 enable 让它先算出下次时间；
 *   2) 手动「立即执行」与定时触发共用同一套执行流程，但不会把任务算成「已到期」。
 */
const scheduleTasks = ref<Array<ScheduleTaskItem & { enabled?: 0 | 1 }>>([])
const scheduleLoading = ref(false)
const scheduleError = ref('')
/** 正在「立即执行」的任务 id（0 表示空闲），用于行内按钮 loading 与防重入 */
const scheduleRunBusy = ref(0)

/** 后端 ScheduleTask → 行组件 ScheduleTaskItem */
function toScheduleRow(task: ApiScheduleTask): ScheduleTaskItem & { enabled?: 0 | 1 } {
  return {
    id: task.id,
    name: task.name,
    agentConfigId: task.agentConfigId ?? null,
    agentName: agentList.value.find(a => Number(a.id) === Number(task.agentConfigId))?.name,
    cronExpression: task.cronExpression || '',
    executeTime: normalizeTimeText(task.executeTime) ?? '',
    instruction: task.instruction,
    maxExecuteCount: task.maxExecuteCount ?? 0,
    executeCount: task.executeCount ?? 0,
    status: task.status,
    enabled: task.status === 'ENABLED' ? 1 : 0,
    lastExecuteTime: normalizeTimeText(task.lastExecuteTime) ?? '',
    lastConversationId: task.lastConversationId ?? undefined
  }
}

async function loadScheduleTasks() {
  scheduleLoading.value = true
  scheduleError.value = ''
  try {
    const res = await getTaskList()
    scheduleTasks.value = (res.data ?? []).map(toScheduleRow)
  } catch (err) {
    scheduleError.value = readErrorMessage(err, '加载定时任务失败')
  } finally {
    scheduleLoading.value = false
  }
}

/* ============================ 定时任务页事件 ============================ */

/**
 * 保存定时任务
 * ----------------------------------------------------------------------------
 * agent_type 是 NOT NULL 列且后端 update 全量覆盖，因此每次都必须显式提交；
 * cron 任务提交 cronExpression + executeTime=null，一次性任务反之（executeTime 传 null 而非空串，
 * 否则 LocalDateTime + @JsonFormat 反序列化会直接 400）。
 */
async function onScheduleSave(payload: Record<string, unknown>) {
  const name = String(payload.name ?? '').trim()
  const instruction = String(payload.instruction ?? '').trim()
  if (!name || !instruction) return

  const cronExpression = (payload.cronExpression as string | null) ?? null
  const executeTime = (payload.executeTime as string | null) ?? null
  const maxExecuteCount = Number(payload.maxExecuteCount ?? 0)
  const status = String(payload.status ?? 'ENABLED')
  const agentConfigId = (payload.agentConfigId as number | null) ?? null
  const editingId = payload.id ? Number(payload.id) : 0

  try {
    if (editingId) {
      await updateTask({
        id: editingId,
        name,
        agentType: 'code_assistant',
        agentConfigId: agentConfigId ?? undefined,
        instruction,
        cronExpression: cronExpression ?? undefined,
        executeTime: executeTime ?? undefined,
        maxExecuteCount,
        status
      })
      // update 会把 execute_time 写成 NULL（cron 模式），必须让后端重算一次，
      // 否则调度器 30s 内会把这个任务当成「到期」立刻跑一次
      if (status === 'ENABLED' && cronExpression) await enableTask(editingId)
      toast.success('已保存定时任务')
    } else {
      const res = await createTask({
        name,
        agentType: 'code_assistant',
        agentConfigId: agentConfigId ?? undefined,
        instruction,
        cronExpression: cronExpression ?? undefined,
        executeTime: executeTime ?? undefined,
        maxExecuteCount
      })
      const newId = Number(res.data?.id ?? 0)
      if (newId && status === 'ENABLED' && cronExpression) await enableTask(newId)
      if (newId && status === 'DISABLED') await disableTask(newId)
      toast.success('已创建定时任务')
    }
    await loadScheduleTasks()
  } catch (err) {
    toast.error(readErrorMessage(err, '保存定时任务失败'))
  }
}

async function onScheduleDelete(id: number | string) {
  const target = scheduleTasks.value.find(t => String(t.id) === String(id))
  if (!(await confirmDelete('定时任务', target?.name))) return
  try {
    await deleteTask(Number(id))
    toast.success('已删除定时任务')
    await loadScheduleTasks()
  } catch (err) {
    toast.error(readErrorMessage(err, '删除定时任务失败'))
  }
}

async function onScheduleToggleStatus(payload: { task: ScheduleTaskItem; enabled: boolean }) {
  const id = Number(payload.task.id)
  try {
    if (payload.enabled) await enableTask(id)
    else await disableTask(id)
    await loadScheduleTasks()
  } catch (err) {
    toast.error(readErrorMessage(err, payload.enabled ? '启用定时任务失败' : '禁用定时任务失败'))
  }
}

/**
 * 立即执行一次（`POST /api/schedule-task/run/{id}`）
 * ----------------------------------------------------------------------------
 * 后端建好会话后异步跑任务指令，因此这里只等「触发成功」，不等 AI 跑完。
 * 触发成功后刷新列表拿最新执行次数/上次执行时间，并提示可以跳到新会话看过程。
 */
async function onScheduleRun(task: ScheduleTaskItem) {
  const id = Number(task.id)
  if (!id || scheduleRunBusy.value) return
  scheduleRunBusy.value = id
  const toastId = toast.loading('正在触发执行…', { description: task.name })
  try {
    const res = await runTaskNow(id)
    const convId = Number(res.data ?? 0)
    toast.update(toastId, {
      type: 'success',
      message: `已触发「${task.name}」`,
      description: convId ? `执行过程在会话 #${convId} 中展开，可从侧栏或「查看会话」进入` : '任务已开始执行'
    })
    await loadScheduleTasks()
  } catch (err) {
    toast.update(toastId, {
      type: 'error',
      message: '立即执行失败',
      description: readErrorMessage(err, '请稍后重试')
    })
  } finally {
    scheduleRunBusy.value = 0
  }
}

/** 跳到该任务最近一次执行产生的会话（聊天页已接真实接口，直接复用切会话逻辑） */
function onScheduleViewConversation(task: ScheduleTaskItem) {
  if (!task.lastConversationId) {
    toast.info('该任务还没有执行过')
    return
  }
  void onSelectConversation(String(task.lastConversationId))
}

/* ============================ 踩坑经验页：真实接口 ============================ */
/**
 * 单页拉取上限（后端 size 上限 100）
 * ----------------------------------------------------------------------------
 * 管理页暂无分页 UI，先一次拉 100 条；`lessonTotal` 是后端筛选条件下的全量条数，
 * 超出时列表页会在统计行标注「已载入 N 条」，不会假装全部已加载。
 */
const LESSON_PAGE_SIZE = 100

const lessonList = ref<LessonItem[]>([])
const lessonClusters = ref<LessonClusterItem[]>([])
const lessonTotal = ref(0)
const lessonLoading = ref(false)
const lessonError = ref('')

/** 后端 Lesson → 列表行（只挑展示层需要的字段，避免把 errorSignature / envParams 带进来） */
function toLessonItem(data: LessonData): LessonItem {
  return {
    id: data.id ?? 0,
    projectKey: data.projectKey,
    toolName: data.toolName,
    errorCategory: data.errorCategory,
    errorCode: data.errorCode,
    symptom: data.symptom,
    rootCause: data.rootCause,
    solution: data.solution,
    paramsJson: data.paramsJson,
    applicableCond: data.applicableCond,
    keywords: data.keywords,
    status: data.status,
    source: data.source,
    type: data.type,
    goal: data.goal,
    hitCount: data.hitCount,
    successCount: data.successCount,
    failCount: data.failCount,
    createdAt: data.createdAt,
    updatedAt: data.updatedAt
  }
}

/** 后端重复组（下划线键）→ 前端驼峰 */
function toClusterItem(raw: LessonCluster): LessonClusterItem {
  return {
    projectKey: raw.project_key,
    toolName: raw.tool_name,
    errorCategory: raw.error_category,
    errorCode: raw.error_code,
    count: raw.cnt,
    sampleId: raw.sample_id
  }
}

/**
 * 拉取经验库：列表 + 重复组并行
 * ----------------------------------------------------------------------------
 * 管理页面**不传 projectKey**：后端语义为「查询全部项目」，避免 LLM 记录经验时用的
 * 项目根目录名与页面默认值不一致，导致明明有数据却查不到。
 */
async function loadLessons() {
  lessonLoading.value = true
  lessonError.value = ''
  try {
    const [pageRes, clusterRes] = await Promise.all([
      pageLessons({ page: 1, size: LESSON_PAGE_SIZE }),
      listLessonClusters(undefined, 50)
    ])
    const page = pageRes.data
    lessonList.value = (page?.items || []).map(toLessonItem)
    lessonTotal.value = Number(page?.total) || 0
    lessonClusters.value = (clusterRes.data || []).map(toClusterItem)
  } catch (err) {
    lessonList.value = []
    lessonClusters.value = []
    lessonError.value = readErrorMessage(err, '请确认后端服务已启动（端口 8084）')
  } finally {
    lessonLoading.value = false
  }
}

/* ============================ 踩坑经验页事件 ============================ */

/** 补全/编辑：只提交非空字段（后端 completeLesson 语义：null 表示不修改） */
async function onLessonSave(payload: Record<string, unknown>) {
  const id = Number(payload.id ?? 0)
  if (!id) {
    toast.warning('缺少经验 ID，无法保存')
    return
  }
  try {
    await updateLesson(id, {
      rootCause: payload.rootCause as string | undefined,
      solution: payload.solution as string | undefined,
      paramsJson: payload.paramsJson as string | undefined,
      applicableCond: payload.applicableCond as string | undefined,
      keywords: payload.keywords as string | undefined
    })
    toast.success('经验已更新')
    await loadLessons()
  } catch (err) {
    toast.error('保存失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

/** 反馈验证结果：后端按「有效累计 ≥2 次转正 / 连续失败 ≥5 次隐藏」自动调整状态 */
async function onLessonFeedback(payload: { lesson: LessonItem; effective: boolean }) {
  const id = Number(payload.lesson.id)
  if (!id) return
  try {
    await feedbackLesson(id, payload.effective)
    toast.success(payload.effective ? '已记录为「有效」' : '已记录为「无效」')
    await loadLessons()
  } catch (err) {
    toast.error('反馈失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

async function onLessonDelete(id: number | string) {
  const target = lessonList.value.find(l => l.id === id)
  const label = target?.symptom ? target.symptom.slice(0, 24) : undefined
  if (!(await confirmDelete('踩坑经验', label))) return
  try {
    await deleteLesson(Number(id))
    toast.success('经验已删除')
    await loadLessons()
  } catch (err) {
    toast.error('删除失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

/** 归并重复组：后端要求 projectKey / toolName / errorCategory / errorCode 全部非空 */
async function onLessonMerge(group: LessonClusterItem) {
  const projectKey = group.projectKey || ''
  const toolName = group.toolName || ''
  const errorCategory = group.errorCategory || ''
  const errorCode = group.errorCode || ''
  if (!projectKey || !toolName || !errorCategory || !errorCode) {
    toast.warning('该分组缺少项目 / 工具 / 类别 / 错误码，无法归并')
    return
  }
  try {
    const message = (await mergeLessonGroup({ projectKey, toolName, errorCategory, errorCode })).data
    toast.success('归并完成', { description: message })
    await loadLessons()
  } catch (err) {
    toast.error('归并失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

async function onLessonRefresh() {
  await loadLessons()
}

/* ============================ 数据库连接页：真实接口 ============================ */

const dbConnections = ref<DbConnectionItem[]>([])
const dbLoading = ref(false)
const dbError = ref('')

/**
 * 后端 DbConnectionVO → 列表行
 * ----------------------------------------------------------------------------
 * 字段基本同名，只需把时间压成本地可读格式（`LocalDateTime` 默认序列化为 ISO-8601），
 * 并保留 `userId`（null=系统级共享，行组件据此显示「系统级 / 我的」）。
 */
function toDbRow(vo: DbConnectionVO): DbConnectionItem {
  return {
    id: vo.id,
    name: vo.name,
    dbType: vo.dbType,
    host: vo.host,
    port: vo.port ?? null,
    databaseName: vo.databaseName,
    username: vo.username,
    hasPassword: vo.hasPassword,
    extraParams: vo.extraParams,
    enabled: Number(vo.enabled) === 1 ? 1 : 0,
    userId: vo.userId ?? null,
    createdAt: normalizeTimeText(vo.createdAt),
    updatedAt: normalizeTimeText(vo.updatedAt)
  }
}

/**
 * 由列表行还原完整提交体（改单个字段也要传全量）
 * ----------------------------------------------------------------------------
 * 后端 `DbConnectionMapper.update` 是**全量 UPDATE**（name/db_type/host/port/database_name/
 * username/password_encrypted/extra_params/enabled 一律覆盖），只传 `{ enabled }` 会把其余列写空。
 * 因此「启用开关」「编辑保存」都必须以当前行数据为基底。
 *
 * `passwordPlain` 故意不带：留空 = 后端保留原密文（update 里 passwordEncrypted 继承旧值）。
 */
function dbPayloadOf(row: DbConnectionItem, patch: Partial<DbConnectionConfig> = {}): DbConnectionConfig {
  const type = (row.dbType || 'mysql').toLowerCase() as DbConnectionConfig['dbType']
  const isH2 = type === 'h2'
  return {
    name: row.name,
    dbType: type,
    // h2 为 file 模式：无主机端口，后端 validate 也只在非 h2 时校验 host
    host: isH2 ? undefined : row.host || undefined,
    port: isH2 ? undefined : row.port ?? undefined,
    databaseName: row.databaseName,
    username: row.username || undefined,
    extraParams: row.extraParams || undefined,
    enabled: Number(row.enabled) === 1 ? 1 : 0,
    ...patch
  }
}

/** 拉取可见连接列表（系统级 + 本人；出参已脱敏，密码只有 hasPassword 布尔） */
async function loadDbConnections() {
  dbLoading.value = true
  dbError.value = ''
  try {
    const list = (await listDbConnections()).data || []
    dbConnections.value = list.map(toDbRow)
  } catch (err) {
    dbConnections.value = []
    dbError.value = readErrorMessage(err, '请确认后端服务已启动（端口 8084）')
  } finally {
    dbLoading.value = false
  }
}

/** 「测试连接」的就地反馈：行内按连接 id 索引，编辑抽屉单条 */
const dbRowTestStates = reactive<Record<string, DbTestState>>({})
const dbEditTestState = ref<DbTestState | null>(null)

/** 执行 SQL 的结果 / 错误 / 进行中标记 */
const dbExecuteResult = ref<SqlExecuteResult | null>(null)
const dbExecuteError = ref('')
const dbExecuting = ref(false)

/* ============================ 数据库连接页事件 ============================ */

/** 抽屉表单 → 提交体（payload 来自 DbConnectionEditDrawer.buildPayload） */
function dbBodyOf(payload: Record<string, unknown>): DbConnectionConfig {
  const body: DbConnectionConfig = {
    name: String(payload.name ?? '').trim(),
    dbType: String(payload.dbType ?? 'mysql').toLowerCase() as DbConnectionConfig['dbType'],
    host: (payload.host as string | null | undefined) ?? undefined,
    port: (payload.port as number | null | undefined) ?? undefined,
    databaseName: String(payload.databaseName ?? '').trim(),
    username: (payload.username as string | null | undefined) ?? undefined,
    extraParams: (payload.extraParams as string | null | undefined) ?? undefined,
    enabled: Number(payload.enabled) === 0 ? 0 : 1
  }
  // 密码只写不读：只有填了新密码才提交（留空 = 后端保留原密文）
  const pwd = String(payload.passwordPlain ?? '')
  if (pwd) body.passwordPlain = pwd
  return body
}

/**
 * 新建 / 编辑连接
 * ----------------------------------------------------------------------------
 * payload 带 id → `PUT /api/db-connections/{id}`，否则 `POST /api/db-connections`。
 * 后端 `validate` 在创建时要求密码必填（用户名空或 sa 的 h2 场景除外），
 * 失败原因（如「主机地址不能为空」）直接 toast 出来；抽屉是乐观关闭的，数据可重新填写。
 */
async function onDbSave(payload: Record<string, unknown>) {
  const id = payload.id as number | undefined
  const body = dbBodyOf(payload)
  try {
    if (id != null) {
      await updateDbConnection(Number(id), body)
      toast.success('连接已更新')
    } else {
      await createDbConnection(body)
      toast.success('连接已创建')
    }
    dbEditTestState.value = null
    await loadDbConnections()
  } catch (err) {
    toast.error('保存失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

async function onDbDelete(id: number | string) {
  const target = dbConnections.value.find(c => String(c.id) === String(id))
  if (!(await confirmDelete('数据库连接', target?.name))) return
  try {
    await deleteDbConnection(Number(id))
    toast.success('连接已删除')
    await loadDbConnections()
  } catch (err) {
    toast.error('删除失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

/** 启用 / 停用：后端 update 全量覆盖，故以当前行为基底只改 enabled */
async function onDbToggle(db: DbConnectionItem) {
  const next = Number(db.enabled) === 1 ? 0 : 1
  try {
    await updateDbConnection(Number(db.id), dbPayloadOf(db, { enabled: next }))
    toast.success(next === 1 ? '已启用' : '已停用')
    await loadDbConnections()
  } catch (err) {
    toast.error('操作失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

/** 行内「测试连接」：已保存连接只传 id，密码由服务端解密 */
async function onDbTest(db: DbConnectionItem) {
  const key = String(db.id)
  dbRowTestStates[key] = { status: 'testing', text: '正在测试连接…' }
  const startedAt = Date.now()
  try {
    const res = await testSavedDbConnection(Number(db.id))
    dbRowTestStates[key] = {
      status: 'ok',
      text: `${res.message || '连接成功'} · ${Date.now() - startedAt} ms`
    }
  } catch (err) {
    dbRowTestStates[key] = { status: 'fail', text: readErrorMessage(err, '连接失败') }
  }
}

/**
 * 编辑抽屉内「测试连接」
 * ----------------------------------------------------------------------------
 * 后端 `DbConnectionService.test` 的规则：入参带 id 且 passwordPlain 为空时，
 * 直接用**库里已存配置**（会忽略表单里改动的其他字段）；只有带明文密码才用表单值。
 * 因此：新建 → `POST /test`（完整配置）；编辑且填了新密码 → `POST /test`；
 *      编辑且未填密码 → `POST /{id}/test`（测的是已保存配置）。
 */
async function onDbTestForm(payload: Record<string, unknown>) {
  dbEditTestState.value = { status: 'testing', text: '正在测试连接…' }
  const startedAt = Date.now()
  const id = payload.id as number | undefined
  const body = dbBodyOf(payload)
  try {
    const res =
      id != null && !body.passwordPlain
        ? await testSavedDbConnection(Number(id))
        : await testDbConnection(body)
    dbEditTestState.value = {
      status: 'ok',
      text: `${res.message || '连接成功'} · ${Date.now() - startedAt} ms`
    }
  } catch (err) {
    dbEditTestState.value = { status: 'fail', text: readErrorMessage(err, '连接失败') }
  }
}

/** 执行 SQL（管理页宽松模式：多条语句按顺序执行，查询结果按 maxRows 截断） */
async function onDbExecute(payload: { id: number | string; sql: string; maxRows: number }) {
  dbExecuting.value = true
  dbExecuteError.value = ''
  dbExecuteResult.value = null
  try {
    const res = await executeDbConnectionSql(Number(payload.id), payload.sql, payload.maxRows)
    dbExecuteResult.value = res.data || null
  } catch (err) {
    dbExecuteError.value = readErrorMessage(err, '执行失败')
  } finally {
    dbExecuting.value = false
  }
}

async function onDbRefresh() {
  await loadDbConnections()
}

/* ============================ 文件管理页：真实接口 ============================ */

/** 每页条数：与后端 `/page` 默认值一致（pageSize 上限 200） */
const FILE_PAGE_SIZE = 20

const fileAssets = ref<FileItem[]>([])
const fileTotal = ref(0)
const fileTotalSize = ref(0)
const filePage = ref(1)
const filePageSize = ref(FILE_PAGE_SIZE)
const fileKeyword = ref('')
const fileStorageFilter = ref<'all' | 'cloud' | 'local'>('all')
const fileStatusFilter = ref<'all' | 'active' | 'deleted' | 'orphan'>('all')
const fileLoading = ref(false)
const fileError = ref('')

/** 后端 FileAssetVO → 列表行（时间压成本地可读格式） */
function toFileRow(vo: FileAssetData): FileItem {
  return {
    id: vo.id,
    fileId: vo.fileId ?? undefined,
    filename: vo.filename,
    displayName: vo.displayName,
    mimeType: vo.mimeType,
    storageType: vo.storageType,
    size: vo.size,
    source: vo.source,
    status: vo.status,
    providerCode: vo.providerCode ?? undefined,
    createdAt: normalizeTimeText(vo.createdAt),
    previewUrl: vo.previewUrl,
    references: vo.references
  }
}

/**
 * 拉取当前页文件（筛选与分页全部交给后端）
 * ----------------------------------------------------------------------------
 * `total` / `totalSize` 是后端在**同一筛选条件下**的全量统计，不受分页影响；
 * 「云端 / 本地 / 孤儿」三项后端没有全量口径，列表页按当前页统计并标注「本页」。
 */
async function loadFiles() {
  fileLoading.value = true
  fileError.value = ''
  try {
    const res = await listFileAssetsPaged({
      page: filePage.value,
      pageSize: filePageSize.value,
      order: 'desc',
      keyword: fileKeyword.value.trim() || undefined,
      status: fileStatusFilter.value === 'all' ? undefined : fileStatusFilter.value,
      storageType: fileStorageFilter.value === 'all' ? undefined : fileStorageFilter.value,
      withRefs: true
    })
    const page = res.data
    fileAssets.value = (page?.records || []).map(toFileRow)
    fileTotal.value = Number(page?.total) || 0
    fileTotalSize.value = Number(page?.totalSize) || 0
    // 删除末页最后一条后页码可能越界：回退到最后一页重查（只回退一次，避免死循环）
    if (!fileAssets.value.length && filePage.value > 1 && fileTotal.value > 0) {
      const lastPage = Math.max(1, Math.ceil(fileTotal.value / filePageSize.value))
      if (lastPage < filePage.value) {
        filePage.value = lastPage
        await loadFiles()
      }
    }
  } catch (err) {
    fileAssets.value = []
    fileTotal.value = 0
    fileTotalSize.value = 0
    fileError.value = readErrorMessage(err, '请确认后端服务已启动（端口 8084）')
  } finally {
    fileLoading.value = false
  }
}

/** 云端文件清单（GET /api/files/cloud；服务端要拉 Provider 全量清单，可能较慢，故按需加载） */
const cloudFiles = ref<CloudFileListModel | null>(null)
const cloudLoading = ref(false)

/** 图片预览地址：preview 接口需鉴权头，用 fetch + blob 生成（api 层已按 id 缓存） */
const filePreviewSrc = ref('')

/* ============================ 文件管理页事件 ============================ */

/** 搜索：300ms 防抖后回第一页重查（避免逐字符打接口） */
let fileKeywordTimer: number | undefined
function onFileSearch(keyword: string) {
  fileKeyword.value = keyword
  filePage.value = 1
  if (fileKeywordTimer) window.clearTimeout(fileKeywordTimer)
  fileKeywordTimer = window.setTimeout(() => {
    void loadFiles()
  }, 300)
}

function onFilePageChange(page: number) {
  if (page < 1 || page === filePage.value) return
  filePage.value = page
  void loadFiles()
}

function onFileFilterChange(payload: {
  storageType: 'all' | 'cloud' | 'local'
  status: 'all' | 'active' | 'deleted' | 'orphan'
}) {
  fileStorageFilter.value = payload.storageType
  fileStatusFilter.value = payload.status
  filePage.value = 1
  void loadFiles()
}

/**
 * 上传文件（后端为单文件接口 `POST /api/files`，故按文件循环调用）
 * ----------------------------------------------------------------------------
 * `uploadFileAsset` 用原生 fetch（大文件可能超过通用 15s 超时），且返回值不抛异常，
 * 需自己判断 `code !== 200`；逐个失败不影响其余文件，最后统一汇报。
 */
async function onFileUpload(files: File[]) {
  if (!files.length) return
  const errors: string[] = []
  let ok = 0
  for (const file of files) {
    try {
      const res = await uploadFileAsset(file, { source: 'upload' })
      if (res.code === 200) ok += 1
      else errors.push(`${file.name}：${res.message || '上传失败'}`)
    } catch (err) {
      errors.push(`${file.name}：${readErrorMessage(err, '上传失败')}`)
    }
  }
  if (ok) toast.success(`已上传 ${ok} 个文件`)
  if (errors.length) toast.error(`${errors.length} 个文件上传失败`, { description: errors[0] })
  filePage.value = 1
  await loadFiles()
}

/** 打开详情抽屉时按需加载预览（仅图片有本地副本可预览；api 层按 id 缓存 objectURL） */
async function onFileOpen(file: FileItem) {
  filePreviewSrc.value = ''
  if (!(file.mimeType || '').startsWith('image/')) return
  try {
    filePreviewSrc.value = await getFilePreviewBlobUrl(Number(file.id))
  } catch (err) {
    // 抽屉内有「预览不可用」占位，本地副本可能已被清理，这里只记录不打扰用户
    console.warn('加载文件预览失败:', readErrorMessage(err, '未知错误'))
  }
}

/** 重命名：空串表示恢复显示原始文件名（PATCH 只改本地 displayName） */
async function onFileRename(payload: { id: number | string; displayName: string }) {
  try {
    await renameFileAsset(Number(payload.id), payload.displayName)
    toast.success(payload.displayName ? '已重命名' : '已恢复原始文件名')
    await loadFiles()
  } catch (err) {
    toast.error('重命名失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

async function onFileDelete(id: number | string) {
  const target = fileAssets.value.find(f => String(f.id) === String(id))
  const label = target?.displayName || target?.filename
  if (
    !(await confirmDelete('文件', label, {
      hint: '将同时清理云端文件（404 视为已删除）、引用记录与本地副本，此操作不可撤销。'
    }))
  ) {
    return
  }
  try {
    await deleteFileAsset(Number(id))
    invalidatePreviewCache(Number(id))
    filePreviewSrc.value = ''
    toast.success('文件已删除')
    await loadFiles()
  } catch (err) {
    toast.error('删除失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

/** 拉取云端文件清单（账号级；与本地记录比对标注「已登记 / 仅云端」） */
async function loadCloudFiles() {
  cloudLoading.value = true
  try {
    const res = await listCloudFiles()
    const data = res.data as CloudFileListData | null
    cloudFiles.value = data
      ? {
          providerCode: data.providerCode,
          total: data.total,
          linkedCount: data.linkedCount,
          cloudOnlyCount: data.cloudOnlyCount,
          cloudOnlySize: data.cloudOnlySize,
          truncated: data.truncated,
          files: (data.files || []).map(f => ({
            fileId: f.fileId,
            filename: f.filename,
            bytes: f.bytes ?? undefined,
            createdAtEpoch: f.createdAtEpoch ?? undefined,
            expiresAtEpoch: f.expiresAtEpoch ?? undefined,
            linked: f.linked,
            linkedAssetId: f.linkedAssetId ?? undefined,
            linkedDisplayName: f.linkedDisplayName ?? undefined,
            linkedStatus: f.linkedStatus ?? undefined
          }))
        }
      : null
  } catch (err) {
    cloudFiles.value = null
    toast.error('云端文件查询失败', { description: readErrorMessage(err, '请稍后重试') })
  } finally {
    cloudLoading.value = false
  }
}

/** 删除云端文件（幂等：404 视为已删除；已登记的本地记录由后端联动清理） */
async function onCloudDelete(fileId?: string) {
  if (!fileId) return
  const target = (cloudFiles.value?.files || []).find(f => f.fileId === fileId)
  if (
    !(await confirmDelete('云端文件', target?.filename, {
      hint: '仅删除云端存储对象；已登记到本地文件库的记录会由后端联动清理。此操作不可撤销。'
    }))
  ) {
    return
  }
  try {
    await deleteCloudFile(fileId, cloudFiles.value?.providerCode)
    if (target?.linkedAssetId != null) {
      invalidatePreviewCache(Number(target.linkedAssetId))
    }
    toast.success('云端文件已删除')
    await Promise.all([loadCloudFiles(), loadFiles()])
  } catch (err) {
    toast.error('删除失败', { description: readErrorMessage(err, '请稍后重试') })
  }
}

/** 清理全部「仅云端」文件（后端无批量接口，逐条删除后统一刷新） */
async function onCloudPurge() {
  const purgeList = (cloudFiles.value?.files || []).filter(f => !f.linked)
  if (!purgeList.length) {
    toast.info('没有需要清理的云端文件')
    return
  }
  const totalBytes = purgeList.reduce((sum, f) => sum + (Number(f.bytes) || 0), 0)
  const ok = await confirm({
    title: `清理 ${purgeList.length} 个仅云端文件`,
    content: `这些文件本地无记录（重装或清理数据后的残留），合计约 ${formatAssetSize(totalBytes)}。\n将逐个删除云端对象，此操作不可撤销。`,
    okText: '清理',
    type: 'danger'
  })
  if (!ok) return

  cloudLoading.value = true
  let done = 0
  const errors: string[] = []
  for (const f of purgeList) {
    // CloudFileItem.fileId 为可选（组件类型定义放宽），缺失的直接跳过
    const remoteId = f.fileId
    if (!remoteId) continue
    try {
      await deleteCloudFile(remoteId, cloudFiles.value?.providerCode)
      done += 1
    } catch (err) {
      errors.push(`${f.filename}：${readErrorMessage(err, '删除失败')}`)
    }
  }
  cloudLoading.value = false
  if (done) toast.success(`已清理 ${done} 个云端文件`)
  if (errors.length) toast.error(`${errors.length} 个文件删除失败`, { description: errors[0] })
  await Promise.all([loadCloudFiles(), loadFiles()])
}

async function onCloudRefresh() {
  await loadCloudFiles()
}

/* ============================ 设备互联页：真实接口 ============================ */
/**
 * 数据接缝（/api/p2p）：
 *   GET  /status                  → { running, peerId, port, peerCount, certFingerprint, myName }
 *   GET  /address                 → { addresses[], bestAddress, hasIpv6 }
 *   GET  /name · POST /name       → 本机名称
 *   GET  /peers                   → P2pPeer[]（在线来自连接池 + 离线来自已知节点表）
 *   POST /qrcode                  → { connectionString, compactString, qrCodeBase64, qrError }
 *   POST /qrcode/parse { image }  → { peerId, address, fingerprint }
 *   POST /connect { connectionString }（后端阻塞等待，超时 10 秒返回 408）
 *   POST /disconnect/{peerId} · /reconnect/{peerId} · DELETE /peer/{peerId}
 *   PUT  /peer/{peerId}/remark { remark }
 *   GET  /agent-auth/sent/{peerId} · /received/{peerId}
 *   POST /agent-auth/grant|/cancel/{peerId} { agentConfigIds: number[] }
 * 说明：status / address / signaling 保持非空默认值，模板里大量 status?.xxx 无需改动；
 *      二维码只在打开「我的二维码」抽屉时生成，避免每次进页面都算一次。
 */
const p2pSelfName = ref('')
const p2pStatus = ref<P2pStatus>({
  running: false,
  peerId: '',
  port: 0,
  peerCount: 0,
  certFingerprint: '',
  myName: ''
})
const p2pAddress = ref<P2pAddressInfo>({ addresses: [], bestAddress: null, hasIpv6: false })
const p2pSignaling = ref<P2pSignalingResult>({
  connectionString: '',
  compactString: '',
  qrCodeBase64: ''
})
const p2pPeers = ref<P2pPeer[]>([])
const p2pSentAgents = ref<Record<string, AgentAuthItem[]>>({})
const p2pReceivedAgents = ref<Record<string, AgentAuthItem[]>>({})
const p2pConnecting = ref(false)
/** 二维码解析后回填的连接串 */
const p2pParsedConnStr = ref('')
const p2pLoading = ref(false)
const p2pError = ref('')

/** 可授权的 Agent：后端 body 为 { agentConfigIds: number[] }，这里把 id 统一成 number */
const p2pAvailableAgents = computed<AgentAuthItem[]>(() =>
  agentList.value.map(a => ({ id: Number(a.id), name: a.name, avatar: a.avatar }))
)

/** 每个节点已授权数量（列表行展示用） */
const p2pGrantedCounts = computed<Record<string, number>>(() => {
  const out: Record<string, number> = {}
  Object.entries(p2pSentAgents.value).forEach(([peerId, list]) => {
    out[peerId] = list.length
  })
  return out
})

async function loadP2p() {
  p2pLoading.value = true
  p2pError.value = ''
  try {
    const [status, address, name, peers] = await Promise.all([
      getP2pStatus().catch(() => null),
      getP2pAddress().catch(() => null),
      getP2pName().catch(() => null),
      getPeers().catch(() => [] as P2pPeer[])
    ])
    if (status) p2pStatus.value = status
    if (address) p2pAddress.value = address
    p2pSelfName.value = name?.name || status?.myName || ''
    p2pPeers.value = peers || []

    // 逐个节点拉「我授权给对方」与「对方授权给我」的 Agent（节点数很少，可并行）
    const sent: Record<string, AgentAuthItem[]> = {}
    const received: Record<string, AgentAuthItem[]> = {}
    await Promise.all(
      (peers || []).map(async peer => {
        const [s, r] = await Promise.all([
          getMyAuthToPeer(peer.peerId).catch(() => [] as AgentAuthItem[]),
          getPeerAuthToMe(peer.peerId).catch(() => [] as AgentAuthItem[])
        ])
        sent[peer.peerId] = s || []
        received[peer.peerId] = r || []
      })
    )
    p2pSentAgents.value = sent
    p2pReceivedAgents.value = received
  } catch (err) {
    p2pError.value = readErrorMessage(err, '加载设备互联信息失败')
  } finally {
    p2pLoading.value = false
  }
}

/** 生成连接二维码（POST /p2p/qrcode） */
async function loadP2pSignaling() {
  try {
    p2pSignaling.value = await generateQrCode()
  } catch (err) {
    toast.error(readErrorMessage(err, '生成连接二维码失败'))
  }
}

/* ============================ 设备互联页事件 ============================ */

/** 在线 → 断开；离线 → 重连 */
async function onP2pToggle(peer: P2pPeer) {
  try {
    if (peer.online) await disconnectPeer(peer.peerId)
    else await reconnectPeer(peer.peerId)
    toast.success(peer.online ? '已断开设备' : '已重连设备')
    await loadP2p()
  } catch (err) {
    toast.error(readErrorMessage(err, peer.online ? '断开设备失败' : '重连设备失败'))
  }
}

async function onP2pDeletePeer(peerId: string) {
  const target = p2pPeers.value.find(p => p.peerId === peerId)
  const label = target?.remark || target?.displayName || target?.name
  if (
    !(await confirmDelete('设备', label, {
      hint: '将同时删除聊天记录与已接收文件，此操作不可撤销。'
    }))
  ) {
    return
  }
  try {
    await deletePeer(peerId)
    toast.success('已删除设备')
    await loadP2p()
  } catch (err) {
    toast.error(readErrorMessage(err, '删除设备失败'))
  }
}

/** 保存备注 + 授权差集（新增 → grant，移除 → cancel） */
async function onP2pSavePeer(payload: { peerId: string; remark: string; grantedIds: number[] }) {
  try {
    await setRemark(payload.peerId, payload.remark)
    const before = (p2pSentAgents.value[payload.peerId] || []).map(a => Number(a.id))
    const after = payload.grantedIds.map(Number)
    const toGrant = after.filter(id => !before.includes(id))
    const toCancel = before.filter(id => !after.includes(id))
    if (toGrant.length) await grantAgentAuth(payload.peerId, toGrant)
    if (toCancel.length) await cancelAgentAuth(payload.peerId, toCancel)
    toast.success('已保存设备设置')
    await loadP2p()
  } catch (err) {
    toast.error(readErrorMessage(err, '保存设备设置失败'))
  }
}

async function onP2pConnect(connectionString: string) {
  const conn = connectionString.trim()
  if (!conn || p2pConnecting.value) return
  p2pConnecting.value = true
  try {
    await connectPeer(conn)
    p2pParsedConnStr.value = ''
    toast.success('设备连接成功')
    await loadP2p()
  } catch (err) {
    toast.error(readErrorMessage(err, '连接设备失败'))
  } finally {
    p2pConnecting.value = false
  }
}

/** 二维码解析：后端只要 peerId + fp 两个必填参数（见 ConnectionStringHelper.parse） */
async function onP2pParseQr(imageBase64: string) {
  try {
    const info = await parseQrCode(imageBase64)
    p2pParsedConnStr.value =
      'p2p://' + info.address + '?peerId=' + encodeURIComponent(info.peerId) +
      '&fp=' + encodeURIComponent(info.fingerprint)
    toast.success('二维码解析成功，确认无误后点「连接」')
  } catch (err) {
    toast.error(readErrorMessage(err, '二维码解析失败'))
  }
}

async function onP2pSaveName(name: string) {
  const value = name.trim()
  if (!value) return
  try {
    await setP2pName(value)
    p2pSelfName.value = value
    toast.success('已更新本机名称')
    await loadP2p()
  } catch (err) {
    toast.error(readErrorMessage(err, '更新本机名称失败'))
  }
}

async function onP2pRefreshQr() {
  await loadP2pSignaling()
}

/* ============================ 模型配置（Provider）：真实接口 ============================ *//* ============================ 模型配置（Provider）：真实接口 ============================ */
/**
 * 数据源：GET /api/llm-providers（管理员接口）—— L1 数据层首个切到真实接口的页面。
 *
 * 三个后端语义必须记住：
 *  1) `apiKey` 与 `extraConfig` 里的密钥类字段（accessKey / secretKey / searchApiKey / apiKey / token）
 *     在列表接口被脱敏成 `****` —— 表示「已配置」，不是空值；编辑时留空 = 不修改；
 *  2) `isDefault` 后端没有单独设置接口（创建强制 0、更新不处理该字段），页面只读展示；
 *  3) 列表接口走 `selectAllEnabled()`，只返回 `enabled = 1` 的记录，
 *     所以「已停用」筛选下永远是空的 —— 停用后该项不再出现在列表里（已知项，见 README）。
 */
const providerList = ref<ProviderItem[]>([])
const providerLoading = ref(false)
const providerError = ref('')

/** 拉取 Provider 列表（刷新按钮 / 增删改后复用） */
async function loadProviders() {
  providerLoading.value = true
  providerError.value = ''
  try {
    const res = await listProviders()
    providerList.value = (res.data || []) as ProviderItem[]
  } catch (error) {
    providerError.value = error instanceof Error ? error.message : String(error)
    providerList.value = []
  } finally {
    providerLoading.value = false
  }
}

/** 余额查询结果（GET /api/llm-providers/{code}/balance） */
const providerBalance = ref<ProviderBalanceModel | null>(null)
const providerBalanceLoading = ref(false)
const providerBalanceError = ref('')

/**
 * 智能体页的 Provider 选项（模型下拉联动的数据源）
 * ----------------------------------------------------------------------------
 * 只映射联动需要的字段，`models` 取自后端 `ProviderConfig.modelList`：
 *   - `listProviders()` 已在响应映射阶段解析出 `parsedModelList`（首选）；
 *   - 万一该字段缺失（例如别处直接塞的原始数据），回落到 `parseModelList(modelList)`。
 *
 * 空 `models` 不在这里过滤掉：Provider 仍可作为「绑定对象」被选中，
 * 只是模型下拉会回退到「全部 Provider 模型合集」，抽屉里有对应提示。
 */
const agentProviderOptions = computed<AgentProviderOption[]>(() =>
  providerList.value.map(p => ({
    id: p.id,
    code: p.code,
    name: p.name,
    models: p.parsedModelList?.length ? p.parsedModelList : parseModelList(p.modelList),
    defaultModel: p.defaultModel,
    isDefault: p.isDefault === 1
  }))
)

/**
 * 聊天页「模型与运行模式」弹层的 Provider 选项。
 *
 * <p>与「智能体编辑」抽屉共用同一份数据源：两处都是「选 Provider → 模型列表联动」，
 * 字段需求完全重合（`models` 来自后端 `modelList`，`isDefault` 用于回退判定），
 * 没必要再拉一次接口或做第二套映射。别名而非拷贝，Provider 列表刷新时两处同时更新。</p>
 */
const chatProviderOptions = agentProviderOptions

/* ============================ 模型配置页事件 ============================ */

/**
 * 保存：有 id → PUT /api/llm-providers/{id}；无 id → POST /api/llm-providers
 * 「apiKey / extraConfig 留空 = 不修改」由后端兜底（LLMProviderController.update 跳过空值与 ****）
 */
async function onProviderSave(payload: ProviderSavePayload) {
  providerError.value = ''
  try {
    if (payload.id) {
      await updateProvider(payload.id, payload)
    } else {
      await createProvider(payload)
    }
    await loadProviders()
    toast.success(payload.id ? '已保存模型配置' : '已创建模型配置')
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    providerError.value = message
    toast.error('保存模型配置失败', { description: message })
  }
}

/** 删除：DELETE /api/llm-providers/{id} */
async function onProviderDelete(provider: ProviderItem) {
  if (!provider.id) return
  if (
    !(await confirmDelete('模型配置', provider.name, { hint: '删除后 code 会被释放，可用同名重建。' }))
  ) {
    return
  }
  providerError.value = ''
  try {
    await deleteProvider(provider.id)
    await loadProviders()
    toast.success(`已删除「${provider.name}」`)
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    providerError.value = message
    toast.error('删除模型配置失败', { description: message })
  }
}

/**
 * 启用开关：PUT /api/llm-providers/{id}，只提交 enabled（后端只写非 null 字段）
 *
 * 注意：页面上的开关当前是只读的（ProviderRow 的 toggle-disabled 属性），
 * 因为后端列表接口只返回 enabled=1 的记录 —— 停用后该项立即从列表消失，
 * 且 code 已被占用无法重建，属于不可撤销操作。
 * 这里保留完整实现，等后端补上「查全部」能力后放开开关即可。
 */
async function onProviderToggle(provider: ProviderItem) {
  if (!provider.id) return
  const nextEnabled = provider.enabled === 0 ? 1 : 0
  providerError.value = ''
  try {
    await updateProvider(provider.id, { enabled: nextEnabled })
    await loadProviders()
    toast.success(nextEnabled ? '已启用该模型配置' : '已停用该模型配置')
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    providerError.value = message
    toast.error('切换启用状态失败', { description: message })
  }
}

/**
 * 余额查询：GET /api/llm-providers/{code}/balance（登录用户即可，非管理员接口）
 *
 * 后端 `ProviderBalanceVO` 与页面 `ProviderBalanceModel` 字段一一对应，
 * 但 `balanceType` 实际可能为 null（老数据/未标注），需归一成 undefined，
 * 否则模板里 `balanceType === 'quota'` 的分支虽然正确，类型上却对不上。
 */
async function onProviderQueryBalance(code: string) {
  providerBalanceLoading.value = true
  providerBalanceError.value = ''
  providerBalance.value = null
  try {
    const res = await getProviderBalance(code)
    const data = res.data
    if (!data) {
      providerBalanceError.value = `Provider「${code}」未返回余额数据`
      toast.warning('余额查询无结果', { description: `Provider「${code}」未返回余额数据` })
      return
    }
    providerBalance.value = {
      providerCode: data.providerCode || code,
      balanceType:
        data.balanceType === 'balance' || data.balanceType === 'quota' ? data.balanceType : undefined,
      isAvailable: data.isAvailable !== false,
      balanceInfos: (data.balanceInfos || []).map(info => ({ ...info })),
      updatedAt: data.updatedAt
    }
    toast.success(`已获取「${code}」余额`)
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    providerBalanceError.value = message
    toast.error('余额查询失败', { description: message })
  } finally {
    providerBalanceLoading.value = false
  }
}

function onProviderClearBalance() {
  providerBalance.value = null
  providerBalanceError.value = ''
  providerBalanceLoading.value = false
}

/** 刷新：重新拉取列表（密钥仍为 **** 掩码） */
function onProviderRefresh() {
  void loadProviders()
}

/* ============================ 运行日志页 ============================ */
/**
 * 数据接缝（`/api/logs`，只读，无需管理员）：
 *   GET /api/logs/dates                                → string[]（有日志的日期，从新到旧）
 *   GET /api/logs/tail?maxLines&skip                    → string[]（只读今天，从旧到新，无行号）
 *   GET /api/logs/search?keyword&level&date&page&size   → { date, lines[{date,fileName,lineNumber,content}], total, page, size }
 *   GET /api/logs/download?date                         → { date, fileNames, content, lineCount, charCount }
 *
 * 说明：
 *   1) tail 只返回纯文本行、且只覆盖「今天」的日志，因此行号退化为展示序号（LogRow 内 index + 1）；
 *      需要真实行号与来源文件请用搜索模式（后端返回 fileName + lineNumber）。
 *   2) 级别筛选由 LogListView 在已加载行上本地过滤，与静态预览一致，避免 tail / search 两套语义分叉。
 *   3) tail 只看今天，历史日期必须走 search + download，这是后端既定设计。
 */
const LOG_PAGE_LINES = 12
const LOG_LOAD_STEP = 12

const logMode = ref<'tail' | 'search'>('tail')
const logAvailableDates = ref<string[]>([])
const logSearchDate = ref('')
const logTotal = ref(0)
const logLoading = ref(false)
const logLines = ref<LogLineItem[]>([])
const logHasMore = ref(false)
/** tail 模式已加载的行数，作为「加载更早」时的 skip 值 */
const logTailLoaded = ref(0)

/** 行 id 自增，保证 v-for key 稳定 */
let logSeq = 0

/** 纯文本行 → 组件行模型（tail 无行号，搜索模式带后端行号） */
function toLogLines(source: string[]): LogLineItem[] {
  return source.map(text => ({ id: `log-${logSeq++}`, text }))
}

/** 拉取有日志的日期列表（失败不阻断页面，仅清空下拉） */
async function loadLogDates() {
  try {
    logAvailableDates.value = await getLogDates()
  } catch (err) {
    logAvailableDates.value = []
    toast.error(readErrorMessage(err, '加载日志日期失败'))
  }
}

/** 读取最新日志（tail 模式，重置游标） */
async function loadLogLatest() {
  logLoading.value = true
  try {
    const lines = await tailLogs(LOG_PAGE_LINES, 0)
    logTailLoaded.value = lines.length
    logLines.value = toLogLines(lines)
    // 返回不足一页说明已到文件开头，没有更早的日志了
    logHasMore.value = lines.length >= LOG_PAGE_LINES
  } catch (err) {
    logLines.value = []
    logTailLoaded.value = 0
    logHasMore.value = false
    toast.error(readErrorMessage(err, '读取最新日志失败'))
  } finally {
    logLoading.value = false
  }
}

/** 刷新：回到实时模式并重新拉取最新日志 */
function onLogRefresh() {
  logMode.value = 'tail'
  logSearchDate.value = ''
  logTotal.value = 0
  void loadLogLatest()
}

/** 搜索：走后端 /logs/search，保留后端返回的真实行号与来源文件 */
async function onLogSearch(query: LogSearchQuery) {
  logMode.value = 'search'
  logSearchDate.value = query.date || ''
  logLoading.value = true
  try {
    const result = await searchLogs({
      keyword: query.keyword,
      level: query.level,
      date: query.date,
      page: 1,
      size: 500
    })
    logLines.value = result.lines.map(line => ({
      id: `log-${logSeq++}`,
      text: line.content,
      lineNumber: line.lineNumber,
      fileName: line.fileName
    }))
    logTotal.value = result.total
  } catch (err) {
    logLines.value = []
    logTotal.value = 0
    toast.error(readErrorMessage(err, '搜索日志失败'))
  } finally {
    logLoading.value = false
  }
}

/** 从搜索结果切回实时视图：重新拉最新 tail，避免停留在旧快照 */
function onLogSwitchTail() {
  logMode.value = 'tail'
  logSearchDate.value = ''
  logTotal.value = 0
  void loadLogLatest()
}

/** 加载更早的日志：skip = 已加载行数，把结果 prepend 到列表头部 */
async function onLogLoadMore() {
  if (!logHasMore.value || logLoading.value) return
  logLoading.value = true
  try {
    const older = await tailLogs(LOG_LOAD_STEP, logTailLoaded.value)
    if (!older.length) {
      logHasMore.value = false
      return
    }
    logTailLoaded.value += older.length
    logLines.value = [...toLogLines(older), ...logLines.value]
    logHasMore.value = older.length >= LOG_LOAD_STEP
  } catch (err) {
    toast.error(readErrorMessage(err, '加载更早日志失败'))
  } finally {
    logLoading.value = false
  }
}

function onLogCopy(text: string) {
  // 复制动作已在组件内完成；如需埋点或全局提示在此处理
  console.log('copy log line', text.slice(0, 60))
}

/** 触发浏览器下载（带 BOM，避免 Windows 记事本中文乱码） */
function logDownloadText(content: string, fileName: string) {
  const blob = new Blob(['\uFEFF' + content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** 导出当前视图（前端拼接已加载行） */
function onLogExportView() {
  const lines = logLines.value
  if (!lines.length) {
    toast.warning('当前没有可导出的日志行')
    return
  }
  const prefix = logMode.value === 'tail' ? 'runtime' : 'search'
  logDownloadText(lines.map(line => line.text).join('\n'), `codecraft-${prefix}-logs.log`)
  toast.success(`已导出当前视图 ${lines.length} 行`)
}

/** 导出指定日期的完整日志（后端拼接归档文件 + 活跃文件） */
async function onLogExportFile() {
  try {
    const result = await exportLogFile(logSearchDate.value || undefined)
    if (!result.content) {
      toast.warning('该日期没有日志内容')
      return
    }
    const header =
      `# CodeCraft 运行日志导出\n` +
      `# 日期：${result.date}\n` +
      `# 文件：${(result.fileNames || []).join(', ') || '-'}\n` +
      `# 行数：${result.lineCount}　字符数：${result.charCount}\n\n`
    logDownloadText(header + result.content, `codecraft-${result.date || 'latest'}.log`)
    toast.success(`已导出 ${result.date} 完整日志（${result.lineCount} 行）`)
  } catch (err) {
    toast.error(readErrorMessage(err, '导出日志失败'))
  }
}
/* ============================ 数据管理页：真实接口 ============================ */
/**
 * 数据接缝（/api/system，均需管理员）：
 *   GET  /data-stats            → { llmProvider, agent, skill, chat, lesson, mcp, dbConnection } 各类别行数
 *   GET  /export-data?types=a,b → JSON 附件（文件名取 Content-Disposition）
 *   POST /import-data（multipart）→ { inserted, updated, failed, details[] }
 *   POST /reset-data            → 清空业务表 + 重建出厂数据 + 删除文件类数据
 * 说明：重置 / 导入后都要重新拉一次 data-stats，否则页面还显示旧行数。
 */
const dataStats = ref<Record<string, number>>({})
const dataExporting = ref(false)
const dataImporting = ref(false)
const dataImportResult = ref<DataImportResult | null>(null)
const dataResetting = ref(false)
/** 「最近导出」由前端记录，接口不返回 */
const dataLastExportAt = ref('')

async function loadDataStats() {
  try {
    dataStats.value = await fetchDataStats()
  } catch (err) {
    toast.error(readErrorMessage(err, '读取数据统计失败'))
  }
}

function onDataRefresh() {
  void loadDataStats()
}

/* ---------- 导出备份 ---------- */
async function onDataExport(types: string[]) {
  if (!types.length || dataExporting.value) return
  dataExporting.value = true
  try {
    const { blob, filename } = await exportSystemData(types)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    const now = new Date()
    const p = (n: number) => String(n).padStart(2, '0')
    dataLastExportAt.value =
      now.getFullYear() + '-' + p(now.getMonth() + 1) + '-' + p(now.getDate()) + ' ' +
      p(now.getHours()) + ':' + p(now.getMinutes()) + ':' + p(now.getSeconds())
    toast.success('已导出备份', { description: filename })
  } catch (err) {
    toast.error(readErrorMessage(err, '导出备份失败'))
  } finally {
    dataExporting.value = false
  }
}

/* ---------- 导入备份 ---------- */
async function onDataImport(file: File) {
  if (!file || dataImporting.value) return
  dataImporting.value = true
  dataImportResult.value = null
  try {
    const res = await importSystemData(file)
    dataImportResult.value = res.data ?? null
    if (dataImportResult.value) {
      const r = dataImportResult.value
      toast.success('导入完成：新增 ' + r.inserted + ' 行，更新 ' + r.updated + ' 行')
    }
    await loadDataStats()
  } catch (err) {
    toast.error(readErrorMessage(err, '导入备份失败'))
  } finally {
    dataImporting.value = false
  }
}

function onDataClearImport() {
  dataImportResult.value = null
}

/* ---------- 重置所有数据 ---------- */
async function onDataReset() {
  if (dataResetting.value) return
  dataResetting.value = true
  try {
    await resetAllData()
    // 桌面端：清掉升级前备份（内含数据库副本）与已下载安装包，让「清空」是彻底清空
    // 浏览器环境 clearLocalAppData() 直接返回 null，不会报错也不会有副作用
    const cleaned = await clearLocalAppData()
    dataImportResult.value = null
    dataLastExportAt.value = ''
    await loadDataStats()
    toast.success('已重置全部数据并恢复出厂状态', {
      description: cleaned?.removed?.length ? `同时清理本机：${cleaned.removed.join('、')}` : undefined
    })
  } catch (err) {
    toast.error(readErrorMessage(err, '重置数据失败'))
  } finally {
    dataResetting.value = false
  }
}

/* ============================ 用户管理页：真实接口 ============================ *//* ============================ 用户管理页：真实接口 ============================ */
/**
 * 数据接缝（/api/user，均需管理员）：
 *   GET    /list?page&pageSize&search → PageResult<User>（password 已置 null）
 *   POST   /create                   → 创建（password 明文提交，后端 MD5）
 *   PUT    /update                   → 更新；Service 对 null 字段继承原值，只传 id+status 也安全
 *   DELETE /delete/{id}              → 删除（后端拒绝删自己，返回 400）
 * 约束：后端 list 只支持 search 参数，角色 / 状态筛选由视图对当前页本地过滤（文案已标注「本页」）。
 */
const userPage = ref(1)
const userPageSize = ref(10)
const userTotal = ref(0)
const userList = ref<UserItem[]>([])
const userLoading = ref(false)
const userError = ref('')
const userQuery = reactive({ keyword: '', role: '', status: '' })

/** 后端 User → 行组件 UserItem（后端时间可能是 ISO，统一压成本地时间） */
function toUserRow(user: ApiUserItem): UserItem {
  return {
    id: user.id,
    username: user.username,
    nickname: user.nickname || '',
    role: user.role,
    email: user.email || '',
    phone: user.phone || '',
    avatar: user.avatar || '',
    status: Number(user.status ?? 1),
    createTime: normalizeTimeText(user.createTime) ?? '',
    updateTime: normalizeTimeText(user.updateTime) ?? ''
  }
}

async function loadUsers() {
  userLoading.value = true
  userError.value = ''
  try {
    const res = await getUserList(userPage.value, userPageSize.value, userQuery.keyword)
    const page = res.data
    userList.value = (page?.records ?? []).map(toUserRow)
    userTotal.value = Number(page?.total ?? 0)
  } catch (err) {
    userError.value = readErrorMessage(err, '加载用户列表失败')
  } finally {
    userLoading.value = false
  }
}

function onUserSearch(keyword: string) {
  userQuery.keyword = keyword
  userPage.value = 1
  void loadUsers()
}

function onUserFilterChange(payload: { role: string; status: string }) {
  userQuery.role = payload.role
  userQuery.status = payload.status
  // 后端 list 只提供 search 参数，角色 / 状态由视图对当前页本地过滤，无需重新请求
}

function onUserPageChange(page: number) {
  userPage.value = page
  void loadUsers()
}

async function onUserSave(payload: UserSavePayload) {
  try {
    if (payload.id) {
      await updateUser({
        id: Number(payload.id),
        username: payload.username,
        nickname: payload.nickname,
        role: payload.role,
        status: payload.status,
        email: payload.email,
        phone: payload.phone,
        // 空串表示不修改（后端 Service 对空密码继承原值）
        password: payload.password,
        // 头像空串 = 清除（后端 updateUser 只在 avatar 为 null 时继承原值）
        avatar: payload.avatar
      })
      // 改的是自己 → 同步 store，侧栏与聊天气泡立即跟着变
      if (Number(payload.id) === currentUserId.value) await refreshProfile()
      toast.success('已保存用户')
    } else {
      await createUser({
        username: payload.username,
        password: payload.password,
        nickname: payload.nickname,
        role: payload.role,
        email: payload.email,
        phone: payload.phone,
        avatar: payload.avatar
      })
      toast.success('已创建用户')
    }
    await loadUsers()
  } catch (err) {
    toast.error(readErrorMessage(err, '保存用户失败'))
  }
}

async function onUserDelete(user: UserItem) {
  if (user.id === currentUserId.value) {
    toast.warning('不能删除当前登录账号')
    return
  }
  if (!(await confirmDelete('用户', user.nickname || user.username))) return
  try {
    await deleteUser(user.id)
    toast.success('已删除用户')
    await loadUsers()
  } catch (err) {
    toast.error(readErrorMessage(err, '删除用户失败'))
  }
}

async function onUserToggle(user: UserItem) {
  const nextStatus = user.status === 1 ? 0 : 1
  try {
    await updateUser({ id: user.id, status: nextStatus })
    toast.success(nextStatus === 0 ? '已停用该账号' : '已启用该账号')
    await loadUsers()
  } catch (err) {
    toast.error(readErrorMessage(err, '更新用户状态失败'))
  }
}

function onUserRefresh() {
  void loadUsers()
}

/* ============================ 菜单权限页：真实接口 ============================ *//* ============================ 菜单权限页：真实接口 ============================ */
/**
 * 数据接缝（/api/menu、/api/role，均需管理员）：
 *   GET    /menu/tree                 → Menu[]（字段与 PermMenuItem 对齐）
 *   POST   /menu/create · PUT /menu/update · DELETE /menu/delete/{id}
 *   GET    /role/list                 → Role[]
 *   GET    /role/menus/{roleId}       → menuId[]
 *   POST   /role/assign-menus         → { roleId, menuIds } 全量覆盖
 *   POST   /role/create · PUT /role/update
 * 说明：角色数量很少（种子 2 个），进页面时一次性把所有角色的菜单预取进 permRoleMenus，
 *      切换角色时视图直接从 map 读，无需再发请求（组件内 watch 会自动同步勾选态）。
 */
const permMenus = ref<PermMenuItem[]>([])
const permRoles = ref<RoleItem[]>([])
const permRoleMenus = reactive<Record<number, number[]>>({})
const permLoading = ref(false)
const permError = ref('')

async function loadPermissions() {
  permLoading.value = true
  permError.value = ''
  try {
    const [menuRes, roleRes] = await Promise.all([getMenuTree(), getRoleList()])
    permMenus.value = (menuRes.data ?? []) as unknown as PermMenuItem[]
    permRoles.value = (roleRes.data ?? []) as unknown as RoleItem[]

    const map: Record<number, number[]> = {}
    await Promise.all(
      permRoles.value.map(async role => {
        try {
          const res = await getRoleMenuIds(role.id)
          map[role.id] = (res.data ?? []).map(Number)
        } catch {
          map[role.id] = []
        }
      })
    )
    // reactive map 不支持整体替换，逐个 key 同步
    Object.keys(permRoleMenus).forEach(key => {
      delete permRoleMenus[Number(key)]
    })
    Object.entries(map).forEach(([roleId, ids]) => {
      permRoleMenus[Number(roleId)] = ids
    })
  } catch (err) {
    permError.value = readErrorMessage(err, '加载菜单权限失败')
  } finally {
    permLoading.value = false
  }
}

async function onMenuSave(payload: MenuSavePayload) {
  const body = {
    name: payload.name,
    path: payload.path,
    icon: payload.icon,
    permissionCode: payload.permissionCode,
    menuType: payload.menuType,
    parentId: payload.parentId ? Number(payload.parentId) : undefined,
    sortOrder: payload.sortOrder ? Number(payload.sortOrder) : 0,
    visible: payload.visible,
    status: payload.status
  }
  try {
    if (payload.id) {
      await updateMenu({ id: Number(payload.id), ...body })
      toast.success('已保存菜单')
    } else {
      await createMenu({ ...body })
      toast.success('已创建菜单')
    }
    await loadPermissions()
  } catch (err) {
    toast.error(readErrorMessage(err, '保存菜单失败'))
  }
}

async function onMenuDelete(menu: PermMenuItem) {
  if (
    !(await confirmDelete('菜单', menu.name, {
      hint: '该菜单的角色授权会一并移除，此操作不可撤销。'
    }))
  ) {
    return
  }
  try {
    await deleteMenu(menu.id)
    toast.success('已删除菜单')
    await loadPermissions()
  } catch (err) {
    toast.error(readErrorMessage(err, '删除菜单失败'))
  }
}

async function onRoleSave(payload: RoleSavePayload) {
  const body = {
    name: payload.name,
    code: payload.code,
    description: payload.description,
    status: payload.status
  }
  try {
    if (payload.id) {
      // code 创建后不可改，后端 update 也只处理 name / description / status
      await updateRole({ id: Number(payload.id), ...body })
      toast.success('已保存角色')
    } else {
      await createRole({ ...body })
      toast.success('已创建角色')
    }
    await loadPermissions()
  } catch (err) {
    toast.error(readErrorMessage(err, '保存角色失败'))
  }
}

async function onAssignMenus(payload: { roleId: number; menuIds: number[] }) {
  try {
    await assignMenusToRole(payload.roleId, payload.menuIds)
    // 后端是全量覆盖语义，本地同步缓存即可
    permRoleMenus[payload.roleId] = [...payload.menuIds]
    toast.success('已保存角色权限')
  } catch (err) {
    toast.error(readErrorMessage(err, '保存角色权限失败'))
  }
}

function onPermRefresh() {
  void loadPermissions()
}

/* ============================ 外观设置页（真实接口） ============================ */
/**
 * 数据接缝：
 *   POST /api/appearance/background   multipart（字段 file）→ { url: "/api/appearance/file/<uuid>.<ext>" }
 * 其余设置（theme / bgOpacity / bgBlur / windowOpacity）来自 settingsStore 并持久化到 localStorage，
 * 后端不入库（与旧前端 frontend/src/store/settings.ts 一致）。
 *
 * 读写方式：一律通过 `settingsStore` 的双向 computed 落到 store。
 * `layouts/AppShell.vue` 读同一个 store 应用主题与背景层，所以改完立刻能看到效果；
 * 刷新后由 pinia-plugin-persistedstate 从 localStorage 恢复。
 */
const appearanceTheme = computed<ThemeMode>({
  get: () => settings.theme,
  set: value => settings.setTheme(value)
})
const appearanceBgUrl = computed({
  get: () => settings.bgImageUrl,
  set: (value: string) => {
    settings.bgImageUrl = value
  }
})
const appearanceBgOpacity = computed({
  get: () => settings.bgOpacity,
  set: (value: number) => {
    settings.bgOpacity = value
  }
})
const appearanceBgBlur = computed({
  get: () => settings.bgBlur,
  set: (value: number) => {
    settings.bgBlur = value
  }
})
const appearanceWindowOpacity = computed({
  get: () => settings.windowOpacity,
  set: (value: number) => {
    settings.windowOpacity = value
  }
})

/** 背景图上传中（只锁上传按钮，不阻塞其它设置项） */
const appearanceUploading = ref(false)

/** 背景图大小上限：与后端 AppearanceController 校验一致（10MB） */
const LOOK_MAX_BG_BYTES = 10 * 1024 * 1024

function onLookThemeChange(theme: ThemeMode) {
  settings.setTheme(theme)
}

async function onLookUploadBg(file: File) {
  if (!file.type.startsWith('image/')) {
    toast.warning('请选择图片文件（png / jpg / webp / gif / bmp）')
    return
  }
  if (file.size > LOOK_MAX_BG_BYTES) {
    toast.warning('背景图不能超过 10MB')
    return
  }
  if (appearanceUploading.value) return
  appearanceUploading.value = true
  try {
    const res = await uploadBackground(file)
    const url = res.data?.url ?? ''
    if (!url) throw new Error('后端未返回背景图地址')
    // 后端返回相对路径（/api/appearance/file/xxx.png），可直接作为 background-image
    settings.bgImageUrl = url
    toast.success('背景图已更新')
  } catch (err) {
    toast.error(readErrorMessage(err, '背景图上传失败'))
  } finally {
    appearanceUploading.value = false
  }
}

function onLookRemoveBg() {
  // 只清本机引用；后端文件保留（后端无删除接口，重新上传会生成新文件）
  settings.bgImageUrl = ''
  toast.success('已移除背景图')
}

function onLookReset() {
  // 对齐旧前端 resetAppearance()：背景图空 / 不透明度 0.5 / 模糊 0 / 窗口 1
  settings.resetAppearance()
  settings.setTheme('light')
  toast.success('已恢复默认外观')
}

/* ============================ 设置（聚合导航）页 ============================ */
function onSettingsNavigate(key: string) {
  // activeKey 已与 route.path 双向绑定，此处等价于 router.push(key)
  activeKey.value = key
}

/* ============================ 页面绑定（L5：router-view） ============================ */
/**
 * 每个路由对应页面组件的 props / 事件绑定
 * ----------------------------------------------------------------------------
 * 为什么用映射表而不是 router 的 props 配置：
 *   1) 各页 props 名差异极大（agents / files / providers …），且值全部来自本文件里的
 *      ref / computed，无法用静态 props 声明表达；
 *   2) 集中一处才能一眼看清「哪一页用哪些数据、接哪些事件」，也便于后续把某一页的
 *      state + handler 整体下沉为独立的页面容器组件（届时删掉对应分支即可）；
 *   3) computed 里 switch 只求值命中的分支，未被访问的 ref 不建立依赖，切页不会互相触发重算。
 *
 * load 字段 = 原 PAGE_LOADERS 的逻辑，现由各 View 在自己的 onMounted 调用：
 *   页面随路由真实挂载 / 卸载，所以「进入某页才拉数据」终于有了正确的表达位置；
 *   每个 load 内部都带「已加载 / 加载中 / 已失败」三重跳过，避免重复请求与失败刷屏。
 */
type PageEventHandler = (...args: any[]) => void

interface PageBinding {
  /** 传给页面组件的 props（v-bind 对象形式，键用 camelCase） */
  props: Record<string, unknown>
  /** 页面组件事件（v-on 对象形式，键为事件名，不含 on 前缀） */
  on: Record<string, PageEventHandler>
}

/**
 * 把可能含 Ref 的 props 对象解包成普通值
 * ----------------------------------------------------------------------------
 * `<component v-bind="obj">` 的对象形式**不会**自动解包 ref：模板的自动解包只覆盖
 * `:prop="someRef"` 这种单值写法。不在这里解包，页面组件收到的会是 Ref 对象本身
 * （表现为 `props.agents.filter is not a function`）。
 * 解包时逐个读取 `.value`，同时也让外层 computed 正确依赖到每个 ref 的值变化。
 */
function unwrapProps(source: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const key of Object.keys(source)) {
    const value = source[key]
    out[key] = isRef(value) ? value.value : value
  }
  return out
}

/*
 * Provider 变化（切 Agent / 换 Agent 绑定的 Provider）时补查余额。
 *
 * 注意：这个 watch 必须放在**所有被依赖的状态定义之后**。
 * watch 注册时会立刻求值一次 getter，而 `chatProviderCode` 依赖 `currentAgent` → `agentList`；
 * 若把 watch 写在 agentList 声明之前，setup 阶段会直接抛
 * `ReferenceError: Cannot access 'agentList' before initialization`（TDZ），整个应用白屏。
 * 只在实际停留在聊天页时触发：Provider 列表是全局状态，在别的页面加载完成时没必要白跑一次请求。
 */
watch(chatProviderCode, code => {
  if (!code || route.path !== '/chat') return
  if (code === chatBalanceCode.value) return
  void loadChatBalance()
})

const pageView = computed((): PageBinding => {
  // 内层立即执行：先拿到「值仍是 Ref 的原始绑定」，再统一解包成普通值（见下方 unwrapProps）
  const raw = ((): PageBinding => {
  switch (route.path) {
    /* ---------------- 聊天页（SSE 流式 + 审批回执 + 会话管理） ---------------- */
    case '/chat':
      return {
        props: {
          conversation,
          messages,
          tasks,
          approval,
          attachments,
          isStreaming,
          // 消息里的本地路径（相对路径）以当前 Agent 工作目录为基准解析
          projectRoot: chatProjectRoot,
          // 「补充需求」的发送态（真实请求在 onChatSupplement 里，组件只负责展示）
          supplementState: chatSupplementState,
          // 头像：用户头像来自 /user/profile，Agent 头像来自 Agent 配置（消息上也有各自快照）
          userAvatar,
          userName,
          agentAvatar,
          // 其他会话的后台任务数（C 档：当前会话空闲但别处还在跑，状态栏给提示入口）
          backgroundCount,
          // 其他会话的任务清单摘要：任务浮层里分组展示，多任务并行时也能看到别处进度
          backgroundTasks,
          // 后端遗留运行标记：为 true 时输入框已解锁，状态栏给「清理标记」出口
          staleTask,
          // 历史可回滚轮次数：消息级回滚按钮只在「本轮有快照」时出现，这个数字让入口常驻可见
          snapshotCount: chatSnapshotCount,
          // 子Agent（F05）：fork 记录读库轮询 + 委托调用心跳，面板里分块展示
          subAgents: chatSubAgents,
          delegateCalls: chatDelegates,
          /*
           * 「未配置 Provider」引导页（F15）：
           * 三个状态一起传 —— 组件要区分「真的没有 Provider」「还在加载」「后端连不上」，
           * 只有第一种才该显示配置引导，后两种显示引导会误导用户白改配置。
           */
          providers: providerList,
          providerLoading,
          providerError,
          // 引导页「去配置」按钮是否需要管理员权限（Provider 读写接口都 checkAdmin）
          isAdmin,
          // 会话列表依赖「当前 Agent」，必须先有 Agent 列表；Provider 列表用于模型下拉
          load: () => {
            if (!agentList.value.length && !agentLoading.value) {
              void loadAgents().then(() => {
                if (!conversationList.value.length && !conversationLoading.value) {
                  void loadConversations()
                }
              })
            } else if (!conversationList.value.length && !conversationLoading.value) {
              void loadConversations()
            }
            if (!providerList.value.length && !providerError.value && !providerLoading.value) {
              // Provider 列表是余额查询的前置：要知道当前 Agent 走哪个 Provider 才能查余额
              void loadProviders().then(() => void loadChatBalance())
            } else {
              void loadChatBalance()
            }
          }
        },
        on: {
          send: onSend,
          stop: onStop,
          approve: onApprove,
          'approve-all': onApproveAll,
          reject: onReject,
          answer: onAnswer,
          continue: onContinue,
          attach: onAttach,
          'attach-history': onAttachHistory,
          optimize: onOptimize,
          'select-model': onSelectModel,
          paste: onPaste,
          'remove-attachment': onRemoveAttachment,
          copy: onCopy,
          regenerate: onRegenerate,
          rollback: onRollbackMessage,
          'load-more': onChatLoadMore,
          'open-changes': onChatOpenChanges,
          supplement: onChatSupplement,
          'switch-background': onSwitchBackground,
          'switch-background-task': onSwitchBackgroundTask,
          'clear-stale': onClearStaleTask,
          // 子Agent面板里「前往会话」：被委托智能体的会话可能在别的 Agent 名下
          'open-subagent-session': onOpenSubagentSession,
          // 「未配置 Provider」引导页的两个出口：跳模型配置 / 重新拉列表
          'configure-provider': onChatConfigureProvider,
          'refresh-providers': onChatRefreshProviders
        }
      }

    /* ---------------- 智能体管理 ---------------- */
    case '/agents':
      return {
        props: {
          agents: agentList,
          providers: agentProviderOptions,
          models: availableModels,
          // 工具集选项来自后端工具注册表（F18）；为空时抽屉内回退内置快照
          toolGroups: toolGroups,
          // 模型下拉要联动「模型配置」页已配置的模型，故 Provider 列表与 Agent 列表并行预加载
          load: () => {
            if (!agentList.value.length && !agentLoading.value) void loadAgents()
            if (!providerList.value.length && !providerError.value && !providerLoading.value) {
              void loadProviders()
            }
            if (!toolGroups.value.length && !toolGroupsLoading.value) void loadToolGroups()
          }
        },
        on: {
          save: onAgentSave,
          create: onAgentCreate,
          delete: onAgentDelete,
          'set-default': onAgentSetDefault,
          select: onAgentSelect
        }
      }

    /* ---------------- 技能管理 ---------------- */
    case '/skills':
      return {
        props: {
          skills: skillList,
          agents: agentOptions,
          loading: skillLoading,
          error: skillError,
          load: () => {
            if (skillLoading.value) return
            if (!skillList.value.length && !skillError.value) void loadSkills()
          }
        },
        on: {
          save: onSkillSave,
          delete: onSkillDelete
        }
      }

    /* ---------------- MCP 服务器 ---------------- */
    case '/mcp':
      return {
        props: {
          servers: mcpServers,
          loading: mcpLoading,
          error: mcpError,
          load: () => {
            if (mcpLoading.value) return
            if (!mcpServers.value.length && !mcpError.value) void loadMcpServers()
          }
        },
        on: {
          reload: onMcpReload,
          connect: onMcpConnect,
          disconnect: onMcpDisconnect,
          refresh: onMcpRefresh,
          save: onMcpSave,
          delete: onMcpDelete,
          'toggle-enabled': onMcpToggleEnabled
        }
      }

    /* ---------------- 定时任务 ---------------- */
    case '/tasks/schedule':
      return {
        props: {
          tasks: scheduleTasks,
          agents: agentOptions,
          loading: scheduleLoading,
          error: scheduleError,
          runBusyId: scheduleRunBusy,
          load: () => {
            if (scheduleLoading.value) return
            if (!scheduleTasks.value.length && !scheduleError.value) void loadScheduleTasks()
            // 行内 agentName 由 Agent 列表补全
            if (!agentList.value.length && !agentLoading.value) void loadAgents()
          }
        },
        on: {
          save: onScheduleSave,
          delete: onScheduleDelete,
          run: onScheduleRun,
          'toggle-status': onScheduleToggleStatus,
          'view-conversation': onScheduleViewConversation
        }
      }

    /* ---------------- 踩坑经验 ---------------- */
    case '/lessons':
      return {
        props: {
          lessons: lessonList,
          clusters: lessonClusters,
          total: lessonTotal,
          loading: lessonLoading,
          error: lessonError,
          load: () => {
            if (lessonLoading.value) return
            if (!lessonList.value.length && !lessonError.value) void loadLessons()
          }
        },
        on: {
          save: onLessonSave,
          feedback: onLessonFeedback,
          delete: onLessonDelete,
          merge: onLessonMerge,
          refresh: onLessonRefresh
        }
      }

    /* ---------------- 数据库连接 ---------------- */
    case '/databases':
      return {
        props: {
          connections: dbConnections,
          loading: dbLoading,
          error: dbError,
          rowTestStates: dbRowTestStates,
          editTestState: dbEditTestState,
          executeResult: dbExecuteResult,
          executeError: dbExecuteError,
          executingRequest: dbExecuting,
          load: () => {
            if (dbLoading.value) return
            if (!dbConnections.value.length && !dbError.value) void loadDbConnections()
          }
        },
        on: {
          save: onDbSave,
          delete: onDbDelete,
          toggle: onDbToggle,
          test: onDbTest,
          'test-form': onDbTestForm,
          execute: onDbExecute,
          refresh: onDbRefresh
        }
      }

    /* ---------------- 文件管理 ---------------- */
    case '/files':
      return {
        props: {
          files: fileAssets,
          total: fileTotal,
          totalSize: fileTotalSize,
          page: filePage,
          pageSize: filePageSize,
          keyword: fileKeyword,
          loading: fileLoading,
          error: fileError,
          cloud: cloudFiles,
          cloudLoading,
          previewSrc: filePreviewSrc,
          // 空列表也要能进（用户可能没有任何文件），失败后不自动重试，由「刷新」按钮兜底
          load: () => {
            if (fileLoading.value) return
            if (!fileAssets.value.length && !fileError.value) void loadFiles()
          }
        },
        on: {
          'page-change': onFilePageChange,
          search: onFileSearch,
          'filter-change': onFileFilterChange,
          upload: onFileUpload,
          open: onFileOpen,
          'open-cloud': loadCloudFiles,
          rename: onFileRename,
          delete: onFileDelete,
          'delete-cloud': onCloudDelete,
          'purge-cloud': onCloudPurge,
          'refresh-cloud': onCloudRefresh,
          reference: onFileReference
        }
      }

    /* ---------------- 设备互联 ---------------- */
    case '/p2p':
      return {
        props: {
          selfName: p2pSelfName,
          status: p2pStatus,
          address: p2pAddress,
          signaling: p2pSignaling,
          peers: p2pPeers,
          grantedCounts: p2pGrantedCounts,
          sentAgentsByPeer: p2pSentAgents,
          receivedAgentsByPeer: p2pReceivedAgents,
          availableAgents: p2pAvailableAgents,
          connecting: p2pConnecting,
          parsedConnectionString: p2pParsedConnStr,
          loading: p2pLoading,
          error: p2pError,
          load: () => {
            if (p2pLoading.value) return
            // 空列表也要能进（还没配对任何设备），失败后不自动重试
            if (!p2pPeers.value.length && !p2pError.value) void loadP2p()
            if (!agentList.value.length && !agentLoading.value) void loadAgents()
          }
        },
        on: {
          toggle: onP2pToggle,
          'delete-peer': onP2pDeletePeer,
          'save-peer': onP2pSavePeer,
          connect: onP2pConnect,
          'parse-qr': onP2pParseQr,
          'save-name': onP2pSaveName,
          'refresh-qr': onP2pRefreshQr,
          // 聊天抽屉关闭后重拉：同步「已授权 N 个」与在线状态（节点数很少，全量刷新代价可接受）
          refresh: () => void loadP2p()
        }
      }

    /* ---------------- 模型配置（Provider） ---------------- */
    case '/providers':
      return {
        props: {
          providers: providerList,
          loading: providerLoading,
          error: providerError,
          balance: providerBalance,
          balanceLoading: providerBalanceLoading,
          balanceError: providerBalanceError,
          load: () => {
            if (!providerList.value.length && !providerError.value && !providerLoading.value) {
              void loadProviders()
            }
          }
        },
        on: {
          save: onProviderSave,
          delete: onProviderDelete,
          toggle: onProviderToggle,
          'query-balance': onProviderQueryBalance,
          'clear-balance': onProviderClearBalance,
          refresh: onProviderRefresh
        }
      }

    /* ---------------- 运行日志 ---------------- */
    case '/logs':
      return {
        props: {
          lines: logLines,
          mode: logMode,
          total: logTotal,
          availableDates: logAvailableDates,
          date: logSearchDate,
          loading: logLoading,
          hasMore: logHasMore,
          load: () => {
            if (logLoading.value) return
            if (!logLines.value.length) void loadLogLatest()
            if (!logAvailableDates.value.length) void loadLogDates()
          }
        },
        on: {
          refresh: onLogRefresh,
          search: onLogSearch,
          'switch-tail': onLogSwitchTail,
          'load-more': onLogLoadMore,
          copy: onLogCopy,
          'export-view': onLogExportView,
          'export-file': onLogExportFile
        }
      }

    /* ---------------- 数据管理 ---------------- */
    case '/data':
      return {
        props: {
          stats: dataStats,
          exporting: dataExporting,
          importing: dataImporting,
          importResult: dataImportResult,
          resetting: dataResetting,
          lastExportAt: dataLastExportAt,
          // 统计接口很轻，每次进页面都刷新，避免显示重置前的旧数字
          load: () => {
            void loadDataStats()
          }
        },
        on: {
          refresh: onDataRefresh,
          export: onDataExport,
          import: onDataImport,
          'clear-import': onDataClearImport,
          reset: onDataReset,
          // 「更新设置」（自动检查 / 下载加速 / 升级前备份份数）集中在版本与更新页，
          // 数据管理页只嵌入检测/下载/安装卡片，故这里给一个跳转
          'open-version-page': () => { void router.push('/about') }
        }
      }

    /* ---------------- 用户管理 ---------------- */
    case '/users':
      return {
        props: {
          users: userList,
          total: userTotal,
          page: userPage,
          pageSize: userPageSize,
          selfId: currentUserId,
          loading: userLoading,
          error: userError,
          load: () => {
            if (userLoading.value) return
            if (!userList.value.length && !userError.value) void loadUsers()
          }
        },
        on: {
          search: onUserSearch,
          'filter-change': onUserFilterChange,
          'page-change': onUserPageChange,
          save: onUserSave,
          delete: onUserDelete,
          toggle: onUserToggle,
          refresh: onUserRefresh
        }
      }

    /* ---------------- 菜单权限 ---------------- */
    case '/menu-permission':
      return {
        props: {
          menus: permMenus,
          roles: permRoles,
          roleMenus: permRoleMenus,
          loading: permLoading,
          error: permError,
          load: () => {
            if (permLoading.value) return
            if (!permMenus.value.length && !permError.value) void loadPermissions()
          }
        },
        on: {
          'save-menu': onMenuSave,
          'delete-menu': onMenuDelete,
          'save-role': onRoleSave,
          'save-assign': onAssignMenus,
          refresh: onPermRefresh
        }
      }

    /* ---------------- 外观设置（纯本地偏好 + 背景图上传） ---------------- */
    case '/appearance':
      return {
        props: {
          theme: appearanceTheme,
          bgImageUrl: appearanceBgUrl,
          bgOpacity: appearanceBgOpacity,
          bgBlur: appearanceBgBlur,
          windowOpacity: appearanceWindowOpacity,
          uploading: appearanceUploading,
          /* P4：右侧面板形态（工作区 / 抽屉），与 settingsStore 双向同步 */
          workspaceMode: settings.workspaceMode
        },
        on: {
          'update:theme': onLookThemeChange,
          'update:workspaceMode': (value: boolean) => settings.setWorkspaceMode(value),
          'update:bg-opacity': (value: number) => {
            appearanceBgOpacity.value = value
          },
          'update:bg-blur': (value: number) => {
            appearanceBgBlur.value = value
          },
          'update:window-opacity': (value: number) => {
            appearanceWindowOpacity.value = value
          },
          'upload-bg': onLookUploadBg,
          'remove-bg': onLookRemoveBg,
          reset: onLookReset
        }
      }

    /* ---------------- 个人信息（组件自包含：内部直调 /user/profile 与 /user/password） ---------------- */
    case '/profile':
      return {
        props: {},
        on: {
          // 资料 / 头像变更后刷新身份快照：侧栏名字、头像、聊天气泡都读 store
          changed: () => {
            void refreshProfile()
          }
        }
      }

    /* ---------------- 版本与更新（F06，组件自包含：内部直调 electronAPI.update 与 /system/version） ---------------- */
    case '/about':
      // 无需 props / 事件：更新状态与后端版本都由 AboutView → VersionCard 自己拉取
      // （主进程 IPC 只在桌面端存在，组件内部按 isUpdateSupported() 降级，父级不参与判定）
      return { props: {}, on: {} }

    /* ---------------- 设置（聚合导航，纯前端） ---------------- */
    case '/settings':
      return {
        props: { isAdmin },
        on: { navigate: onSettingsNavigate }
      }

    default:
      return { props: {}, on: {} }
  }
  })()

  // 统一解包：`v-bind="obj"` 的对象形式**不会**自动解包 ref
  // （模板里的自动解包只作用于 `:prop="someRef"` 这种单值写法），
  // 这里逐个读 .value，顺带让 computed 正确依赖到每个 ref 的值变化。
  return { props: unwrapProps(raw.props), on: raw.on }
})

</script>
