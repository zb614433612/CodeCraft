# Vue3 登录功能实现文档

## 概述

实现了基于 Vue3 + TypeScript + Pinia 的登录功能，包含双重MD5加密、API接口调用、用户信息存储等完整流程。

## 功能特性

### 1. 用户Store管理
- 使用 Pinia 管理用户状态
- 存储用户信息、登录状态、token、随机码
- 支持数据持久化到 localStorage
- 提供完整的类型定义

### 2. API服务层
- 封装统一的请求函数
- 提供 `getRandomCode` 和 `login` 接口
- 统一的错误处理和响应类型
- 支持跨域代理配置

### 3. 双重MD5加密
- 原生JavaScript实现的MD5加密函数
- 双重加密逻辑：先加密明文密码，再拼接随机码二次加密
- 符合后端安全要求

### 4. 登录页面
- 炫酷的太阳系动画背景
- AI机器人视觉交互
- 响应式表单验证
- 完整的登录流程处理

### 5. 路由配置
- 登录页面：`/login` (隐藏布局)
- 首页：`/home` (显示用户信息)
- 自动重定向和跳转逻辑

## 文件结构

```
src/
├── api/
│   ├── user.ts          # 用户相关API接口
│   └── index.ts         # API导出
├── store/
│   ├── index.ts         # 原有计数store
│   └── user.ts          # 用户store (新增)
├── utils/
│   └── md5.ts           # MD5加密函数 (新增)
├── views/
│   ├── LoginView.vue    # 登录页面 (已更新)
│   └── HomeView.vue     # 首页 (新增)
├── router/
│   └── index.ts         # 路由配置 (已更新)
└── App.vue              # 主应用组件 (已更新)
```

## 登录流程

### 1. 用户输入
- 用户在登录页面输入用户名和密码
- 表单进行基础验证

### 2. 获取随机码
- 调用 `GET /api/user/random-code` 接口
- 请求体：`{ "username": "admin" }`
- 响应：`{ "code": 200, "message": "success", "data": { "randomCode": "abc123" } }`
- 保存随机码到 store

### 3. 双重MD5加密
- 第一次MD5：`md5(明文密码)`
- 拼接：`第一次MD5结果 + 随机码`
- 第二次MD5：`md5(拼接后的字符串)`

### 4. 调用登录接口
- 调用 `POST /api/user/login` 接口
- 请求体：`{ "username": "admin", "password": "二次加密后的密码" }`
- 响应：`{ "code": 200, "message": "登录成功", "data": { ...用户信息 } }`

### 5. 保存用户信息
- 将响应数据保存到 Pinia store
- 更新登录状态
- 跳转到首页

## 接口规范

### 通用响应格式
```typescript
interface ApiResponse<T = any> {
  code: number;      // 状态码
  message: string;   // 消息
  data: T;           // 响应数据
  timestamp: number; // 时间戳
}
```

### 随机码接口
- **URL**: `POST /api/user/random-code`
- **请求体**:
  ```json
  {
    "username": "string"
  }
  ```
- **响应体**:
  ```json
  {
    "code": 200,
    "message": "success",
    "data": {
      "randomCode": "string"
    },
    "timestamp": 1678886400000
  }
  ```

### 登录接口
- **URL**: `POST /api/user/login`
- **请求体**:
  ```json
  {
    "username": "string",
    "password": "string"  // 二次加密后的密码
  }
  ```
- **响应体**:
  ```json
  {
    "code": 200,
    "message": "登录成功",
    "data": {
      "id": 1,
      "username": "admin",
      "nickname": "管理员",
      "avatar": "url",
      "email": "admin@example.com",
      "phone": "13800138000",
      "roles": ["admin"],
      "permissions": ["*:*:*"]
    },
    "timestamp": 1678886400000
  }
  ```

## 跨域配置

Vite 代理配置已添加，前端请求 `/api` 路径会自动代理到 `http://localhost:8084`：

```typescript
// vite.config.ts
server: {
  proxy: {
    '/api': {
      target: 'http://localhost:8084',
      changeOrigin: true,
      rewrite: (path) => path.replace(/^\/api/, '/api')
    }
  }
}
```

## 使用方法

### 1. 启动开发服务器
```bash
npm run dev
```
访问: http://localhost:5185

### 2. 构建生产版本
```bash
npm run build
```

### 3. 预览构建结果
```bash
npm run preview
```

## 注意事项

1. **后端API**: 确保后端服务在 `http://localhost:8084` 运行
2. **跨域**: 开发环境下使用 Vite 代理，生产环境需要配置反向代理
3. **密码加密**: MD5加密仅为示例，实际生产环境应使用更安全的加密方式
4. **错误处理**: 所有API调用都有 try-catch 错误处理
5. **类型安全**: 使用 TypeScript 确保类型安全

## 扩展功能建议

1. **Token管理**: 添加JWT token存储和自动刷新
2. **权限控制**: 基于角色的路由权限控制
3. **记住密码**: 实现"记住我"功能
4. **验证码**: 添加图形验证码
5. **多语言**: 支持多语言切换
6. **主题切换**: 支持明暗主题

## 测试数据

### 成功登录流程
1. 用户名: `admin`
2. 密码: `123456` (示例)
3. 随机码: 从接口获取 (如: `7f3a2b1c`)
4. 第一次MD5: `md5("123456") = "e10adc3949ba59abbe56e057f20f883e"`
5. 拼接: `"e10adc3949ba59abbe56e057f20f883e" + "7f3a2b1c"`
6. 第二次MD5: `md5("e10adc3949ba59abbe56e057f20f883e7f3a2b1c")`

### 错误处理
- 用户名或密码错误
- 网络连接失败
- 服务器异常
- 接口响应格式错误