# Detail Views Implementation Plan

> **For agentic workers:** Use executing-plans to implement task by task.

**Goal:** Implement Spec 002 and verify all 14 editors at four viewport sizes.
**Architecture:** Retain StandardDetailView and current data containers. Use shared descriptor classes and a small UI presentation helper for repeated grids, metadata, and initial validation presentation; targeted controller fixes retain framework action state and typed values.
**Tech Stack:** Jmix 3.1.0, Vaadin 25, Java 21, Gradle, PostgreSQL, Playwright.
**Spec:** `.spec/features/spec-002-detail-views.md`

## Global Constraints
- Preserve routes, bindings, permissions, compositions, navigation and save/cancel behavior.
- Do not alter entity types or schemas or edit generated frontend output.
- All persistence tests and browser writes use a disposable database.
- Check 1440×900, 1024×768, 768×800 and 390×844.

## Review Focus
- Selection disappears before an Edit action: safe no-op, no mutation.
- New form untouched vs invalid Save: neutral initially, constraints active on save.
- Nested composition cancel: child changes never escape parent context.
- Long names, 30 rows, and resize: readable context, bounded grids, visible footer.
- Null and fractional payment days: preserve null, reject fractional values without rounding.

### Task 1: Behavioral regressions
**Files:** ProjectDetailView.java, ContractDetailView.java, new DetailViewBehaviorUiTest.java under the existing view test package.
**Interfaces:** Preserve component IDs; presentation changes will preserve those IDs.
- [x] Create disposable database and run baseline suite.
- [x] Add UI tests for project action state/no-selection, pristine new-form validation, and payment-day formatting/fraction rejection. Run and confirm failing assertions before fixes.
- [x] Verify framework APIs using pinned source jars; fix project action enablement and whole-day parsing in the existing controllers.
- [x] Confirm targeted tests pass; validation presentation will be integrated in Task 2.

### Task 2: Shared editor presentation
**Files:** all 14 detail descriptors; corresponding controllers where needed; new view/support/DetailViewPresentation.java; frontend/themes/fin-assist/view/detail-view.css; styles.css; messages_ru.properties.
**Interfaces:** `.detail-editor`, `.detail-content`, `.detail-actions`, `.detail-form-compact`, `.detail-form-single`, `.detail-grid`, `.detail-toolbar`, `.detail-context` classes. Retain form, detailActions and existing component IDs.
- [x] Wrap each editor body in a scroller; keep footer separate; apply bounded compact form and dialog styles.
- [x] Size small collection grids by row count up to a bounded maximum, preserving scrolling/virtualization and totals. Use shared UI helper only for repeated behavior.
- [x] Group project/correction actions and shorten Addendum tabs. Remove empty Addendum button. Add localized empty states.
- [x] Replace audit date pickers with collapsed metadata; readable wrapping parent context; localized correction-item parent context and missing-parent guard.
- [x] Fix initial validation presentation using the diagnosed framework mechanism without removing save validation.
- [x] Apply consistent save captions, timezone and amount/date labels, customer and user grouping. Validate XML/message references and compile.

### Task 3: Verification and correction
**Files:** focused UI tests, browser evidence/verification record under `.spec/features/`.
**Interfaces:** all prior behaviors and styles.
- [x] Run terminating clean test against the disposable datasource, inspect every failure and counts.
- [x] Start the application on the disposable datasource; use Playwright to test all editors at the matrix sizes, nested dialogs, invalid save and valid saves/cancel, 0/2/6/30 rows and permission-sensitive actions.
- [x] Fix discovered issues and repeat only affected checks, then run final suite if code changed.
- [x] Request fresh code review against the full diff and spec; resolve important findings.
- [x] Record exact verification outcomes and limitations; stop owned processes and keep branch ready for user review.
