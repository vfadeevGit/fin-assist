# Spec 002 — Detail View UI Polish

Status: Implemented and verified; accepted by the user on 2026-10-28.

## 1. Purpose

Make the existing detail editors consistent, readable, and usable when the browser or a dialog is resized. Fix the concrete usability defects found in the Playwright audit while preserving business workflows and persisted data.

The user requested a specification based on the reported findings. The requirements below include proposed design decisions made by the author; they are not a record of individually approved visual choices.

## 2. Workspace and Evidence

- Worktree: `/private/tmp/finassist-detail-views`; branch: `ui/detail-views`.
- Base: `d4ca2e4` (`chore/jmix-3.1-upgrade`). Platform: Jmix 3.1.0, Java 21, Vaadin 25.
- `.spec/features` in the worktree links to the original checkout's shared feature specifications. New specifications are ignored by Git under the existing project policy.
- Uncommitted application changes in the original checkout are not part of this baseline. Its current `AGENTS.md` instructions were copied into the worktree.
- Audit: 2026-10-27, local application, Russian locale, admin account, Playwright. All 14 detail views were opened. Main page layouts were checked at 1440×900, 1024×768, 768×800, and 390×844; nested dialogs were also inspected at desktop and narrow widths.
- Existing demo data was used for customers, contracts, projects, schedules, users, and business directions. Addenda/corrections were inspected as unsaved new forms and nested dialogs because no persisted addenda were available. This does not establish that populated correction workflows already work.
- The temporary application is stopped. No business records were saved during the review. Startup applied pending framework migrations to the local development database; that database is not an untouched test fixture.

Representative screenshots are temporary local evidence, not permanent specification assets:

- Project mobile: `/private/tmp/audit-projects-390.png`
- Addendum desktop: `/private/tmp/audit-addenda-1440.png`
- Shipment correction laptop: `/private/tmp/audit-shipment-schedule-corrections-1024.png`
- Shipment correction item dialog: `/private/tmp/shipment-correction-item-dialog-390.png`
- Payment schedule laptop: `/private/tmp/audit-payment-schedules-1024.png`

## 3. Scope and Boundaries

All 14 existing detail views are in scope. Apply shared requirements consistently, with view-specific changes below. Cover full-page routes and existing nested-dialog entry paths.

Preserve routes, view IDs, container bindings, composition ownership, permissions, relationship filtering, totals, date ordering, correction application, and save/cancel semantics. Preserve existing return navigation. Do not silently save a parent when opening a child.

No schema migration, entity type change, new business workflow, list-view redesign, main-view redesign, or framework upgrade is planned. Existing user-facing functionality must remain available. App-wide changes to validation or action captions are outside scope unless they are proven necessary and their wider impact is reviewed.

## 4. Design Approach

Three approaches were considered while composing this draft:

1. Individual descriptor fixes: small initial edits, but repeated spacing and resizing rules would continue to diverge.
2. Shared detail-layout conventions with targeted controller fixes: consistent layout and action placement, while retaining existing Jmix editors and bindings. **Recommended approach in this draft.**
3. Replace the editors with a new UI architecture: substantially larger regression surface without a demonstrated need.

Use shared semantic theme classes and existing framework components. Do not introduce a new base controller or fragment solely for visual uniformity. The implementer may extract a genuinely repeated structure where this reduces complexity without changing data ownership. Verify all APIs, descriptor attributes, theme variants, and tokens against the actual Jmix 3.1 / Vaadin 25 dependencies.

## 5. Findings and Required Outcomes

