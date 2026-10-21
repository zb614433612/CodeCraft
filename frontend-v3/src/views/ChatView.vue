<!--
  聊天页（v3）
  ----------------------------------------------------------------------------
  布局：头部（标题 / meta / 工具组）→ 消息流（概览条 + 消息 + 审批 + 任务）→ 输入区
  状态：
    empty     —— 新建会话空状态（只显示 Logo + slogan + 输入框 + 快捷指令）
    chat      —— 常规对话
    streaming —— 生成中（输入框禁用、发送按钮变停止、最后一条消息带光标）

  聊天增强（v3）：
    1) markdown 正文：由 ChatMessage 用 v-html 渲染，代码块 / 表格 / diff 均有样式；
    2) 文件路径跳转：正文里的本地路径渲染成芯片，点击在右侧抽屉预览（文本 / 图片）；
    3) 网页地址：markdown 外链统一 target=_blank 新窗口打开；
    4) 一键到底：上滑离开底部时出现浮动按钮；
    5) 流式可上滑：用户上滑查看历史时，流式增量不再把视图拽回底部；
    6) 运行状态栏（输入区上方）：执行状态 + 本次耗时 + 「补充需求」注入；
    7) 任务看板常驻入口：状态栏「任务 N/M」，点击向上弹出任务清单浮层；
    8) 改动/回滚常驻入口：状态栏「改动 +A/-D」，打开 ChangeReview 面板。

  数据接缝：
    props.conversation  -> 会话详情接口（标题、模型、执行模式、统计）
    props.messages      -> 会话消息接口（含工具调用、思考过程）
    props.tasks         -> task_manager 工具输出解析结果
    props.approval      -> permission_request / ask_user 事件
    props.projectRoot   -> 当前 Agent 工作目录（解析相对路径用）
    emit('send')        -> POST 会话发送 + SSE 流式接收
    emit('stop')        -> 取消当前流
    emit('approve'...)  -> 审批回执接口
