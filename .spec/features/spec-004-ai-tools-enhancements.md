# Spec 004 — AI Tools Enhancements

Status: P0 implemented; verification and remaining acceptance checks are recorded in section 12. P1–P3 remain planned.
Branch: `feature/add-ai-tools`
Platform: Jmix 3.1.0, Java 21, Vaadin 25.
Reference: local `~/IdeaProjects/jmix-crm`, reviewed at commit `14ea04b`.

## 1. Purpose

Turn the existing AI chat into a practical financial analysis assistant: answers should link to their source records, use the application's financial calculations, and support questions about records the user is already viewing.

This specification covers the complete feature list identified in the CRM review. The user authorized implementation and testing of the first-priority (P0) features. Each priority is independently deliverable; lower-priority features remain planned.

## 2. Existing application and adoption approach

At the start of this work, fin-assist already included AI Tools core, Flow UI, chat persistence, AI Chat components, and the OpenAI Spring AI starter. The AI Tools database changelog was registered, and composite menus enabled. P0 now extends the standard chat with application-specific link tools, a financial prompt, and curated discovery.

CRM uses AI Tools core with custom conversation entities, storage, services, and UI. Its implementation is a reference, not a module that can be copied directly into fin-assist.

Adopt extensions incrementally around the standard fin-assist chat. Retain existing history, persistence, Markdown rendering, user isolation, and add-on services where they meet requirements. If an extension point is insufficient, document the limitation and the smallest necessary customization before replacing a service or view. Do not introduce a second conversation store solely to copy CRM UI.

Alternatives considered:

- Copy CRM's entire chat: comprehensive, but duplicates built-in features and introduces substantial schema and maintenance work.
- Add only links: solves the immediate issue but omits the requested analysis and context features.
- Extend the standard chat in stages: preferred; delivers the full feature set while reusing supported Jmix functionality.

## 3. Priority and dependency map

| ID | Priority | Feature | Dependencies |
| --- | --- | --- | --- |
| AI-01 | P0 — foundation | Record and list deep links | Existing routes and permissions |
| AI-02 | P0 — foundation | Financial domain prompt and answer conventions | AI-01; existing financial business rules |
| AI-03 | P0 — foundation | Curated entity discovery | Existing Jmix access control |
| AI-04 | P1 — analysis and context | Report discovery and execution | P0; report output adaptation |
| AI-05 | P1 — analysis and context | “Chat about this” with selected records | P0; bounded entity-context loading |
| AI-06 | P2 — assistance and evidence | Context-sensitive suggested questions | AI-02, AI-05 |
| AI-07 | P2 — assistance and evidence | Uploaded attachments and context cards | AI-05; attachment storage and ownership |
| AI-08 | P2 — assistance and evidence | Downloadable generated report attachments | AI-04, AI-07 |
| AI-09 | P3 — efficiency | Separate small model for conversation titles | Existing title-generation extension points |
| AI-10 | P3 — interaction polish | Progress, failure handling, and conversation UI refinements | P1/P2 workflows; reuse built-in UI first |

Security checks, localization, and tests are part of every feature, not deferred to P3. Basic progress and recoverable errors must accompany long-running P1 features; P3 refines their presentation.

## 4. P0 — Foundation

### AI-01: Record and list deep links

Register an application-specific `JmixAiTool` for resolving entity detail and list links. Support Customer, Contract, Project, Addendum, LineOfBusiness, payment/shipment schedules, and their corrections where an accessible view exists. Child records without a standalone detail route may link to an appropriate parent only when that relationship and destination are explicitly supported.

- Derive URLs from registered views and supported Jmix/Vaadin navigation APIs. Do not infer routes from Java names or trim route strings and append arbitrary IDs.
- Use application-relative links that work locally, behind the production proxy, and with a servlet context path.
- Validate entity names and ID types. Respect entity/record read access and destination view permissions; return an unavailable result for inaccessible or missing targets without disclosing their details.
- Return labels based on accessible business identifiers. Do not invent IDs or URLs and do not show raw UUIDs as link captions.
- Prefer a bounded batch request for result tables to avoid a separate model round trip for each row.
- Aggregate rows receive record links only when they identify actual source records. A group label must not be given a fabricated entity URL.

Acceptance: a multi-record answer contains working Markdown links; each opens the correct existing record. Tests cover list links, invalid IDs, missing views, deleted records, denied access, context paths, and users with different permissions. Verify rendered links in the actual chat.

