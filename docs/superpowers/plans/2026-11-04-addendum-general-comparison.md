# Addendum General comparison implementation plan

> **For agentic workers:** Use executing-plans to implement this plan. User approved the three-column design and requested implementation in this session.

**Goal:** Show Before → Change → After for contract amount and end date on General.
**Architecture:** Retain existing entity-bound inputs and read-only preview controls; reorganize the descriptor with scoped responsive CSS. Use the existing ContractTermsService preview to populate the newly visible before-date field. No persistence or calculation changes.
**Tech Stack:** Jmix 3.1.0, Vaadin 25, Java 21.
**Spec:** `.spec/features/spec-004-addendum-polishing.md`, approved three-column revision.

## Constraints and review focus
- Keep decimal input and DDMMYY parsing, permissions, validation and tab behavior.
- Effective date determines the historical before values; missing context clears previews.
- Empty changes show unchanged results; invalid input suppresses results and unchanged hints.
- Desktop aligns both rows across three columns; narrow containers stack each comparison in reading order.
- Preserve existing dirty work on addendum-polishing; leave changes uncommitted.

## Task: General comparison
- [x] Extend AddendumTermsUiTest to verify historical end date, effective-date changes, unchanged hints and missing-context clearing.
- [x] Run the focused test and observe failure before implementing the new field.
- [x] Modify addendum-detail-view.xml, AddendumDetailView.java, messages_ru.properties and detail-view.css. Identification comes first, then aligned amount/date comparison rows with editable center cells.
- [x] Update Spec 004 with the approved layout and behavior.
- [x] Run focused tests, static XML/message checks, isolated clean test and browser checks at desktop and narrow widths. Verify decimal/date entry, tab switching, invalid input and cancellation.

## Verification so far
- New historical-date regression failed because `endDateBeforeField` did not exist, then passed after implementation.
- Focused Addendum UI tests passed. Full isolated `clean test`: 155 tests, zero failures/errors/skips.
- XML parsing, localized message-reference audit and whitespace checks passed.
- Independent review found no important issues. Browser verification completed.

- Browser verified input/preview behavior, keyboard order, tab round trip, cancel and five viewport sizes. Increased stacking breakpoint to 52rem after measuring maximum-length result clipping at compact widths; final values fit at all checked widths.
- Temporary app stopped. Changes remain uncommitted on addendum-polishing.