-->
<template>
  <!--
    P4：两列布局 —— 左列聊天主流程，右列「工作区」（多标签 + 可拆分屏 + 内嵌网页）。
    workspaceVisible 为 false 时右列不渲染，结构与旧版单列完全一致（抽屉模式走同一条路径）。
    内部缩进未整体重排：这次改动只是加一层容器，重排会让 400 行模板的 diff 变得不可审。
  -->
  <div class="cc-chat" :class="{ 'is-ws': workspaceVisible }">
    <div class="cc-chat__main">
  <!-- ============ 头部 ============ -->
  <header class="cc-header">
    <div class="cc-header__title">{{ conversation.title || '新会话' }}</div>
    <div class="cc-header__meta">
      {{ conversation.model }}<template v-if="conversation.executionMode"> · {{ conversation.executionMode }}</template>
    </div>

    <div class="cc-header__right">
      <div class="cc-toolgroup">
        <button
          v-for="tool in tools"
          :key="tool.key"
          class="cc-toolgroup__btn"
          :class="{ 'is-on': isToolActive(tool.key) }"
          @click="toggleDrawer(tool.key)"
        >
          <svg class="cc-i cc-i--sm"><use :href="'#' + tool.icon" /></svg>{{ tool.label }}
        </button>
      </div>

      <!--
        AI 浏览器入口：只在真的开了窗口时出现。
        「永远显示一个灰按钮」会让人以为功能坏了；没有窗口时什么都不显示才是正确的空状态。
        点击展开面板，可显示/隐藏窗口、关闭标签 —— 用户不必再翻任务栏找 AI 在操作哪个页面。
      -->
      <div v-if="browserTabs.length" class="cc-browser">
        <button
          class="cc-browser__btn"
          :class="{ 'is-on': browserPanelOpen }"
          :title="`AI 正在操作 ${browserTabs.length} 个浏览器页面`"
          @click="toggleBrowserPanel"
        >
          <svg class="cc-i cc-i--sm"><use href="#i-globe" /></svg>
          AI 浏览器
          <span class="cc-browser__count">{{ browserTabs.length }}</span>
        </button>
        <div v-if="browserPanelOpen" class="cc-browser__panel">
          <div class="cc-browser__head">
            <span>AI 正在浏览</span>
            <button class="cc-browser__mini" title="刷新状态" @click="$emit('browser-refresh')">
              <svg class="cc-i cc-i--xs"><use href="#i-refresh" /></svg>
            </button>
          </div>
          <ul class="cc-browser__list">
            <li v-for="tab in browserTabs" :key="tab.tabId" class="cc-browser__item">
              <span class="cc-browser__tab">{{ tab.tabId }}</span>
              <span class="cc-browser__url" :title="tab.pageUrl || '(空白页)'">
                {{ tab.pageUrl || '(空白页)' }}
              </span>
              <span v-if="tab.active" class="cc-browser__badge">当前</span>
              <span class="cc-browser__ops">
                <button
                  class="cc-browser__mini"
                  :title="tab.visible ? '隐藏窗口' : '显示窗口'"
                  @click="$emit('browser-show', { tabId: tab.tabId, visible: !tab.visible })"
                >
                  <svg class="cc-i cc-i--xs"><use :href="tab.visible ? '#i-moon' : '#i-sun'" /></svg>
                </button>
                <button
                  class="cc-browser__mini is-danger"
                  title="关闭这个标签"
                  @click="$emit('browser-close', tab.tabId)"
                >
                  <svg class="cc-i cc-i--xs"><use href="#i-close" /></svg>
                </button>
              </span>
            </li>
          </ul>
          <div class="cc-browser__foot">
            <span class="cc-browser__hint">
              {{ browserAvailable ? '操作由 AI 发起，这里只是查看与收尾' : '浏览器通道当前不可用' }}
            </span>
            <button class="cc-browser__mini is-danger" title="关闭全部标签" @click="$emit('browser-close', '')">
              全部关闭
            </button>
          </div>
        </div>
      </div>
      <!--
        头部只保留真实可用的工具组按钮。
        此前的「搜索会话 / 更多操作」两个图标按钮没有任何 @click，属纯装饰占位，
        已删除：会话搜索改由侧栏搜索框承担，会话操作（重命名/删除/导出）在侧栏会话项菜单里。
      -->
    </div>
  </header>

  <!--
    ============ 未配置 Provider：引导页优先 ============
    一个可用模型都没有时，输入什么都发不出去。先把「为什么发不出去、要怎么配」讲清楚，
    比让用户敲完字再吃一句接口错误划算得多。
  -->
  <ProviderGuide
    v-if="showProviderGuide"
    :is-admin="isAdmin"
    @configure="$emit('configure-provider')"
    @refresh="$emit('refresh-providers')"
  />

  <!--
    ============ 加载中 / 加载失败 ============
    这两块必须放在「空状态」之前：切换会话时分页请求还没回来，messages 是空的，
    若直接落到空态，用户看到的是「开始新对话」——观感就是「点了会话没加载」。
    加载失败更不能装成空会话，否则用户会以为聊天记录丢了。
  -->
  <div v-else-if="loading" class="cc-empty cc-empty--state">
    <span class="cc-loading-dot" />
    <p class="cc-empty__sub">正在加载聊天记录…</p>
  </div>
  <div v-else-if="loadError" class="cc-empty cc-empty--state">
    <svg class="cc-i" style="width: 26px; height: 26px; color: var(--danger)"><use href="#i-alert" /></svg>
    <div class="cc-empty__title" style="font-size: var(--fs-lg)">聊天记录加载失败</div>
    <p class="cc-empty__sub">{{ loadError }}</p>
    <button class="cc-btn cc-btn--primary" @click="$emit('reload')">重试</button>
  </div>

  <!-- ============ 空状态 ============ -->
  <div v-else-if="isEmpty" class="cc-empty">
    <div class="cc-empty__logo">C</div>
    <h1 class="cc-empty__title">让 AI 融入你的开发流程</h1>
    <p class="cc-empty__sub">描述你的需求，或选择一个快捷指令开始</p>

    <div class="cc-empty__composer">
      <ChatComposer
        v-model="draft"
        :model="conversation.model"
        :rows="3"
        :context-mode="conversation.contextMode"
        placeholder="例如：帮我梳理这个项目的登录链路，并指出风险点"
        @send="onSend"
        @attach="$emit('attach')"
        @attach-history="$emit('attach-history')"
        @select-model="$emit('select-model')"
      />
    </div>

    <div class="cc-quick">
      <button v-for="q in quickActions" :key="q.title" class="cc-quick__card" @click="draft = q.prompt">
        <span class="cc-quick__icon"><svg class="cc-i cc-i--sm"><use :href="'#' + q.icon" /></svg></span>
        <span>
          <span class="cc-quick__title">{{ q.title }}</span>
          <span class="cc-quick__desc">{{ q.desc }}</span>
        </span>
      </button>
    </div>

    <!--
      空状态下也要能看见「别的会话还在跑」。
      运行状态栏只在消息流分支（v-else）里渲染，而这里是最容易被忽略的场景：
      用户新建空会话后，后台正好有一个任务在推进，没有任何提示会以为任务丢了。
    -->
    <button
      v-if="backgroundCount > 0"
      class="cc-runbar__bg cc-empty__bg"
      :title="`另有 ${backgroundCount} 个会话在后台生成，点击切换到该会话`"
      @click="$emit('switch-background')"
    >
      <span class="cc-runbar__pulse is-bg" />
      {{ backgroundCount }} 个会话后台运行中
    </button>
  </div>

  <!-- ============ 消息流 ============ -->
  <template v-else>
    <div class="cc-stream-wrap">
      <!-- wheel / touch 用于捕获「用户主动上滑」意图：流式增长时据此暂停自动贴底 -->
      <div
        ref="streamRef"
        class="cc-stream"
        @scroll="onScroll"
        @click="onStreamClick"
        @wheel="onWheel"
        @touchstart.passive="onTouchStart"
        @touchmove.passive="onTouchMove"
        @touchend.passive="onTouchEnd"
      >
        <div class="cc-stream__inner">
          <!-- 概览条：工具调用 / 改动 / 上下文（轻量一行） -->
          <div v-if="conversation.stats" class="cc-overview">
            <span><b>{{ conversation.stats.toolCalls }}</b> 个工具调用</span>
            <template v-if="conversation.stats.changedFiles != null">
              <span class="cc-overview__dot" />
              <button class="cc-overview__link" title="查看改动并回滚" @click="$emit('open-changes')">
                <b>{{ conversation.stats.changedFiles }}</b> 个文件改动
                <span style="color: var(--ok)">+{{ conversation.stats.added ?? 0 }}</span>
                <span style="color: var(--danger)">-{{ conversation.stats.deleted ?? 0 }}</span>
              </button>
            </template>
            <span class="cc-overview__dot" />
            <span>上下文 <b>{{ conversation.stats.contextTokens }}</b></span>
            <span class="cc-overview__right">{{ conversation.updatedAt }}</span>
          </div>

          <!--
            窗口化占位块（F08）：只渲染最近 windowSize 条消息，更早的收进这一行。
            滚到顶或点它会多放一段（不重新请求后端）；后端已无更多历史时提示可一直展开到最早。
          -->
          <div v-if="loadingMore || hiddenCount" class="cc-stream__window">
            <!-- 后端分页请求进行中：必须给出明确反馈，否则用户以为滚到顶没反应 -->
            <div v-if="loadingMore" class="cc-stream__window-loading">
              <span class="cc-loading-dot" />
              正在加载更早的聊天记录…
            </div>
            <button
              v-else
              class="cc-stream__window-btn"
              :title="`已加载的更早消息还有 ${hiddenCount} 条`"
              @click="showEarlier"
            >
              <svg class="cc-i cc-i--sm" style="transform: rotate(-90deg)"><use href="#i-chev" /></svg>
              上方还有 {{ hiddenCount }} 条消息 · 点击展开
            </button>
          </div>

          <!-- 消息列表（窗口化后只渲染尾部一段，见 utils/virtual-list） -->
          <ChatMessage
            v-for="msg in renderedMessages"
            :key="msg.id"
            :message="msg"
            :user-avatar="userAvatar"
            :user-name="userName"
            :agent-avatar="agentAvatar"
            @copy="$emit('copy', $event)"
            @regenerate="$emit('regenerate', $event)"
            @rollback="$emit('rollback', $event)"
            @expand="onMessageExpand"
          />

          <!--
            任务清单不再内联在消息流末尾：
            内联面板需要滚到最底部才看得到（长会话里几乎不可达），已移到输入区上方的
            运行状态栏（常驻入口 + 上弹浮层，见下方 cc-runbar）。
          -->
        </div>
      </div>

      <!-- 一键到底：上滑离开底部时出现，点击回到最新一条 -->
      <button v-if="!atBottom" class="cc-jump-bottom" title="回到最新" @click="scrollToBottom(true)">
        <svg class="cc-i cc-i--sm"><use href="#i-chev" /></svg>
        回到最新
      </button>
    </div>

    <!--
      待回执区（工具授权 / 用户询问 / 迭代确认）
      ----------------------------------------------------------------------------
      卡片原先内联在消息列表末尾：长会话里 AI 的最后一条输出在下方几屏之外，
      用户根本看不到「任务已暂停、在等你回执」，只能自己往下滚才发现（表现为「弹窗在下面」）。
      现在固定在「消息区 ↔ 输入区」之间：无论滚到哪儿都在同一位置可见；
      容器限高 + 内部滚动，内容再长也不会把阅读区挤没。
      新回执到达时：用户本就在底部附近 → 自动贴底看上下文；正在翻历史 → 不打断，
      由右上角「回到最新上下文」提供出口。
    -->
    <section
      v-if="approval"
      class="cc-approval-dock"
      :class="`is-${approval.askType || 'permission'}`"
      role="status"
      aria-live="polite"
    >
      <div class="cc-approval-dock__bar">
        <span class="cc-approval-dock__pulse" />
        <span class="cc-approval-dock__label">{{ approvalDockLabel }}</span>
        <span class="cc-approval-dock__hint">任务已暂停，需要你的回执</span>
        <button
          v-if="!atBottom"
          class="cc-approval-dock__jump"
          type="button"
          title="滚动到消息流底部，查看 AI 最后一条输出"
          @click="scrollToBottom(true)"
        >
          回到最新上下文
          <svg class="cc-i cc-i--sm"><use href="#i-chev" /></svg>
        </button>
      </div>
      <div class="cc-approval-dock__body">
        <ChatApproval
          :title="approval.title"
          :description="approval.description"
          :command="approval.command"
          :from="approval.from"
          :ask-type="approval.askType"
          @approve="$emit('approve')"
          @approve-all="$emit('approve-all')"
          @reject="$emit('reject')"
          @answer="$emit('answer', $event)"
          @continue="$emit('continue', $event)"
        />
      </div>
    </section>

    <!--
      运行状态栏（对齐旧项目底部工具条）
      ----------------------------------------------------------------------------
      左：执行状态 + 本次执行耗时（流式时）；「补充需求」入口也在这里，注入正在跑的 ToolLoop
      右：任务看板（完成数/总数）与改动回滚面板的常驻入口 —— 原先二者分别藏在内联面板
      和消息流顶部概览条里，长会话中根本滚不到，等于没有入口。
    -->
    <div class="cc-runwrap">
      <div class="cc-runbar">
        <!-- 左侧：状态 + 计时 + 补充需求 -->
        <div class="cc-runbar__status">
          <template v-if="isStreaming">
            <span class="cc-runbar__pulse" />
            <span class="cc-runbar__text">{{ streamStatusText }}</span>
            <span v-if="elapsedMs > 0" class="cc-runbar__elapsed">{{ elapsedText }}</span>
            <button
              v-if="!supplementOpen"
              class="cc-runbar__supp"
              title="告诉 AI 你临时追加的需求，会在它下一轮迭代时收到"
              @click="openSupplement"
            >
              <span class="cc-runbar__supp-icon">💡</span>补充需求
            </button>
          </template>
          <template v-else>
            <span class="cc-runbar__text is-idle">就绪</span>
            <!--
              别的会话在后台跑：这里给一条常驻提示 + 一键切过去。
              多会话并行后「当前会话空闲」不等于「什么都没发生」，不提示会让用户以为任务丢了。
            -->
            <button
              v-if="backgroundCount > 0"
              class="cc-runbar__bg"
              :title="`另有 ${backgroundCount} 个会话在后台生成，点击切换到该会话`"
              @click="$emit('switch-background')"
            >
              <span class="cc-runbar__pulse is-bg" />
              {{ backgroundCount }} 个会话后台运行中
            </button>
            <!--
              后端遗留的运行标记：入会话时后端报 running 但事件零增长，判定为疑似僵尸任务。
              输入已解锁，这里给一条「只在前端忽略」的出口 —— 不调 cancel，
              避免把仍在慢速产出的真实任务掐断（后端任务留给用户在会话里显式停止）。
            -->
            <button
              v-if="staleTask"
              class="cc-runbar__bg is-stale"
              title="后端仍标记该会话任务运行中，但已无任何新事件；点击只在页面上忽略这条标记，不会中断后端任务"
              @click="$emit('clear-stale')"
            >
              <span class="cc-runbar__warn">!</span>
              遗留运行标记 · 点击忽略
            </button>
          </template>
        </div>

        <!-- 右侧：子Agent / 任务 / 改动 -->
        <div class="cc-runbar__ops">
          <!--
            子Agent入口（F05）：主Agent 用 agent 工具 fork 出子Agent 才出现。
            运行中优先显示「N 运行」并带脉冲点，让「并行分身正在干活」这件事一眼可见。
          -->
          <button
            v-if="showSubAgentEntry"
            class="cc-runbar__btn"
            :class="{ 'is-active': subAgentPopOpen }"
            :title="
              subAgentStat.running
                ? `${subAgentStat.running} 个子Agent正在并行执行，点击查看`
                : `本会话共 ${subAgentStat.total} 个子Agent，点击查看执行明细`
            "
            @click="toggleSubAgentPop"
          >
            <span class="cc-runbar__agent-emoji">🤖</span>
            子Agent
            <span v-if="subAgentStat.running" class="cc-runbar__badge is-run">
              <span class="cc-sa__pulse" />{{ subAgentStat.running }}
            </span>
            <span v-else-if="subAgentStat.total" class="cc-runbar__badge">{{ subAgentStat.total }}</span>
            <span v-if="delegateActive" class="cc-runbar__badge is-bg" title="有委托调用在进行">↗{{ delegateActive }}</span>
          </button>
          <button
            class="cc-runbar__btn"
            :class="{ 'is-active': taskPopOpen }"
            :title="tasks.length ? '查看任务清单' : 'AI 还没建立任务清单（任务看板由 task_manager 工具产出）'"
            @click="toggleTaskPop"
          >
            <svg class="cc-i cc-i--sm"><use href="#i-task" /></svg>
            任务
            <span v-if="tasks.length" class="cc-runbar__badge">{{ taskStat.done }}/{{ taskStat.total }}</span>
            <!-- 有任务被前置依赖挡住时补一个警示标记：「进度停住」比「还剩几个」更值得提示 -->
            <span v-if="taskStat.blocked" class="cc-runbar__badge is-warn" :title="`${taskStat.blocked} 个任务在等前置任务完成`">
              {{ taskStat.blocked }} 阻塞
            </span>
            <!-- 其他会话也有任务清单时给个角标，否则并行跑起来完全看不出别处进度 -->
            <span v-if="backgroundTasks.length" class="cc-runbar__badge is-bg">+{{ backgroundTasks.length }}</span>
          </button>
          <button
            class="cc-runbar__btn"
            :title="
              snapshotCount > 0
                ? `查看本会话改动并回滚（历史上有 ${snapshotCount} 轮改动可回滚）`
                : '查看本会话改动并回滚'
            "
            @click="$emit('open-changes')"
          >
            <svg class="cc-i cc-i--sm"><use href="#i-undo" /></svg>
            改动
            <span v-if="changedSummary" class="cc-runbar__badge is-diff">{{ changedSummary }}</span>
            <!--
              轮次徽章：回滚按钮挂在「本轮有快照」的 AI 消息上，最近几轮没改文件时一条都不出现，
              用户会以为功能没做。这里如实标出「历史上有 N 轮可回滚」，入口永远看得见。
            -->
            <span v-if="snapshotCount > 0" class="cc-runbar__badge">{{ snapshotCount }} 轮</span>
          </button>
        </div>
      </div>

      <!-- 任务浮层：从状态栏向上弹出，不占消息区高度。
           多任务并行时同时展示「当前会话清单」与「其他会话进度」，后者只读、点击可切过去 -->
      <div v-if="taskPopOpen" class="cc-taskpop">
        <div class="cc-taskpop__head">
          <span>任务清单</span>
          <span class="cc-taskpop__count">{{ taskStat.done }} / {{ taskStat.total }}</span>
          <!-- 阻塞数：依赖未完成的条目占多少，一眼看出「进度卡在哪」 -->
          <span v-if="taskStat.blocked" class="cc-taskpop__blocked">{{ taskStat.blocked }} 阻塞</span>
          <button class="cc-icon-btn cc-icon-btn--sm" title="关闭" @click="taskPopOpen = false">
            <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
          </button>
        </div>
        <div v-if="!tasks.length" class="cc-taskpop__empty">
          AI 还没建立任务清单。任务看板由 task_manager 工具输出生成，复杂任务才会计入。
        </div>
        <div v-else class="cc-taskpop__body">
          <div
            v-for="item in tasks"
            :key="item.id"
            class="cc-taskpop__item"
            :class="{
              'is-done': item.status === 'done',
              'is-doing': item.status === 'doing',
              'is-blocked': isBlocked(item, tasks),
              'is-failed': item.failed
            }"
            :title="taskTitle(item)"
          >
            <!-- 左侧色条表达优先级（HIGH 红 / MEDIUM 橙 / LOW 灰） -->
            <span class="cc-taskpop__prio" :class="`is-${(item.priority || 'none').toLowerCase()}`" />
            <span class="cc-taskpop__box">{{ item.status === 'done' ? '✓' : '' }}</span>
            <span class="cc-taskpop__id">{{ item.id }}</span>
            <span class="cc-taskpop__title">{{ item.title }}</span>
            <span v-if="blockedBy(item, tasks).length" class="cc-taskpop__wait">
              等待 {{ blockedBy(item, tasks).join('、') }}
            </span>
            <span v-else-if="item.failed" class="cc-taskpop__failed">失败</span>
            <span v-if="item.priority" class="cc-taskpop__prio-tag" :class="`is-${item.priority.toLowerCase()}`">
              {{ item.priority }}
            </span>
          </div>
        </div>

        <!-- 其他会话：多任务并行时的全局视图（只读，点击切过去看明细） -->
        <template v-if="backgroundTasks.length">
          <div class="cc-taskpop__sep">
            <span>其他会话</span>
            <span class="cc-taskpop__sep-hint">并行运行中，点击可切换</span>
          </div>
          <div class="cc-taskpop__body">
            <button
              v-for="g in backgroundTasks"
              :key="g.key"
              type="button"
              class="cc-taskpop__item is-other"
              :title="`切换到「${g.title}」查看明细`"
              @click="onPickBackgroundTask(g.key)"
            >
              <span v-if="g.running" class="cc-taskpop__live" title="仍在运行" />
              <span v-else class="cc-taskpop__box" />
              <span class="cc-taskpop__title">{{ g.title }}</span>
              <span class="cc-taskpop__count is-sm">{{ g.done }}/{{ g.total }}</span>
            </button>
          </div>
        </template>
      </div>

      <!--
        子Agent浮层（F05）：与任务浮层同一位置向上弹出，二者互斥（见 toggleSubAgentPop / toggleTaskPop）。
        数据全部来自父级：fork 子Agent 来自 GET /api/sub-agents，委托调用来自 delegate_progress 心跳。
      -->
      <SubAgentPanel
        v-if="subAgentPopOpen"
        :runs="subAgents"
        :delegates="delegateCalls"
        @close="subAgentPopOpen = false"
        @open-session="onOpenDelegateSession"
      />
    </div>

    <!-- 补充需求输入条：仅在生成中展示，发送后注入当前 ToolLoop 的下一轮迭代 -->
    <div v-if="supplementOpen" class="cc-supplement">
      <input
        ref="supplementInputRef"
        v-model="supplementText"
        class="cc-supplement__input"
        placeholder="补充需求，AI 会在下一轮处理时收到…"
        :disabled="supplementSending"
        @keydown.enter="sendSupplement"
      />
      <button class="cc-btn cc-btn--primary" :disabled="supplementSending || !supplementText.trim()" @click="sendSupplement">
        {{ supplementSending ? '发送中…' : '发送' }}
      </button>
      <button class="cc-btn cc-btn--ghost" :disabled="supplementSending" @click="cancelSupplement">取消</button>
    </div>

    <!-- 输入区 -->
    <ChatComposer
      ref="composerRef"
      v-model="draft"
      :model="conversation.model"
      :attachments="attachments"
      :running="isStreaming"
      :context-tokens="conversation.stats?.contextTokens"
      :context-mode="conversation.contextMode"
      :balance="conversation.balance"
      :optimizing="optimizingPrompt"
      :undo-available="optimizeUndoAvailable"
      @send="onSend"
      @stop="$emit('stop')"
      @attach="$emit('attach')"
      @attach-history="$emit('attach-history')"
      @optimize="$emit('optimize')"
      @undo-optimize="undoOptimize"
      @select-model="$emit('select-model')"
      @remove-attachment="$emit('remove-attachment', $event)"
      @paste="$emit('paste', $event)"
    />
  </template>
    </div>

    <!--
      右侧工作区（P4）：多标签 + 可拆分屏 + 内嵌网页。
      只有 settings.workspaceMode = true 且工作区展开时才渲染；
      关掉它就走下面的旧抽屉，两条路互不干扰。
    -->
    <WorkspacePanel
      v-if="workspaceVisible"
      :root="projectRoot"
      @close="workspace.setOpen(false)"
      @switch-mode="onSwitchToDrawer"
    />
  </div>

  <!-- ============ 右侧工具抽屉（旧模式，settings.workspaceMode = false 时使用） ============ -->
  <div v-if="drawerOpen" class="cc-mask is-open" @click="closeDrawer" />
  <!-- 抽屉宽度 400px：Git 面板要放三列操作按钮 + 折叠区，终端要够多列，340px 太挤 -->
  <aside class="cc-drawer" :class="{ 'is-open': drawerOpen }" style="--drawer-w: 400px">
    <DrawerResizer storage-key="chat-tools" />
    <div class="cc-drawer__head">
      <div class="cc-drawer__tabs">
        <button
          v-for="tool in tools"
          :key="tool.key"
          class="cc-drawer__tab"
          :class="{ 'is-active': drawerTab === tool.key }"
          @click="drawerTab = tool.key"
        >
          {{ tool.label }}
        </button>
      </div>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="closeDrawer">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>
    <div class="cc-drawer__body">
      <!-- 消息里的文件路径点击后：在抽屉内直接预览 -->
      <div v-if="drawerTab === 'files' && filePreview" class="cc-fileview">
        <div class="cc-fileview__head">
          <span class="cc-fileview__path" :title="filePreview.path">{{ filePreview.path }}</span>
          <button class="cc-icon-btn cc-icon-btn--sm" title="关闭预览" @click="closeFilePreview">
            <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
          </button>
        </div>
        <div v-if="filePreview.loading" class="cc-fileview__state">加载中…</div>
        <div v-else-if="filePreview.error" class="cc-fileview__state is-error">{{ filePreview.error }}</div>
        <img v-else-if="filePreview.imageUrl" class="cc-fileview__img" :src="filePreview.imageUrl" alt="图片预览" />
        <pre v-else class="cc-fileview__code">{{ filePreview.content }}</pre>
      </div>

      <slot v-else name="drawer" :tab="drawerTab">
        <!-- 默认占位：实际由页面注入文件树 / Git / 终端组件 -->
        <div style="padding: 24px 8px; text-align: center; color: var(--text-4); font-size: 12.5px">
          {{ drawerTab === 'files' ? '文件树' : drawerTab === 'git' ? 'Git 变更' : '终端' }}面板（待接入）
        </div>
      </slot>
    </div>
  </aside>