### AI-02: Financial domain prompt and answer conventions

Extend the supported chat system-prompt mechanism while preserving the framework's tool, read-only-query, and localization instructions.

- Explain the meanings of contracts, projects, addenda, schedules, and corrections using the implemented business rules.
- Distinguish base and actual schedule/report modes, effective dates, payment versus shipment measures, and planned values versus realized facts. Do not claim the application records actual payments merely because a report mode is named “actual.”
- State reporting period, mode, currency/unit where available, and meaningful limitations when presenting calculations.
- Prefer approved report tools for complex calculations once AI-04 is available. Before that, do not reference tools that have not been registered.
- Request record IDs with valid query aliases when links are needed, then use the link tool. Never hardcode CRM routes or copy its unaliased query examples.
- Use Russian by default according to the user's locale, readable business labels, compact tables, and concise interpretations. Do not expose internal queries or technical errors in normal answers.
- Try case-insensitive and partial customer/project/contract searches before reporting no match. Ask for clarification when several plausible records remain.

Acceptance: representative questions produce linked, understandable answers with explicit financial mode and period. Ambiguous names are clarified; empty results and permission-limited data do not become invented conclusions. Prompt-level tests verify that framework rules remain present.

### AI-03: Curated entity discovery

Use an explicit business-entity allowlist in addition to the framework's permission checks. Include the entities needed for contracts, customers, projects, schedules, corrections, and related line items. Exclude users, authentication/security records, internal chat data, and infrastructure entities by default.

Use the same registry where practical for discovery, link generation, and context selection. The registry must distinguish entities usable in queries from entities that have list/detail views. A child item may be queryable without a direct link.

Acceptance: allowed entities remain subject to CRUD, attribute, and row-level restrictions. Hidden entities cannot be exposed by custom tools simply because their names are supplied directly.

## 5. P1 — Analysis and record context

### AI-04: Report discovery and execution

Introduce tools to discover approved reports, inspect parameter definitions, and execute them with validated parameters. Initially support `CashflowCalendar` and `ListOfContracts`.

- Reuse existing report/service calculations, including base/actual selection, addendum effectiveness, and correction fallback behavior. Do not duplicate those calculations in prompts or generated JPQL.
- Expose report codes, readable descriptions, parameter aliases/types, required inputs, supported modes, and available output formats.
- Resolve entity parameters through secured data access and use business names in clarifications. Enforce report execution permissions as well as the application allowlist.
- Return structured success/error results. Limit rows/output size and explain truncation or pagination; never truncate silently.
- Fin-assist's current templates produce XLSX. Provide structured calculation results or add an appropriate CSV/HTML template before enabling analysis. Merely requesting CSV from an XLSX template is not an adequate adaptation.
- Keep existing user-facing report output and behavior compatible.

Acceptance: tool output matches existing report results for base and actual modes, date boundaries, corrections, and incomplete-correction fallback. Invalid parameters and unauthorized reports are rejected. Integration tests cover real report calculation; live-model evaluation covers correct tool selection and explanation.

### AI-05: “Chat about this” and selected-record context

Add a localized action on relevant detail views and list selections. Start with Customer, Contract, Project, and Addendum, then schedules and corrections. Open a non-modal side panel where supported, keeping the business view accessible; use an appropriate narrow-screen layout.

- Supply stable entity references automatically; users should not copy IDs into chat.
- Show context cards identifying the selected records and allow removal before submission.
- Load context using secured DataManager access and explicit fetch plans. Bound record count and related collections; do not serialize an unrestricted entity graph.
- Recheck access when context is loaded again. Explain unavailable context without exposing data from inaccessible records.
- Use persisted values. Disable the action for a new unsaved record; explain that unsaved edits are not included for an existing record.
- Preserve conversation continuity and integrate with the existing conversation store. Verify the supported extension mechanism before adding reference persistence.

Acceptance: single-record and multi-selection actions open the correct context; follow-up questions retain it. Removing a pending card excludes that context from the submitted message. Permission changes, deleted records, large selections, and unsaved edits have defined behavior.

## 6. P2 — Assistance and supporting evidence

### AI-06: Context-sensitive suggested questions

Provide localized, deterministic suggestion templates based on record type and selection count. Examples: explain a contract's schedule changes; summarize a customer's projects; compare selected projects; identify upcoming planned payments for a stated period.

