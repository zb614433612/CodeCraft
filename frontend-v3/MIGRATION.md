# v3 前端：迁移与部署说明

> 这份文档只讲「v3 是怎么接进这个仓库的、部署在哪、怎么发版」。
> 功能清单、迭代记录、设计细节见同目录 `README.md`（v3 开发日志，250KB+）。

---

## 1. 两套前端并存

同一个后端下同时部署两套独立 SPA，用户可随时切换：

| | 经典版（v2） | 新版（v3） |
|---|---|---|
| 访问路径 | `/` | `/v3/` |
| 源码目录 | `frontend/` | `frontend-v3/` |
| 构建产物 | `src/main/resources/static/`（根） | `src/main/resources/v3-static/` |
| 资源映射 | Spring Boot 默认 `classpath:/static/` | `SpaConfig` 的 ResourceHandler：`/v3/**` → `classpath:/v3-static/` |
| 路由模式 | history | hash（`#/chat`） |
| Vite base | `./` | `./` |
| 默认入口 | — | ✅ 未设置偏好时默认进新版 |

### 为什么 v3 产物不放在 `static/v3/`

最初就是放在 `static/v3/` 的，`mvn package` 一次就没了 —— 因为经典版（`frontend/vite.config`）
把 `outDir` 指向 `src/main/resources/static` 且开了 `emptyOutDir: true`，
**每次构建都会清空整个 static 目录**（pom 里挂着 frontend-maven-plugin，打包即触发）。

于是改为独立目录 `src/main/resources/v3-static/`，两套产物彻底解耦：
旧版随便清 static，v3 不受影响。

### 为什么 v3 必须挂在 `/v3/` 且带尾斜杠

v3 用 `base: './'`（产物里写 `./assets/index-xxx.js`）。浏览器停在 `/v3`（无尾斜杠）时，
相对路径会解析成 `/assets/index-xxx.js` —— 那是经典版的目录，结果是整页 404 白屏。

因此后端 `SpaConfig` 里是「两条规则 + 一条资源映射」，缺一不可：

```java
registry.addRedirectViewController("/v3", "/v3/");                        // 补尾斜杠
registry.addViewController("/v3/").setViewName("forward:/v3/index.html"); // hash 路由入口
registry.addResourceHandler("/v3/**").addResourceLocations("classpath:/v3-static/");
```

**不要**给 `/v3/**` 加 ViewController 回退来「支持」`/v3/anything`：那会把
`/v3/assets/*.js` 也 forward 成 index.html（返回 HTML 而非 JS），直接白屏。
v3 是 hash 路由，服务器只会收到 `/v3/`，`/v3/anything` 返回 404 是正确行为。

**不要**把 v3 的 `base` 改成绝对路径 `/v3/` 来「绕过」跳转 —— Electron 下以 `file://`
加载时绝对路径会全部失效。

---

## 2. 版本切换机制

唯一的粘合剂是 localStorage 里的 **`cc.uiVersion`**：

| 值 | 行为 |
|---|---|
| 未设置 | 首次访问 `/` 时自动写 `'v3'` 并跳到 `/v3/`（默认新版） |
| `'v3'` | 同上（记住上次选择） |
| `'v2'` | 留在经典版，右下角出现浮动按钮「体验新版」 |

两端实现（键名与取值必须一致，改一处必须同步改另一处）：

- 新版：`frontend-v3/src/utils/ui-version.ts`（账号菜单「切换到经典版」）
- 经典版：`frontend/src/index.html` 与 `src/main/resources/static/index.html` 里注入的内联脚本

> ⚠️ 经典版的注入脚本写在**源码** `frontend/index.html` 里，所以重新构建旧版会自动带上；
> 同时已手工注入进当前产物 `static/index.html`，保证不重新构建也能立即生效。

### 逃生通道

万一 v3 产物损坏（白屏）导致回不去，访问 **`/index.html?v2=1`** 可强制留在经典版：

```
http://localhost:8084/index.html?v2=1
```

`?v2=1` 优先级高于 localStorage，只有当次请求有效，不会写入偏好。

---

## 3. localStorage 键的划分

这是两版共存最容易踩的地方：**共享的必须共享，隔离的必须隔离**。

| 键 | 归属 | 说明 |
|---|---|---|
| `settings` | **共享** | pinia settings store。主题、背景图、背景透明度/模糊、窗口透明度、上下文模式都在这里 → 两版改一处另一处立刻生效（用户明确要求） |
| `cc.uiVersion` | **共享** | 版本偏好（见上） |
| `codecraft.token` | 新版独占 | v3 自己的 token |
| `v3.user` | 新版独占 | v3 的 pinia user store（`userInfo` / `rememberedUsername`） |
| `v3.workspace` | 新版独占 | v3 的多标签工作区状态（v2 无此功能） |
| `user` | 经典版独占 | v2 的 pinia user store，**含 token 字段** |
| `codecraft.sidebar-collapsed` | 新版独占 | 侧栏折叠 |
| `codecraft.drawerWidth.*` | 新版独占 | 各抽屉宽度 |
| `codecraft.auto-login` | 新版（预留） | 自动登录凭据覆盖，当前登录页不提供入口 |