</template>

<script setup lang="ts">
import DrawerResizer from '../components/DrawerResizer.vue'
import { computed, nextTick, onBeforeUnmount, ref, watch, onMounted } from 'vue'
import ChatComposer, { type Attachment } from '../components/ChatComposer.vue'
import ChatMessage, { type ChatMessageModel } from '../components/ChatMessage.vue'
import ChatApproval from '../components/ChatApproval.vue'
import ProviderGuide from '../components/ProviderGuide.vue'
/* P4：右侧工作区（多标签 / 分屏 / 内嵌网页） */
import WorkspacePanel from '../components/WorkspacePanel.vue'
import { useSettingsStore } from '../store/settings'
import { useWorkspaceStore } from '../store/workspace'
import { toast } from '../utils/toast'
/*
 * 任务看板已改为「状态栏按钮 + 上弹浮层」，ChatTaskPanel 组件不再内联渲染。
 * 类型与统计口径统一来自 utils/task-list（F12：优先级 / 依赖 / 阻塞）。
 */
import type { TaskItem } from '../utils/task-list'
import { blockedBy, isBlocked, taskStats } from '../utils/task-list'
/* F05：子Agent面板（fork 子Agent 读库 + 委托调用心跳） */
import SubAgentPanel from '../components/SubAgentPanel.vue'
import type { DelegateCall, SubAgentRun } from '../utils/agent-event'
import { formatDuration, subAgentStats } from '../utils/agent-event'
/* F08：消息列表窗口化（尾部窗口 + 顶部占位块） */
import { useTailWindow } from '../utils/virtual-list'
import { readProjectFile, getProjectFileBlobUrl } from '../api/project'
import { describeMissingFile, resolveFileTarget, type ResolvedFileTarget } from '../utils/file-path'
/* AI 浏览器标签：状态来自 /api/browser/sessions（后端实时问 Electron bridge） */
import type { BrowserTab } from '../api/browser'

