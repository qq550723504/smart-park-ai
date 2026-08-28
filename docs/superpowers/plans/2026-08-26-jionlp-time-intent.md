# JioNLP Time Intent Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the finite Java time-expression grammar with a qualified, internal JioNLP sidecar and a Java-owned evidence reconciler, while preserving fail-closed analytics behavior and exposing safe time-resolution metadata.

**Architecture:** LLM emits only verbatim time mentions. Java locates those mentions in the original question, sends the question plus exact excluded spans to an internal Python/JioNLP service, converts Unicode code-point offsets back to Java UTF-16 offsets, and reconciles parser evidence with model evidence before SQL admission. The Java application owns the reference clock, timezone, final range validation, clarification snapshot, and empty-result semantics.

**Tech Stack:** Java 17, Spring Boot 4, Spring Web RestClient, JUnit 5, Python 3.12, JioNLP 1.5.29, FastAPI, Uvicorn, pytest, Docker Compose.

**Spec:** docs/superpowers/specs/2026-08-26-jionlp-time-intent-design.md

## Global Constraints

- Keep all work on branch codex/jionlp-time-intent-design; do not mutate main, push, merge, deploy, or edit GitHub review state.
- Preserve existing public constructors and compatibility overloads until the final cleanup task.
- Do not add another growing Java regex grammar. The provider boundary and golden corpus are the source of truth.
- Fail closed on parser outage, invalid parser output, unsupported timezone, ambiguous evidence, multiple distinct ranges, unresolved explicit time, and invalid ranges.
- Never expose sidecar URL, raw question, parser internals, or dependency version in REST/SSE payloads.
- Use a fixed reference Instant in every test. No test may call wall-clock time directly.
- Stage only files listed by the current task.

---

## Task 1: Build and qualify the internal JioNLP sidecar

**Files**

- Add time-parser/requirements.in
- Add time-parser/requirements.txt
- Add time-parser/pyproject.toml
- Add time-parser/app.py
- Add time-parser/corpus/time_intent_golden.jsonl
- Add time-parser/tests/test_contract.py
- Add time-parser/tests/test_golden_corpus.py
- Add time-parser/scripts/verify-time-intent-corpus.ps1

- [ ] Write the sidecar contract tests first.

  The health test must return HTTP 200 with status UP, provider jionlp, and version 1.5.29. The resolve test must submit question 上周一到周三能耗 with referenceInstant 2026-08-25T00:00:00Z, timezone Asia/Shanghai, and no excluded spans, then assert PARSED, the exact mention text, start 0/end 6, fromInclusive 2026-08-16T16:00:00Z, and toExclusive 2026-08-19T16:00:00Z. The exclusion test must submit MTR-2026-08-01表计的能耗 with the exact entity span 0-12 and assert NONE with no mentions. The timezone test must submit UTC and assert HTTP 400 with reasonCode UNSUPPORTED_TIMEZONE.

- [ ] Pin direct dependencies in requirements.in: jionlp==1.5.29, fastapi, uvicorn[standard], pytest, httpx, and pip-tools. Generate requirements.txt with pip-compile and retain hashes.

- [ ] Implement app.py with request and response models matching the spec. Accept only Asia/Shanghai. Parse against the supplied referenceInstant, honor excluded code-point spans, return end-exclusive code-point offsets, and return NONE rather than a guessed range when parsing fails or no eligible mention remains. Return a stable reasonCode for each non-PARSED result.

- [ ] Add the 120-row JSONL golden corpus. Include all fifteen PR20 review inputs and categories for absolute/calendar (25), rolling/Chinese numbers/quantifiers (25), composed expressions (20), multiple/ambiguous/unsupported (15), no-time/entity IDs (15), and boundaries/invalid/reversed (20). Each row must contain id, question, referenceInstant, timezone, excludedSpans, expected status, expected mentions, expected ranges, and expected reasonCode.

- [ ] Implement test_golden_corpus.py to run every row deterministically, verify status and exact spans, and enforce the safety gates: zero explicit-time-to-NONE rows, zero unresolved-to-PARSED rows, zero negative/entity-to-range rows, all PR20 rows passing, and no duplicate equivalent ranges.

- [ ] Implement verify-time-intent-corpus.ps1 to install the locked requirements, run the contract and corpus tests, run pip-audit, and run pip-licenses with an Apache-2.0-compatible allowlist. A failure in correctness, security, or license checks stops qualification and requires evaluating Duckling against the same corpus; do not switch production behavior automatically.

