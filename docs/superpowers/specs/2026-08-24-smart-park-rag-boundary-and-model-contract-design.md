# Smart Park RAG Boundary and Model Contract Design

## Status

Approved in chat on 2026-08-24. This design fixes the four outstanding PR #4 review findings at their ownership boundaries instead of adding isolated seed documents or prompt text.

## Problem

The RAG adapter currently implements one unscoped `KnowledgePort`, while both customer service and alert diagnosis consume it. RAG startup seeds only customer-service documents, so enabling RAG silently changes the alert workflow's knowledge source and can remove operational runbooks from alert diagnosis.

The DashScope answer adapter also has two contract-boundary defects. Safety policy, user input, and retrieved documents share one user message, and the parser owns stricter rules than the prompt communicates. The model can therefore receive prompt-injection content at the same instruction level as policy or return a response that is syntactically plausible but semantically invalid for the workflow. `RETRIEVAL_UNAVAILABLE` is an internal deterministic failure reason and must not be model-selectable.

## Goals

1. Make customer-service and alert-operational knowledge explicit, selectable, and independently searchable in both Mock and RAG modes.
2. Keep knowledge administration and returned metadata honest about the document domain.
3. Put immutable answer safety rules in a system message and treat the question/evidence as untrusted user data.
4. Define one structured-answer contract shared by prompt construction and parser validation.
5. Preserve the existing safe-handoff behavior when retrieval or model output fails.
6. Keep the default Mock mode offline and preserve existing compatibility constructors where they do not hide a domain decision.

## Non-goals

- No persistent vector database or cross-process consistency work.
- No change to alert risk-gate policy or customer-service ticket state transitions.
- No real authentication or authorization redesign.
- No model-generated tool calls or device control.

## Architecture

### Explicit knowledge domains

Add `KnowledgeDomain` with `CUSTOMER_SERVICE` and `ALERT_OPERATIONS` values. `KnowledgeDocument` carries its domain, and knowledge retrieval accepts the domain as part of the application contract. This makes the caller responsible for selecting the business corpus and prevents a generic query such as `energy` from crossing bounded contexts.

The Mock adapter filters the existing in-memory corpus by domain. The RAG adapter maintains a vector index per domain while keeping one adapter boundary for knowledge administration. Each index receives only documents from its domain. Existing alert runbooks become RAG seeds under `ALERT_OPERATIONS`; customer documents remain under `CUSTOMER_SERVICE`.

The alert workflow and `ParkKnowledgeTool` request `ALERT_OPERATIONS`. `CustomerServiceWorkflow` requests `CUSTOMER_SERVICE`. Admin requests and metadata responses include the domain so newly managed documents cannot be silently assigned to the wrong corpus. The public customer response continues to expose only safe citation metadata, not document content.

### Structured customer answer contract

Create a shared contract helper that defines:

- exact fields: `answer`, `needsHuman`, `reason`, `citationIds`;
- model-selectable reasons: `SUPPORTED`, `INSUFFICIENT_EVIDENCE`, `POLICY_LIMIT`;
- `RETRIEVAL_UNAVAILABLE` as workflow-only;
- `needsHuman=false` requiring `SUPPORTED` and at least one unique citation from retrieved evidence;
- `needsHuman=true` requiring a non-supported model reason and an empty citation list.

The DashScope adapter builds a system message from this contract and a user message containing only explicitly delimited intent, question, and evidence. The parser validates the same rules before constructing `CustomerAnswer`, including rejecting unknown/internal reasons, empty or duplicate citations, and citations not present in the evidence.

### Failure behavior

Retrieval failures remain deterministic and produce `RETRIEVAL_UNAVAILABLE` without invoking the model. Any model call, parsing, contract, or citation failure remains inside the existing workflow catch and creates the safe human-handoff result. Prompt content and model output are not logged or returned.

## Compatibility and migration

- Update all application callers and test doubles to pass a domain explicitly.
- Preserve `KnowledgeAdminPort` as the administrative capability, but make its document operations domain-aware through `KnowledgeDocument`.
- Update request/response DTOs and README/architecture documentation to show the domain field.
- Keep existing Mock fixture behavior by assigning current operational runbooks to `ALERT_OPERATIONS` and current customer guides to `CUSTOMER_SERVICE`.
- Do not add a fallback domain to production retrieval; missing domain data should fail validation rather than reintroduce cross-domain search.

## Verification strategy

Add tests before implementation for:

1. Mock and RAG domain isolation: customer queries cannot return alert runbooks and alert queries cannot return customer guides.
2. RAG startup contains both operational and customer seed sets and indexes them separately.
3. Alert workflow requests the operational domain and customer workflow requests the customer domain.
4. Parser rejects `RETRIEVAL_UNAVAILABLE`, empty/duplicate citations, non-empty citations on handoff, and unsupported cross-field combinations.
5. DashScope prompt construction places safety policy in the system message, delimiters and untrusted-data instructions in the user message, and the exact answer contract in the generated instructions.
6. Existing safe-handoff, admin, controller, and full application-context behavior remains green.

Run targeted tests after each slice, then the complete Maven test suite, UI build, and `git diff --check`.