export interface ConversationDetail {
  id: string
  title: string
  model: string
  executionMode?: string
  /** 上下文模式：full / compact（输入区底部据此显示「⚡ 精简」徽章） */
  contextMode?: string
  balance?: string
  updatedAt?: string
  stats?: {
    toolCalls: number
    /** 文件改动数来自快照接口，未接入时为空 —— 概览条相应片段自动隐藏 */
    changedFiles?: number
    added?: number
    deleted?: number
    contextTokens: string
  }
}

export interface ApprovalPayload {
  title: string
  /**
   * 问题正文（后端 question 原文，**markdown**）。
   * 由 ChatApproval 用 renderMarkdown 渲染后 v-html 输出 ——
   * 因此这里不要截断、也不要预先转义，代码块 / diff 红绿都依赖原文完整。
   */
  description?: string
  command?: string
  from?: string
  /**
   * 后端 ask_user 事件的 askType：
   * permission（授权）/ clarification（提问）/ iteration_confirm（迭代确认）。
   * 决定审批卡片的按钮文案与是否必须文本回答。
   */
  askType?: string
}

const props = withDefaults(
  defineProps<{
    conversation: ConversationDetail
    messages?: ChatMessageModel[]
    tasks?: TaskItem[]
    approval?: ApprovalPayload | null
    attachments?: Attachment[]
    /** 是否生成中（由 SSE 状态决定） */
    isStreaming?: boolean
    /** 当前 Agent 工作目录（解析消息里的相对路径用） */
    projectRoot?: string
    /**
     * 「补充需求」的发送状态（由父级持有：只有父级知道会话 id 与接口结果）。
     * idle / sending / done / error —— done 时组件清空并收起输入条，error 时保留内容便于重试。
     */
    supplementState?: 'idle' | 'sending' | 'done' | 'error'
    /** 当前用户头像（图片 URL / emoji / 单字）：渲染在用户气泡右侧 */
    userAvatar?: string
    /** 当前用户昵称（无头像时取首字） */
    userName?: string
    /** 当前 Agent 头像：消息自身没带 agentAvatar 时兜底 */
    agentAvatar?: string
    /**
     * 其他会话的后台任务数（多会话并行流 C 档）。
     * 当前会话空闲但别的会话在跑时，状态栏给一条可点击的提示，避免用户以为没反应。
     */
    backgroundCount?: number
    /**
     * 其他会话的任务清单摘要（多任务并行适配）。
     * 任务浮层里以「其他会话」分组只读展示，点击 emit('switch-background-task') 切过去看明细。
     * 没有这份数据时，并行跑多个会话只能一个个点进去才知道别处的进度。
     */
    backgroundTasks?: Array<{ key: string; title: string; done: number; total: number; running: boolean }>
    /**
     * 当前会话是否带「后端遗留的运行标记」（僵尸任务）。
     * 为 true 时输入框已解锁，状态栏给一条「清理标记」的出口。
     */
    staleTask?: boolean
    /**
     * 本会话「历史上有几轮改动可回滚」（未回滚的快照数）。
     *
     * <p>为什么单独传：消息上的「回滚改动」按钮只在**本轮产生过快照**时出现，
     * 而最近几轮往往没改文件 → 页面上一个按钮都没有，看起来像功能没做。
     * 状态栏据此常驻显示「改动 · N 轮」，用户不必先翻历史就能找到入口。</p>
     */
    snapshotCount?: number
    /**
     * 子Agent列表（F05）。
     *
     * <p>来源是 `GET /api/sub-agents`（读 `sub_agent_log` 表），不是 SSE 事件 ——
     * 后端 fork 子Agent 时从不发 `agent_event`，只把结果写成纯文本塞进思考流。
     * 父级在「流式期间轮询 + 会话切回时拉一次」维护这份数据。</p>
     */
    subAgents?: SubAgentRun[]
    /**
     * 委托调用列表（`delegate_progress` 心跳）：A 委托另一个智能体执行任务的进行项。
     * 与子Agent（fork）语义不同，面板里分块展示。
     */
    delegateCalls?: DelegateCall[]
    /**
     * 模型提供商列表（组件只关心长度）。
     *
     * <p>为空且不在加载中、也没有错误时，空态整块换成「未配置 Provider」引导页：
     * `GET /api/llm-providers` 只返回 `enabled = 1` 的记录，空数组即「一个可用模型都没有」，
     * 此时让用户对着输入框敲字、发出去再报接口错误，体验最差。判定见 `showProviderGuide`。</p>
     */
    providers?: unknown[]
    providerLoading?: boolean
    providerError?: string
    /** 是否管理员：决定引导页的「去配置」能否点击（Provider 读写接口都要 checkAdmin） */
    isAdmin?: boolean
    /**
     * 聊天记录加载态（首次进入 / 切换会话时由父级控制）。
     *
     * <p>没有这个 prop 时，切换会话后会先闪一下「开始新对话」的空态（分页请求还没回来），
     * 用户看到的就是「点了会话没加载」。</p>
     */
    loading?: boolean
    /** 更早历史正在加载（滚动到顶部触发）：顶部占位块换成「正在加载更早的聊天记录…」 */
    loadingMore?: boolean
    /** 聊天记录加载失败原因：非空时展示错误条 + 重试按钮，而不是误导性的空态 */
    loadError?: string
    /**
     * 提示词优化进行中（由父级持有：只有父级知道请求状态）。
     *
     * <p>优化是一次同步等待 LLM 返回的请求（3~10 秒），Composer 需要据此把 ✨ 按钮
     * 换成旋转图标、锁住输入框并打出「AI 正在优化…」，否则用户以为点了没反应。</p>
     */
    optimizingPrompt?: boolean
    /**
     * AI 浏览器标签列表（本会话）。
     *
     * <p>为什么由父级传入而不是组件自己拉：只有父级知道「什么时候该刷新」——
     * SSE 里出现 browser 工具调用、用户点显示/关闭之后都要重查。
     * 组件只负责展示与转发操作。</p>
     */
    browserTabs?: BrowserTab[]
    /** 浏览器通道是否可用（非桌面客户端 / 客户端刚启动为 false，此时隐藏入口） */
    browserAvailable?: boolean
    /**
     * 进入页面时的数据加载（由 App.vue 的 PAGE_BINDINGS 注入）
     * 页面随路由真实挂载，所以在这里调一次即可；重复进页由父级的「已加载 / 加载中 / 已失败」跳过
     */
    load?: () => void
  }>(),
  {
    messages: () => [],
    tasks: () => [],
    approval: null,
    attachments: () => [],
    isStreaming: false,
    projectRoot: '',
    supplementState: 'idle',
    userAvatar: '',
    userName: '',
    agentAvatar: '',
    backgroundCount: 0,
    backgroundTasks: () => [],
    staleTask: false,
    snapshotCount: 0,
    subAgents: () => [],
    delegateCalls: () => [],
    providers: () => [],
    providerLoading: false,
    providerError: '',
    isAdmin: false,
    loading: false,
    loadingMore: false,
    loadError: '',
    optimizingPrompt: false,
    browserTabs: () => [],
    browserAvailable: false
  }
)

