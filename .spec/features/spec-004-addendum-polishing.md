# Spec 004 Addendum detail input and tab layout

Status: Approved for implementation by the user on 2026-11-04; implemented on `addendum-polishing`.
Date: 2026-11-04
Branch: `addendum-polishing`

## Purpose and scope

Make Addendum entry comfortable for financial users: accept decimal adjustments without disrupting typing, recognize dates entered without separators, and give shipment/payment corrections their own working area. This specification applies to `Addendum.detail`, opened from the Addendum list or with parent-contract context, including dialog presentation where supported. It does not redesign the Addendum list itself.

Extend [Spec 002](spec-002-detail-views.md) and retain the shared styling of [Spec 003](spec-003-css-styles.md). The three-tab arrangement below supersedes Spec 002's Addendum layout with general fields above two bottom correction tabs. Existing permission, validation, persistence, and navigation requirements remain applicable.

## Current behavior and evidence

The descriptor `src/main/resources/ru/stnovator/finassist/view/addendum/addendum-detail-view.xml` binds `amountAdjustmentField` to the decimal property and sets `valueChangeMode="EAGER"`. The controller refreshes calculated terms on relevant container property changes. Per-keystroke conversion and preview updates are investigation targets, not a confirmed root cause of the reported decimal-entry problem. Reproduce with actual keyboard input before choosing a fix.

`newEndDateField` is a property-bound date picker. The controller has no custom compact-date parsing. General fields and the terms hint currently appear above `correctionsTabs` in one scroller. Correction records open in detail dialogs and belong to the Addendum composition. The configured application locale is `ru`.

The entity stores `amountAdjustment` as `BigDecimal`, with database precision 19 and scale 2 and validation of at most 17 integer digits and 2 fractional digits. Both the amount adjustment and the new end date are optional. Negative adjustments are valid only if the resulting contract amount remains nonnegative.

## Proposed approach

Use three tabs across the whole detail body: **Общие**, **Отгрузки**, **Платежи** (General, Shipments, Payments). General is selected on initial opening. Keep a compact, read-only identity header and a shared Save/Close footer outside the tab content. Preserve current correction dialogs and composition ownership.

Alternatives considered:

| Approach | Benefit | Tradeoff |
| --- | --- | --- |
| Keep general fields above bottom correction tabs | Smallest layout change | Corrections still require scrolling past unrelated fields |
| Three tabs across the detail body — recommended | More correction workspace and clear separation of tasks | General fields are hidden while working on corrections; retain identity in the header |
| Inline editing of correction schedules | Fewer dialog transitions | Adds validation, keyboard, and unsaved-edit complexity beyond this polish |

Author proposals for review: accept both comma and dot decimal separators; interpret compact dates strictly as six-digit `DDMMYY`; commit typed values on blur, Enter, or Save; retain dialog-based correction editing. These choices were approved by the request to implement this specification.

## Decimal adjustment entry

1. Allow normal typing, paste, selection, replacement, backspace, and cursor movement without reformatting after each keystroke. In-progress text such as `-`, `12,`, or `12.` must remain editable and must not trigger repeated server conversion errors or reset the caret.
2. Accept an optional leading minus, integer input, and up to two fractional digits with either comma or dot as decimal separator. Trim surrounding whitespace. Accept correctly grouped thousands separated by ordinary, nonbreaking, or narrow nonbreaking spaces; reject malformed grouping. Do not interpret commas or dots as thousands separators.
3. Normalize only when committing input on blur, Enter, or Save. Use exact decimal conversion, never a floating-point intermediate. Display the committed value with the application's Russian decimal formatting; preserve its numeric value when reopening.
4. Blank means `null`; `0` means an explicit zero adjustment. Do not convert one into the other. A trailing separator with no fractional digits is incomplete at commit time and must be corrected or removed.
5. Reject mixed decimal separators, letters, scientific notation, excess fractional digits, and overflow with a localized field error. Never silently truncate, round, or save the previous value while invalid text is displayed.
6. Refresh the before/after preview after a valid committed change. Invalid input must not appear to have been applied: suppress the affected after-preview values and show a clear hint until corrected. A preview failure must not erase the user's input.
7. Save must commit the focused field and validate its current text before persisting. Enter commits a field without unexpectedly saving/closing the entire view. Preserve existing domain checks, including duplicate effective dates and a nonnegative resulting amount.