- [ ] Run from time-parser: python -m pytest -q; powershell -ExecutionPolicy Bypass -File scripts/verify-time-intent-corpus.ps1. Capture the exact JioNLP version in the health response and test output.

- [ ] Commit only Task 1 files with message: feat: add qualified jionlp time parser sidecar.

## Task 2: Add the Java sidecar client and provider adapter

**Files**

- Add src/main/java/com/example/smartpark/analytics/agent/time/TimeParserSpan.java
- Add src/main/java/com/example/smartpark/analytics/agent/time/TimeParserMention.java
- Add src/main/java/com/example/smartpark/analytics/agent/time/TimeParserResponse.java
- Add src/main/java/com/example/smartpark/analytics/agent/time/UnicodeOffsetMapper.java
- Add src/main/java/com/example/smartpark/analytics/agent/time/JioNlpClient.java
- Add src/main/java/com/example/smartpark/analytics/agent/time/JioNlpTimeIntentProvider.java
- Add src/test/java/com/example/smartpark/analytics/agent/time/UnicodeOffsetMapperTest.java
- Add src/test/java/com/example/smartpark/analytics/agent/time/JioNlpClientTest.java
- Add src/test/java/com/example/smartpark/analytics/agent/time/JioNlpTimeIntentProviderTest.java
- Modify src/main/java/com/example/smartpark/analytics/config/AnalyticsProperties.java
- Modify src/main/java/com/example/smartpark/analytics/config/AnalyticsConfiguration.java
- Modify src/main/resources/application.yml

- [ ] Write failing mapper tests for an emoji-prefixed question 🔔今天上午能耗: sidecar code-point start 1/end 7 must become Java UTF-16 start 2/end 8. Add tests for surrogate pairs inside a mention, invalid boundaries, and non-monotonic spans.

- [ ] Write failing client tests using MockRestServiceServer for request serialization, response deserialization, timeout, HTTP 4xx/5xx, malformed JSON, provider/version mismatch, and missing required fields.

- [ ] Define immutable DTOs with explicit validation: TimeParserSpan uses code-point start/end; TimeParserMention carries text, span, type, definition, fromInclusive, toExclusive, empty, and reasonCode; TimeParserResponse carries provider, version, echo reference/timezone, status, mentions, and reasonCode.

- [ ] Implement UnicodeOffsetMapper with a single conversion path from validated code-point offsets to UTF-16 offsets. Reject offsets that split a surrogate pair or exceed the original question.

- [ ] Implement JioNlpClient with a bounded RestClient, connect/read timeout, maximum response size, expected provider jionlp, and expected version 1.5.29. Map every transport, schema, version, and validation error to TimeParserUnavailableException or TimeParserInvalidResponseException.

- [ ] Implement JioNlpTimeIntentProvider as the TimeIntentProvider adapter. Pass the Java reference Instant and Asia/Shanghai, convert model spans to exact excluded code-point spans, map parser mentions to TimeIntent evidence, and never synthesize a fallback range when the sidecar is unavailable.

- [ ] Add nested AnalyticsProperties.TimeIntent properties for enabled, base URL, connect timeout, read timeout, max response bytes, expected provider, expected version, and allowed timezone. Bind the sidecar URL in application.yml without exposing credentials.

- [ ] Register client/provider beans in AnalyticsConfiguration and inject the provider into OperationsAnalysisGraph. Keep the old constructor overload delegating to the new dependency graph until Task 6.

- [ ] Run focused tests with .\mvnw.cmd -B -Dtest=UnicodeOffsetMapperTest,JioNlpClientTest,JioNlpTimeIntentProviderTest test.

- [ ] Commit only Task 2 files with message: feat: add java jionlp time intent adapter.

## Task 3: Make model time evidence verbatim and reconcile it with parser evidence

**Files**

- Add src/main/java/com/example/smartpark/analytics/agent/ModelTimeEvidence.java
- Add src/main/java/com/example/smartpark/analytics/agent/TimeEvidenceReconciler.java
- Add src/test/java/com/example/smartpark/analytics/agent/TimeEvidenceReconcilerTest.java
- Modify src/main/java/com/example/smartpark/analytics/agent/AnalyticsModelClient.java
- Modify src/main/java/com/example/smartpark/analytics/agent/LlmAnalyticsModelClient.java

- [ ] Write failing reconciler tests for:
  - parser PARSED plus model empty -> PARSED;
  - parser PARSED plus exact model mentions -> PARSED;
  - parser NONE plus explicit model mention -> UNSUPPORTED;
  - parser PARSED plus an unmatched model mention -> AMBIGUOUS;
  - two distinct ranges -> MULTIPLE;
  - equivalent duplicate ranges -> one PARSED range;
  - parser error -> fail closed;
  - zero-width current-period range -> EMPTY.

