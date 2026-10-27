# Independent contract schedule corrections — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the contract list report, apply an eligible shipment-only or payment-only amendment without discarding it because the other schedule is unchanged.

**Architecture:** Preserve selection of the latest eligible contract addendum. Resolve shipment and payment sources independently, then merge their rows by date and calculate totals from those same sources. Keep the existing XLSX layout and render an accurate localized source description.

**Tech Stack:** Java 21, the repository's Jmix 2.8.1 / Spring Boot 3 dependencies, DataManager, Jmix Reports, Gradle, PostgreSQL, JUnit integration tests.

**Spec:** The agreed investigation and proposed fix in this conversation; concrete behavior is specified below. This is a proposed implementation plan, not authorization to implement.

## Behavior and scope

For each project, in `ACTUAL_WITH_ADDENDUMS`, use this table for the selected eligible addendum:

| Shipment correction items | Payment correction items | Shipment source | Payment source |
|---|---|---|---|
| Present | Present | Addendum | Addendum |
| Present | Absent | Addendum | Base |
| Absent | Present | Base | Addendum |
| Absent | Absent | Base | Base |

Base mode and absence of an eligible addendum always use both base schedules. Corrections replace a schedule; they are not deltas added to the base. A zero-amount correction item still counts as present. An empty correction header continues to mean no correction items, not an instruction to erase a schedule.

Preserve `effectiveDate <= reportDate`, existing tie ordering, contract/project/customer filters, and latest-addendum selection. This focused fix does not search older addenda separately for each schedule. If amendments are intended to accumulate across history, specify that behavior separately before extending selection.

Reproduction: contract `2026-02-15-ФАСАД`, project `Оформление фасада заводоуправления`, addendum `2026-04` effective 2026-04-01. At report date 2026-10-27, shipment 789000 must appear on 2026-04-01 instead of 2026-03-01; base payments must remain intact. Shipment total remains 7189000, payment total 30780000.

## Global constraints

- Use DataManager and constructor injection; no schema changes or new dependencies.
- Keep UI/report text localized in all source locale files. Currently only `src/main/resources/ru/stnovator/finassist/messages_ru.properties` exists; recheck at implementation time.
- Preserve the template's existing `cashflowSource` placeholder and row layout.
- Do not change the cashflow calendar or report-picker permissions in this fix.
- Use isolated test fixtures and cleanup; do not edit existing business records to reproduce tests.
- Do not commit `.playwright-cli/` investigation artifacts.

## Review focus

- Payment-only amendments must work symmetrically with shipment-only amendments.
- Future amendments and the effective-date boundary must preserve eligibility rules.
- Empty correction headers and zero-valued items must remain distinguishable.
- Both dates and totals must derive from the same sources, including same-day aggregation.
- Repeated report runs after correction edits must not reuse a stale source decision.

## Task 1: Independently select and load schedule sources

**Files:**
- Modify: `src/main/java/ru/stnovator/finassist/report/ListOfContracts.java`
- Test: `src/test/java/ru/stnovator/finassist/report/ListOfContractsTest.java`
- Modify: `src/main/resources/ru/stnovator/finassist/messages_ru.properties`
- Modify: `Readme.md` (contract-list behavior only)

**Interfaces:**
- Keep existing public dataset delegate methods and input parameters.
- Replace the single active source in `ProjectSourceDecision` with `UUID shipmentAddendumId`, `UUID paymentAddendumId`, and `String sourceMessage`.
- Project-band data carries `shipmentAddendumId` and `paymentAddendumId`; null means base for that schedule.
- Change `computeCashflow` to `computeCashflow(UUID projectId, UUID shipmentAddendumId, UUID paymentAddendumId)`.
- Change `CashflowKey` to contain project ID and both source IDs. Records and totals delegates must construct the same key.
- Retain `resolveProjectSource(UUID projectId, UUID selectedAddendumId, ContractListMode mode)` with the revised return record; remove its persistent thread-local decision cache and `ProjectSourceKey` so edits cannot leave stale decisions on reused threads.