| Input | Expected committed value or result |
| --- | --- |
| `1234` | `1234` |
| `1234,56` or `1234.56` | `1234.56` |
| `1 234,56` | `1234.56` |
| `-5000,25` | `-5000.25`, subject to resulting-contract validation |
| `0` / empty | Explicit zero / null |
| `99999999999999999,99` | Exact value accepted by field precision rules |
| `100000000000000000` | Integer precision error |
| `12,345`, `1,234.56`, `1e3`, `-`, `12,` | Invalid at commit; no persistence |

## Separator-free end-date entry

Retain a calendar picker and normal localized date entry. Add compact entry for exactly six digits in day-month-year order. For example, `311226` becomes `31.12.2026` and `010227` becomes `01.02.2027` on blur, Enter, or Save. Provide localized help: `Введите ДД.ММ.ГГГГ или ДДММГГ (год 00–99 означает 2000–2099)`.

Use strict calendar validation. `290228` is valid; `290227`, `310427`, zero days/months, and incomplete strings are invalid. Do not roll dates into the next month, guess a missing year, interpret compact input as MMDDYY, or accept the previous eight-digit compact format. Two-digit years always map to 2000–2099 (`00` → 2000, `99` → 2099), independent of the current date. Dotted dates retain four-digit years and can represent other centuries. Trim surrounding whitespace; reject embedded whitespace in compact input. Use a date-only value without timezone conversion.

Partial text remains editable without errors on every keystroke. Invalid committed text stays visible with a localized error and blocks Save. Clearing the field commits null and restores the inherited end date in the preview. Choosing a date from the calendar replaces typed text and clears any parsing error. Dates before the contract start remain invalid under existing business rules.

Apply this behavior to `newEndDate` only. Extending compact-date entry to `effectiveDate` or other screens is outside this change.

## Detail layout and correction workflow

```text
Addendum identity: number · contract · customer
[ Общие ] [ Отгрузки (2) ] [ Платежи (2) ]
┌────────────────────────────────────────────┐
│ Active tab content                         │
│ General form OR correction toolbar/grid     │
│                                            │
└────────────────────────────────────────────┘
[ Save and close ] [ Close ]
```

The identity header reflects current edited values, wraps on narrow screens, and provides a neutral placeholder for an unsaved Addendum. It must not duplicate editable fields or disclose context the user cannot read.

**General:** contract, number, effective date, amount before, adjustment, amount after, new end date, resulting end date, customer context, and terms feedback. Keep the before/adjustment/after sequence visually clear. Preserve inherited contract context and the read-only contract on existing Addenda.

**Shipments and Payments:** each tab contains its existing correction toolbar and project correction grid. Show a localized empty state and a clear Create action. Display the count of current, nonremoved correction records, not the count of schedule items, in each tab caption. Counts update from unsaved composition changes as well as saved data. Existing Create/Edit/Remove permission and selection rules remain in force.

Changing tabs never saves, cancels, reloads, or discards changes. Invalid general input may remain while the user views corrections; mark General as containing an error after validation. Save from any tab validates the entire Addendum and its compositions. If an error is in another tab, activate that tab and focus the first failing control; open the relevant correction editor when necessary to expose a child error. Errors must not remain hidden behind an inactive tab.

Keep correction editors bound to the same parent DataContext. Closing a child with Save stages its edits in the parent; cancelling the parent discards those edits. Preserve project eligibility, existing filtering, ordering, totals, and correction application semantics. Existing prerequisites for creating corrections continue to apply, with a clear explanation when required parent context is missing.

Give the active tab the available body area. Large grids scroll within a bounded area; empty/sparse grids avoid unnecessary blank space. Keep the shared footer visible without overlapping content. At narrow widths, all three tab labels remain reachable, full identities remain accessible, and the form stacks vertically. Preserve unsaved values, selected tab, and selected row when resizing. Tabs support standard keyboard navigation and visible focus; counts must not be the only accessible tab names.

## Acceptance and verification