const emit = defineEmits<{
  (e: 'send', text: string): void
  (e: 'stop'): void
  (e: 'approve'): void
  (e: 'approve-all'): void
  (e: 'reject'): void
  /** 审批卡片的文本回答（clarification 必填；permission 下为用户自定义指令） */
  (e: 'answer', text: string): void
  /** 迭代确认「继续 +N 次」：参数为本次扩容的迭代次数（10 / 20 / 50） */
  (e: 'continue', additional: number): void
  (e: 'attach'): void
  (e: 'attach-history'): void
  (e: 'optimize'): void
  (e: 'select-model'): void
  (e: 'paste', ev: ClipboardEvent): void
  (e: 'remove-attachment', id: string): void
  (e: 'copy', msg: ChatMessageModel): void
  (e: 'regenerate', msg: ChatMessageModel): void
  /** 回滚本轮代码改动（本轮产生过快照时，AI 消息操作区才出现该按钮） */
  (e: 'rollback', msg: ChatMessageModel): void
  /** 滚动到消息顶部（加载更早历史） */
  (e: 'load-more'): void
  /** 聊天记录加载失败后点「重试」：由父级重新拉当前会话 */
  (e: 'reload'): void
  /** 点击概览条的「N 个文件改动」或状态栏的「改动」：打开改动/回滚面板 */
  (e: 'open-changes'): void
  /** 生成过程中补充需求：注入正在执行的 ToolLoop 下一轮迭代 */
  (e: 'supplement', text: string): void
  /** 切到某个后台运行中的会话（多会话并行流：当前会话空闲时状态栏的提示入口） */
  (e: 'switch-background'): void
  /** 切到某个「其他会话」查看任务明细（任务浮层里的其他会话分组） */
  (e: 'switch-background-task', key: string): void
  /** 清理后端遗留的运行标记（僵尸任务出口） */
  (e: 'clear-stale'): void
  /** 引导页「去配置」：由父级跳「模型配置」页 */
  (e: 'configure-provider'): void
  /** 引导页「刷新」：重新拉一次 Provider 列表 */
  (e: 'refresh-providers'): void
  /** 子Agent面板里点「前往会话」：切到被委托智能体的会话看明细 */
  (e: 'open-subagent-session', sessionId: number): void
  /** AI 浏览器：显示 / 隐藏某个标签窗口 */
  (e: 'browser-show', payload: { tabId: string; visible: boolean }): void
  /** AI 浏览器：关闭某个标签窗口；tabId 为空表示关闭全部 */
  (e: 'browser-close', tabId: string): void
  /** AI 浏览器：手动刷新标签状态 */
  (e: 'browser-refresh'): void
}>()

/** 点任务浮层里的其他会话：先收起浮层，再交给父级切会话 */
function onPickBackgroundTask(key: string) {
  taskPopOpen.value = false
  emit('switch-background-task', key)
}

/** 点子Agent面板里的「前往会话」：收起浮层并切会话 */
function onOpenDelegateSession(sessionId: number) {
  subAgentPopOpen.value = false
  emit('open-subagent-session', sessionId)
}

/** 空状态判定：无消息且不在生成中 */
const isEmpty = computed(() => props.messages.length === 0 && !props.isStreaming)

/**
 * 是否用「配置引导页」替代聊天界面。
 *
 * 三个条件缺一不可：
 *   · providers 为空 —— 真的一个可用 Provider 都没有（列表接口只返回 enabled=1）；
 *   · !providerLoading —— 首屏有一段空数组窗口，抢在加载完成前显示会闪一下；
 *   · !providerError   —— 后端连不上属于另一类故障，由页面自身的错误提示负责，
 *                         这里显示「没有 Provider」会误导用户白改配置。
 */
const showProviderGuide = computed(
  () => props.providers.length === 0 && !props.providerLoading && !props.providerError
)

const draft = ref('')

/**
 * AI 浏览器面板展开态。
 *
 * <p>面板是「看一眼 + 收尾」的轻量入口，不做成弹窗：浏览器操作的主体是模型，
 * 用户多数时候只需要扫一眼地址、必要时把窗口调出来或关掉。</p>
 */
const browserPanelOpen = ref(false)

function toggleBrowserPanel() {
  browserPanelOpen.value = !browserPanelOpen.value
}

/** 标签全部关闭后自动收起面板，避免留一个指向空列表的浮层 */
watch(
  () => props.browserTabs.length,
  len => {
    if (len === 0) browserPanelOpen.value = false
  }
)

/**
 * 提示词优化的撤销快照（`before` = 优化前原文，`result` = 回填后的文本）。
 *
 * <p>放在 ChatView 而不是 App：撤销是否可用取决于「草稿有没有被用户改过」，
 * 而草稿是这里的内部状态。挂在 App 上会让每次输入都触发整个 App 重渲染。</p>
 */
const optimizeUndo = ref<{ before: string; result: string } | null>(null)

/** 草稿仍是优化结果时才提供撤销，避免用户改完字点撤销把新输入覆盖掉 */
const optimizeUndoAvailable = computed(
  () => !!optimizeUndo.value && draft.value === optimizeUndo.value.result
)

/** 回填优化结果并记录撤销快照（由父级在请求成功后调用） */
function applyOptimizedDraft(before: string, result: string) {
  optimizeUndo.value = { before, result }
  draft.value = result
}

/** 撤销：回到优化前原文；快照只用一次 */
function undoOptimize() {
  const snap = optimizeUndo.value
  if (!snap) return
  // 双保险：computed 已保证按钮只在草稿等于结果时出现，这里再校验一次
  if (draft.value !== snap.result) {
    optimizeUndo.value = null
    return
  }
  draft.value = snap.before
  optimizeUndo.value = null
}

/** 切会话后旧快照立刻作废：否则「撤销」会把上个会话的原文填进当前输入框 */
watch(
  () => props.conversation.id,
  () => {
    optimizeUndo.value = null
  }
)
const streamRef = ref<HTMLElement | null>(null)
const composerRef = ref<InstanceType<typeof ChatComposer> | null>(null)

/** 头部工具组（文件 / Git / 终端） */
const tools = [
  { key: 'files', label: '文件', icon: 'i-file' },
  { key: 'git', label: 'Git', icon: 'i-git' },
  { key: 'term', label: '终端', icon: 'i-term' }
] as const

const drawerOpen = ref(false)
const drawerTab = ref<'files' | 'git' | 'term'>('files')

/* ---------------- P4：右侧工作区 ---------------- */

const settings = useSettingsStore()
const workspace = useWorkspaceStore()

/** 工作区是否占位：既要开着开关，也要处于展开状态 */
const workspaceVisible = computed(() => settings.workspaceMode && workspace.open)

/** 文件 / Git / 终端 三个按钮对应的工作区标签类型 */
const TOOL_TAB: Record<'files' | 'git' | 'term', 'tree' | 'git' | 'term'> = {
  files: 'tree',
  git: 'git',
  term: 'term'
}

/** 按钮高亮：工作区模式下看两栏是否有同类标签激活，抽屉模式下沿用旧判定 */
function isToolActive(key: 'files' | 'git' | 'term') {
  if (settings.workspaceMode) {
    if (!workspace.open) return false
    const kind = TOOL_TAB[key]
    return workspace.activeTabOf('main')?.kind === kind || workspace.activeTabOf('side')?.kind === kind
  }
  return drawerOpen.value && drawerTab.value === key
}

function toggleDrawer(tab: 'files' | 'git' | 'term') {
  /*
   * 工作区模式：同一个按钮承担「打开 / 收起」。
   * 已经停在同一类标签上时再点一次就收起面板，行为和旧抽屉的 toggle 一致。
   */
  if (settings.workspaceMode) {
    const kind = TOOL_TAB[tab]
    if (workspace.open && workspace.activeTabOf('main')?.kind === kind) {
      workspace.setOpen(false)
      return
    }
    workspace.openTab(kind)
    return
  }
  if (drawerOpen.value && drawerTab.value === tab) {
    drawerOpen.value = false
    return
  }
  drawerTab.value = tab
  drawerOpen.value = true
}

