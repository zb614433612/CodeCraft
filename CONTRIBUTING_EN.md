> 🌐 中文版：[🇨🇳 CONTRIBUTING](./CONTRIBUTING.md)
# 🎯 Contributing Guide

Thank you for your interest in CodeCraft! We welcome all forms of contributions, including but not limited to:

- Bug reports 🐛
- Feature suggestions 💡
- Documentation improvements 📖
- Code fixes or new features 🚀

## 📋 Contribution Process

### 1. Reporting Issues

Before submitting an issue, please search existing issues to see if your problem has already been covered.

**Bug reports** should include:
- Clear description of the issue
- Steps to reproduce (as detailed as possible)
- Expected behavior vs actual behavior
- Environment information (OS, Java version, Node.js version)
- Relevant logs or screenshots

**Feature suggestions** should include:
- What feature you want
- What problem it solves
- Possible implementation ideas (optional)

### 2. Submitting Pull Requests

1. **Fork this repository** to your GitHub account
2. **Create a feature branch**: `git checkout -b feature/your-feature-name`
3. **Develop and test**
   - Follow existing code style
   - Keep code concise and clear
   - Add necessary comments
4. **Commit code**
   - Use clear commit messages (English recommended, or bilingual)
   - Format: `type(scope): description`
   - Example: `feat(tool): add new search tool`
   - Example: `fix(api): correct pagination offset`
5. **Push to your repository**: `git push origin feature/your-feature-name`
6. **Create Pull Request** targeting the `main` branch
   - PR title should clearly describe the changes
   - PR description should reference related issues (e.g., `Closes #123`)

### 3. Code Review

- Maintainers will review your PR as soon as possible
- There may be suggestions for changes; please be patient
- PR will be merged once all CI checks pass

## 🧑‍💻 Development Environment Setup

Please refer to [BUILD_AND_RUN.md](BUILD_AND_RUN.md) for detailed instructions.

### Quick Development Tips

```bash
# Backend development (hot start)
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.profiles.active=local"

# Frontend development (hot reload)
cd frontend && npm run dev

# Run backend tests
mvn test

# Build complete project
mvn clean package -DskipTests
```

## 🎨 Code Standards

- **Java**: Follow [Alibaba Java Development Manual](https://github.com/alibaba/p3c), use Lombok to reduce boilerplate code
- **Frontend**: Use TypeScript, follow ESLint + Prettier configuration
- **Commit Messages**: Recommended to use [Conventional Commits](https://www.conventionalcommits.org/) specification

## 📝 Branch Naming Suggestions

| Branch Type | Naming Format | Example |
|------------|--------------|---------|
| New Feature | `feature/xxx` | `feature/token-auth` |
| Bug Fix | `fix/xxx` | `fix/null-pointer` |
| Documentation | `docs/xxx` | `docs/api-guide` |
| Refactoring | `refactor/xxx` | `refactor/tool-executor` |
| Performance | `perf/xxx` | `perf/file-read-cache` |

## 🔒 Security Vulnerabilities

If you discover a security vulnerability, please **do not** report it publicly via Issues. Please send an email directly to the project maintainer.

---

Thank you again for contributing to CodeCraft! 🎉