1. Reproduce the reported amount-entry failure using real typing in the current view and record locale, input sequence, and observed behavior. Diagnose whether conversion, eager binding, or preview updates cause the failure.
2. Verify every decimal example above by typing and paste, including spaced grouping with all three supported space characters. Exercise cursor edits, negative values, clearing, immediate Save while focused, and valid/invalid transitions. Confirm exact round trips through persistence.
3. Verify compact, dotted, leap-year, invalid, incomplete, and blank dates, calendar selection after invalid typing, immediate Save, and reopen. No silent rollover or stale-value persistence is allowed.
4. Confirm that valid amount/date changes refresh the preview, invalid input is identified without losing text, and all existing contract-term constraints still block invalid saves.
5. Exercise all three tabs with 0, 2, and at least 30 corrections in isolated fixtures. Check count updates, selection/action state, retained edits across tab switches, and Save from a correction tab with an invalid general field.
6. Create/edit/remove a correction and its items, then cancel the parent and verify nothing was persisted. Repeat with Save and verify the complete composition was persisted. Preserve navigation back to the originating list and unsaved-change prompts.
7. Verify routed and supported dialog entry, with and without inherited contract context, at 1440×900, 1024×768, 768×800, and 390×844. The footer remains usable, grids remain bounded, and no page-level horizontal overflow occurs.
8. Verify keyboard-only use and existing restricted roles as well as admin. Check Russian captions, helpers, parsing errors, and accessible names; add translations for any additional locales enabled at implementation time.

Existing local `DEMO-UX-*` records support manual exploration: `DEMO-OG-1-2026` has sequential and future changes; `DEMO-UX-08-SCHEDULES` has two shipment and two payment corrections. Use disposable fixtures for destructive acceptance tests and larger collections, preserving the developer's demo data.

## Implementation boundaries

Expected changes: Addendum descriptor/controller, localized messages, scoped presentation/style adjustments, a read-only correction-comparison service, and focused parsing/binding/composition/comparison regression tests. No entity, schema, report, contract-term calculation, or permission redesign is intended. Avoid global datatype changes that alter input behavior in unrelated screens.

Use the project's Jmix skills and UI scaffolding guidance when implementing. Verify component and parsing APIs against Jmix 3.1.0 / Vaadin 25 and Java 21; do not assume examples for earlier framework versions apply. The implementer chooses the concrete component/parser and synchronization mechanism after reproducing the failure and verifying available APIs.

Close implementation with Jmix-aware static inspection, a terminating clean test run against an isolated datasource with context-load coverage, and real keyboard/pointer browser verification. Record the input and layout evidence. This specification is based on code inspection and the user's reported symptoms; it does not claim browser reproduction or implementation verification has already occurred.


## Implementation verification record

Implemented on 2026-11-04. The amount field keeps the Jmix entity value source and uses local conversion on commit. The end-date field keeps the calendar and property binding, uses strict client parsing, and preserves unparsable text against a null binding echo. Automatic calendar opening while typing is disabled; the calendar button remains available. No global datatype, entity, schema, or permission changes were made.

Browser reproduction before the fix: entering `1` in the focused eager amount field immediately reformatted it to `1,00`. Framework inspection confirmed successful client changes were formatted on every eager update.

| Changed artifact | Verification |
| --- | --- |
| `AddendumAmountField.java` | Java compilation, exact-decimal parser tests, real typing/Enter/blur and invalid-input browser checks |
| `AddendumEndDateField.java` and `frontend/addendum/compact-date.js` | Java compilation, Node calendar tests, compact/date-only entry, invalid text retention, clear, and calendar recovery in browser |
| `AddendumDetailView.java` | Java compilation, UI tests for preview, finance-manager editing, hidden-tab validation, counts, security-erased references and composition validation; independent review |
| `addendum-detail-view.xml` | XML parsing, message-reference audit, UI context/load tests and rendered three-tab view |
| `messages_ru.properties` | New keys resolve; Russian labels and errors inspected in browser |
| `detail-view.css` | Browser geometry across all three tabs at 1440×900, 1024×768, 768×800 and 390×844; footer visible and no page horizontal overflow |
| Java and JavaScript regression tests | Gradle compilation/execution and `node --test frontend/addendum/compact-date.test.mjs` |

The terminating clean test run passed 141 tests with zero failures/errors/skips using `finassist_addendum_test`, an isolated PostgreSQL database. A subsequent focused run passed all 7 Addendum UI tests, including the added nested-item error-routing regression. Static verification used compilation and descriptor/message checks; no Jmix-aware IDE inspection tool was connected.

Browser checks covered both decimal separators, all three grouping spaces, negative/zero/blank values, precision rejection, invalid Enter with preview suppression, compact dates, leap years, invalid calendar dates, immediate Save, and reopen. Immediate Save persisted `9876.54` and `2028-01-31` exactly. Calendar selection recovered from invalid text. A nested payment item remained unpersisted after dialog saves, was discarded when the Addendum was cancelled, and persisted as `123.45` on `2026-04-01` only when the Addendum was saved. Populated tabs were inspected with 30 shipment corrections and 2 payment corrections; grid heights stayed bounded at 344px and 128px respectively.

