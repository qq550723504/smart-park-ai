# CI Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a least-privilege GitHub Actions workflow that validates the Java backend and Vue/TypeScript frontend for changes targeting `main`.

**Architecture:** Use one workflow with two independent jobs: a Maven test job for the Java 17 backend and a Node.js build job for the `ui` frontend. Run the jobs on pushes and pull requests for `main`, expose a manual trigger, cache dependencies, and cancel superseded runs without adding deployment or model-service access.

**Tech Stack:** GitHub Actions, Ubuntu runners, Temurin Java 17, Maven Wrapper 3.9.11, Node.js 22, npm, Spring Boot, Vue, TypeScript, Vite.

**Spec:** `docs/superpowers/specs/2026-08-23-ci-workflow-design.md`

## Global Constraints

- push: main
- pull_request: target branch main
- workflow_dispatch: manual reruns
- Workflow permissions: contents: read
- Cancel superseded runs in the same workflow/ref concurrency group
- Use the repository Maven Wrapper with ./mvnw -B test
- Use ui/package-lock.json with npm ci
- Run npm run build for TypeScript checking and Vite production output
- Do not set or read DashScope/API keys; default tests remain offline
- Do not modify application code, dependency versions, deployment configuration, or branch protection
- Preserve unrelated uncommitted workspace changes

---

### Task 1: Add the CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `pom.xml`, `mvnw`, `ui/package-lock.json`, and `ui/package.json`.
- Produces: `backend` and `frontend` GitHub Actions checks for `main` pushes and pull requests.

- [ ] **Step 1: Create `.github/workflows/ci.yml`**

The file must contain:

    name: CI

    on:
      push:
        branches: [main]
      pull_request:
        branches: [main]
      workflow_dispatch:

    permissions:
      contents: read

    concurrency:
      group: ci-${{ github.workflow }}-${{ github.ref }}
      cancel-in-progress: true

    jobs:
      backend:
        name: Backend tests
        runs-on: ubuntu-latest
        steps:
          - name: Check out repository
            uses: actions/checkout@v4
          - name: Set up Java
            uses: actions/setup-java@v4
            with:
              distribution: temurin
              java-version: '17'
              cache: maven
          - name: Run backend tests
            run: ./mvnw -B test

      frontend:
        name: Frontend build
        runs-on: ubuntu-latest
        defaults:
          run:
            working-directory: ui
        steps:
          - name: Check out repository
            uses: actions/checkout@v4
          - name: Set up Node.js
            uses: actions/setup-node@v4
            with:
              node-version: '22'
              cache: npm
              cache-dependency-path: ui/package-lock.json
          - name: Install frontend dependencies
            run: npm ci
          - name: Build frontend
            run: npm run build

- [ ] **Step 2: Check the workflow diff and scope**

Run:

    git diff -- .github/workflows/ci.yml
    git status --short

Expected: only `.github/workflows/ci.yml` is newly added by this task; existing application modifications remain unstaged and unchanged.

### Task 2: Verify the CI commands locally

**Files:**
- Test: `pom.xml`, `mvnw`, `ui/package-lock.json`, `ui/package.json`

**Interfaces:**
- Consumes: the commands declared in `.github/workflows/ci.yml`.
- Produces: local evidence that backend tests and frontend production build complete without model credentials.

- [ ] **Step 1: Run backend tests**

Run from the repository root:

    .\mvnw.cmd -B test

Expected: Maven exits 0 and tests pass without requiring a DashScope/API key.

- [ ] **Step 2: Run the frontend build**

Run from the repository root:

    Push-Location ui
    npm ci
    npm run build
    Pop-Location

Expected: npm exits 0 and Vite writes the production bundle under `ui/dist`.

- [ ] **Step 3: Parse and inspect the workflow YAML**

Use an available YAML parser or GitHub Actions syntax checker to confirm `.github/workflows/ci.yml` parses and contains `push`, `pull_request`, `workflow_dispatch`, `permissions`, `concurrency`, `backend`, and `frontend`. Do not add a runtime dependency solely for this check.

Expected: the file parses successfully and contains no secret, deployment, or write permission.

### Task 3: Commit the focused workflow change

**Files:**
- Commit: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: the verified workflow from Tasks 1–2.
- Produces: one focused commit containing only the CI workflow.

- [ ] **Step 1: Confirm only the workflow is staged**

    git add -- .github/workflows/ci.yml
    git diff --cached --name-only

Expected: output contains only `.github/workflows/ci.yml`.

- [ ] **Step 2: Commit the workflow**

    git commit --only .github/workflows/ci.yml -m "ci: add backend and frontend checks"

Expected: a new focused commit is created; unrelated working-tree changes are not included.

- [ ] **Step 3: Verify final repository state**

    git show --stat --oneline HEAD
    git status --short

Expected: HEAD contains only the workflow commit, and pre-existing application modifications remain outside that commit.
