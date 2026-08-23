# CI 工作流设计

## 目标

为 `spring-ai-alibaba` 建立基础持续集成检查，确保后端测试和前端生产构建在合并到 `main` 前可重复执行，并避免 CI 获得不必要的部署或模型服务权限。

## 触发与权限

- `push`：`main`
- `pull_request`：目标分支为 `main`
- `workflow_dispatch`：允许手动重跑
- 工作流权限：`contents: read`
- 使用并发组按工作流和分支/PR 编组，新提交到同一变更时取消旧运行

## 作业

### 后端

- 使用 Ubuntu runner、Temurin Java 17 和 Maven 缓存
- 使用仓库自带 Maven Wrapper 执行 `./mvnw -B test`
- 不设置或读取 DashScope/API 密钥；默认测试必须保持离线可运行

### 前端

- 使用 Ubuntu runner 和 Node.js 22
- 使用 `ui/package-lock.json` 执行 `npm ci`
- 执行 `npm run build`，覆盖 TypeScript 类型检查和 Vite 生产构建

## 文件边界

新增 `.github/workflows/ci.yml`。本次不修改应用代码、依赖版本、部署配置或分支保护设置。

## 验证

- 本地执行 `./mvnw -B test`
- 本地在 `ui` 目录执行 `npm ci` 和 `npm run build`
- 使用 YAML 解析/静态检查确认工作流语法、触发器、权限和命令与设计一致

## 不在范围内

- 镜像构建、发布、部署和生产环境冒烟测试
- DashScope 等外部模型服务的集成测试
- 强制状态检查名称或 GitHub 分支保护规则的变更