/** 工作区头部「切回抽屉模式」：把形态开关关掉，并把当前标签对应的面板立刻用抽屉打开 */
function onSwitchToDrawer() {
  settings.setWorkspaceMode(false)
  workspace.setOpen(false)
  toast.info('已切回抽屉模式', { description: '想改回来：「外观设置 → 右侧面板」' })
}

function closeDrawer() {
  drawerOpen.value = false
}

/** 空状态快捷指令 */
const quickActions = [
  { icon: 'i-book', title: '解释项目结构', desc: '快速读懂这个仓库的分层与关键模块', prompt: '帮我解释一下这个项目的整体结构与关键模块' },
  { icon: 'i-bug', title: '修复编译错误', desc: '定位报错并给出最小改动方案', prompt: '帮我定位并修复当前的编译错误' },
  { icon: 'i-check', title: '补单元测试', desc: '为指定模块生成可运行的测试用例', prompt: '为当前模块补充单元测试' },
  { icon: 'i-brush', title: '整理样式规范', desc: '统一颜色 / 圆角 / 间距的设计 token', prompt: '帮我梳理前端样式规范并统一 design token' }
]

function onSend() {
  const text = draft.value.trim()
  if (!text) return
  emit('send', text)
  draft.value = ''
  // 发送后草稿清空，优化撤销入口没有意义了，顺手释放快照
  optimizeUndo.value = null
}

/* ============================ 滚动：贴底 / 一键到底 ============================ */

/** 用户是否停留在底部（距底 80px 内视为「在看最新」）—— 决定「回到最新」按钮是否出现 */
const atBottom = ref(true)
/** 是否跟随最新输出：几何位置 + 用户最近一次上滑共同决定 */
const follow = ref(true)
/** 最近一次「向上滚动」的时间戳（ms）：流式增长时短暂豁免自动贴底，尊重用户查历史 */
let lastScrollUpAt = 0

/** 底部判定阈值（px）：流式内容增长很快，阈值过小会频繁「脱底」导致后续不再跟随 */
const BOTTOM_GAP = 80
/** 真正贴到底的阈值（px）：只有回到这里才认为用户主动恢复跟随 */
const STICK_GAP = 8
/** 手动上滑后豁免自动贴底的时长（ms） */
const SCROLL_UP_GRACE = 1200

function gapToBottom(el: HTMLElement) {
  return el.scrollHeight - el.scrollTop - el.clientHeight
}

function updateAtBottom() {
  const el = streamRef.value
  if (!el) return
  const gap = gapToBottom(el)
  atBottom.value = gap < BOTTOM_GAP
  follow.value = atBottom.value
  // 真正贴底（几乎无间距）才清除「上滑豁免」：轻微上滑后仍能保持短暂不跟随
  if (gap < STICK_GAP) lastScrollUpAt = 0
}

async function scrollToBottom(smooth = false) {
  await nextTick()
  const el = streamRef.value
  if (!el) return
  // 主动贴底（新消息 / 点「回到最新」/ 跟随增长）后不再保留上滑豁免
  lastScrollUpAt = 0
  el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
  atBottom.value = true
  follow.value = true
  if (smooth) return
  // 二次校正：markdown 是 80ms 节流渲染的，nextTick 之后内容还会继续撑高，
  // 一次 scrollTo 往往落后几十~上百像素，这里在下两帧内追平（仍落后才补，避免抖动）
  const settle = (depth: number) => {
    requestAnimationFrame(() => {
      const cur = streamRef.value
      if (!cur || !follow.value || depth > 2) return
      if (gapToBottom(cur) < 2) return
      cur.scrollTop = cur.scrollHeight
      settle(depth + 1)
    })
  }
  settle(0)
}

/* ---------- 待回执区（审批 / 询问固定停靠） ---------- */

/** 停靠条标题：按 askType 区分「等你授权 / 等你回答 / 等你决定」 */
const approvalDockLabel = computed(() => {
  switch (props.approval?.askType) {
    case 'clarification':
      return 'AI 在等你回答问题'
    case 'iteration_confirm':
      return 'AI 在等你确认是否继续'
    default:
      return '有工具操作在等你授权'
  }
})

/**
 * 新回执到达：用户本就在底部附近时补一次贴底，方便对照 AI 最后一条输出；
 * 正在翻历史则不打断（卡片已固定可见），由停靠条上的「回到最新上下文」提供出口。
 */
watch(
  () => props.approval,
  val => {
    if (!val) return
    if (atBottom.value) void scrollToBottom(true)
  }
)

/**
 * 流式增长时是否应该贴底。
 * 用户刚手动上滑过 → 本次不贴（避免「拉不上去」）；否则看是否仍在跟随态。
 */
function shouldStick() {
  if (Date.now() - lastScrollUpAt < SCROLL_UP_GRACE) return false
  return follow.value
}

/* ---------- 用户主动上滑的意图捕获（滚轮 / 触摸） ---------- */
function onWheel(e: WheelEvent) {
  if (e.deltaY < 0) lastScrollUpAt = Date.now()
}

let touchStartY = 0
/** 手指按住滚动时不要抢滚动条（否则会和手感打架） */
let touching = false
function onTouchStart(e: TouchEvent) {
  touching = true
  touchStartY = e.touches[0]?.clientY ?? 0
}
function onTouchMove(e: TouchEvent) {
  const y = e.touches[0]?.clientY ?? 0
  // 手指下滑 = 内容向上翻（看更早的历史）
  if (y > touchStartY + 2) lastScrollUpAt = Date.now()
  touchStartY = y
}
function onTouchEnd() {
  touching = false
}

/** 尾部指纹：用于区分「新增消息」「头部插入历史」「流式增长」 */
let lastTailId = ''
let lastTailSig = ''
let lastCount = 0

/**
 * 加载更早历史前的滚动快照（后端分页的位置补偿用）。
 *
 * <p>声明必须放在上面那个 watch 之前：watch 回调里会读它，
 * 若声明在下文（onScroll 附近）会命中 TDZ（Cannot access before initialization）。</p>
 */
let prependAnchor: { scrollHeight: number; scrollTop: number } | null = null

watch(
  () => {
    const last = props.messages[props.messages.length - 1]
    // 思考过程长度也要参与：思考区不断增高时同样需要跟随（正文此时还是空的）
    const thinkLen = (last?.process ?? []).reduce((n, p) => n + (p.text?.length ?? 0), 0)
    return {
      id: last?.id ?? '',
      len: last?.content?.length ?? 0,
      thinkLen,
      toolCount: last?.tools?.length ?? 0,
      count: props.messages.length
    }
  },
  async cur => {
    const sig = `${cur.len}/${cur.thinkLen}/${cur.toolCount}`
    const newMessage = cur.id !== lastTailId
    const prependedOnly = !newMessage && sig === lastTailSig && cur.count > lastCount
    const grew = sig !== lastTailSig
    lastTailId = cur.id
    lastTailSig = sig
    lastCount = cur.count
    /*
      头部插入历史消息（后端分页 / 本地窗口展开）时不贴底，否则用户会被拽到最新一条。

      后端分页这条路径还必须把视口钉回原位置：新消息插在前面会让内容整体下移，
      不补偿的话用户翻到的那条记录直接跑掉（表现为「加载完就跳到别处」）。
      本地窗口展开（showEarlier）自己做了补偿，此时 prependAnchor 为 null，直接返回即可。
    */
    if (prependedOnly) {
      if (prependAnchor) {
        const anchor = prependAnchor
        prependAnchor = null
        await nextTick()
        const el = streamRef.value
        if (el) {
          el.scrollTop = Math.max(0, el.scrollHeight - anchor.scrollHeight + anchor.scrollTop)
          // markdown / 代码高亮会在下一帧继续撑高，再校正一次（只在仍偏离锚点时才补，避免抖动）
          requestAnimationFrame(() => {
            const cur = streamRef.value
            if (!cur) return
            const target = cur.scrollHeight - anchor.scrollHeight + anchor.scrollTop
            if (Math.abs(cur.scrollTop - target) > 2) cur.scrollTop = Math.max(0, target)
          })
        }
      }
      return
    }
    // 新增消息（发消息 / 打开会话）总是贴底；
    // 同一条消息的流式增长（正文 / 思考 / 工具）只在跟随态贴底 —— 上滑查历史时不再被拽回
    if (newMessage) await scrollToBottom()
    else if (grew && shouldStick()) await scrollToBottom()
  }
)

/**
 * 高度兜底观察器：markdown 节流渲染、代码高亮、图片/表格撑高都会让容器高度二次变化，
 * 这些变化不经过 messages 数据（watch 抓不到），只能靠 ResizeObserver 补齐，
 * 否则「跟随」会稳定地差最后几十像素，累积后彻底脱底。
 */
