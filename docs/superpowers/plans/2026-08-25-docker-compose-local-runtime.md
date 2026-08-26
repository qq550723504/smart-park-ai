# Smart Park Docker Compose Local Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reproducible Docker Compose runtime that starts the Mock/offline demo by default and enables the real PR11 analytics chain through an explicit analytics profile.

**Architecture:** Build the Spring Boot backend into a Java 17 runtime image, run the Vue UI in a Node 22 Vite container, and provide a dedicated PostgreSQL 16 analytics container. `compose.yaml` is credential-free by default; `compose.analytics.yaml` is an override carrying the `analytics` profile and requires the DashScope key and database passwords before starting the real analytics mode.

**Tech Stack:** Docker Compose, Maven/Spring Boot 4, Eclipse Temurin 17, Node 22, Vite, PostgreSQL 16 Alpine.

**Spec:** `docs/superpowers/specs/2026-08-25-docker-compose-local-runtime-design.md`

## Global Constraints

- The default command must start a usable Mock/offline demo without an API key.
- Real analytics must use a dedicated PostgreSQL database and the fixed `smartpark_analytics_ro` runtime role.
- Credentials must stay in the local `.env` file and never enter tracked files, Dockerfiles, image layers, or command arguments.
- Existing local application behavior and the protected `main` branch must remain unchanged.
- The UI proxy must resolve the backend by Compose service name inside the frontend container and remain `localhost`-based for local non-Compose development.

### Task 1: Add reproducible backend and frontend images

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`
- Create: `ui/Dockerfile`
- Create: `ui/.dockerignore`

**Interfaces:**
- Produces a backend image exposing port `8080` and running `java -jar /app/app.jar`.
- Produces a frontend image exposing port `5173` and running Vite on `0.0.0.0`.

- [ ] **Step 1: Add the backend multi-stage image**

Use Maven with Java 17 for the build stage, run `mvn -B -DskipTests package`, and copy the generated executable JAR into an Eclipse Temurin 17 JRE image. Install only `curl` in the runtime image so Compose can perform an HTTP health check.

- [ ] **Step 2: Add the frontend development image**

Use Node 22 Alpine, copy `package*.json` before `npm ci` for layer reuse, then copy the UI source and run `npm run dev -- --host 0.0.0.0`.

- [ ] **Step 3: Exclude host/build state from both build contexts**

Exclude `.git`, `.worktrees`, `.env*`, `target`, `ui/node_modules`, and `ui/dist` from the root context; exclude `.git`, `.env*`, `node_modules`, and `dist` from the UI context.

- [ ] **Step 4: Build both images independently**

Run:

```powershell
docker build -t smart-park-backend:test .
docker build -t smart-park-ui:test ui
```

Expected: both commands exit `0`; no `.env` or host `node_modules` content is copied into the build context.

- [ ] **Step 5: Commit the image inputs**

```powershell
git add Dockerfile .dockerignore ui/Dockerfile ui/.dockerignore
git commit -m "build: add local backend and frontend images"
```

### Task 2: Add default and analytics Compose configurations

**Files:**
- Create: `compose.yaml`
- Create: `compose.analytics.yaml`
- Create: `.env.example`
- Modify: `.gitignore`

**Interfaces:**
- Default command: `docker compose --env-file .env.example up --build`.
- Full analytics command: `docker compose --env-file .env -f compose.yaml -f compose.analytics.yaml --profile analytics up --build`.
- Backend service name: `backend`; frontend service name: `frontend`; database service name: `analytics-postgres`.

- [ ] **Step 1: Add safe environment defaults and local secret exclusions**

Add `.env` and `.env.*` exclusions while allowing `.env.example`. The example leaves model and analytics passwords empty, sets both feature flags false, and contains no credential value that can authenticate to an external system.

- [ ] **Step 2: Define the default Compose services**

Define `analytics-postgres`, `backend`, and `frontend`. PostgreSQL uses `POSTGRES_HOST_AUTH_METHOD=trust` only in the credential-free default demo, with a named volume and a health check. Backend uses Mock mode, points its inactive analytics URL at `analytics-postgres`, depends on the database health check, and exposes `8080`. Frontend depends on backend health, exposes `5173`, and receives `VITE_API_PROXY_TARGET=http://backend:8080`.

- [ ] **Step 3: Define the explicit analytics override**