### 登录态为什么不会掉

v2 把 token 存在 `user` 键里，v3 存在 `codecraft.token`，两者本来不通用。为了让「来回切不掉登录」：

**v3 的 `getStoredToken()` 在自身没有 token 时，只读回退读 `user` 键里的 `token`**
（`src/utils/http-client.ts` 的 `readLegacyToken()`）。单向、只读 —— v3 从不写 `user` 键，
所以经典版的登录态不会被污染。

结果：经典版 → 新版免登录；新版 → 经典版本来就是同一份 `user` 键，自然也免登录。

若那份 token 已过期，v3 首次请求会 401 并跳到 v3 的登录页，正常重新登录即可。

### 主题/背景图共享的实测结论

- v3 的 settings store `persist` **没有**加 `key`，键名仍是 `settings`，与 v2 同一个键；
- 两版字段名完全一致（`theme` / `bgImageUrl` / `bgOpacity` / `bgBlur` / `windowOpacity` 等），
  v3 多出 `workspaceMode`（v2 读到会忽略，不影响）；
- 实测：塞一份 `{theme:'dark', projectRoot:'E:/legacy-only-field'}` 进 `settings`，
  v3 启动后 `data-theme="dark"` 生效；v3 改主题写回后，`projectRoot` 这类 v2 独有字段仍在，
  且**没有**生成 `v3.settings` 这个独立键。

---

## 4. 本地开发

```bash
cd frontend-v3
npm install
npm run dev          # http://localhost:5173/ ，代理 /api -> 127.0.0.1:8084
```

后端端口不是默认 8080，是 **8084**（见 `application.yml` 的 `server.port`）。
可用环境变量覆盖代理目标：

```bash
set CC_BACKEND=http://192.168.1.9:8084 && npm run dev
```

dev server 下访问 `http://localhost:5173/` 就是 v3 本体，**不会**经过版本切换脚本
（那段脚本只存在于经典版的 `index.html`）。

其它命令：

```bash
npm run typecheck    # vue-tsc --noEmit -p tsconfig.app.json（必须带 -p，否则空跑）
npm run build        # 产物落到 ../src/main/resources/v3-static/
npm run check:api    # 前端 api 调用与后端 Controller 的契约比对
```

---

## 5. 构建与发版

```bat
scripts\pack-v3.bat              :: 装依赖 + typecheck + build
scripts\pack-v3.bat --skip-check :: 只构建
```

产物直接写进 `src/main/resources/v3-static/`（`vite.config.ts` 的 `outDir` 已指向那里，
没有额外的复制步骤）。`emptyOutDir: true` 是显式声明的 —— outDir 在项目根之外，
Vite 默认不会清理外部目录，不清会导致带 hash 的旧文件越积越多。

发版注意：

1. `src/main/resources/static/` 与 `src/main/resources/v3-static/` 都在 `.gitignore` 里
   （构建产物不入库），所以**构建是发版流程的一部分**；
2. 打包后端前必须跑过 `pack-v3.bat`，否则 jar 里的 `/v3/` 是旧版本；
3. 后端运行中替换静态资源不生效（Spring Boot 从 jar 内的 classpath 读），
   需要重启后端或执行 `scripts\relink-backend.bat` 重建并替换 jar。

### 打包时怎么跳过旧版前端构建

`pom.xml` 的 frontend-maven-plugin 之前**没有** `<skip>` 配置，注释里承诺的
`-DskipFrontend=true` 一直是空操作（每次 package 都会重建经典版前端并清空 static）。
本次已补上：

```xml
<properties>
  <skipFrontend>false</skipFrontend>
</properties>
...
<plugin>
  <artifactId>frontend-maven-plugin</artifactId>
  <configuration>
    <skip>${skipFrontend}</skip>
  </configuration>
```

现在 `mvn -DskipFrontend=true package` 才真能跳过（实测 `static/index.html` 时间戳不再变化）。

---

## 6. Electron 桌面壳适配

桌面壳（`electron/main.js`）用 `titleBarStyle: 'hidden'`，原生窗口按钮浮在右上角。
v3 为此补了三处（v2 原本就有，迁移时一度丢失）：

| 位置 | 作用 |
|---|---|
| `frontend-v3/index.html` 的早期内联脚本 | `<head>` 里就判定 `window.electronAPI`，给 `<html>` 挂 `cc-electron` 类（避免首帧闪动） |
| `frontend-v3/src/App.vue` | 渲染 `.cc-titlebar`（32px、`-webkit-app-region: drag`），放在登录页/主界面分支**之外** |
| `frontend-v3/src/layouts/AppShell.vue` | 兜底再挂一次 `cc-electron` 类 |
| `frontend-v3/assets/app.css` §3 | `html.cc-electron` 下给 `.cc-shell` / `.cc-login` 让出 32px 顶部，抽屉与遮罩同步 `top: 32px` |