Suggestions populate the composer and remain editable before sending. Only suggest capabilities actually available in the current rollout. Mixed selections receive generic comparison/summarization suggestions. Do not spend a model request to generate suggestions initially; CRM's current suggestion selection is template-based.

Acceptance: relevant suggestions appear for single, multiple, mixed, and empty selections, with no unsupported financial claims or automatic model calls when a suggestion is merely displayed.

### AI-07: Uploaded attachments and context cards

Support persisted attachments associated with authorized conversation messages, with readable names, type indicators, removal before submission, download, and explicit unavailable/error states.

- Initial AI-readable inputs: CSV, text, Markdown, JSON, HTML converted to suitable text, and supported images when the configured model accepts image input.
- Define configurable upload count, byte-size, extracted-text, and total-context limits before enabling uploads. Enforce limits server-side and visibly mark any truncation.
- Validate content types, handle storage cleanup, and enforce conversation ownership for upload, read, and download.
- Treat file and record content as untrusted data, not instructions overriding the system prompt. Do not load arbitrary external URLs embedded in content.
- CRM accepts PDF/DOC/DOCX/XLS/XLSX as attachments but does not extract their text in its current resolver. Fin-assist must clearly label such files as unavailable for AI analysis unless a separately tested extraction path is implemented. Full Office/PDF parsing and OCR are not implied by file upload support.

Acceptance: text and supported image inputs reach the model correctly; unsupported files are not claimed to have been analyzed. Tests cover ownership, limits, malformed files, cleanup, and missing storage objects.

### AI-08: Generated report attachments

Attach successful report artifacts to the answer that produced them, with report name, parameters, generation time, and output type. Provide an authorized download action and distinguish generated artifacts from user uploads.

Store the artifact used for the answer as a snapshot. A later download must not silently rerun the report against changed data. An analysis-friendly representation and a downloadable XLSX may coexist, provided both use consistent parameters and calculations.

Acceptance: the artifact remains available after reopening the conversation, matches the answer's period/mode, and cannot be downloaded by another unauthorized user. Failed persistence cleans up orphan files and reports the attachment failure without falsely claiming a download exists.

## 7. P3 — Efficiency and interaction polish

### AI-09: Small model for conversation titles

Use the built-in Jmix title-generation mechanism where possible; customize its provider only to support a separately configurable lightweight model. Keep the analysis model independently configurable.

Bound title context and output, preserve manually edited titles, and use a first-message/default-title fallback on failure. Validate model parameters against the selected provider; do not copy CRM model names, temperature, or token settings blindly.

Acceptance: title requests use the configured small model without changing analysis requests. Failures never block chat, and user-edited titles survive asynchronous completion. Existing history remains compatible.

### AI-10: Progress and conversation UI refinements

Reuse standard progress publication, history, title editing, and Markdown rendering before introducing custom components. Add missing context panels, attachment presentation, and report-specific progress as needed.

- Show meaningful stages such as loading context, reading data, and running a report.
- Keep the UI responsive and prevent duplicate submissions while a response is pending.
- Define timeout, navigation-away, and retry behavior. A retry must not create duplicate assistant messages or report attachments. Do not promise cancellation unless the underlying task/model request can actually be canceled.
- Render safe Markdown with accessible links and keyboard-operable controls. Localize statuses and errors.
- Preserve current-user authentication in background work. Do not use elevated access to make user-triggered analysis succeed.

Acceptance: slow and failing requests show understandable states; navigating away and returning does not corrupt history. Desktop and narrow layouts, focus behavior, and permissions are verified in rendered views.

## 8. Shared constraints and exclusions

- AI access is read-only for business records. Executing approved reports and persisting chat artifacts is allowed; modifying contracts, schedules, or business data through the model is excluded.
- Conversation access does not grant additional business-data access. Custom tools must enforce permissions independently of prompt instructions.
- Preserve existing `aitools-chat-user` behavior and historical conversations. New persistent artifacts require migrations and ownership policies.
- Use environment-based provider configuration. Do not commit credentials or log full prompts, financial context, attachments, or provider secrets by default.
- Bound context and tool output. CRM's complete-history replay and full report text should not be copied without limits.
- Reuse existing Lumo styling and Russian localization. Verify APIs against installed Jmix 3.1/Vaadin 25 dependencies.
- No wholesale CRM chat replacement, automatic deployment, model-provider migration, external web retrieval, or new financial accounting semantics is included.

