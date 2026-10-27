# CSS Styles Implementation Plan

> Execute inline using executing-plans. User approved Spec 003 and implementation on main.

**Goal:** Apply Spec 003 to the existing Lumo theme.
**Architecture:** Shared verified Lumo tokens, scoped public component parts, local Roboto assets. No business or descriptor changes.
**Tech Stack:** Jmix 3.1, Vaadin 25, CSS, Java 21.
**Spec:** Original checkout `.spec/features/spec-003-css-styles.md`.

## Constraints and review focus
Preserve density, semantics, existing user changes, and dark-theme appearance. Verify overlay inheritance, Cyrillic/fallback fonts, invalid versus focused fields, disabled actions, narrow layouts and selected rows.

## Tasks
- [x] Fast-forward main to the approved existing Jmix upgrade in an isolated checkout.
- [x] Package official Roboto WOFF2 assets with licence and font-display swap.
- [x] Implement shared colour/typography/shape tokens and scoped button, field, grid, dialog, navigation and login rules in the existing theme imports.
- [x] Parse all CSS, verify properties/parts against installed sources, check assets and contrast.
- [x] Run terminating clean test against disposable PostgreSQL; inspect actual test results.
- [x] Render representative screens and states; record evidence and limitations, review final diff.

No behavior tests added for CSS values. Existing integration tests plus browser checks cover regression risk. Original checkout remains on feature/main-view; all implementation resides on main at /private/tmp/finassist-css-main.

## Verification results
- 22 tests, zero failures/errors; terminating Gradle 9.8 clean test passed against the disposable PostgreSQL database finassist_css on localhost:55433. System Gradle used the same version as the project wrapper.
- All three changed CSS files parsed with PostCSS; git diff --check passed. Framework properties and public parts checked against installed Vaadin 25.3 / Jmix 3.1 sources. No IDE inspection connector was available.
- WOFF2 validated with FontTools: Cyrillic, Latin, rouble, numerals and 400–600 variable weights; unauthenticated font URL returned HTTP 200 font/woff2.
- Chrome desktop walk confirmed shell and Customers list render with the new palette, selected navigation, disabled actions, grid headers and Cyrillic typography. Payment schedules list also opened. No business data created during the browser walk.
- Independent CSS review found notification-button inheritance issues; all three button overrides now exclude notification-card descendants. Also corrected primary-button weight, field label colour and grid hover.
- Browser automation profile was occupied by another session. Native Chrome became user-active during the walk. Full responsive matrix, nested dialogs, login, computed-style and interaction-state measurements remain unverified; no full visual acceptance claimed.
- Generated frontend manifest churn restored; no business code or descriptors changed.