| ID | Observed problem | Required outcome |
| --- | --- | --- |
| DV-01 | Project Edit is enabled with no selected row and opens an unexpected-error dialog. | Selection and permission-aware row actions; defensive handling of missing selection. |
| DV-02 | Contract actions start around y=965 at 1440×900; Project actions around y=871 at 1024×768. Other complex editors also push actions below the viewport. | Save/cancel remain visible within the page or dialog while content scrolls independently. |
| DV-03 | Sparse/empty tab grids occupy 400px and dominate the editor. | Content-aware grid sizing for small collections; bounded scrolling for larger collections. |
| DV-04 | Tab content adds about 32px inset beyond the form margin, leaving only about 294px for a mobile table. | Forms, toolbars, and tables share consistent left/right alignment with one content-padding layer. |
| DV-05 | Project toolbar spans up to four rows on mobile; correction actions wrap awkwardly at laptop width. | Separate collection-level and row-level actions, with compact responsive wrapping. |
| DV-06 | Long addendum tab captions hide the second tab; mobile grid headers become ambiguous. | Short, distinct tab labels and readable grid headers with deliberate narrow-screen behavior. |
| DV-07 | Long project/contract references are clipped, sometimes showing only their suffix on focus. | Full identity is available without editing the value or relying on hover. |
| DV-08 | Read-only audit fields consume full form rows, including empty date/time pickers. | Secondary metadata is compact and visually distinct from editable fields. |
| DV-09 | Addendum has an empty button between OK and Cancel. | Remove the unlabeled control; every remaining action has a meaningful accessible name. |
| DV-10 | Both correction-item forms expose raw message keys for the parent reference. User shows “Time zone” in Russian UI. | Complete localized labels and validation messages. |
| DV-11 | New addendum, correction, and correction-item forms show validation errors immediately on open. | Pristine forms show required indicators without error styling; validation remains effective on interaction/save. |
| DV-12 | Derived parent context looks editable or required even when it cannot be edited. | Clearly distinguish editable relationships from derived or inherited context. |
| DV-13 | Simple editors have oversized fields and weak grouping. | Compact bounded forms with related fields grouped together. |
| DV-14 | Payment days display as `15,00`. | Display whole-day values without decimal places while preserving the BigDecimal binding and database representation. |
| DV-15 | Generic “OK” captions and inconsistent action placement obscure the save behavior. | Explicit save-and-close caption and consistent footer action order. |
| DV-16 | Item dialogs have excess title-to-form spacing and inconsistent amount terminology. | Compact dialog spacing and consistent date/amount captions. |

## 6. Shared Layout and Interaction Requirements

### 6.1 Content and footer

- Use a bounded editor shell with a scrollable content area and a separate footer. The footer must not cover content, grid rows, validation messages, or keyboard focus.
- Footer order: primary **Сохранить и закрыть**, then **Отмена**. Use equivalent captions for every supported application locale. Apply captions to these editors rather than changing unrelated global actions.
- Preserve standard Jmix save, close, dirty-state prompts, and any existing keyboard shortcuts. In a composition dialog, saving continues to update the parent DataContext; the caption does not imply an immediate database commit.
- At the four target viewports, both footer buttons must be visible without scrolling the editor body. They may wrap inside the footer at narrow widths. The last content element must remain reachable above the footer.
- Keep dialogs within the viewport with a reachable title/close control and footer. Complex dialogs may use most available width; two-field dialogs should remain compact on desktop.
- Avoid horizontal scrolling of the whole page or dialog. A bounded grid may scroll horizontally when preserving useful column widths requires it.

### 6.2 Spacing and form sizing

- Proposed spacing baseline: 16px content padding, about 24px between semantic sections, and 8–16px between related controls, using verified theme tokens where available. A dialog's existing padding counts toward this budget; do not add a second layer.
- Use a shared compact form style with a proposed maximum width of 48rem for Customer and User, and 36rem for Business direction. Align these forms to the content start, not to the middle of a large empty page.
- Compact item dialogs should generally be about 28–32rem wide on desktop and shrink to available width. Avoid a large empty band between title and form.
- Forms may use two columns when each field remains useful; collapse to one based on available content width, including inside dialogs. Do not depend only on the browser width.
- Long project names should have a full-width field in the Project form. Long reference identities must also be accessible through wrapping supporting text or an equivalent focus/touch-accessible presentation. A hover-only tooltip is insufficient.

### 6.3 Collections, toolbars, and tabs

- Empty grids must present a short localized empty-state message and the applicable next action or prerequisite. Do not reserve a 400px blank rectangle.
- Small collections should size to their visible rows and header/footer. Bound larger collections to the available content area and retain virtualization/internal scrolling rather than rendering an unbounded list.
- Preserve totals and sorting. Numeric amount cells and totals should align consistently for comparison. Keep amounts readable; do not silently truncate significant digits.
- Column headers may wrap. On narrow screens prefer a deliberate grid scroll area over rendering several indistinguishable ellipsized headings. Do not remove columns or data solely to fit the viewport.
- Project: put schedule-level actions separately from item Create/Edit/Delete. Show Create schedule when the schedule is absent; show Remove schedule when it exists. Preserve the distinction between removing a schedule and removing a row.
- Corrections: group Fill from schedule and Clear separately from item Create/Edit/Delete. Preserve existing confirmation behavior. Do not use icons without accessible names to solve overflow.
- At 390px, each action group should need at most two rows; no button text may be clipped. Use a localized explanation when a prerequisite disables a whole group.
- Addendum tabs: use short captions such as **Отгрузки** and **Оплаты** under a visible **Корректировки планов** section heading. Both choices must be visible together at 390px. Preserve selection and content when resizing.

### 6.4 Action state and validation