## 9. Delivery and verification

Deliver P0 first; then AI-04 and AI-05 as separate reviewable increments; then P2 and P3. Each increment must work without features from a later increment. Preserve tool names or provide a compatibility strategy if stored conversation behavior depends on them.

For every increment:

1. Read the applicable Jmix artifact skills and verify unfamiliar framework APIs before implementation.
2. Add focused deterministic tests for tools, permissions, parameters, links, and persistence. Port relevant CRM test patterns, not its entity-specific fixtures.
3. Run Jmix-aware static inspection where available, compile, and a terminating `clean test` against an isolated database.
4. Render every changed view/action with appropriate restricted users, including narrow-screen and keyboard checks.
5. Keep live LLM evaluation separately opt-in; normal CI uses mocks or a dummy key and must not call a paid model.

Maintain an evaluation set covering linked record lists, ambiguous customer names, contract summaries, base/actual cash flow, correction fallback, selected-record comparisons, denied data access, empty results, and attachment questions. Evaluate source accuracy and financial correctness, not exact wording. A larger model does not substitute for deterministic calculation tests.

Release acceptance: all implemented features pass their acceptance criteria; the application context starts; existing report results remain unchanged; no cross-user data leakage occurs; and all new configuration/operational requirements are documented.

## 10. CRM source map

Paths below are relative to `~/IdeaProjects/jmix-crm/` and refer to the reviewed commit, not guaranteed stable APIs.

| Concern | Reference |
| --- | --- |
| Routes and links | `src/main/java/com/company/crm/ai/tool/ViewsDiscoveryTool.java` |
| Tool registration and report allowlist | `src/main/java/com/company/crm/ai/tool/CrmAiToolsConfig.java` |
| Domain prompt | `src/main/resources/prompts/crm-analytics-system-prompt.st` |
| Entity filtering | `src/main/java/com/company/crm/ai/tool/CrmAvailableEntityFilter.java` |
| Context definitions and fetch plans | `src/main/java/com/company/crm/ai/context/AiContextEntityDefinition.java` |
| Report discovery/execution | `src/main/java/com/company/crm/ai/tool/ReportsDiscoveryTool.java`, `RunReportTool.java`; `ai/report/` |
| Context action | `src/main/java/com/company/crm/ai/view/context/AiChatAboutThisSupport.java` |
| Secured record context | `src/main/java/com/company/crm/ai/memory/EntityReferenceContentResolver.java` |
| Suggested questions | `src/main/java/com/company/crm/ai/view/component/support/PromptSuggestionSupport.java` |
| Attachment input | `src/main/java/com/company/crm/ai/service/AiAttachmentMediaResolver.java` |
| Generated artifacts | `src/main/java/com/company/crm/ai/report/run/AiReportExecutionService.java` |
| Small-model titles | `src/main/java/com/company/crm/ai/service/AiConversationTitleService.java` |
| Background UI work | `src/main/java/com/company/crm/ai/view/conversation/task/AssistantResponseTaskCoordinator.java` |
| Ownership policies | `src/main/java/com/company/crm/security/role/AiChatUserRole.java` |
| Test patterns | `src/test/java/com/company/crm/test/ai/` and `src/test/java/com/company/crm/ai/` |

## 11. Decisions to settle during implementation

These do not block the initial P0 work:

- Whether AI-04 should consume structured calculation results or additional text report templates; choose the approach that shares existing business logic without duplicating it.
- The supported add-on extension points for persisted record references and attachments; avoid replacing conversation storage unless a verified limitation requires it.
- Concrete upload, selection, context, and report-output limits, based on representative data and the deployed model's limits.
- Whether direct links to a selected tab/section are needed beyond record-detail links. Do not copy CRM's hardcoded tab indices into fin-assist.
- Whether PDF/Office extraction warrants a later scoped feature. Until then, the UI must accurately state what content is analyzed.

## 12. P0 implementation and verification — 2026-11-04

Follow-up: the agent identity is Захар, Финансовый менеджер. The Russian chat message caption is Захар.

Implemented AI-01, AI-02 and AI-03 on `feature/add-ai-tools`:

