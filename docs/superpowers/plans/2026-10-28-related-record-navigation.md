# Related-record navigation implementation plan

> Execute inline using executing-plans, with a final independent review.

**Goal:** Implement the approved expandable grids and combined project base schedule.
**Architecture:** Authenticated services load bounded pages and aggregate schedule data; a shared presentation controller installs accessible expanders and preserves per-UI list state. Existing list/detail views remain the navigation endpoints.
**Tech Stack:** Java, Jmix 3.1.0 (actual build), Vaadin, PostgreSQL, Gradle.
**Spec:** `.spec/related-record-navigation.md`

## Global constraints

- Modify this worktree only. Preserve existing CRUD/report/lookup behavior.
- Localize every label in all locale bundles (currently Russian).
- No elevated data loading, EntityManager, schema changes, or eager child collections.
- Child page size 50. Same-tab navigation. Schedule aggregation precedes pagination.

## Review focus

- Hidden schedule headers/items must not leak through aggregate dates or totals.
- Predicate security cannot be translated to aggregate SQL; provide a bounded entity-stream fallback when policies exist.
- Parent changes clear expansions; returning from details restores them without caching entities.
- Empty final pages and deleted source records leave a usable grid.
- Lookup grids and nested project grids must not acquire unwanted expanders.

## Task 1: Secure data services

Files: `service/RelatedRecordsService.java`, `service/ProjectScheduleService.java`, `service/RelatedDataAccess.java`, service records for page/amount rows; tests under `src/test/java/ru/stnovator/finassist/service/`.

- [x] Add integration tests for contract/project scoping, source permission, same-date sum, missing versus zero, empty sides, 51+ dates, cross-project exclusion, hidden headers/items and attribute denial.
- [x] Run the new tests and confirm failures before implementation.
- [x] Implement `contracts(UUID,int)`, `projects(UUID,int)`, and `schedule(UUID,LocalDate)` with 50-row pages, next-page flags and schedule totals. Validate parent access separately.
- [x] Fast-path schedule grouping uses authenticated scalar loads only without item row policies; policy-bearing items use secured entity loads in bounded chunks and streaming date aggregation. Headers are checked via entity loads before either path.
- [x] Run service tests and inspect output.

## Task 2: Inline presentation and navigation state

Files: `view/support/RelatedRecordsPresentation.java`, `view/support/RelatedListState.java`, the customer/contract/project list controllers and descriptors, `messages_ru.properties`, theme CSS if needed; UI tests under `view/related/`.

- [x] Write tests that fail for missing expanders, child grids and schedule totals.
- [x] Install chevron buttons and item-details renderers in three lists; child grids use bounded pages and localized error/retry/empty states.
- [x] Keep data resolution in services. Use permission-aware columns and detail links, accessible names/expanded state, no recursive expansion.
- [x] Restore filters/pagination via existing URL facets and explicit per-UI expansion/selection/sort state on same-tab detail return. Reset state on parent reload/filter/sort/page changes.
- [x] Test all three paths, keyboard-accessible button semantics, paging, independent expansions, lookup exclusion, navigation and return state.

## Task 3: Verification and review

- [x] Check all changed files with JetBrains inspections where available.
- [x] Run `./gradlew test` and report exact results.
- [x] Exercise a running app in browser if available.
- [x] Independent whole-change review; fix material findings and rerun affected checks.
- [x] Leave the feature in its branch/worktree for review without merging.

## Execution notes

- Worktree: `/private/tmp/fin-assist-issue-8`; branch: `feat/related-record-navigation`; base: `d4f5325`.
- Ruling: use a temporary external worktree to avoid modifying the user's already-edited root `.gitignore`.
- Ruling: use actual Jmix 3.1.0 APIs, correcting the stale 2.7 spec reference without changing dependencies.

- Baseline: `./gradlew test` passed before feature code.
- Service RED: new service tests failed because the services did not exist. GREEN: relationship and schedule tests passed.
- UI RED: three tests failed on missing expander columns. GREEN: all three expansion paths passed.
- Security checks: JPQL/predicate item restrictions, hidden headers and batch boundaries passed; amount-attribute denial added to full-suite run.
- Review fix: stale links now recheck source visibility and current relationship membership (`staleChildLinkCannotOpenAfterReassignment`).
- Review fix: schedule fast path now uses date cursors, so later pages do not scan preceding date groups. Cursor state contains dates only.
- Review fix: missing-child focus falls back to the parent; missing-parent focus falls back to the grid; child grid scrolls to the initiating row.
- Review RED: detach/reattach test reproduced the permanent loading state; reset-on-detach fix is in full-suite verification.
- Ruling: item row policies use secured bounded entity batches for totals because arbitrary predicates cannot be translated safely to scalar aggregation. Cost: restricted-user totals scale with visible schedule length; no unbounded entity collection is retained. Standard unrestricted finance roles use database aggregates and date-keyset paging.
- IDE check limitation: the connected IDE rejected the external worktree path as outside its open project. Gradle compilation and integration/UI tests remain available.
- Review accessibility findings resolved: parent captions are included in accessible names when the identifying attribute is readable; pager IDs are unique per parent. These fulfill the spec's multi-expansion accessibility requirement.

- Full suite passed with 94 tests before the final finance-role check.
- Additional RED: `financeManagerCanOpenBothRelatedDetails` showed that the existing finance role granted list views but neither destination detail view. Added only Contract.detail and Project.detail to FinanceManagerRole, matching its existing entity CRUD rights; restricted custom roles remain unchanged.

- Browser verification found URL parameter ordering changed on Back; added `reorderedQueryParametersPreserveExpandedRowsOnReturn` (RED) and compare query parameter maps instead of serialized query strings.
- Added revoked-permission collapse regression (RED): users can now close an already-open region after read access is revoked, but cannot reopen it.
- Finance-role fix passed the full suite (95 tests); the two subsequent regression fixes are in the final full-suite run.

- Final browser regression: Space collapsed the schedule but Enter could not reopen it because Grid replaced the focused chevron. Preserve the initiating toggle ID and focus its replacement on attach; rerun the same keyboard script after restart.

## Final validation

- `./gradlew test`: 97 tests, zero failures/errors after final code changes.
- Browser: Contract → Projects displays project links inline without a dialog. Customer expansion and same-tab contract opening; browser Back restores expansion; combined base schedule displays dates, missing-side dashes and totals; Space/Enter collapse/reopen succeeds after focus fix.
- Final review findings addressed; no deferred minor findings remain.
- IDE inspection unavailable for this external worktree (tool explicitly rejects paths outside the open IDE project).
- Worktree remains isolated; original checkout changes preserved. No merge or push performed.