- [ ] Replace requestedTimeRange in the model contract with requestedTimeMentions: a list of exact strings copied from normalizedQuestion. Keep a deprecated compatibility constructor that accepts RequestedTimeRange and converts it only for source compatibility; the graph must stop reading model timestamps.

- [ ] Update the LLM JSON schema and prompt to require requestedTimeMentions and explicitly forbid ISO timestamps, inferred dates, relative-expression normalization, and invented text. Add an example containing 过去2个星期.

- [ ] Implement ModelTimeEvidence.fromQuestion(List<String>, String) to locate every exact occurrence, reject null/blank/non-verbatim mentions, preserve UTF-16 spans, and return deterministic ordering.

- [ ] Implement TimeEvidenceReconciler with the approved matrix. Compare parser spans and model spans, dedupe only equivalent ranges, classify unresolved explicit mentions as UNSUPPORTED, classify unmatched mentions as AMBIGUOUS, classify distinct ranges as MULTIPLE, and propagate EMPTY without constructing a QueryPlan.

- [ ] Run .\mvnw.cmd -B -Dtest=TimeEvidenceReconcilerTest,LlmAnalyticsModelClientTest test and inspect serialized model requests to confirm no model timestamp field remains.

- [ ] Commit only Task 3 files with message: feat: reconcile verbatim model and parser time evidence.

## Task 4: Integrate EMPTY semantics, safe metadata, REST/SSE, and UI

**Files**

- Add src/main/java/com/example/smartpark/analytics/agent/TimeResolutionMetadata.java
- Add src/test/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraphTimeResolutionTest.java
- Modify src/main/java/com/example/smartpark/analytics/agent/TimeIntentResult.java
- Modify src/main/java/com/example/smartpark/analytics/agent/TimeConstraintResolver.java
- Modify src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java
- Modify src/main/java/com/example/smartpark/analytics/OperationsAnalysisService.java
- Modify src/main/java/com/example/smartpark/analytics/AnalysisRunStore.java
- Modify src/main/java/com/example/smartpark/web/OperationsAnalysisDtos.java
- Modify src/main/java/com/example/smartpark/execution/model/DisplayPayload.java
- Modify src/main/java/com/example/smartpark/web/ExecutionDtos.java
- Modify ui/src/types/analytics.ts
- Modify ui/src/types/execution.ts
- Modify ui/src/components/analytics/OperationsAnalysisPage.vue
- Modify ui/src/components/execution/ExecutionEventCard.vue
- Add or modify focused Java and UI tests next to the touched components.

- [ ] Add TimeIntentResult status EMPTY and TimeResolutionMetadata fields status, fromInclusive, toExclusive, source, explanation, empty. Preserve existing constructors through delegating overloads.

- [ ] Change TimeConstraintResolver so today/current week/current month/current quarter/current year at an exact period boundary returns EMPTY. Do not subtract a second, create a one-second range, or send empty ranges to QueryPlan.

- [ ] Update OperationsAnalysisGraph to use one captured reference Instant, call the provider once, pass parser/model evidence to TimeEvidenceReconciler, reject UNSUPPORTED/AMBIGUOUS/MULTIPLE without SQL, and return a structured empty result for EMPTY. Ensure clarification and rerun paths reuse the same captured reference instant in their snapshot.

- [ ] Extend AnalysisRunResult and AnalysisRunStore.RunRecord with additive timeResolution metadata and compatibility constructors. Persist metadata in completed, clarification, failed, expired, and rerun records.

- [ ] Add DisplayPayload.TimeRangePayload to the sealed display contract with status, range, source, explanation, and empty. Emit it with the understanding/terminal events; retain existing payload variants unchanged.

- [ ] Map only safe metadata in OperationsAnalysisDtos and ExecutionDtos. Exclude sidecar URL, raw question, parser version, and internal reason details from public payloads. Add UI types and render a compact status/range/explanation card for parsed, unsupported, ambiguous, multiple, and empty states.

- [ ] Write tests proving EMPTY performs zero SQL calls, sidecar outage does not use a default range, persisted records contain metadata, SSE and REST payloads contain only safe fields, and the UI renders empty/unsupported states.

- [ ] Run .\mvnw.cmd -B -Dtest=OperationsAnalysisGraphTimeResolutionTest,OperationsAnalysisServiceTest,OperationsAnalysisDtosTest test and the focused UI test command defined by ui/package.json.

