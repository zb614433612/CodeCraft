# CodeCraft 前端模块

CodeCraft 的桌面端前端界面，基于 **Vue 3 + TypeScript + Vite 6** 构建。

## 技术栈

| 技术 | 用途 |
|------|------|
| Vue 3 + Composition API | 前端框架 |
| TypeScript | 类型安全 |
| Pinia | 状态管理 |
| Vue Router | 路由管理 |
| Vite 6 | 构建工具 |
| KaTeX | 数学公式渲染 |
| SSE (Server-Sent Events) | 流式响应 |

## 目录结构

```
frontend/
├── src/
│   ├── api/          # 后端 API 调用封装
│   ├── assets/       # 静态资源（图标、图片）
│   ├── components/   # 通用组件
│   ├── layouts/      # 布局组件
│   ├── router/       # 路由配置
│   ├── store/        # Pinia 状态管理
│   ├── utils/        # 工具函数
│   └── views/        # 页面视图
├── public/           # 公共静态资源
├── package.json      # 依赖配置
└── vite.config.ts    # Vite 构建配置
```

## 开发

```bash
# 安装依赖
npm install

# 启动开发服务器（端口 5173，代理 API 到 8084）
npm run dev

# 构建生产版本
npm run build
```

## 页面功能

- **CodeAssistantView** — 与 AI Agent 对话的主界面
- **AgentConfigView** — 自定义 AI Agent 配置管理（提示词、工具集、模型、执行模式）
- **SkillManageView** — 技能管理（创建/编辑/删除技能、绑定 Agent）
- **ConfigView** — 系统配置管理
- **LoginView** — 用户登录
- **UserManageView** — 用户管理
- **MenuPermissionView** — 菜单权限管理
- **ScheduleTaskView** — 定时任务管理
- **LogView** — 系统日志查看
- **ProfileView** — 个人资料
- **P2pPanel** — P2P 远程协作（设备配对、Agent授权、消息收发）

> 详细的项目运行说明请参见根目录的 [BUILD_AND_RUN.md](../BUILD_AND_RUN.md)