In `compose.analytics.yaml`, attach the backend to profile `analytics`, set `SMARTPARK_ANALYTICS_ENABLED=true` and `SPRING_AI_DASHSCOPE_ENABLED=true`, require `AI_DASHSCOPE_API_KEY`, `SMARTPARK_ANALYTICS_DB_ADMIN_PASSWORD`, and `SMARTPARK_ANALYTICS_DB_RO_PASSWORD` using Compose `${VAR:?message}` interpolation, replace PostgreSQL trust authentication with the supplied admin password, and force the runtime user to `smartpark_analytics_ro`.

- [ ] **Step 4: Validate merged Compose configuration without starting services**

Run:

```powershell
docker compose --env-file .env.example config --quiet
Copy-Item .env.example .env -Force
docker compose --env-file .env -f compose.yaml -f compose.analytics.yaml --profile analytics config
```

Expected: the default config succeeds; the analytics config fails clearly while the copied `.env` has empty required values; no secret is printed from tracked files.

- [ ] **Step 5: Commit the Compose configuration**

```powershell
git add compose.yaml compose.analytics.yaml .env.example .gitignore
git commit -m "build: add compose demo and analytics profile"
```

### Task 3: Make the UI proxy container-aware and document operation

**Files:**
- Modify: `ui/vite.config.ts:5-13`
- Modify: `README.md` in the quick-start and configuration sections

**Interfaces:**
- `VITE_API_PROXY_TARGET` defaults to `http://localhost:8080` outside Compose and is `http://backend:8080` inside Compose.
- README documents both default Mock startup and explicit analytics startup, including `.env` creation and cleanup.

- [ ] **Step 1: Make the Vite proxy configurable**

Read `process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080'` and use that value for the `/api` proxy without changing the existing local development default.

- [ ] **Step 2: Document the two Compose commands and health endpoints**

Document default startup, frontend URL, capabilities endpoint, `docker compose ps`, logs, shutdown, named-volume behavior, and the analytics command with required variables. State explicitly that the analytics database is dedicated and the runtime role is read-only.

- [ ] **Step 3: Run the UI typecheck/build**

Run:

```powershell
Set-Location ui
npm ci
npm run build
Set-Location ..
```

Expected: exit `0` and the local proxy fallback remains unchanged.

- [ ] **Step 4: Commit the proxy and documentation**

```powershell
git add ui/vite.config.ts README.md
git commit -m "docs: document compose runtime and proxy target"
```

### Task 4: Run the Compose smoke path and verify handoff

**Files:**
- Modify: none unless verification exposes a configuration defect.

**Interfaces:**
- Default smoke path proves backend and frontend reachability without DashScope.
- Analytics config validation proves required secrets are fail-closed before startup.

- [ ] **Step 1: Start the default stack**

Run:

```powershell
docker compose --env-file .env.example up -d --build
docker compose --env-file .env.example ps
```

Expected: `analytics-postgres`, `backend`, and `frontend` are running and healthy.

- [ ] **Step 2: Verify HTTP behavior**

Run:

```powershell
$ui = Invoke-WebRequest -UseBasicParsing http://localhost:5173/
$capabilities = Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/operations/capabilities
if ($ui.StatusCode -ne 200 -or $capabilities.StatusCode -ne 200) { throw 'Compose smoke check failed' }
$capabilities.Content
```

Expected: both responses are `200`; capabilities report `analyticsEnabled=false` and no real model is called.

- [ ] **Step 3: Verify fail-closed analytics configuration**

Run the analytics `config` command with empty required values and assert a non-zero exit with an error naming the missing variable. Do not put a real key into command history or tool output.

- [ ] **Step 4: Stop the default stack without deleting its named volume**

```powershell
docker compose --env-file .env.example down
```

Expected: containers stop; the named PostgreSQL volume remains unless the user explicitly requests `down -v`.

- [ ] **Step 5: Run final repository checks**

Run `git diff --check`, `mvnw.cmd -B test`, and `npm.cmd run build` from the isolated worktree. Record Docker warnings separately from failures.

- [ ] **Step 6: Commit any verification-only correction and report the result**

If a configuration defect is found, fix only the scoped files, rerun the failing check, and commit it with a focused message. Otherwise leave the implementation commits unchanged and report exact commands and outcomes.
