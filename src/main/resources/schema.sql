-- 创建会话表
CREATE TABLE IF NOT EXISTS conversation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(255) NOT NULL COMMENT '会话名称',
  user_id BIGINT COMMENT '用户ID，关联sys_user.id',
  agent_type VARCHAR(50) DEFAULT 'code_assistant' COMMENT '会话类型：code_assistant',
  agent_config_id BIGINT COMMENT '所属Agent配置ID，关联agent_config.id',
  work_dir VARCHAR(500) COMMENT '会话级工作目录',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  updated_at DATETIME NOT NULL COMMENT '更新时间',
  INDEX idx_user_id (user_id),
  INDEX idx_agent_type (agent_type),
  INDEX idx_agent_config_id (agent_config_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='会话表';

-- 创建会话消息表
CREATE TABLE IF NOT EXISTS conversation_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT NOT NULL COMMENT '会话ID',
  role VARCHAR(20) NOT NULL COMMENT '消息角色: system, user, assistant, tool',
  content LONGTEXT COMMENT '消息内容',
  reasoning LONGTEXT COMMENT '思考过程（仅assistant角色）',
  tool_calls LONGTEXT COMMENT '工具调用数据块（JSON格式）',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  INDEX idx_conversation_id (conversation_id),
  INDEX idx_created_at (created_at),
  FOREIGN KEY (conversation_id) REFERENCES conversation(id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4 COMMENT='会话消息表';

-- 创建上下文压缩记录表
CREATE TABLE IF NOT EXISTS conversation_compaction (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT NOT NULL COMMENT '所属会话ID',
  summary LONGTEXT NOT NULL COMMENT '压缩后的结构化摘要内容（Markdown格式）',
  start_message_id BIGINT NOT NULL COMMENT '被压缩的起始消息ID',
  end_message_id BIGINT NOT NULL COMMENT '被压缩的结束消息ID',
  token_savings INT NOT NULL DEFAULT 0 COMMENT '压缩节省的token数',
  superseded TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已被后续压缩覆盖（逻辑删除）',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  INDEX idx_compaction_cid (conversation_id),
  INDEX idx_start_message (start_message_id),
  INDEX idx_end_message (end_message_id),
  INDEX idx_superseded (superseded),
  FOREIGN KEY (conversation_id) REFERENCES conversation(id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4 COMMENT='上下文压缩记录表';

-- 创建用户表（使用sys_user避免关键字冲突）
CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
  password VARCHAR(32) NOT NULL COMMENT '密码（MD5加密，32位）',
  nickname VARCHAR(50) COMMENT '用户昵称',
  role VARCHAR(20) DEFAULT 'user' COMMENT '角色：admin=管理员, user=普通用户',
  email VARCHAR(100) COMMENT '邮箱',
  phone VARCHAR(20) COMMENT '手机号',
  avatar VARCHAR(255) COMMENT '头像URL',
  status TINYINT DEFAULT 1 COMMENT '状态：1=启用, 0=禁用',
  create_time DATETIME NOT NULL COMMENT '创建时间',
  update_time DATETIME NOT NULL COMMENT '更新时间',
  INDEX idx_username (username)
) DEFAULT CHARSET=utf8mb4 COMMENT='用户表';



-- ============================================================
-- Skill 技能系统
-- ============================================================
CREATE TABLE IF NOT EXISTS skill (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL COMMENT '技能名称',
  description VARCHAR(500) NOT NULL COMMENT '技能描述',
  tool_names TEXT NOT NULL COMMENT '工具名称列表，JSON数组格式',
  instructions TEXT COMMENT '技能指令/提示词',
  trigger_words TEXT COMMENT '触发词/同义词，JSON数组。如 ["查天气","气温","天气","温度","预报"]',
  confidence DOUBLE DEFAULT 0.5 COMMENT '置信度 0.0-1.0',
  usage_count INT DEFAULT 0 COMMENT '使用次数',
  success_count INT DEFAULT 0 COMMENT '成功次数',
  fail_count INT DEFAULT 0 COMMENT '失败次数',
  user_id BIGINT NOT NULL COMMENT '所属用户ID',
  agent_type VARCHAR(50) NOT NULL COMMENT '所属助手类型 code_assistant',
  agent_config_id BIGINT COMMENT '所属Agent配置ID（null=全局技能）',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- H2 兼容的索引创建（MySQL 的 INDEX 内联定义在 H2 的 MODE=MySQL 下部分不支持）
CREATE INDEX IF NOT EXISTS idx_skill_user_agent ON skill(user_id, agent_type);
CREATE INDEX IF NOT EXISTS idx_skill_agent_config_id ON skill(agent_config_id);
CREATE INDEX IF NOT EXISTS idx_skill_confidence ON skill(confidence);


-- ============================================================
-- 多Agent协作：子Agent执行记录表
-- ============================================================
CREATE TABLE IF NOT EXISTS sub_agent_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  agent_id VARCHAR(100) NOT NULL COMMENT '子Agent唯一标识，如 sub-dal-1（由主Agent指定）',
  parent_conversation_id BIGINT NOT NULL COMMENT '父会话ID，关联 conversation.id',
  parent_turn_id VARCHAR(50) COMMENT '创建该子Agent的那条消息的 turnId，用于消息级联删除',
  name VARCHAR(200) COMMENT '子Agent名称',
  status VARCHAR(20) NOT NULL DEFAULT 'running' COMMENT '状态：running / completed / failed / timeout',
  instructions TEXT COMMENT '子Agent的完整任务描述（由主Agent传入）',
  context_mode VARCHAR(20) DEFAULT 'inherit_summary' COMMENT '上下文继承模式：inherit_summary / inherit_full / none',
  tools TEXT COMMENT '子Agent可用的工具列表，JSON数组格式',
  skills TEXT COMMENT '子Agent可用的技能列表，JSON数组（名称或ID）',
  summary TEXT COMMENT '结构化执行摘要（返回给主Agent的文本）',
  full_messages LONGTEXT COMMENT '子Agent的完整消息历史（JSON格式，含system/user/assistant/tool全部消息）',
  file_changes TEXT COMMENT '文件变更记录，JSON格式：{"created":[],"modified":[],"deleted":[]}',
  compile_result VARCHAR(50) COMMENT '编译结果：passed / failed / not_run',
  iterations_used INT DEFAULT 0 COMMENT '实际使用的迭代次数',
  max_iterations INT DEFAULT 30 COMMENT '最大允许迭代次数',
  error_message TEXT COMMENT '错误信息（状态为failed/timeout时记录）',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  completed_at DATETIME COMMENT '完成时间',
  updated_at DATETIME NOT NULL COMMENT '更新时间',
  INDEX idx_parent_conversation_id (parent_conversation_id),
  INDEX idx_parent_turn_id (parent_turn_id),
  INDEX idx_agent_id (agent_id),
  INDEX idx_status (status),
  FOREIGN KEY (parent_conversation_id) REFERENCES conversation(id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4 COMMENT='子Agent执行记录表';


-- ============================================================
-- 角色菜单权限管理模块
-- ============================================================

-- 1. 菜单表（menu_type: LINK=左侧菜单栏, MANAGE=管理页面/底部菜单）
CREATE TABLE IF NOT EXISTS sys_menu (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(50) NOT NULL COMMENT '菜单名称',
  path VARCHAR(200) COMMENT '前端路由路径',
  icon VARCHAR(50) COMMENT '图标名称（如 RobotOutlined）',
  parent_id BIGINT DEFAULT NULL COMMENT '父菜单ID',
  sort_order INT DEFAULT 0 COMMENT '排序号',
  permission_code VARCHAR(100) COMMENT '权限标识',
  menu_type VARCHAR(20) DEFAULT 'LINK' COMMENT '类型：LINK=左侧菜单栏, MANAGE=管理页面',
  visible TINYINT DEFAULT 1 COMMENT '是否可见',
  status TINYINT DEFAULT 1 COMMENT '状态：1=启用, 0=禁用',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_parent_id (parent_id),
  INDEX idx_sort (sort_order)
) DEFAULT CHARSET=utf8mb4 COMMENT='菜单表';

-- 2. 角色表
CREATE TABLE IF NOT EXISTS sys_role (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(50) NOT NULL COMMENT '角色名称',
  code VARCHAR(50) NOT NULL UNIQUE COMMENT '角色编码',
  description VARCHAR(200) COMMENT '角色描述',
  status TINYINT DEFAULT 1 COMMENT '状态：1=启用, 0=禁用',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- 3. 角色-菜单关联表
CREATE TABLE IF NOT EXISTS sys_role_menu (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_id BIGINT NOT NULL COMMENT '角色ID',
  menu_id BIGINT NOT NULL COMMENT '菜单ID',
  UNIQUE KEY uk_role_menu (role_id, menu_id),
  FOREIGN KEY (role_id) REFERENCES sys_role(id) ON DELETE CASCADE,
  FOREIGN KEY (menu_id) REFERENCES sys_menu(id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4 COMMENT='角色菜单关联表';

-- 初始化数据
-- 角色
INSERT IGNORE INTO sys_role (id, name, code, description) VALUES (1, '管理员', 'admin', '系统管理员，拥有所有权限');
INSERT IGNORE INTO sys_role (id, name, code, description) VALUES (2, '普通用户', 'user', '普通用户，仅基础功能');

-- 菜单项（LINK=左侧菜单栏, MANAGE=管理页面/底部菜单入口, SETTING=左侧底部设置菜单）
INSERT IGNORE INTO sys_menu (id, name, path, icon, parent_id, sort_order, menu_type) VALUES
(4, 'AI 助手', '/code-assistant', 'CodeOutlined', NULL, 4, 'LINK'),
(5, '用户管理', '/user-management', 'UserOutlined', NULL, 5, 'MANAGE'),
(6, '菜单权限管理', '/menu-permission', 'SafetyOutlined', NULL, 6, 'MANAGE'),
(7, '个人信息', '/profile', 'FormOutlined', NULL, 7, 'MANAGE'),
(8, '配置', '/config', 'SettingOutlined', NULL, 2, 'SETTING');

-- 管理员分配所有菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r, sys_menu m WHERE r.code = 'admin';

-- 普通用户分配左侧菜单 + 个人信息
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r, sys_menu m
WHERE r.code = 'user' AND m.name IN ('编码助手','个人信息');

-- ============================================================
-- 系统配置表（用户动态配置，如 DeepSeek API Key）
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  config_key VARCHAR(100) NOT NULL UNIQUE COMMENT '配置键',
  config_value TEXT COMMENT '配置值',
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_config_key (config_key)
) DEFAULT CHARSET=utf8mb4 COMMENT='系统配置表';

INSERT IGNORE INTO sys_config (config_key, config_value) VALUES ('deepseek_api_key', '');

-- ============================================================
-- 定时任务表
-- ============================================================
CREATE TABLE IF NOT EXISTS schedule_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL COMMENT '任务名称',
  agent_type VARCHAR(50) NOT NULL COMMENT 'Agent类型',
  agent_config_id BIGINT COMMENT 'Agent配置ID，关联agent_config.id',
  instruction TEXT NOT NULL COMMENT '任务指令',
  cron_expression VARCHAR(100) COMMENT 'cron表达式（周期任务）',
  execute_time DATETIME COMMENT '指定执行时间（一次性任务）',
  status VARCHAR(20) DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
  last_execute_time DATETIME COMMENT '上次执行时间',
  last_conversation_id BIGINT COMMENT '上次执行的会话ID',
  execute_count INT DEFAULT 0 COMMENT '已执行次数',
  max_execute_count INT DEFAULT 0 COMMENT '最大执行次数（0=不限）',
  user_id BIGINT NOT NULL COMMENT '创建者用户ID',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_task_status (status),
  INDEX idx_task_agent_type (agent_type),
  INDEX idx_task_user_id (user_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='定时任务表';

-- 新增 SETTING 菜单：定时任务
INSERT IGNORE INTO sys_menu (id, name, path, icon, parent_id, sort_order, menu_type) VALUES
(9, '定时任务', '/schedule-tasks', 'ClockCircleOutlined', NULL, 6, 'SETTING');

-- 新增 SETTING 菜单：运行日志
INSERT IGNORE INTO sys_menu (id, name, path, icon, parent_id, sort_order, menu_type) VALUES
(10, '运行日志', '/logs', 'FileTextOutlined', NULL, 7, 'SETTING');

-- 管理员分配新菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r, sys_menu m WHERE r.code = 'admin' AND m.id IN (9, 10);

-- ============================================================
-- 自定义 Agent 配置表
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL COMMENT 'Agent 名称',
  description VARCHAR(500) COMMENT 'Agent 描述',
  avatar VARCHAR(20) DEFAULT '🤖' COMMENT '头像 emoji',
  system_prompt TEXT COMMENT '系统提示词（为空则使用默认 code_agent_prompt.txt）',
  tool_names TEXT COMMENT '可用工具列表，JSON数组，null=全部工具',
  model_name VARCHAR(100) DEFAULT 'deepseek-v4-flash' COMMENT '模型名称',
  thinking_mode VARCHAR(20) DEFAULT 'non-thinking' COMMENT '思考模式：non-thinking/thinking/thinking_max',
  execution_mode VARCHAR(10) DEFAULT 'manual' COMMENT '执行模式：auto/manual',
  temperature DOUBLE DEFAULT 0.3 COMMENT '采样温度，0-2之间，控制输出随机性',
  work_dir VARCHAR(500) COMMENT '工作目录（绝对路径），为空使用项目根目录',
  sort_order INT DEFAULT 0 COMMENT '排序号',
  enabled TINYINT DEFAULT 1 COMMENT '是否启用',
  is_default TINYINT DEFAULT 0 COMMENT '是否为默认Agent',
  is_builtin TINYINT DEFAULT 0 COMMENT '是否为内置Agent（不允许修改删除）',
  provider_id BIGINT DEFAULT NULL COMMENT 'LLM Provider ID',
  provider_code VARCHAR(30) DEFAULT NULL COMMENT 'LLM Provider Code',
  character_profile TEXT DEFAULT NULL COMMENT '角色设定',
  user_id BIGINT COMMENT '创建者用户ID（null=系统级）',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_user_id (user_id),
  INDEX idx_enabled (enabled),
  INDEX idx_sort (sort_order)
) DEFAULT CHARSET=utf8mb4 COMMENT='Agent配置表';

-- ============================================================
-- LLM Provider 配置表（多模型平台适配）
-- ============================================================
-- ★ Provider 由用户在 LLM管理 页面手动创建，不再初始化默认数据
CREATE TABLE IF NOT EXISTS llm_provider (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(30) NOT NULL UNIQUE COMMENT 'Provider编码：deepseek/openai/anthropic/ollama/custom',
  name VARCHAR(100) NOT NULL COMMENT '显示名称，如 DeepSeek / OpenAI / Claude',
  base_url VARCHAR(300) NOT NULL COMMENT 'API Base URL，如 https://api.deepseek.com',
  api_key VARCHAR(200) COMMENT 'API Key（可加密存储，为空时从 sys_config 兜底读取）',
  default_model VARCHAR(100) COMMENT '该 Provider 的默认模型名',
  model_list TEXT COMMENT '可用模型列表 JSON 数组，如 ["deepseek-v4-pro","deepseek-v4-flash"]',
  request_template VARCHAR(30) DEFAULT 'deepseek' COMMENT '请求模板类型：deepseek / openai / anthropic / ollama / custom',
  is_default TINYINT DEFAULT 0 COMMENT '是否为默认 Provider',
  enabled TINYINT DEFAULT 1 COMMENT '是否启用',
  sort_order INT DEFAULT 0 COMMENT '排序号',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_provider_code (code),
  INDEX idx_provider_enabled (enabled)
) DEFAULT CHARSET=utf8mb4 COMMENT='LLM Provider 配置表';

-- agent_config 表新增 provider_id 字段（用于绑定 Agent → LLM Provider）
-- H2 不支持 IF NOT EXISTS 的 ALTER TABLE ADD COLUMN，使用 MySQL/MariaDB 语法
-- ALTER TABLE agent_config ADD COLUMN IF NOT EXISTS provider_id BIGINT DEFAULT 1 COMMENT 'LLM Provider ID，关联 llm_provider.id' AFTER is_builtin;

-- 初始化默认编码助手 Agent（内置，不可修改删除）
INSERT IGNORE INTO agent_config (id, name, description, avatar, system_prompt, tool_names, model_name, thinking_mode, execution_mode, temperature, work_dir, sort_order, enabled, is_default, is_builtin, provider_id, provider_code, character_profile, created_at, updated_at)
VALUES (1, 'AI 助手', '默认的AI编程助手，拥有全部工具', '🤖', NULL, NULL, 'deepseek-v4-flash', 'non-thinking', 'manual', 0.3, NULL, 1, 1, 1, 1, 1, NULL, NULL, NOW(), NOW());

-- 新增 SETTING 菜单：智能体
INSERT IGNORE INTO sys_menu (id, name, path, icon, parent_id, sort_order, menu_type) VALUES
(11, '智能体', '/agent-config', 'RobotOutlined', NULL, 5, 'SETTING');

-- ============================================================
-- MCP 外部服务器配置表（MCP Client 方向）
-- ============================================================
-- ★ 用于配置 CodeCraft 作为 MCP Client 连接的外部 MCP Server（http / stdio 两种传输）
CREATE TABLE IF NOT EXISTS mcp_server (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL COMMENT '服务器名称（展示用），如 GitHub',
  type VARCHAR(20) DEFAULT 'http' COMMENT '传输类型：http=Streamable HTTP, stdio=本地进程',
  url VARCHAR(500) COMMENT 'http 类型：MCP 端点 URL，如 http://localhost:3001/mcp',
  command VARCHAR(500) COMMENT 'stdio 类型：启动命令，如 npx -y @modelcontextprotocol/server-github',
  headers TEXT COMMENT '自定义请求头 JSON，如 {"Authorization":"Bearer xxx"}',
  tool_prefix VARCHAR(50) DEFAULT '' COMMENT '工具名前缀（防冲突），如 github_，空则用服务器名小写',
  permission_level VARCHAR(20) DEFAULT 'SAFE' COMMENT '权限档位：SAFE=只读 / DATA=可写数据 / HIGH_RISK=高危（写文件、执行命令等）',
  enabled TINYINT DEFAULT 0 COMMENT '是否启用：1=启用, 0=停用',
  auto_register TINYINT DEFAULT 1 COMMENT '启用时是否自动注册其工具到 ToolRegistry：1=是, 0=否',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  updated_at DATETIME NOT NULL COMMENT '更新时间',
  INDEX idx_mcp_server_enabled (enabled)
) DEFAULT CHARSET=utf8mb4 COMMENT='MCP 外部服务器配置表';

-- 管理员分配智能体菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r, sys_menu m WHERE r.code = 'admin' AND m.id = 11;

-- 新增 SETTING 菜单：技能
INSERT IGNORE INTO sys_menu (id, name, path, icon, parent_id, sort_order, menu_type) VALUES
(12, '技能', '/skill-manage', 'ToolOutlined', NULL, 4, 'SETTING');

-- 管理员分配技能菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r, sys_menu m WHERE r.code = 'admin' AND m.id = 12;

-- 新增 SETTING 菜单：协作
INSERT IGNORE INTO sys_menu (id, name, path, icon, parent_id, sort_order, menu_type) VALUES
(13, '协作', '/p2p', 'LinkOutlined', NULL, 3, 'SETTING');

-- 管理员分配协作菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r, sys_menu m WHERE r.code = 'admin' AND m.id = 13;

-- 新增 SETTING 菜单：数据管理（重置所有数据）
INSERT IGNORE INTO sys_menu (id, name, path, icon, parent_id, sort_order, menu_type) VALUES
(14, '数据管理', '/system-setting', 'DeleteOutlined', NULL, 8, 'SETTING');

-- 管理员分配数据管理菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r, sys_menu m WHERE r.code = 'admin' AND m.id = 14;

-- 新增 SETTING 菜单：MCP 服务器（外部 MCP Server 配置与连接管理）
INSERT IGNORE INTO sys_menu (id, name, path, icon, parent_id, sort_order, menu_type) VALUES
(15, 'MCP 服务器', '/mcp-config', 'ApiOutlined', NULL, 9, 'SETTING');

-- 管理员分配 MCP 服务器菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r, sys_menu m WHERE r.code = 'admin' AND m.id = 15;

-- 新增 SETTING 菜单：踩坑经验（成长体系管理页面）
INSERT IGNORE INTO sys_menu (id, name, path, icon, parent_id, sort_order, menu_type) VALUES
(16, '踩坑经验', '/lesson-manage', 'BookOutlined', NULL, 10, 'SETTING');

-- 管理员分配踩坑经验菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r, sys_menu m WHERE r.code = 'admin' AND m.id = 16;

-- ============================================================
-- Agent 后台任务表（用于追踪流式任务状态、支持页面刷新后重连）
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'running',
  iteration INT DEFAULT 0,
  max_iterations INT DEFAULT 50,
  error_message TEXT,
  event_count INT DEFAULT 0,
  pending_question_uuid VARCHAR(36),
  pending_question_text TEXT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_agent_task_conv_id ON agent_task(conversation_id);
CREATE INDEX IF NOT EXISTS idx_agent_task_status ON agent_task(status);

-- ============================================================
-- P2P 聊天记录表
-- ============================================================
CREATE TABLE IF NOT EXISTS p2p_chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    peer_id VARCHAR(64) NOT NULL,
    sender_name VARCHAR(100) DEFAULT '',
    content TEXT NOT NULL,
    direction VARCHAR(10) NOT NULL,
    message_type VARCHAR(20) DEFAULT 'chat',
    agent_config_id BIGINT DEFAULT NULL,
    agent_name VARCHAR(100) DEFAULT NULL,
    file_name VARCHAR(255) DEFAULT NULL,
    file_size BIGINT DEFAULT NULL,
    mime_type VARCHAR(100) DEFAULT NULL,
    file_category VARCHAR(20) DEFAULT NULL,
    transfer_id VARCHAR(36) DEFAULT NULL,
    file_status VARCHAR(20) DEFAULT NULL,
    local_path VARCHAR(500) DEFAULT NULL,
    created_at DATETIME NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_p2p_msg_peer ON p2p_chat_message(peer_id);
CREATE INDEX IF NOT EXISTS idx_p2p_msg_time ON p2p_chat_message(created_at);

-- ============================================================
-- P2P Agent 授权记录表
-- ============================================================
CREATE TABLE IF NOT EXISTS p2p_agent_authorization (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    peer_id VARCHAR(64) NOT NULL COMMENT '对方机器特征码',
    agent_config_id BIGINT NOT NULL COMMENT '被授权的Agent配置ID',
    agent_name VARCHAR(100) DEFAULT '' COMMENT 'Agent名称（冗余，P2P两端独立）',
    agent_description VARCHAR(500) DEFAULT '' COMMENT 'Agent描述（冗余）',
    agent_avatar VARCHAR(20) DEFAULT '🤖' COMMENT 'Agent头像（冗余）',
    user_id BIGINT DEFAULT NULL COMMENT '授权用户ID',
    direction VARCHAR(10) NOT NULL COMMENT '方向：sent=我授权给对方, received=对方授权给我',
    status VARCHAR(10) NOT NULL DEFAULT 'active' COMMENT 'active / cancelled',
    created_at DATETIME NOT NULL,
    cancelled_at DATETIME,
    INDEX idx_paa_peer_dir (peer_id, direction),
    INDEX idx_paa_peer_agent (peer_id, agent_config_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_paa_peer_agent_dir ON p2p_agent_authorization(peer_id, agent_config_id, direction);

-- ============================================================
-- P2P Agent 会话映射表（peerId+agentId → conversationId）
-- ============================================================
CREATE TABLE IF NOT EXISTS p2p_agent_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    peer_id VARCHAR(64) NOT NULL COMMENT '调用方机器特征码',
    agent_config_id BIGINT NOT NULL COMMENT '使用的Agent配置ID',
    conversation_id BIGINT NOT NULL COMMENT '本机conversation表的会话ID',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_pac_peer_agent (peer_id, agent_config_id),
    INDEX idx_pac_conv (conversation_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pac_peer_agent ON p2p_agent_conversation(peer_id, agent_config_id);

-- ============================================================
-- 成长体系：踩坑经验表（lesson）
-- 记录 LLM 执行过程中的失败与解法，支持按需检索（不注入上下文）
-- ============================================================
CREATE TABLE IF NOT EXISTS lesson (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_key     VARCHAR(64)  NOT NULL COMMENT '项目标识（隔离第一维度，取项目根目录名）',
  tool_name       VARCHAR(64)  NOT NULL COMMENT '工具名：command/file_writer/mcp_server_manager...',
  error_category  VARCHAR(32)  NOT NULL COMMENT '错误类别：COMPILE/DEPENDENCY/NETWORK/AUTH/MCP_HANDSHAKE/SQL/PARAM/ENV/OTHER',
  error_code      VARCHAR(128) NOT NULL COMMENT '归一化错误码：NoClassDefFoundError/ECONNREFUSED/401...',
  error_signature VARCHAR(64)  NOT NULL COMMENT '指纹：md5(projectKey|tool|category|code|paramsHash)，同坑去重',
  symptom         VARCHAR(512) NOT NULL COMMENT '失败现象',
  root_cause      VARCHAR(1024) COMMENT '根因',
  solution        VARCHAR(2048) NOT NULL COMMENT '解法（支持{变量占位符}）',
  params_json     TEXT COMMENT '变量参数JSON：[{"name":"缺失依赖","value":"starter-web"}]',
  applicable_cond VARCHAR(512) COMMENT '适用条件（自然语言约束）',
  keywords        VARCHAR(512) COMMENT '标签，空格分隔，兜底检索用',
  status          TINYINT DEFAULT 0 COMMENT '状态：0=草稿 1=有效 2=隐藏',
  source          VARCHAR(16) DEFAULT 'auto' COMMENT '来源：auto=自动捕获 / llm=LLM主动记录 / manual=人工沉淀',
  hit_count       INT DEFAULT 0 COMMENT '被检索命中次数',
  success_count   INT DEFAULT 0 COMMENT '应用后有效次数',
  fail_count      INT DEFAULT 0 COMMENT '应用后仍失败次数',
  type            VARCHAR(16) DEFAULT 'FAILURE' COMMENT '经验类型：FAILURE=失败经验 / DETOUR=弯路经验（P1）',
  goal            VARCHAR(256) COMMENT 'DETOUR 专用：任务目标（检索维度，P1）',
  env_params      VARCHAR(512) COMMENT '环境参数 JSON：{"os":"windows"}（P2：记录时自动附加，检索异环境降权）',
  created_at      DATETIME NOT NULL COMMENT '创建时间',
  updated_at      DATETIME NOT NULL COMMENT '更新时间'
) DEFAULT CHARSET=utf8mb4 COMMENT='踩坑经验表';

-- P1 弯路通道：已有库兼容（H2 2.x ADD COLUMN IF NOT EXISTS）
ALTER TABLE lesson ADD COLUMN IF NOT EXISTS type VARCHAR(16) DEFAULT 'FAILURE' COMMENT '经验类型：FAILURE=失败经验 / DETOUR=弯路经验';
ALTER TABLE lesson ADD COLUMN IF NOT EXISTS goal VARCHAR(256) COMMENT 'DETOUR 专用：任务目标（检索维度）';
CREATE INDEX IF NOT EXISTS idx_lesson_type ON lesson(project_key, type);
-- P2 环境参数检索：已有库兼容
ALTER TABLE lesson ADD COLUMN IF NOT EXISTS env_params VARCHAR(512) COMMENT '环境参数 JSON：{"os":"windows"}';

-- ============================================================
-- P0 归一化管线：LLM 语义归一化缓存表
-- key = md5(toolName|errorText前512)，不含参数（类别/错误码与参数解耦）
-- 命中缓存直接回写草稿，避免重复调 LLM；TTL 由 Service 清理（默认 7 天）
-- ============================================================
CREATE TABLE IF NOT EXISTS lesson_norm_cache (
  cache_key       VARCHAR(64)  PRIMARY KEY COMMENT '缓存键：md5(toolName|errorText前512)',
  error_category  VARCHAR(32)  NOT NULL COMMENT '语义归一化错误类别',
  error_code      VARCHAR(128) NOT NULL COMMENT '语义归一化稳定短码',
  tool_name       VARCHAR(64)  NOT NULL COMMENT '工具名',
  root_cause      VARCHAR(1024) COMMENT '根因（LLM 提炼，可空）',
  solution        VARCHAR(2048) COMMENT '解法（可空：归一化不强求解法，主要靠 C2 复盘）',
  created_at      DATETIME NOT NULL COMMENT '创建时间',
  updated_at      DATETIME NOT NULL COMMENT '最后命中时间'
) DEFAULT CHARSET=utf8mb4 COMMENT='归一化缓存表';

-- ============================================================
-- P0 归一化管线：规则自学习表（P0 简化版：只沉淀不淘汰）
-- LLM 归一化成功时沉淀 (报错文本片段 → 类别+短码)；提取时规则表优先命中
-- 同一 text_pattern 的 LLM 结果一致 ≥2 次 → status 转正（P2 完整闭环）
-- ============================================================
CREATE TABLE IF NOT EXISTS lesson_rule (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  text_pattern    VARCHAR(512) NOT NULL COMMENT '报错文本包含片段（转小写归一化）',
  error_category  VARCHAR(32)  NOT NULL COMMENT '错误类别',
  error_code      VARCHAR(128) NOT NULL COMMENT '稳定短码',
  hit_count       INT DEFAULT 0 COMMENT '沉淀次数（同 pattern 再次被 LLM 产出时 +1）',
  match_hit_count INT DEFAULT 0 COMMENT '提取命中次数（归一化时实际匹配到该规则时 +1，P2 使用度追踪）',
  source          VARCHAR(16) DEFAULT 'llm' COMMENT '来源：llm=LLM沉淀 / manual=人工',
  status          TINYINT DEFAULT 0 COMMENT '状态：0=候选 1=转正',
  created_at      DATETIME NOT NULL,
  updated_at      DATETIME NOT NULL
) DEFAULT CHARSET=utf8mb4 COMMENT='归一化规则自学习表';
-- P2 使用度追踪：已有库兼容（match_hit_count 列）
ALTER TABLE lesson_rule ADD COLUMN IF NOT EXISTS match_hit_count INT DEFAULT 0 COMMENT '提取命中次数（归一化时实际匹配到该规则时 +1）';
-- L3 修复：text_pattern 唯一约束（防并发重复插入；learnRule 捕获 DuplicateKey 降级计数转正）
CREATE UNIQUE INDEX IF NOT EXISTS uk_rule_pattern ON lesson_rule(text_pattern);
-- L6 修复：删除冗余非唯一索引（uk_rule_pattern 唯一索引已覆盖 text_pattern 查询用途；已有库兼容清理）
DROP INDEX IF EXISTS idx_rule_pattern;

-- H2 兼容的索引创建（照 skill 表惯例：内联 INDEX 在 H2 MODE=MySQL 下部分不支持）
CREATE UNIQUE INDEX IF NOT EXISTS uk_lesson_signature ON lesson(error_signature);
CREATE INDEX IF NOT EXISTS idx_lesson_scope ON lesson(project_key, tool_name, error_category, error_code);
CREATE INDEX IF NOT EXISTS idx_lesson_status ON lesson(status);
CREATE INDEX IF NOT EXISTS idx_lesson_hit ON lesson(hit_count);

-- ============================================================
-- Phase 20：外部数据库连接配置表
-- ============================================================
-- ★ execute_sql 工具通过 connection 参数指定外部连接执行 SQL（用户提供 IP/账号密码的业务库），
--   不传 connection 时仍连 CodeCraft 自身系统库（向后兼容）。
CREATE TABLE IF NOT EXISTS db_connection (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL COMMENT '连接名称（execute_sql connection 参数用），如 订单库',
  db_type VARCHAR(20) NOT NULL DEFAULT 'mysql' COMMENT '数据库类型：mysql / postgresql / h2',
  host VARCHAR(200) COMMENT '主机地址（h2 file 模式可空，路径放 database_name）',
  port INT COMMENT '端口（mysql 默认 3306 / postgresql 默认 5432）',
  database_name VARCHAR(200) COMMENT '数据库名（h2 file 模式为 .mv.db 文件路径）',
  username VARCHAR(100) COMMENT '用户名',
  password_encrypted TEXT COMMENT '密码（AES-GCM 加密存储，API 不回显明文）',
  extra_params VARCHAR(500) COMMENT 'JDBC URL 附加参数，如 useSSL=false&serverTimezone=Asia/Shanghai',
  enabled TINYINT DEFAULT 1 COMMENT '是否启用：1=启用 0=停用',
  user_id BIGINT COMMENT '归属用户（null=系统级共享，所有用户可见）',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  updated_at DATETIME NOT NULL COMMENT '更新时间',
  INDEX idx_db_connection_user (user_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='外部数据库连接配置表（Phase 20）';

-- 新增 SETTING 菜单：数据库连接（外部业务库连接管理）
INSERT IGNORE INTO sys_menu (id, name, path, icon, parent_id, sort_order, menu_type) VALUES
(17, '数据库连接', '/db-connections', 'DatabaseOutlined', NULL, 11, 'SETTING');

-- 管理员分配数据库连接菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r, sys_menu m WHERE r.code = 'admin' AND m.id = 17;
