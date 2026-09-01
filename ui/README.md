# 智慧园区工作流 UI

基于 Vue 3、TypeScript、Vite、Element Plus 和 Vue Flow 的单工作流控制台。

## 本地开发

先在项目根目录启动 Spring Boot 后端，再启动前端：

```bash
cd ui
npm install
npm run dev
```

本机开发访问 <http://localhost:5173>。使用 Docker 启动时，默认前端端口只绑定 `127.0.0.1:5173`；如需让局域网设备访问，必须从项目根目录显式设置绑定地址：

```powershell
$env:SMARTPARK_FRONTEND_BIND_HOST="0.0.0.0"
docker compose --env-file .env `
  -f compose.yaml `
  -f compose.analytics.yaml `
  -f compose.showcase.yaml `
  --profile analytics up --build -d
```

然后其他设备访问宿主机 IP 的 `5173` 端口，例如 <http://192.168.1.10:5173>。Vite 会把 `/api` 请求代理到 backend（容器内为 `http://backend:8080`）。该 HTTP 局域网模式不支持浏览器语音输入；语音演示请使用本机 `localhost` 或 HTTPS。

## 构建

```bash
npm run build
```

构建产物位于 `ui/dist`。UI 遵守后端现有脱敏策略，不直接展示完整诊断、审批人或工单业务内容。