- `BusinessEntityRegistry` explicitly includes 13 business entities; `BusinessAvailableEntityFilter` narrows discovery and delegates permission filtering to Jmix.
- `finassist_getEntityLinks` accepts at most 50 IDs and preserves result order; `finassist_getEntityListLink` resolves list destinations. Both use registered views and Vaadin routes, the servlet context path, and current-user permissions. Missing, deleted, hidden and denied targets return the same unavailable result. Empty/oversized batches return an empty list.
- Labels use readable local business attributes only when permitted; otherwise they use the localized entity caption. Related instance-name objects are deliberately not traversed, avoiding disclosure of inaccessible relationships. Answers may use an accessible business label already returned by the query.
- `FinancialChatSystemPromptProvider` appends the financial resource to the framework prompt without copying its instructions. It covers revised plans versus realized facts, reporting parameters, ambiguity, links and the calendar's correction-item selection/fallback rules.
- Standard chat storage and UI, existing roles and schema remain unchanged. A finance-manager user still cannot receive a Customer detail link unless the role is separately granted that view.

Automated evidence: `./gradlew --no-daemon clean test`, Java 21, disposable PostgreSQL 16, dummy OpenAI key: **131 tests, 21 suites, zero failures/errors/skips**. Eleven new tests cover registered callbacks, real detail/list routes, navigation to the requested record, unknown entities, invalid/missing/deleted IDs, view/CRUD/attribute denial, both JPQL and predicate row restrictions, bounded batches, context paths without UI/session state, missing views, curated discovery, framework prompt preservation and template rendering. No live model calls were made.

Initial tests failed for the missing registry/prompt/tools; callback tests failed before the tool methods were implemented. Fresh-context review identified the empty-addendum selection wording; its regression test failed before correction and passed afterward.

Static verification (IDE semantic inspection unavailable; re-inspect when connected):

| File | Verification |
| --- | --- |
| `ai/BusinessEntityRegistry.java` | Compile, metadata/discovery integration test |
| `ai/BusinessAvailableEntityFilter.java` | Compile, CRUD/attribute/discovery integration tests |
| `ai/EntityLinksTool.java` | Compile, actual framework callbacks, security and navigation tests |
| `ai/FinancialChatSystemPromptProvider.java` | Compile, Spring context and prompt-template tests |
| `prompts/financial-chat-system-prompt.st` | Nonempty/whitespace checks, template rendering, reviewed against calendar code |
| `ai/AiToolsConfigurationTest.java` | Test compilation and execution: 3 passed |
| `ai/EntityLinksToolTest.java` | Test compilation and execution: 8 passed |
| This spec and the P0 implementation plan | Nonempty/whitespace and content review |

Remaining acceptance evidence: the browser access host was unavailable, so actual chat Markdown rendering was not checked. Server-side UI navigation is verified. Representative live-model answers also remain unevaluated; deterministic prompt tests cannot establish LLM answer quality. When browser access is available, request a multi-customer table, click two returned links, test an ambiguous customer search, and compare a BASE versus ACTUAL_WITH_ADDENDUMS explanation. Repeat with a restricted user and a deployed context path. P1–P3 are not implemented.

## 13. List-view AI Tools shortcuts — 2026-11-04

Added a `Спросить Захара` toolbar button to Contract, Project, Customer, Addendum, PaymentSchedule and ShipmentSchedule lists. Each uses the shared `finassist_openAiTools` view action to navigate to the existing AI chat hub. No row selection is required. This is general chat access; selected-record context (AI-05) remains planned. The action checks the current user's hub-view permission, disables the shortcut when access is denied, and checks permission again before navigation. Existing roles are unchanged.

Verification: all six list XML descriptors passed parsing, action-binding and caption-reference checks; `OpenAiToolsAction.java` and `AiToolsShortcutUiTest.java` compiled. All seven AI entry buttons (the six lists and the welcome card) share the caption `Спросить Захара` and the tooltip `Открыть чат с финансовым помощником`; existing UI tests check both strings. Twelve UI integration cases instantiate every affected view, verify the visible caption, click the enabled button to reach `AiChatHubView`, and check disabled state for users without chat access. Tests failed for missing buttons before implementation and passed afterward. Final `clean test`: **143 tests, zero failures/errors/skips**, using disposable PostgreSQL and a dummy API key. No IDE semantic inspection or browser visual verification was available; UI integration checks verified component creation and navigation for all six buttons. The disposable database was removed after testing.