- Project Edit/Remove enablement must require an existing schedule, a selected row, and the applicable framework/security permission. Avoid overriding framework action state with a schedule-exists check alone.
- Handlers must safely handle selection disappearing before invocation. Edit/Remove with no row must not open an error dialog or mutate data.
- Investigate why pristine forms validate on open before changing code. Suppress premature presentation, not the constraints or server-side validation.
- Show a field's error after relevant user interaction, or after a save attempt; untouched fields remain neutral until save. Attempting to save invalid data keeps the editor open, shows localized errors, and brings the first invalid field into view without hiding the footer.
- Automatically supplied relationships must retain their binding and initialization even if their editable field is replaced by a context display.

### 6.5 Metadata and localization

- Show derived customer/contract context as read-only text or clearly read-only fields without required markers or inactive picker affordances. Do not alter the metadata constraints themselves.
- Move creation/modification timestamps into a secondary, collapsed-by-default **Сведения о записи** section. Show formatted text, not disabled date/time pickers. Omit the section on an unsaved record with no metadata; use a localized empty value for an absent modification timestamp on an existing record.
- Both correction-item parent labels must be localized, for example **Корректировка плана оплат** / **Корректировка плана отгрузок**. Validation messages must never expose entity/property keys.
- User timezone label: **Часовой пояс** in Russian. Retain all existing timezone choices and behavior.
- Use consistent amount wording: **Плановая сумма оплаты**, **Плановая сумма отгрузки**, **Новая сумма оплаты**, **Новая сумма отгрузки**. Use matching planned/new date terminology in the corresponding forms and grids.

## 7. View-specific Requirements and Coverage

Descriptor paths below are relative to `src/main/resources/ru/stnovator/finassist/view/`. Controllers live in the corresponding packages under `src/main/java/ru/stnovator/finassist/view/`.

| View / descriptor | Specific work beyond shared requirements | Required states |
| --- | --- | --- |
| Customer — `customer/customer-detail-view.xml` | Name spans the compact form; INN and KPP grouped together below it. | New, existing; page and related-entity dialog where supported. |
| Business direction — `lineofbusiness/line-of-business-detail-view.xml` | Compact single-field editor with consistent footer. | New, existing; page and lookup/open path. |
| User — `user/user-detail-view.xml` | Group identity, personal information, preferences; localize timezone; preserve existing username/password visibility rules. | New with password fields; existing user; active and inactive states. |
| Contract — `contract/contract-detail-view.xml` | Group customer/number, dates, and payment terms; compact projects/addenda tabs; format days as whole numbers. | New/existing; both tabs; zero, few, and many child rows. |
| Project — `project/project-detail-view.xml` | Full-width name, readable parent context, grouped schedule controls, correct row-action state. | Both tabs; schedule absent/present; row selected/unselected; page and Contract → Project dialog. |
| Addendum — `addendum/addendum-detail-view.xml` | Remove empty button; shorten tabs; distinguish derived customer context; delay validation presentation. | New and populated; both tabs; direct/list and contract-context navigation. |
| Payment schedule — `paymentschedule/payment-schedule-detail-view.xml` | Compact parent context/metadata and responsive item grid. | Existing/new; empty/few/many items; total and ordering preserved. |
| Shipment schedule — `shipmentschedule/shipment-schedule-detail-view.xml` | Same conventions as payment schedule. | Same states as payment schedule. |
| Payment correction — `paymentschedulecorrection/payment-schedule-correction-detail-view.xml` | Group toolbar; compact context/metadata; delay validation. | New/populated, addendum/project dependencies, page and Addendum → Correction dialog. |
| Shipment correction — `shipmentschedulecorrection/shipment-schedule-correction-detail-view.xml` | Same conventions as payment correction. | Same states as payment correction. |
| Payment item — `paymentscheduleitem/payment-schedule-item-detail-view.xml` | Compact dialog, consistent amount/date wording. | Create/edit through Project and Payment schedule; existing direct route. |
| Shipment item — `shipmentscheduleitem/shipment-schedule-item-detail-view.xml` | Same conventions as payment item. | Create/edit through Project and Shipment schedule; existing direct route. |
| Payment correction item — `paymentschedulecorrectionitem/payment-schedule-correction-item-detail-view.xml` | Localized parent context, compact dialog, delayed validation. | Create/edit through parent; existing direct route; invalid save. |
| Shipment correction item — `shipmentschedulecorrectionitem/shipment-schedule-correction-item-detail-view.xml` | Same conventions as payment correction item. | Same states as payment correction item. |