**拖拽条为什么放在 App.vue 而不是 AppShell**：登录页（`/login`）是独立全屏布局，不套
AppShell。放在 AppShell 里会导致「用户在登录页拖不动窗口」—— 这是实测发现的真实缺陷。

**抽屉为什么也要让位**：抽屉是 `position: fixed; top: 0`，头部关闭按钮正好落在原生窗口按钮
那片区域，不让位就点不到。

浏览器环境下 `window.electronAPI` 不存在，以上规则全部匹配不上，零副作用（已实测无 `.cc-titlebar`、无 `cc-electron` 类）。

---

## 7. 与桌面开发目录的关系

本次迁移的源目录是 `C:\Users\61443\Desktop\codecraft`。迁移时**刻意排除**：

| 排除项 | 原因 |
|---|---|
| `node_modules/` | 平台相关且体积大；`npm install` 可重建 |
| `dist/` | 构建产物，`npm run build` 可重建 |
| `preview.html`（588KB） | 纯静态设计预览页，靠浏览器直接打开看样式，不参与构建、不参与运行 |

其余全部迁入（`src/`、`assets/`、`index.html`、`vite.config.ts`、`tsconfig*.json`、
`package.json`、规划/日志文档、`scripts/check-api-contract.cjs`）。

> ⚠️ 迁移后 **`frontend-v3/` 是唯一真相源**。桌面目录若继续改动会产生分叉，
> 建议只作备份或直接删除。

---

## 8. 本次迁移的后端改动

`src/main/java/com/example/agentdeepseek/config/SpaConfig.java`（纯新增，不影响经典版）：

```diff
+ private static final String V3_RESOURCE_LOCATION = "classpath:/v3-static/";
+
+ @Override
+ public void addResourceHandlers(ResourceHandlerRegistry registry) {
+     registry.addResourceHandler("/v3/**").addResourceLocations(V3_RESOURCE_LOCATION);
+ }
+
  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
+     registry.addRedirectViewController("/v3", "/v3/");
+     registry.addViewController("/v3/").setViewName("forward:/v3/index.html");
      registry.addViewController("/{path:[a-zA-Z0-9\\-]+}")
              .setViewName("forward:/index.html");
  }
```

外加 `pom.xml` 补 `<skipFrontend>` 属性与 `<skip>` 配置（修 bug，不改行为）。

---

## 9. 验证记录

| 组 | 项数 | 结果 |
|---|---|---|
| 路由与静态资源（HTTP 层） | 18 | ✅ 全通过 |
| 版本切换链路（真实浏览器 / CDP） | 12 | ✅ 全通过 |
| Electron 适配 + 主题共享（CDP） | 13 | ✅ 全通过 |
| `npm run typecheck` | — | ✅ 0 error |
| `npm run build` | 372 modules | ✅ 通过 |
| `mvn -DskipFrontend=true package` | — | ✅ 通过（且确认不再清空 static） |

关键断言摘要：

- `/v3` → 302 → `/v3/`；`/v3/` → 200 且返回 v3 入口（含 `./assets/` 相对路径、含 `cc-electron`）；
- `/v3/assets/index-*.js` → 200，content-type `text/javascript`；
- `/` → 200 且含 `cc.uiVersion` 注入脚本与「体验新版」按钮逻辑；
- `/code-assistant` → 200（经典版 SPA 回退未被破坏）；
- `/api/user/current` → 401（API 没被 SPA 回退吃掉）；
- 首次访问 `/` 自动跳 `/v3/#/login?redirect=/chat`，并写入 `cc.uiVersion=v3`；
- 偏好 `v2` 时访问 `/` 不跳转、经典版正常挂载、右下角出现「体验新版」；
- 点「体验新版」→ 回 `/v3/`，偏好切回 `v3`；
- `/index.html?v2=1` 强制留在经典版；
- v3 读到共享的 `settings.theme=dark` → `data-theme="dark"`；v3 写回后 v2 独有字段仍在，无 `v3.settings` 键；
- 注入 `window.electronAPI` 后：`.cc-titlebar` 渲染 1 个、`position: fixed`、高 32px、`app-region: drag`、`.cc-login` padding-top 32px。

---

## 10. 已知边界

- **hash 路由**：`/v3/#/chat` 刷新正常（服务器只看到 `/v3/`），但 URL 带 `#` 不适合分享链接/SEO；
- **`/v3/anything` 返回 404**：hash 路由不占 path，这是正确行为（原因见 §1）；
- **两套 store 不互通**：v3 除共享的 `settings` 外，其余本地状态都是 `v3.*` 前缀，互不影响；
- **`?v2=1` 只在当次 URL 生效**：不写 localStorage，刷新后仍按偏好走；
- **登录页独立**：v3 有 `/v3/#/login`，v2 是隐形登录 + 首页跳转；两版 token 在同一个后端 `TokenStore` 里都有效；
- **桌面壳未改**：`electron/main.js` 仍 `loadURL(http://localhost:8084)`，靠服务端 302 + 前端脚本完成版本分流，安装包无需重打。
