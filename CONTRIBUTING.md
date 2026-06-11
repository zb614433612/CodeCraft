> 🌐 English Version：[🇬🇧 CONTRIBUTING_EN](./CONTRIBUTING_EN.md)
# 🎯 贡献指南

感谢你对 CodeCraft 的关注！我们欢迎任何形式的贡献，包括但不限于：

- 报告 Bug 🐛
- 提交新功能建议 💡
- 改进文档 📖
- 提交代码修复或新功能 🚀

## 📋 贡献流程

### 1. 报告 Issue

在提交 Issue 前，请先搜索已有的 Issue 是否已覆盖你的问题。

**Bug 报告** 请包含：
- 问题的清晰描述
- 复现步骤（尽可能详细）
- 预期行为与实际行为
- 运行环境信息（操作系统、Java 版本、Node.js 版本）
- 相关日志或截图

**功能建议** 请包含：
- 你想要的功能是什么
- 解决了什么问题
- 可能的实现思路（可选）

### 2. 提交 Pull Request

1. **Fork 本仓库** 到你的 GitHub 账号
2. **创建功能分支**：`git checkout -b feature/your-feature-name`
3. **开发与测试**
   - 遵循现有的代码风格
   - 保持代码简洁清晰
   - 添加必要的注释
4. **提交代码**
   - 使用清晰的提交信息（建议英文或中英双语）
   - 格式：`type(scope): description`
   - 示例：`feat(tool): add new search tool`
   - 示例：`fix(api): correct pagination offset`
5. **推送到你的仓库**：`git push origin feature/your-feature-name`
6. **创建 Pull Request** 到本仓库的 `main` 分支
   - PR 标题清晰描述改动内容
   - PR 描述关联相关 Issue（如 `Closes #123`）

### 3. 代码审查

- 维护者会尽快审查你的 PR
- 可能会有修改建议，请保持耐心
- 所有 CI 检查通过后即可合并

## 🧑‍💻 开发环境搭建

请参考 [BUILD_AND_RUN.md](BUILD_AND_RUN.md) 中的详细说明。

### 快速开发建议

```bash
# 后端开发（热启动）
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.profiles.active=local"

# 前端开发（热更新）
cd frontend && npm run dev

# 运行后端测试
mvn test

# 构建完整项目
mvn clean package -DskipTests
```

## 🎨 代码规范

- **Java**：遵循 [阿里巴巴 Java 开发手册](https://github.com/alibaba/p3c)，使用 Lombok 简化样板代码
- **前端**：使用 TypeScript，遵循 ESLint + Prettier 配置
- **提交信息**：建议使用 [Conventional Commits](https://www.conventionalcommits.org/) 规范

## 📝 分支命名建议

| 分支类型 | 命名格式 | 示例 |
|---------|---------|------|
| 新功能 | `feature/xxx` | `feature/token-auth` |
| Bug 修复 | `fix/xxx` | `fix/null-pointer` |
| 文档更新 | `docs/xxx` | `docs/api-guide` |
| 重构 | `refactor/xxx` | `refactor/tool-executor` |
| 性能优化 | `perf/xxx` | `perf/file-read-cache` |

## 🔒 安全漏洞

如发现安全漏洞，请**不要**通过 Issue 公开报告，请直接发送邮件至项目维护者邮箱。

---

再次感谢你对 CodeCraft 的贡献！🎉