In correction-item dialogs, display the inherited parent as read-only context rather than an apparently editable picker without actions. Existing direct routes remain viewable/editable for existing items. Creating orphan child records through a bare `/new` URL is not a new supported workflow: if no parent is supplied, show a clear localized instruction to open the editor from its parent and prevent saving until context exists. Do not add a parent-selection workflow as part of this polish.

For payment days, the existing property is BigDecimal and database scale is zero. Render `15` rather than `15,00`; preserve null and whole-number round trips. Reject fractional UI input with a clear message instead of silently rounding. Do not migrate or rewrite stored values.

## 8. Acceptance Criteria

1. **Coverage:** all 14 views open without runtime errors through the required entry paths. Existing records retain their values and relations after a no-change save.
2. **Responsive layout:** at 1440×900, 1024×768, 768×800, and 390×844, no fields overlap, no action labels are clipped, and no page/dialog-level horizontal overflow occurs. Resize an already open editor down and back up without losing input, selected tab, or selected row.
3. **Footer:** save/cancel remain fully visible while the body is scrolled to its top and bottom, including with validation messages and nested dialogs. The footer does not obscure the final field or table row.
4. **Collections:** verify 0, 2, 6, and at least 30 rows using isolated fixtures. Sparse grids no longer reserve 400px of empty space; larger grids remain bounded, scrollable, and retain totals, ordering, and selection behavior.
5. **Action state:** in both Project tabs, Edit/Remove are disabled without a selected row; enabled only when permitted with selection; clearing selection disables them again. No unexpected-error dialog occurs. Create/remove schedule states update without reopening the editor.
6. **Validation:** pristine new Addendum, Correction, and Correction-item forms show no red errors. Invalid save surfaces required errors and does not persist; correcting values clears the errors. Existing valid records do not become invalid because context is displayed read-only.
7. **Localization:** no raw message keys, blank buttons, or untranslated timezone labels remain. Every visible action has an accessible name. Check all configured application locales.
8. **Readability:** both Addendum tabs are identifiable at 390px; full project/contract identity is available via keyboard and touch; important grid values and headers remain accessible.
9. **Data integrity:** totals, schedule ordering, correction application, security restrictions, dirty-state prompts, and parent-child save/cancel behavior match the baseline. Cancelling an item or parent does not persist discarded changes.
10. **Presentation:** compact forms respect their width limits; INN/KPP are grouped; metadata is secondary; whole-day payment values display without decimals and round-trip correctly.

## 9. Implementation Boundaries and Verification

Expected artifacts: the 14 descriptors and affected controllers, shared theme styles/imports, existing message bundles, and focused regression tests. Read the project UI scaffolding skill plus the applicable Jmix detail-view, styling, localization, composition/dialog, fetch-plan, API-verification, and test skills before changing those artifacts. Preserve existing data-container ownership when restructuring layout.

The implementer owns exact CSS mechanics, verified component choice, content-width breakpoints, grid height calculations, and test organization within the measurable requirements above. Visual dimensions in this draft are starting constraints; deviations that change the specified behavior should be brought back for review.

Verification sequence:

1. Capture before-state reproductions for DV-01 and DV-11; diagnose their causes rather than hiding exceptions or disabling validation.
2. Run Jmix-aware static inspection on every changed file, including descriptors and message references. Verify framework symbols and computed theme styles against the active version.
3. Run terminating `./gradlew --no-daemon clean test` with actual context-loading tests. Use an isolated test datasource; do not run data-writing tests against the developer's working database.
4. Use Playwright for the acceptance matrix and real pointer/keyboard interaction. Browser-only component property mutation is not valid verification. Use a disposable datasource with seeded parent/child data for saves, deletes, corrections, and permission checks.
5. Compare desktop/narrow screenshots for every changed view and nested dialog. Record viewport, locale, entry path, fixture state, result, and any remaining limitation. Test keyboard traversal and focus visibility as well as pointer interaction.
6. Check existing permitted and restricted roles where actions or context presentation changed. Admin-only testing cannot prove permissions were preserved.
7. Keep generated frontend output out of the change. Stop only audit/test processes started by the implementer; do not rebuild outputs used by someone else's running application.

Automated regression tests should focus on the no-selection error, validation timing/invalid save, payment-day conversion, and any changed parent-context/save behavior. Do not add brittle tests for every spacing value; verify those through the browser matrix.

## 10. Review Status

This document replaced the initial stub and was approved for implementation by the user. Implementation and engineering verification are complete in `ui/detail-views`; final user visual approval is not implied. The proposed approach, footer behavior, compact-form widths, metadata presentation, shortened tab captions, and parent-context treatment are explicit author decisions for review. No product decision is currently left as an unexplained TBD.