let innerObserver: ResizeObserver | null = null

function bindStreamObserver() {
  innerObserver?.disconnect()
  innerObserver = null
  const inner = streamRef.value?.querySelector('.cc-stream__inner') as HTMLElement | null
  if (!inner || typeof ResizeObserver === 'undefined') return
  innerObserver = new ResizeObserver(() => {
    const el = streamRef.value
    if (!el) return
    if (!shouldStick()) return
    // 已经贴底就不必再滚，避免无意义的 scroll 抖动
    if (gapToBottom(el) < 2) return
    void scrollToBottom()
  })
  innerObserver.observe(inner)
}

// 空状态 → 消息流是 v-if 切换，streamRef 后出现，需在 ref 就绪时重新绑定
watch(streamRef, () => bindStreamObserver(), { flush: 'post' })
onMounted(() => bindStreamObserver())

/* ---------- 流式期兜底：节流渲染 / 图片撑高偶发漏帧时，保证最终仍贴底 ---------- */
let stickTimer: number | null = null
function stopStickTimer() {
  if (stickTimer != null) {
    clearInterval(stickTimer)
    stickTimer = null
  }
}

watch(
  () => props.isStreaming,
  streaming => {
    stopStickTimer()
    if (!streaming) return
    // 低频兜底（120ms）：只在「跟随态且确实落后」时才写 scrollTop，开销可忽略。
    // 思考过程 / 工具调用组展开后的内容增长也走这条路径，保证滚动条始终在最底部。
    stickTimer = window.setInterval(() => {
      if (touching) return
      const el = streamRef.value
      if (!el || !shouldStick()) return
      if (gapToBottom(el) < 2) return
      el.scrollTop = el.scrollHeight
    }, 120)
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  stopStickTimer()
  innerObserver?.disconnect()
  innerObserver = null
})

/* ---------- 窗口化（F08）：只渲染最近一段消息 ---------- */
/**
 * 消息窗口：默认渲染最近 60 条，更早的收进顶部占位块，每次展开多放 40 条。
 *
 * 不走「逐项高度测量 + 绝对定位」那套虚拟列表，是因为消息高度极不稳定
 * （思考折叠、工具展开、代码块、图片、diff 都会变高），测量值一旦失准就会出现
 * 占位块与真实内容高度不一致 —— 滚动条会跳。代价与取舍见 utils/virtual-list.ts。
 */
const {
  visible: renderedMessages,
  hiddenCount,
  expand: expandWindow,
  reset: resetWindow
} = useTailWindow(computed(() => props.messages ?? []), { initial: 60, step: 40 })

/** 切换会话时回到初始窗口（否则上个会话展开到 400 条，切过来还是 400 条） */
watch(() => props.conversation.id, () => resetWindow())

/** 展开节流：一次滚动可能连发多个 scroll 事件，避免同一帧叠加多次扩容 */
let expandingWindow = false

/**
 * 展开一段更早的消息，并补偿滚动位置。
 *
 * 新内容插在**上方**，浏览器保持 scrollTop 数值不变，视觉上等同于「内容突然往下跳」；
 * 用「距底部距离」在 nextTick 后反算 scrollTop，视口就钉在原来那条消息上。
 * 同时把 follow 置 false：用户在看历史，别让 ResizeObserver 的贴底逻辑把人拽回最新。
 */
async function showEarlier() {
  if (expandingWindow || !hiddenCount.value) return
  expandingWindow = true
  const el = streamRef.value
  const distanceFromBottom = el ? el.scrollHeight - el.scrollTop : 0
  follow.value = false
  expandWindow()
  await nextTick()
  if (el) {
    el.scrollTop = Math.max(0, el.scrollHeight - distanceFromBottom)
    /*
      markdown / 代码高亮会在下一帧继续把内容撑高，只补偿一次仍会差几十像素，
      用户看到的就是「展开后位置偏了一点」。这里再对齐一帧（只在确实偏离时补）。
    */
    requestAnimationFrame(() => {
      const cur = streamRef.value
      if (!cur) return
      const target = Math.max(0, cur.scrollHeight - distanceFromBottom)
      if (Math.abs(cur.scrollTop - target) > 2) cur.scrollTop = target
    })
  }
  expandingWindow = false
}

/**
 * 向后端要更早的历史。
 *
 * <p>先记录滚动锚点（scrollHeight / scrollTop），数据到达后由上面那个 watch
 * 用高度差回算 scrollTop，把视口钉回用户正在看的那条消息上。</p>
 */
function requestMore() {
  const el = streamRef.value
  if (el) prependAnchor = { scrollHeight: el.scrollHeight, scrollTop: el.scrollTop }
  emit('load-more')
}

/** 滚动到顶部时请求更早历史；同时同步「是否在底部 / 是否跟随」 */
function onScroll() {
  const el = streamRef.value
  if (!el) return
  updateAtBottom()
  if (el.scrollTop > 24) return
  // 先展开本地窗口（已加载但被窗口挡住的更早消息），本地放完了再向后端要更早的历史。
  // 反过来会导致「明明还有内容在内存里，却先发一次网络请求」。
  if (hiddenCount.value > 0) void showEarlier()
  else requestMore()
}

/**
 * 用户展开「思考过程 / 工具调用组」。
 * 这两个区域承载的是「最新进展」，展开后其内容继续增长时一直贴底（由 watch + ResizeObserver 驱动）；
 * 展开动作本身只在「用户仍在跟随态」时把视口带到最新内容 ——
 * 若用户此前已手动上滑离开底部，则尊重其浏览位置，不强行拽回。
 */
function onMessageExpand() {
  if (!follow.value) return
  void scrollToBottom(true)
}

/* ============================ 运行状态栏：耗时 / 补充需求 / 任务与改动入口 ============================ */

/**
 * 本次执行耗时（ms）。
 * ----------------------------------------------------------------------------
 * 对齐旧项目 `CodeAssistantView.elapsedTime`：流式开始清零并每秒累加，结束后归零。
 * 状态栏整行只在 isStreaming 时出现，所以不需要保留「上一次跑了多久」。
 */
const elapsedMs = ref(0)
let elapsedTimer: number | null = null
let runStartAt = 0

function stopElapsedTimer() {
  if (elapsedTimer != null) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
}

function startElapsedTimer() {
  stopElapsedTimer()
  runStartAt = Date.now()
  elapsedMs.value = 0
  // 1s 粒度足够（「13m 31s」这个量级），避免状态栏每帧重渲染
  elapsedTimer = window.setInterval(() => {
    if (runStartAt) elapsedMs.value = Date.now() - runStartAt
  }, 1000)
}

watch(
  () => props.isStreaming,
  streaming => {
    if (streaming) {
      startElapsedTimer()
    } else {
      stopElapsedTimer()
      elapsedMs.value = 0
    }
  },
  { immediate: true }
)

onBeforeUnmount(stopElapsedTimer)

/** 42s / 13m 31s / 1h 2m 8s —— 与旧项目 formatElapsed 完全一致 */
function formatElapsed(ms: number): string {
  const totalSec = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(totalSec / 3600)
  const minutes = Math.floor((totalSec % 3600) / 60)
  const secs = totalSec % 60
  if (hours > 0) return `${hours}h ${minutes}m ${secs}s`
  if (minutes > 0) return `${minutes}m ${secs}s`
  return `${secs}s`
}

const elapsedText = computed(() => formatElapsed(elapsedMs.value))

/**
 * 执行状态文案（启发式推断）。
 * v3 的 SSE 没有旧项目那样的 streamStatus 状态机，只能按最后一条 assistant 消息的
 * 形态判断：已出正文 → 输出中；无正文但已有工具卡 → 工具执行中；否则 → 思考中。
 */
const streamStatusText = computed(() => {
  if (!props.isStreaming) return ''
  const last = props.messages[props.messages.length - 1]
  if (!last || last.role !== 'assistant') return '正在提交…'
  if ((last.content || '').trim()) return '正在输出…'
  if ((last.tools ?? []).length > 0) return '工具执行中…'
  return '思考分析中…'
})

/**
 * 任务看板统计（完成数 / 总数 / 阻塞数）。
 *
 * 口径统一走 utils/task-list 的 {@link taskStats}：内嵌面板（ChatTaskPanel）、
 * 状态栏徽章与浮层头部都用它，避免三处各写一遍 filter 导致数字对不上。
 */
const taskStat = computed(() => taskStats(props.tasks))

/** 任务行悬停提示：把优先级 / 依赖 / 失败原因一次说清（行内空间只够放短标签） */
function taskTitle(item: TaskItem): string {
  const parts: string[] = [`${item.id} · ${item.title}`]
  if (item.priority) parts.push(`优先级：${item.priority}`)
  if (item.group) parts.push(`分组：${item.group}`)
  const waiting = blockedBy(item, props.tasks)
  if (waiting.length) parts.push(`等待前置任务：${waiting.join('、')}`)
  if (item.failed) parts.push('该任务执行失败')
  return parts.join('\n')
}
/** 任务浮层显隐（浮层向上弹出，不占消息区高度） */
const taskPopOpen = ref(false)