Browser writes were confined to disposable test fixtures; the local demo data was preserved. Browser testing used admin; finance-manager behavior and security-erased reference handling were checked in UI tests. The full browser matrix was not repeated under every restricted role or every supported dialog entry path.


Adjustment on 2026-11-04: compact end-date input changed to `DDMMYY` at the user’s request. Display and dotted input remain `DD.MM.YYYY`. The fixed 2000–2099 century rule avoids a moving year cutoff.

Six-digit adjustment verification: both Node parser tests passed. Real browser entry verified `311226`, `010227`, leap date `290228`, and year boundaries `010100`/`311299`; invalid dates and the superseded eight-digit compact form remain visible with errors. The temporary browser app was stopped after verification.

The subsequent isolated `clean test` run passed 142 tests with zero failures/errors/skips.


## Approved revision: informative correction grids (2026-11-04)

Both the Shipment and Payment tabs retain their existing entity-bound composition grids and Create/Edit/Remove dialogs. Replace creation/modification date columns with this project summary:

| Column | Content |
| --- | --- |
| Expand | Accessible button opens a read-only date comparison |
| Проект | Existing project caption |
| Сумма до → после | Exact schedule totals before this Addendum and in the current draft |
| Изменение суммы | Signed difference; zero remains visible |
| Последняя дата до → после | Maximum scheduled date on each side |

There is **no item-count column or item-count badge in the project row**. Tab captions continue to count project corrections, as already implemented.

Expanded details show `Дата | До | После | Разница`, ordered by date. Initially show only changed dates; `Показать весь график` reveals unchanged dates too. Aggregate duplicate dates with exact BigDecimal addition. A missing side is `—`, distinct from an explicitly scheduled zero; use zero only when calculating the difference. Display an unchanged message when no dates differ. Creation/modification timestamps appear below the comparison, subject to existing attribute permissions. No inferred links between individual items or claims of rescheduling are made.

### Baseline and draft rules

Compare each project and schedule type independently against the **latest nonempty correction strictly before the edited Addendum's effective date**, excluding the edited Addendum itself. If none exists, use the project's base schedule. Compare complete schedules, including scheduled dates before the Addendum's effective date; effective date selects the version, not individual rows. Future corrections are excluded. A correction of the other type does not reset this baseline.

This rule belongs to the Addendum editor. Existing report implementations currently use different selection rules; report behavior remains unchanged in this revision.

An empty current correction shows `Нет позиций` with no delta or end-date comparison. It is an unfinished draft, not an instruction to delete the entire previous schedule. Missing item dates/amounts show `Заполните дату и сумму` and suppress partial calculations. Missing context or inaccessible source data shows `Сравнение недоступно`; never expose protected values or present partial history as a complete baseline.

The footer sums before/after/difference across the displayed project corrections, not all projects in the contract. If any correction is empty, incomplete, or unavailable, suppress numerical footer totals and explain that rows need checking. An empty grid shows dashes. No count totals are introduced.

Refresh summaries and expanded details when child dialogs save into the parent DataContext, when corrections are created/removed or projects change, and when the effective date changes. These calculations must include unsaved changes. Parent Cancel still discards composition edits; calculation never saves data. Baselines are loaded in batches and cached for the current context; cell rendering performs no database queries.

### Additional acceptance checks

- Test precise totals, duplicate dates, added/removed dates, explicit zero, unchanged schedules, empty drafts, and incomplete drafts.
- Verify latest earlier nonempty correction, independent shipment/payment history, base fallback, current/future exclusion, and historical scheduled dates.
- Verify permission-limited data cannot leak into summaries or produce partial totals.
- Verify expandable details, changed/all toggle, footer totals, unsaved child-edit refresh, and parent cancellation in the browser.
- Both grids remain bounded and internally scrollable with large schedules and narrow screens; the Addendum footer remains accessible.


### Correction-grid verification record

Implemented on 2026-11-04. Compilation checked all changed Java artifacts (`ScheduleComparison`, `CorrectionComparisonService`, `CorrectionGridPresentation`, `AddendumDetailView`, and their tests). The Addendum XML parses and its message references, plus the presentation's new Russian keys, resolve. All changed files are nonempty and pass whitespace checks. No Jmix-aware IDE inspection tool was available.

