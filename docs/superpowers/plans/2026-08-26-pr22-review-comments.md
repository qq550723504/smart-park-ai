# PR #22 Review Comments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct the seven unresolved documentation claims from merged PR #22 so the architecture and customer-capabilities documents match the current `main` implementation.

**Architecture:** Keep this as a documentation-only follow-up. The implementation is the source of truth: the alert graph conditionally routes ENERGY and ACCESS scenarios, approval audit entries retain only safe metadata, and analytics/RAG startup dependencies are explicit. The follow-up branch will update wording and dependency tables without changing runtime behavior.

**Tech Stack:** Markdown documentation, Java/Spring Boot source inspection, Maven verification, GitHub GraphQL review-thread state.

**Spec:** GitHub PR #22 review threads `PRRT_kwDOUBdFkc6cXFPp`, `PRRT_kwDOUBdFkc6cXFPt`, `PRRT_kwDOUBdFkc6cXFPv`, `PRRT_kwDOUBdFkc6cXFPx`, `PRRT_kwDOUBdFkc6cXFPy`, `PRRT_kwDOUBdFkc6cXFP2`, and `PRRT_kwDOUBdFkc6cXFP5`.

## Global Constraints

- Do not change Java behavior or public API contracts.
- Describe only behavior verified in the current `main` implementation.
- Preserve the security boundary that audit records and public projections omit approval comments and raw diagnostic content.
- Keep the follow-up PR unmerged; only update, reply to, and resolve the original review threads after verification.

---

### Task 1: Correct architecture claims

**Files:**
- Modify: `docs/architecture.md:141-143` for scenario-specific alert routing.
- Modify: `docs/architecture.md:252-254` for DTO scope, administrator startup use, and API-key provisioning wording.

**Interfaces:**
- Consumes: `AlertWorkflow.compileGraph`, `AlertWorkflowNodes.scenarioRoute`, `ApprovalController`, `AuditEntry`, `AnalyticsConfiguration`, and `application.yml` as implementation evidence.
- Produces: Architecture documentation that names `energyAnalysis` and `securityReview`, limits the tracking-only claim to alert projections, includes startup read-only-role credential provisioning, and describes the API key as recommended environment provisioning rather than an enforced source.

- [ ] **Step 1: Update the alert flow diagram**

Add the conditional route after `collectParkContext`: ENERGY → `energyAnalysis` → `retrieveKnowledge`, ACCESS → `securityReview` → `retrieveKnowledge`, and other classifications → `retrieveKnowledge`.

- [ ] **Step 2: Narrow security and credential claims**

Change the Web DTO statement to describe alert redacted diagnostic/approval/work-order projections. State that analytics administrator credentials are used for Flyway, optional local demo refresh, and startup provisioning of the read-only role credential. State that `AI_DASHSCOPE_API_KEY` is the recommended environment-variable provisioning path and that deployment secret management remains responsible for the actual source boundary.

- [ ] **Step 3: Inspect the rendered diff**

Run `git diff --check` and review the exact changed paragraphs against the cited Java and YAML lines.

### Task 2: Correct customer capability dependencies

**Files:**
- Modify: `docs/customer-capabilities.md:79` for the actual audit record scope.
- Modify: `docs/customer-capabilities.md:115-116` for RAG and analytics online dependencies.

**Interfaces:**
- Consumes: `AuditEntry`, `ApprovalController`, `RagKnowledgeConfiguration`, `AnalyticsConfiguration`, DashScope configuration, and the analytics Compose overlay.
- Produces: Customer-facing capability rows that name required `EmbeddingModel`/DashScope key/network access for RAG and required `ChatModel`/DashScope key/network access for analytics, while describing approval auditability as safe metadata rather than audited opinions.

- [ ] **Step 1: Narrow the approval audit sentence**

State that approval actions and outcomes are audited as role/action/workflow metadata; the current `AuditEntry` does not include the approval decision or reviewer comment.

- [ ] **Step 2: Expand RAG and analytics dependency rows**

Document the RAG embedding model and DashScope key/network requirement, and document the analytics ChatModel plus DashScope key/network requirement in addition to the PostgreSQL database and credentials.

- [ ] **Step 3: Validate all seven comment requirements**

Run focused searches for the old overclaims and the required implementation terms, then run the project verification command selected from the repository build files.

### Task 3: Deliver and close the review feedback

**Files:**
- No additional source files beyond the documentation changes above.

**Interfaces:**
- Consumes: Verified follow-up branch commit and original PR thread IDs.
- Produces: A pushed follow-up branch/PR, one technical reply in each original thread, and confirmed resolved thread state.

- [ ] **Step 1: Commit only the intended documentation files**

Stage the plan and two documentation files explicitly, then commit with `docs: address PR 22 review comments`.

- [ ] **Step 2: Push the follow-up branch and open a draft PR**

Push `codex/address-pr22-comments` and open a Draft PR against `main`, linking PR #22 and listing the seven addressed claims.

- [ ] **Step 3: Reply and resolve original threads**

Reply in each original inline thread with the exact documentation change and follow-up PR link, resolve only after the final verification is green, then re-fetch `isResolved` and `isOutdated` for all seven threads.
