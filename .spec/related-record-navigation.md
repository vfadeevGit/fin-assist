# Related-Record Navigation Specification

## Status and Source

- Status: draft for review; implementation is not part of this change.
- Source: [GitHub issue #8 — Add related-record navigation to customer, contract, and addendum lists](https://github.com/vfadeevGit/fin-assist/issues/8).
- Date: 2026-10-28.
- The issue provides the original context and acceptance criteria. Subsequent user instructions supersede its original relationship scope: Customer → Contracts, Contract → Projects, and Project → Combined base schedule (only in the Projects list). Expandable rows with inline related grids are the user-confirmed interaction, based on the supplied CRM screenshot.

## Purpose

Finance users need to reach records related to a customer or contract, and review a project's planned shipments and payments, by expanding its row without losing their place in the originating list. The feature must work consistently for empty, small, and large collections and respect the current user's permissions.

## Scope

| Source list     | Expanded record | Related records                                              | Detail view opened      |
| --------------- | --------------- | ------------------------------------------------------------ | ----------------------- |
| `Customer.list` | Customer        | Contracts whose `customer` is the expanded customer          | `Contract.detail`       |
| `Contract.list` | Contract        | Projects whose `contract` is the expanded contract           | `Project.detail`        |
| `Project.list`  | Project         | Base shipment and payment items aggregated by date           | None; read-only summary |

The project expansion is a read-only combined schedule table, not a list of individual entities to open.

The feature adds navigation to existing data. It does not change ownership, correction contents, report calculations, or existing CRUD behavior.

## Existing Application Context

- Controllers are in `src/main/java/ru/stnovator/finassist/view/`, under `customer`, `contract`, and `project`.
- Corresponding XML descriptors are in `src/main/resources/ru/stnovator/finassist/view/`.
- All three source list views extend `StandardListView` and already provide a generic filter and pagination with URL query parameter support.
- The source list fetch plans do not load entire related collections. This must remain true.
- `Project.contract` is a mandatory many-to-one relationship. A project belongs to one contract, so Project → Contract is explicitly excluded from row expansion. Contract projects are resolved directly through this relationship, independently of addenda and corrections.
- `FinanceManagerRole` defines planning-entity permissions and list-view access. Any required view policies must be checked explicitly during implementation.
- Existing UI tests and fixtures are under `src/test/java/ru/stnovator/finassist/view/` and `test_support/`.

## Reference Implementation

The user supplied `AIR_Screenshot_2026-10-28_11-57-21.png`, showing a customer row expanded to reveal an **Orders** heading and a nested table. Use this layout and interaction as the reference, without copying the CRM dashboard cards or unrelated filters.

Local reference repository: `~/IdeaProjects/jmix-crm`:

- `src/main/resources/com/company/crm/view/client/client-list-view.xml`: a frozen, non-sortable `itemDetails` column before the business columns.
- `src/main/java/com/company/crm/view/client/ClientListView.java`: supplies the expander renderer, installs `setItemDetailsRenderer(...)`, and sets `setDetailsVisibleOnClick(false)`.
- `src/main/java/com/company/crm/app/util/ui/renderer/CrmRenderers.java`: `itemDetailsColumnRenderer(...)` toggles row details with right/down chevrons; `clientDetails()` creates a heading and a nested `DataGrid` with striped rows and bounded height.

Adapt this item-details pattern to the project's Jmix 3.1.0 version. The reference populates its nested grid from `client.getOrders()`; FinAssist must instead load related records on demand with bounded pagination. Use a focusable button for the chevron rather than copying the reference's mouse-only icon listener.

## Expandable-Grid Interaction

### Row expansion

Add a narrow, frozen, non-sortable chevron column before the business columns of each source grid. It shows a right-pointing chevron when collapsed and a down-pointing chevron when expanded.

- Activating the chevron expands or collapses a full-width details area immediately beneath that parent row. Expansion opens no dialog and does not navigate away.
- Expansion is independent of row selection. Users do not need to select a parent first; clicking its chevron must not alter selection or trigger editing. Ordinary row clicks retain existing selection behavior and do not toggle details.
- All rows start collapsed. Multiple rows on the current parent page may be expanded independently; expanding another row does not collapse the first.
- Show an expander only where the user has permission to read the related entity type; for project schedules, apply the side-specific access rules below. Do not preload related records or counts to decide whether an authorized row is empty.
- Hide the expander when the source list is used as a lookup for another field. Normal lookup selection remains unchanged.
- Changing the parent filter, sort, or page collapses the expanded rows and releases their child data. Merely selecting a different parent does not reset expansion.

### Inline related grid

The details area contains a localized relationship heading and a nested grid, aligned and indented as in the screenshot. It remains inside the source grid, above the next parent row.

| Parent list | Heading       | Child columns                                                       |
| ----------- | ------------- | ------------------------------------------------------------------- |
| Customers   | Contracts     | Contract internal number (link), start date, end date, total amount |
| Contracts   | Projects      | Project name (link), line of business                               |
| Projects    | Base schedule | Date, planned shipments, planned payments; totals footer            |

- Respect attribute permissions for all displayed values and links.
- Apply an immutable parent-ID constraint before the first child load. Each expanded row has independent child data and pagination; switching a child page must not change the parent page or another expanded row.
- Default child page size: 50 records for relationship grids or 50 distinct dates for the combined schedule; enforce a finite page size for every request. Use a bounded-height child grid with scrolling and pagination beneath it so a large collection does not make the parent row grow indefinitely.
- Use compact striped rows and a relationship heading. No separate related-record toolbar, modal browser, generic child filter, create/remove/report actions, or recursive expanders are required. Existing parent filters and standalone list actions remain available.
- Default ordering: contracts by `startDate desc, internalID asc, id asc`; projects by `name asc, id asc`. Preserve a unique final tie-breaker when sorting to keep pagination stable.
- Show a localized loading state while a page is being fetched. With no visible results, show a localized neutral empty state such as “No related records available.” Do not distinguish absence from records hidden by permissions.
- A load failure shows a localized inline error and retry action; it must not be presented as an empty collection.
- Collapsing a row releases its loaded child components/data. Re-expansion reloads the first page from persisted data. Any late load completion for a collapsed row must be ignored.

### Opening a related record and returning

For Customer → Contracts and Contract → Projects, activating a child identifier/name link navigates to its existing detail view in the same browser tab using `ViewNavigators`. This feature introduces no dialog windows, either for expansion or child-record opening. A keyboard-accessible link is required; double-click may be an additional shortcut.

- Normal detail-view edit permissions and unsaved-change handling apply; navigation grants no additional write access. If a user can read the child data but lacks detail-view access, display the identifier as plain text.
- Before navigating, retain the parent filter, sort order, page, selected record IDs, expanded parent IDs, and each expanded child grid's page and sort order. Also retain the initiating child ID and a scroll/focus anchor.
- Closing/saving the detail or using browser Back restores the source list and these states for the in-session navigation round trip. Restore focus to the initiating child link and bring its expanded parent into view where still available.
- Reload visible data on return so edits and permission changes are reflected. If the child disappears, keep the parent expanded and focus its chevron; if a child page becomes empty, move to the last available page.
- If the parent no longer exists or matches the source filter, omit its expansion, clear any stale selection, and focus the parent grid. Repair an empty parent page by moving to the last available page.
- Browser reloads, bookmarks, and cross-session restoration of expansion are outside scope. Retained state should contain identifiers and UI settings, not cached unrestricted entity data.
- Recheck source and target access when loading/opening. If either is deleted or becomes inaccessible, show a neutral localized unavailable message and leave the source list usable without exposing stale details.

## Relationship Rules

1. Customer → Contracts includes only non-deleted, permitted contracts whose `customer.id` matches the expanded customer ID.
2. Contract → Projects includes only non-deleted, permitted projects whose `contract.id` matches the expanded contract ID. Include projects even when they have no addenda or corrections.
3. Missing, deleted, or inaccessible related data produces the same neutral empty state. If the relationship attribute itself is not readable, do not resolve or expose the related record.
4. Addendum effective dates, correction headers/items, and report modes do not affect membership in either grid.
5. After a project is reassigned to another contract, refreshing or returning to the list must show it under the new contract and remove it from the previous contract's projects.

## Combined Project Schedule

### Placement and presentation

Only the standalone `Project.list` exposes this expansion. Project rows nested beneath a contract remain ordinary linked rows with no further expander. Expanding a project in the Projects list displays one compact, read-only table headed **Base schedule**, with **Date**, **Planned shipments**, and **Planned payments** columns and a totals footer. It follows the same chevron, loading/error, collapse, accessibility, and independent pagination behavior as the other expansions.

The model has one optional `ShipmentSchedule` and one optional `PaymentSchedule` per project, each with dated amount items. Display their items as a combined timeline rather than displaying schedule headers. Aggregated rows have no detail link, edit action, or double-click navigation because a row may represent several source items.

### Calculation rules

- Use non-deleted, permitted `ShipmentScheduleItem` and `PaymentScheduleItem` records whose `schedule.project.id` matches the expanded project. Check access to the project, schedule headers, item records, and required relationship/date/amount attributes.
- Use base schedules only. Do not select active addenda, read correction items, apply report-date rules, or recalculate contract amounts.
- Form the distinct union of dates from both visible item sets. Show one row per date, ordered by date ascending. Date order is fixed in this first version.
- For each date, independently sum shipment amounts and payment amounts using decimal arithmetic. Aggregate before pagination; never page the two item types separately and merge those partial pages.
- Show an em dash for a side with no visible items on that date. Show a numeric zero when visible items exist and their amounts sum to zero. Preserve negative amounts if present in persisted data.
- Show totals for all visible matching items of the expanded project, across all date pages, rather than just the displayed page. Each side's total is the sum of its own amounts. An available side with no items totals zero.
- Include all stored dates, past and future; this version has no date-range filter or cumulative-balance column. Format dates and monetary values using the application's locale and existing amount formatting.
- If one schedule is absent or empty, show dates and amounts from the other side. If neither side has visible items, show the neutral localized empty state; do not add an artificial zero row.
- Hide the project expander if neither side is readable. If only one side's required entity/attribute access is available, show the readable side and mark the other column and total as unavailable, without emitting dates, amounts, counts, or existence information from it. Row-level hidden data never contributes to dates or totals; absence and row-level restrictions are not distinguished.

### Example

Given shipment items of 100 and 50 on October 1, a payment of 80 on October 1, and a payment of 70 on October 5, the combined table shows:

| Date      | Planned shipments | Planned payments |
| --------- | ----------------- | ---------------- |
| October 1 | 150               | 80               |
| October 5 | —                 | 70               |
| Total     | 150               | 150              |

### Loading and refresh

Load the first date page and totals only on expansion. Use bounded queries that select a page of distinct visible dates and aggregate amounts for those dates, plus authorized totals queries; do not materialize all schedule items or all dates in memory to paginate. Count distinct dates across both sides if the paginator needs a count. A single date may have many items, so bounding dates alone is insufficient if raw items are then loaded without aggregation.

Implementation note: ordinary schedule pages use date cursors and database aggregates. Where item row-level policies apply, secured entity reads in fixed-size batches preserve policy behavior; totals must inspect all permitted items, so query work scales with schedule size while retained memory stays bounded.

Do not assume scalar/aggregate queries enforce the same permissions as entity loads. The service must explicitly preserve entity, attribute, and row-level restrictions for headers and items throughout date selection, sums, and counts. Verify restricted-user results against permitted source records.

Re-expansion reloads persisted data. When returning from editing the project through its existing edit action, restore expansion/page state and refresh the schedule and totals; if the page no longer exists, move to the last available date page. Late results for collapsed or replaced rows must not overwrite another project's table.

## Data Loading and Security

- Keep relationship resolution in a service with constructor injection. Controllers handle row expansion, inline rendering, detail navigation, and restoring UI state.
- Use authenticated Jmix `DataManager` access and parameterized queries; do not use `EntityManager`, unrestricted loading, or elevated authentication.
- Apply entity, attribute, view, and row-level permissions to source and target records, including the relationship attributes. Read permission alone must not bypass detail-view permission checks.
- Counts, if exposed by pagination, must use the same relationship and permission scope as visible rows. No hidden record names or counts may leak through titles, messages, or navigation controls.
- Load only the requested page and the attributes needed by its columns and instance names. Do not traverse `Customer`/`Contract` collections to build choices in memory.
- Do not load addenda or correction data to resolve these relationships, or fetch all project IDs and paginate only afterward.
- Use database-level pagination with no duplicate result rows, with bounded queries per page rather than one query per displayed row. Resolve secure query details during implementation and verify them with restricted-user integration tests.
- Load child records only when their parent is expanded or their child page changes. Initial parent-list loading must not query each row's relationships. Re-expansion reloads persisted data; do not retain an unbounded cross-view cache of related entities.
- No new persistent entities or schema changes are expected. Index changes require query-plan evidence during implementation.

## Localization and Accessibility

All action captions, context headers, empty states, errors, and accessibility labels must use message keys. Add them to every locale bundle present at implementation time; the repository currently has `messages_ru.properties`.

Suggested Russian headings: **Договоры** for customer expansions, **Проекты** for contract expansions, and **Базовый график** for project expansions. Schedule columns: **Дата**, **План отгрузки**, **План оплаты**; footer: **Итого**. Final wording and key names may follow existing project conventions.

Users must be able to focus a row's chevron, expand/collapse it, page through related records, activate a child link, and return using only the keyboard. Use standard accessible Jmix/Vaadin controls and visible focus indicators. The chevron button must expose a localized accessible name and expanded/collapsed state (`aria-expanded`), and identify its controlled details region. Enter or Space toggles expansion; Tab reaches the child links and pagination. If a region collapses while focus is inside it, restore focus to its chevron. Each child grid needs an accessible name identifying its relationship and parent using permitted attributes.

## Acceptance Scenarios

| Scenario                                                                             | Expected result                                                                                        |
| ------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------ |
| Expand a customer with multiple contracts                                            | Its contracts appear inline directly beneath the customer; no dialog or route change occurs            |
| Expand a contract with multiple projects                                             | Only projects belonging to that contract appear inline, including projects without corrections         |
| Expand a project in the Projects list                                                | One combined Date / Planned shipments / Planned payments table appears inline                          |
| View projects nested beneath a contract                                              | No schedule expander is present                                                                        |
| Multiple shipment/payment items share a date                                         | One row shows independent sums for that date                                                           |
| Dates exist on only one side, or amounts sum to zero                                 | Missing side displays a dash; an actual zero sum displays zero                                         |
| Schedule spans more than 50 distinct dates                                           | Dates page without splitting aggregates; totals cover every permitted date                             |
| One schedule is absent or inaccessible                                               | Available data remains visible; restricted values and dates are not disclosed                          |
| Both schedules have no visible items                                                 | A neutral empty state appears without artificial rows                                                  |
| Related record is deleted or restricted, or its relationship attribute is unreadable | No inaccessible related data is exposed                                                                |
| Reassign a project to another contract, then return or refresh                       | The project appears under its new contract and no longer under its previous contract                   |
| Expand an unselected parent, then select another row                                 | Expansion works independently of selection and remains open                                            |
| Expand two parents, then collapse one                                                | Both may be open; collapsing one leaves the other's state intact                                       |
| No visible related records                                                           | The expanded region shows the neutral empty state and remains collapsible                              |
| Customer contracts or contract projects exceed 50 rows                               | Child paging is bounded and stable, without duplicates or incorrect totals; parent paging is unchanged |
| Change the parent filter, sort, or page                                              | Expansions collapse and old child data is released                                                     |
| Collapse during loading, then re-expand                                              | Stale results are ignored; a fresh first page loads for the correct parent                             |
| Activate a child link and return via save, close, or browser Back                    | Detail opens in the same tab; parent state, expansions, child pages, and focus are restored            |
| Save a record so it leaves the result set                                            | Refreshed grids omit it and repair stale focus, selection, and empty pages                             |
| Access is revoked or data is deleted while a row is expanded                         | Further open/load is denied safely without stale detail disclosure                                     |
| User lacks related-entity read access                                                | Expander is unavailable and invoking the handler cannot bypass permissions                             |
| User can read a child but lacks detail-view access                                   | Permitted data is visible but the identifier is not an active link                                     |
| Keyboard-only interaction and each configured locale                                 | All three expansions work with accessible states and localized labels                                  |
| Ordinary standalone lists and lookup dialogs                                         | Existing CRUD, reporting, and lookup behavior remain unchanged                                         |

## Implementation Validation

- Schedule integration tests cover same-date aggregation, disjoint dates, zero/negative amounts, one or both schedules missing, more than 50 dates, totals across pages, cross-project isolation, soft deletion, and entity/attribute/row-level restrictions on either side.
- Integration tests cover both direct relationship predicates, projects without corrections, pagination, soft deletion, project reassignment, and entity/attribute/row-level permissions. Include hidden projects and projects belonging to another contract to verify that neither appears in the expanded result.
- UI tests cover all three inline expansion paths, empty/loading/error states, selection independence, multiple expanded rows, collapse/reload behavior, child pagination, same-tab detail navigation, return-state preservation, and unavailable-record handling. Assert that expansion opens no dialog and does not change the route.
- Project UI tests verify that schedule rows have no navigation/edit action, only the standalone Projects list offers schedule expansion, and refreshed totals/page state reflect saved changes.
- Verify keyboard navigation and focus restoration in a running browser, plus localized rendering.
- Inspect query behavior with a fixture larger than one page to confirm bounded loading and no per-row relationship queries.
- Check modified implementation files with JetBrains file inspections and run `./gradlew test` before declaring the feature implemented.

## Non-Goals and Remaining Decisions

Schedule expansion inside the Contract → Projects grid, Project → Contract expansion, addendum-list expansion, Contract → Addenda expansion, an affected-project column, record counts on source rows, bulk navigation, report changes, new permission grants to restricted users, and correction editing workflows are outside scope. Existing addendum views and existing CRUD behavior remain unchanged.

The expandable-grid interaction, the two relationship grids, and the combined schedule shown only in the Projects list are confirmed by the user. Base schedules are the initial data basis proposed in the design discussion; addendum-adjusted schedules are outside this version. Multiple independent expansions, a 50-row page for collection relationships, and same-tab child-detail navigation are specified design defaults. Component extraction, service names, message keys, and the secure query/state-restoration implementation are left to the builder within the requirements above.