- [ ] Commit only Task 4 files with message: feat: expose safe time resolution and empty results.

## Task 5: Package the sidecar safely in Compose and add integration checks

**Files**

- Add time-parser/Dockerfile
- Add time-parser/.dockerignore
- Modify compose.yaml
- Modify compose.analytics.yaml
- Modify src/main/resources/application.yml
- Modify src/main/java/com/example/smartpark/analytics/config/AnalyticsProperties.java
- Add src/test/java/com/example/smartpark/analytics/config/AnalyticsComposeSecurityTest.java

- [ ] Build a non-root Python image that installs requirements.txt, starts Uvicorn on port 8081, has no shell-based startup interpolation, and exposes only the health endpoint to the Compose healthcheck.

- [ ] Add analytics-time-parser under the analytics profile in compose.analytics.yaml. Set SMARTPARK_ANALYTICS_TIME_INTENT_URL to http://analytics-time-parser:8081, add a healthcheck, make backend depend on parser health, do not publish a host port, and set read_only, no-new-privileges, dropped capabilities, and a writable temporary directory only where required.

- [ ] Keep the default compose.yaml offline profile disabled for the parser and analytics. The analytics overlay must explicitly set every inherited value that could otherwise come from the host environment.

- [ ] Add a security test that parses docker compose config with and without host variables and asserts no parser host port, no secret leakage, a non-root user, explicit URL, and healthy dependency ordering.

- [ ] Run docker compose -f compose.yaml -f compose.analytics.yaml --profile analytics config and the security test. Start the analytics profile, wait for both healthchecks, run one real parser request through the backend, then stop the stack.

- [ ] Commit only Task 5 files with message: chore: package analytics time parser securely.

## Task 6: Remove the finite grammar and run the complete verification gates

**Files**

- Delete src/main/java/com/example/smartpark/analytics/agent/FiniteGrammarTimeIntentProvider.java
- Delete src/test/java/com/example/smartpark/analytics/agent/FiniteGrammarTimeIntentProviderTest.java
- Modify src/main/java/com/example/smartpark/analytics/agent/TimeRangeParser.java
- Modify all remaining callers and tests that still construct requestedTimeRange or the finite grammar.

- [ ] First write an architecture test that fails if any production analytics class imports or instantiates FiniteGrammarTimeIntentProvider, calls Pattern.compile for time recognition, or reads model-generated timestamps.

- [ ] Replace TimeRangeParser with a thin compatibility facade that delegates to the provider boundary and contains no finite grammar or Pattern.compile. Remove obsolete finite-grammar tests only after the new corpus and reconciler tests cover their accepted behavior.

- [ ] Remove requestedTimeRange from production decision paths. Retain only explicitly documented source-compatibility constructors, and add a test proving they cannot affect SQL admission.

- [ ] Run the complete verification set:
  - .\mvnw.cmd -B test
  - Set-Location ui; npm.cmd ci; npm.cmd run test:unit; npm.cmd run build; Set-Location ..
  - docker compose -f compose.yaml -f compose.analytics.yaml --profile analytics config
  - git diff --check
  - powershell -ExecutionPolicy Bypass -File time-parser/scripts/verify-time-intent-corpus.ps1

- [ ] Review the final diff for unrelated changes, confirm all new tests execute real assertions rather than empty selections, and confirm no public response contains parser internals.

- [ ] Commit the cleanup with message: refactor: retire finite analytics time grammar.

- [ ] Stop at local verification. Report local tests, container checks, remote CI, review status, merge status, deployment status, and business acceptance as separate facts; do not imply any remote or production state.

---

## Final self-review checklist

- [ ] Every spec requirement has a task and a concrete verification command.
- [ ] Every task names exact files, tests before implementation, implementation details, and a commit boundary.
- [ ] No task relies on a placeholder, an unspecified edge-case instruction, or a future decision.
- [ ] Java DTO names and fields match the sidecar JSON contract.
- [ ] Code-point offsets are converted exactly once at the Java boundary.
- [ ] EMPTY, UNSUPPORTED, AMBIGUOUS, MULTIPLE, and parser failure cannot reach SQL.
- [ ] The sidecar has no public host port and uses a pinned, license-checked dependency.
- [ ] Compatibility constructors cannot reintroduce model timestamps or the finite grammar.
- [ ] UI and REST/SSE changes are additive and redact internal parser details.
- [ ] Verification covers Java, UI, Compose, security, corpus, and dependency/license gates.