/* ---------- 子Agent面板（F05） ---------- */

/**
 * 子Agent浮层显隐。
 * 与任务浮层互斥：两者都从状态栏向上弹出、占据同一块区域，同时开会叠在一起。
 */
const subAgentPopOpen = ref(false)
/** 子Agent统计（运行中 / 已完成 / 失败），用于按钮徽章与浮层标题 */
const subAgentStat = computed(() => subAgentStats(props.subAgents))
/** 是否有委托调用在进行（心跳未过期） */
const delegateActive = computed(() => props.delegateCalls.length)
/** 子Agent按钮是否该显示：有任何一个子Agent或委托项才显示，避免空状态下塞一个死按钮 */
const showSubAgentEntry = computed(() => subAgentStat.value.total > 0 || delegateActive.value > 0)

/** 打开子Agent浮层（与任务浮层互斥） */
function toggleSubAgentPop() {
  subAgentPopOpen.value = !subAgentPopOpen.value
  if (subAgentPopOpen.value) taskPopOpen.value = false
}

/** 打开任务浮层（与子Agent浮层互斥） */
function toggleTaskPop() {
  taskPopOpen.value = !taskPopOpen.value
  if (taskPopOpen.value) subAgentPopOpen.value = false
}

/**
 * ESC 关闭状态栏上方的轻量浮层（子Agent / 任务）。
 *
 * 静态预览 preview.html 早就有一条完整的 ESC 链路（模型弹层 → 补充需求条 →
 * 任务浮层 → 抽屉），Vue 侧此前只做了各组件自己的（附件菜单、模型选择器等），
 * 这两个浮层没有出口，只能再点一次按钮关。这里补齐，语义与预览一致：
 * 轻量浮层优先，互斥关系保证两个里最多命中一个。
 */
function onPopoverKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  if (subAgentPopOpen.value) {
    subAgentPopOpen.value = false
    return
  }
  if (taskPopOpen.value) taskPopOpen.value = false
}
onMounted(() => document.addEventListener('keydown', onPopoverKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onPopoverKeydown))

/** 改动徽章文案「+N/-M」；没有改动记录时返回空串，按钮只显示「改动」两个字 */
const changedSummary = computed(() => {
  const stats = props.conversation.stats
  if (!stats || stats.changedFiles == null) return ''
  return `+${stats.added ?? 0}/-${stats.deleted ?? 0}`
})

/* ---------- 补充需求 ---------- */

const supplementOpen = ref(false)
const supplementText = ref('')
const supplementInputRef = ref<HTMLInputElement | null>(null)
/** 真实发送态由父级回传（父级才持有会话 id 与接口结果），组件只负责展示 */
const supplementSending = computed(() => props.supplementState === 'sending')

// 父级发送成功 → 清空并收起；失败（error）保持输入内容，方便直接重试
watch(
  () => props.supplementState,
  state => {
    if (state === 'done') {
      supplementText.value = ''
      supplementOpen.value = false
    }
  }
)

/**
 * 流式结束时收起输入条。
 * 「补充需求」只对正在跑的 ToolLoop 有意义，任务停下后再发会进入 SupplementStore 队列却无人消费。
 * 注意：这个 watch 必须放在 supplementOpen 声明之后（immediate 求值会在 setup 阶段直接读到它）。
 */
watch(
  () => props.isStreaming,
  streaming => {
    if (!streaming) supplementOpen.value = false
  }
)

async function openSupplement() {
  supplementOpen.value = true
  await nextTick()
  supplementInputRef.value?.focus()
}

function cancelSupplement() {
  supplementOpen.value = false
  supplementText.value = ''
}

/**
 * 发送补充需求。
 * 这里只做非空校验 + 上抛，不做乐观插入：补充消息由后端注入正在跑的 ToolLoop，
 * 会在下一轮迭代时落成正式消息，前端先插一条假的会和随后的历史消息重复。
 */
function sendSupplement() {
  const text = supplementText.value.trim()
  if (!text || supplementSending.value) return
  emit('supplement', text)
}

/* ============================ 文件路径预览 ============================ */

interface FilePreviewState {
  /** 实际读取的路径（相对路径会被解析成绝对路径后显示） */
  path: string
  loading: boolean
  error: string
  content: string
  imageUrl: string
}

const filePreview = ref<FilePreviewState | null>(null)
/** 请求序号：快速连点多个路径时丢弃过期响应 */
let previewSeq = 0
/** 图片预览的 blob URL，切换/卸载时需要释放 */
let previewObjectUrl = ''

function releasePreviewUrl() {
  if (previewObjectUrl) {
    URL.revokeObjectURL(previewObjectUrl)
    previewObjectUrl = ''
  }
}

function closeFilePreview() {
  previewSeq++
  releasePreviewUrl()
  filePreview.value = null
}

/**
 * 打开右侧抽屉预览。
 *
 * <p>`target` 允许传「点击时已经解析好的结果」：工作区标签与抽屉共用同一次
 * `/project/resolve`，避免一次点击发两次请求；错误提示也能带上原始路径。</p>
 */
async function openFilePreview(target: string | ResolvedFileTarget, kind: 'file' | 'image') {
  // 文件预览占用「文件」标签页：即使当前停在 Git/终端也要切过来
  drawerTab.value = 'files'
  drawerOpen.value = true

  releasePreviewUrl()
  const seq = ++previewSeq
  const rawPath = typeof target === 'string' ? target : target.raw
  filePreview.value = { path: rawPath, loading: true, error: '', content: '', imageUrl: '' }

  try {
    const resolved = typeof target === 'string'
      ? await resolveFileTarget(target, props.projectRoot || '')
      : target
    if (seq !== previewSeq) return
    filePreview.value.path = resolved.path
    if (!resolved.found) {
      throw new Error(describeMissingFile(rawPath, props.projectRoot || '', resolved.candidates))
    }

    if (kind === 'image') {
      const url = await getProjectFileBlobUrl(resolved.path)
      if (seq !== previewSeq) {
        URL.revokeObjectURL(url)
        return
      }
      previewObjectUrl = url
      filePreview.value.imageUrl = url
    } else {
      const res = await readProjectFile(resolved.path)
      if (seq !== previewSeq) return
      // 后端异常（如文件不存在）会返回 Spring 默认错误页（无 code/message 字段），
      // 这里必须显式校验，否则会静默显示空白。
      if (res?.code !== 200 || typeof res.data !== 'string') {
        throw new Error(
          `${describeMissingFile(rawPath, props.projectRoot || '', resolved.candidates)}\n` +
          `后端返回：${res?.message || '文件不存在或无法访问'}`
        )
      }
      filePreview.value.content = res.data
    }
  } catch (err: any) {
    if (seq !== previewSeq) return
    filePreview.value.error = err?.message || '文件加载失败'
  } finally {
    if (seq === previewSeq && filePreview.value) filePreview.value.loading = false
  }
}

/**
 * 消息区点击委托：只接管带 `data-cc-path` 的链接（由 utils/markdown 生成）。
 * 普通外链有 target=_blank，不在这里处理。
 *
 * <p>解析必须发生在**点击时、开标签之前**：工作区标签以 `payload.path` 做去重 key，
 * 直接塞相对路径会与文件树打开的同一文件形成两个标签，且标签内读取时后端会按
 * 进程 user.dir 拼路径（Electron 下是 jar 目录）→ 表现为「点了没反应 / 打不开」。
 * 旧版（frontend/CodeAssistantView.vue）能点开相对路径靠的就是「先拼绝对路径再读」。</p>
 */
async function onStreamClick(event: MouseEvent) {
  const target = event.target as Element | null
  const anchor = target?.closest('[data-cc-path]') as HTMLElement | null
  if (!anchor) return
  event.preventDefault()
  event.stopPropagation()
  const path = anchor.dataset.ccPath || ''
  if (!path) return
  const kind = (anchor.dataset.ccKind as 'file' | 'image') || 'file'

  const resolved = await resolveFileTarget(path, props.projectRoot || '')

  /*
   * 工作区模式：开成标签而不是抽屉里的只读预览。
   * 文件标签自带编辑器与保存，图片标签可点开大图 —— 比预览更进一步，
   * 也符合「点一次路径 = 打开这个文件」的直觉。
   * 未找到时也开标签：标签内会显示工作目录 / 候选 / 重试按钮，
   * 比「点了什么都不发生」更容易定位问题。
   */
  if (settings.workspaceMode) {
    workspace.openTab(kind === 'image' ? 'image' : 'file', { path: resolved.path })
    return
  }
  void openFilePreview(resolved, kind)
}

onBeforeUnmount(() => {
  releasePreviewUrl()
})

defineExpose({
  focusComposer: () => composerRef.value?.focus(),
  /** 读取输入框草稿（AI 优化提示词需要） */
  getDraft: () => draft.value,
  /** 回填输入框草稿（重新生成等场景；不记录优化撤销快照） */
  setDraft: (text: string) => {
    draft.value = text
  },
  /** 回填 AI 优化结果，并记录一份可撤销快照（草稿再被编辑即失效） */
  applyOptimizedDraft
})

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>
