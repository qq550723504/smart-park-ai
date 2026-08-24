# Smart Park RAG Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the current RAG worktree compile, enforce a configurable minimum retrieval similarity, and restore the intended adapter package boundary without changing the root `main` worktree.

**Architecture:** Keep `KnowledgePort` and `KnowledgeAdminPort` as application boundaries. `RagKnowledgeAdapter` remains the only adapter that knows Spring AI `VectorStore`; its configuration moves beside it under `adapter.rag`. The customer workflow continues to convert retrieval and model failures into safe human handoff.

**Tech Stack:** Java 17, Spring Boot 3.5.8, Spring AI 1.1.2, Spring AI Alibaba 1.1.2.2, JUnit 5, AssertJ, Maven Wrapper.

**Spec:** `docs/superpowers/specs/2026-08-24-smart-park-rag-knowledge-design.md`

## Global Constraints

- Keep `mock` as the default mode and keep tests offline.
- Do not expose knowledge正文、Prompt、模型原文或 API Key in public DTOs, audit records, or retrieval traces.
- Retrieval failures must enter safe human handoff instead of returning unverified model text.
- Use Spring AI `VectorStore`; do not maintain a second handwritten vector index.
- Do not modify or clean the unrelated dirty root `main` worktree.

### Task 1: Restore compilation and RAG configuration ownership

**Files:**
- Modify: `src/main/java/com/example/smartpark/web/WebDtos.java`
- Create: `src/main/java/com/example/smartpark/adapter/rag/RagKnowledgeConfiguration.java`
- Delete: `src/main/java/com/example/smartpark/adapter/mock/RagKnowledgeConfiguration.java`
- Test: existing `src/test/java/com/example/smartpark/integration/RagModeContextTest.java`

**Interfaces:**
- Consumes: `CustomerAnswer.Reason`, `EmbeddingModel`, `SimpleVectorStore`, and `RagKnowledgeAdapter`.
- Produces: a compiling RAG worktree and a configuration class owned by `adapter.rag`.

- [ ] **Step 1: Add the missing `CustomerAnswer` import and move the configuration class.**
- [ ] **Step 2: Run the focused RAG context test.**

Run: `cmd.exe /d /c ".\\mvnw.cmd -B -Dtest=RagModeContextTest test"`

Expected: PASS; the RAG context starts with the fake embedding model and the RAG adapter is the `KnowledgePort` bean.

- [ ] **Step 3: Commit the compilation and ownership fix.**

```powershell
git add src/main/java/com/example/smartpark/web/WebDtos.java src/main/java/com/example/smartpark/adapter/rag/RagKnowledgeConfiguration.java
git add -u src/main/java/com/example/smartpark/adapter/mock/RagKnowledgeConfiguration.java
git commit -m "fix: restore rag configuration ownership"
```

### Task 2: Enforce a configurable minimum similarity score

**Files:**
- Modify: `src/main/java/com/example/smartpark/adapter/rag/RagKnowledgeAdapter.java`
- Modify: `src/main/java/com/example/smartpark/adapter/rag/RagKnowledgeConfiguration.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/example/smartpark/adapter/rag/RagKnowledgeAdapterTest.java`
- Modify: `src/test/java/com/example/smartpark/integration/RagModeContextTest.java`

**Interfaces:**
- Consumes: Spring AI `SearchRequest.Builder.similarityThreshold(double)`.
- Produces: `RagKnowledgeAdapter(VectorStore, Collection<KnowledgeDocument>, double)` and default `0.65` threshold configuration.

- [ ] **Step 1: Write a failing test for a low-similarity document.**

The test will build a `SimpleVectorStore` with a deterministic embedding model that gives the query/document pair a score below `0.65`, then assert that `rankedSearch` returns no match. It will also assert that a score above the threshold remains searchable.

- [ ] **Step 2: Run the focused adapter test and confirm it fails because the current search accepts all vector-store results.**

Run: `cmd.exe /d /c ".\\mvnw.cmd -B -Dtest=RagKnowledgeAdapterTest test"`

Expected: FAIL at the new low-similarity assertion.

- [ ] **Step 3: Add the threshold constructor and use `similarityThreshold(minSimilarityScore)` instead of `similarityThresholdAll()`.**

Validate the threshold is finite and in `[0, 1]`; keep the existing two-argument constructor delegating to `0.65` for test and caller compatibility. Bind `smartpark.knowledge.min-similarity-score` with a default of `0.65` in the RAG configuration.

- [ ] **Step 4: Run adapter and context tests.**

Run: `cmd.exe /d /c ".\\mvnw.cmd -B -Dtest=RagKnowledgeAdapterTest,RagModeContextTest test"`

Expected: PASS with the low-similarity result excluded and the configured RAG bean still active.

- [ ] **Step 5: Commit the threshold behavior.**

```powershell
git add src/main/java/com/example/smartpark/adapter/rag/RagKnowledgeAdapter.java src/main/java/com/example/smartpark/adapter/rag/RagKnowledgeConfiguration.java src/main/resources/application.yml src/test/java/com/example/smartpark/adapter/rag/RagKnowledgeAdapterTest.java src/test/java/com/example/smartpark/integration/RagModeContextTest.java
git commit -m "fix: enforce rag similarity threshold"
```

### Task 3: Full regression and artifact documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture.md`
- Verify: all Java tests and `ui/` build

**Interfaces:**
- Consumes: the fixed RAG adapter and configuration.
- Produces: documented `SMARTPARK_KNOWLEDGE_MODE=rag` and `SMARTPARK_KNOWLEDGE_MIN_SIMILARITY_SCORE` behavior, with complete verification evidence.

- [ ] **Step 1: Update documentation with the default threshold and in-memory lifecycle.**
- [ ] **Step 2: Run the complete backend test suite.**

Run: `cmd.exe /d /c ".\\mvnw.cmd -B test"`

Expected: PASS with zero failures/errors; the conditional DashScope smoke test may skip without credentials.

- [ ] **Step 3: Install/verify UI dependencies only if needed, then run `npm.cmd run build` from `ui/`.**
- [ ] **Step 4: Run `git diff --check` and inspect both worktree status and changed paths.**
- [ ] **Step 5: Commit documentation and verified regression changes.**

```powershell
git add README.md docs/architecture.md
git commit -m "docs: document rag similarity threshold"
```