- [ ] Replace `actualModeFallsBackToBaseWhenProjectHasIncompleteCorrectionPair` with a shipment-only regression using its existing fixture: base shipment 500 and payment 200 on 2026-02-01; amended shipment 100 on 2026-02-12. Assert base payment remains 200 on February 1, shipment there is zero, shipment on February 12 is 100, payment there is zero, and totals are 100 / 200. Build the records/totals parent band from the actual project row rather than manually forcing a null source.
- [ ] Add the symmetric payment-only test: base shipment 500 survives, amended payment 100 replaces base payment 200, and dates/totals reflect those sources.
- [ ] Run `./gradlew test --tests 'ru.stnovator.finassist.report.ListOfContractsTest'`; confirm the new behavior assertions fail for the paired-source fallback before changing production behavior.
- [ ] Update source decisions and project-band keys to select each schedule independently. Retain base loading for one side when only the other source ID is present. Do not simply change `&&` to `||`: that would drop the unchanged schedule.
- [ ] Update `computeCashflow`, records/totals delegates, and `CashflowKey` together. Preserve TreeMap date ordering and sum aggregation; use each schedule's own source ID in its DataManager query.
- [ ] Remove the persistent project-source decision cache. Retain the existing records-to-totals computation cache with its expanded key and existing removal after totals.
- [ ] Add source keys under `ru.stnovator.finassist.report.listOfContracts`: `cashflowSourceShipmentAddendum` = `Отгрузки: доп. соглашение; оплаты: база`; `cashflowSourcePaymentAddendum` = `Отгрузки: база; оплаты: доп. соглашение`. Preserve existing labels for both-base and both-addendum. Assert all four labels in tests.
- [ ] Add regression tests for both corrections, neither correction, base mode with corrections, future addendum exclusion, and exact effective-date inclusion. Preserve latest-addendum ordering coverage from the existing tests.
- [ ] Add empty-header and zero-amount correction tests: empty header falls back for that side; a zero-valued correction row replaces a nonzero base schedule.
- [ ] Add repeated-source-resolution coverage on the same test thread: resolve shipment-only, save payment correction, resolve again, and assert both sources now point to the addendum. Also verify changing mode does not reuse another run's values.
- [ ] Assert same-day aggregation and records/totals agreement with mixed sources. Retain selected-project filtering coverage so another project's corrections cannot affect source selection.
- [ ] Update the contract-list paragraph in `Readme.md` to document independent fallback and mixed source labels. Keep the calendar's current paired-source description accurate.
- [ ] Check each modified Java/properties/documentation file using JetBrains `get_file_problems(..., onlyErrors=false)` where supported; resolve new errors.
- [ ] Run `./gradlew test --tests 'ru.stnovator.finassist.report.ListOfContractsTest'`, then `./gradlew test`. Required outcome: all tests pass; report any unrelated baseline failures explicitly.

## Task 2: Verify the generated report on the local deployment

**Inputs:** Task 1's completed report implementation and passing tests.
**Output:** Reproduction evidence showing correct dates, totals, and source text in the generated XLSX.

- [ ] Start the local application. Use report administration to run `List Of Contracts` if the local admin's ordinary report picker is still empty; record the route used.
- [ ] Generate actual mode for 2026-10-27 against the existing reproduction contract. Confirm the addendum is identified correctly, shipment 789000 appears on April 1, and no shipment 789000 remains on March 1. A March 1 row may remain because base payments still occur then.
- [ ] Confirm shipment total 7189000, payment total 30780000, and source text `Отгрузки: доп. соглашение; оплаты: база`. Inspect the rendered source row for clipping; change row sizing only if needed and reverify layout.
- [ ] Generate base mode and confirm shipment 789000 still appears on March 1. Verify a local project with both correction types still reports addendum schedules.
- [ ] Retain the resulting evidence outside tracked source changes, remove any agent logpoints, and stop the app if started only for verification.
- [ ] Review the diff for scope and commit the verified fix with tests and documentation, excluding browser artifacts.

## Follow-up boundaries

`CashflowCalendar.resolveProjectSelections` has the same paired-correction condition. It will remain inconsistent with the corrected contract-list report until separately addressed; extending this change to that report requires its own selection and test review.

The empty report picker observed for the local admin is separate from the schedule bug. The report ran successfully through administration; permissions were not changed during investigation.

## Completion criteria

The shipment-only and payment-only regressions pass; base and complete-pair behavior remain correct; the real local XLSX has the amended shipment date and unchanged payment schedule; source text accurately identifies mixed data; full test results and any limitations are reported.

## Execution results — 2026-10-27

- Implemented independent schedule source IDs, mixed-source labels, and source-decision cache removal.
- Updated `.spec/list-of-contracts-report.md`, `.spec/business-requirements-document.md` (FR-08), and `Readme.md`; cashflow-calendar behavior remains unchanged.
- Regression RED: four new tests failed on base amounts / stale source before implementation. GREEN: full `./gradlew test` passed, 22 tests, zero failures/errors/skips.
- Added mixed-source same-day aggregation and cross-project isolation coverage; fixed the new fixture's missing required line-of-business field. Reviewer found no production defect; its test-coverage suggestions were addressed.
- Local UI run through report administration: actual report for 2026-10-27 has shipment 789000 on April 1, zero shipment on March 1, shipment total 7189000 and payment total 30780000. Base report retains March 1 shipment.
- Rendered actual XLSX with LibreOffice; mixed source label fits existing merged cell, so no template change was needed.
- IDE checks found no code errors; unused-message/annotation warnings and Markdown table formatting warnings remain.
- Ruling: worked on a dedicated local branch in the existing IDE workspace to retain the local deployment setup. The user subsequently authorized commit, push, and production deployment. Include the two updated `.spec` documents explicitly in that commit while preserving the repository-wide ignore rule.