Focused tests verified exact date buckets, explicit zero versus absent dates, empty/incomplete drafts, latest earlier nonempty baseline selection, base fallback, current/future exclusion, and independent schedule types. Security regressions reproduced and fixed JPQL-filter bypass through collection fetches and joined headers; the service now reads items and verifies referenced headers directly. The unsaved-edit UI test checks footer refresh after item amount changes and correction removal, and verifies cancellation does not persist the item.

Browser verification used isolated `finassist_addendum_test` fixtures, preserving the local demo database. Both grids rendered their new columns; a payment comparison showed `180,00 → 233,45`, delta `+53,45`, and the changed last date. Expanded rows correctly aggregated duplicate base dates, omitted the unchanged date by default, and displayed absent sides as dashes. The all-dates toggle revealed the unchanged date. Saving nested dialogs with an unsaved amount edit refreshed the open comparison to `180,00 → 240,00`, delta `+60,00`, retained the toggle, and updated individual date buckets. Removing an empty correction restored complete footer totals. Cancelling the parent and reopening restored `233,45` and both corrections; database checks confirmed the original `123.45` item amount remained.

At 1440×900, 1024×768, 768×800, and 390×844, the expanded grid remained bounded at 448px, the page width did not overflow, and the action footer remained inside the viewport. The 30-row shipment fixture remained bounded and scrollable. Creation/modification details moved out of the summary columns. Browser checks used admin; automated tests exercised restricted item/header policies. The temporary browser app was stopped. Independent review found no important defects or deferred findings.

Final verification: the isolated terminating `clean test` run passed **154 tests**, with zero failures, errors, or skipped tests. Both compact-date Node tests also passed.


## Approved revision: General before/change/after comparison

The user selected the desktop three-column design over a compact two-column alternative. The three-column design keeps the original value, editable change and calculated result horizontally aligned for each parameter.

Identification comes first: contract/customer context, Addendum number and effective date. Below it, columns `До ДС | Изменение | После ДС` contain two rows:

- Contract amount before, signed amount adjustment, calculated amount after.
- End date before, replacement end date accepting DDMMYY, resulting end date after.

Use the existing historical preview for both before values; effective date selects the applicable prior terms. Inputs retain their existing bindings, permissions, decimal/date parsing and validation. Before/after controls are read-only, visually quieter, and skipped in sequential keyboard input. The center column contains the two editable changes in amount-then-date order. Blank changes retain the original value and show `Без изменения` beside the result; invalid input suppresses results and unchanged hints. Missing context or a failed preview clears all preview values.

On containers at or below 52rem, stack each row in before/change/after order with explicit field labels; keep the amount comparison together before the date comparison. The footer and correction tabs retain their existing behavior. No entity, schema, calculation or permission changes.


### General comparison verification

The isolated terminating `clean test` run passed **155 tests**, with zero failures/errors/skips. The new UI regression verifies the previous end date from an earlier Addendum, effective-date changes, unchanged hints, invalid-input suppression and missing-context clearing. Existing decimal/date, security and composition tests remain green. Independent review found no important defects.

Static checks: `AddendumDetailView.java` and `AddendumTermsUiTest.java` compiled and passed the UI tests; `addendum-detail-view.xml` parsed and all message references resolved in `messages_ru.properties`; `detail-view.css` was checked in the running browser. Spec and plan Markdown were checked for whitespace, and all seven changed files are nonempty. No Jmix-aware IDE inspection tool was available.

Browser checks used the existing isolated `finassist_addendum_test` fixture. Verified the two aligned comparison rows, historical before date, `150000,25` input yielding `250 000,25`, compact `310327` yielding `31.03.2027`, unchanged hints after clearing inputs, invalid-text result suppression, keyboard traversal from adjustment to new date, and successful preview refresh after a tab round trip. Waited for the target tab to render before editing its fields. Unsaved edits were discarded through the parent Cancel flow.

Responsive checks covered 1440×900, 1122×800, 1024×768, 768×800 and 390×844. Browser measurements verified full visibility of a maximum-length adjustment and its resulting amount, no page horizontal overflow, and a visible action footer. The final stacking threshold is 52rem of usable container width, selected after reproducing amount clipping at the smaller initial threshold. Desktop comparison remains three columns above that threshold. The temporary test app was stopped; the developer's demo database was untouched.
