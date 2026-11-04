# Addendum polishing implementation plan

> **For agentic workers:** Use executing-plans to implement this plan task by task.

**Goal:** Implement Spec 004 input handling and whole-view tabs.
**Architecture:** Keep property-bound Jmix components and composition containers. Use an Addendum-specific decimal field with strict conversion; configure strict client date parsing on the existing calendar control. Validate General even when a correction tab is selected.
**Tech Stack:** Java 21, Jmix 3.1.0, Vaadin 25, PostgreSQL.
**Spec:** `.spec/features/spec-004-addendum-polishing.md`

## Global constraints

Preserve exact decimal values, Russian messages, permissions, parent DataContext ownership, and demo data. Work on the user-requested `addendum-polishing` branch. Do not commit unrelated working-tree changes. No global datatype replacement.

## Review focus

- Invalid text must never save the previous value.
- Inactive tab content must still be validated.
- Field security must survive component customization.
- Clearing input must preserve null versus zero semantics.
- Nested corrections must remain staged until parent save.

## Tasks

- [x] Establish baseline and isolated PostgreSQL test database; inspect component conversion and date APIs.
- [x] Add failing decimal parser tests for both separators, grouping, precision, null and malformed input; implement `AddendumAmountField.parse(String): BigDecimal` and bound field integration. Verify focused tests.
- [x] Add strict compact-date parser tests; configure the existing picker without changing other date fields. Verify invalid calendar dates and calendar selection.
- [x] Restructure descriptor into General, Shipment, Payment tabs with shared identity/footer; add localized counts and hidden-tab validation routing. Extend Addendum UI tests for invalid saves, tab retention, counts and restricted-role binding.
- [x] Run compile, focused tests, full terminating clean test, descriptor/message checks, browser input/layout walk, and independent final review. Record limitations accurately.

## Execution record

Implementation authorized by the user's request to implement Spec 004. Execute inline; no separate plan approval is needed. Existing dirty files belong to earlier work and remain untouched.


Final verification: clean test passed 141 tests (zero failures/errors/skips); Node parser test passed. Browser input, persistence, composition cancel/save and viewport checks passed on the isolated PostgreSQL database. Independent reviewer found no remaining important issue after fixes. Existing generated/frontend and configuration changes from earlier work were preserved.

Rulings: use GenericComponentLoader for locally specialized bound fields so Jmix attribute security remains active. Install date parsing after the attachment-time connector update. Preserve invalid date text by suppressing only an unparsable-null binding echo. Validate correction compositions explicitly, skip security-erased attributes, and reveal nested item errors through secured composition dialog builders. Keep commits for the user to review; no merge or push performed.

Final focused run: all 7 AddendumTermsUiTest tests passed, including invalid nested-item editor routing and security-erased references. The final empty-state fix was browser-verified. Temporary app stopped.
