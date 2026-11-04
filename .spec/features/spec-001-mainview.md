# Spec 001 — Main View as the Application Entry Point

Status: Requirements agreed; specification ready for review.
Branch: `feature/main-view`
Platform: Jmix 3.1.0, Java 21, Vaadin 25.3, existing Jmix Lumo theme.

## 1. Purpose

Make the home page a clear, enterprise-style entry point to Customers, Contracts, and Projects. Users should immediately understand how many records they can access and how to open each feature.

This specification records the decisions accepted in interview questions Q1–Q12. It replaces the original four-feature stub: Addendums are excluded from the dashboard, but remain available through the existing menu.

## 2. Scope

### Included

- Three informational count indicators, followed by three navigation tiles.
- Navigation to the existing Customer, Contract, and Project list views.
- Counts of accessible, non-deleted records.
- Permission-dependent visibility, loading, partial failure, and empty states.
- Refresh on home entry and return, plus a manual refresh action.
- Responsive styling, keyboard accessibility, and Russian localization.
- Preservation of the existing drawer, home route, account controls, and welcome notification.

### Excluded

- Addendums tiles or indicators.
- Financial sums, budgets, trends, charts, date ranges, and status filters.
- Creating or editing records directly from the dashboard.
- Changes to list filters, CRUD behavior, entity schemas, or role grants.
- Automatic polling or live updates while the user remains on the page.
- A replacement application theme or navigation system.

## 3. Existing Application

`MainView` extends `StandardMainView` and owns the root route. Its descriptor contains the application header, navigation drawer, account indicator, logout action, and an empty `initialLayout`.

The controller displays a welcome notification in `ReadyEvent`, guarded by `welcomeShown`. Dashboard refresh must be independent of this once-only guard.

| Feature | Existing Russian title | View ID | Route | Entity |
| --- | --- | --- | --- | --- |
| Customers | Клиенты | `Customer.list` | `/customers` | `Customer` |
| Contracts | Контракты | `Contract.list` | `/contracts` | `Contract` |
| Projects | Проекты | `Project.list` | `/projects` | `Project` |

All three lists use MainView as their router layout and support generic filtering and URL query parameters. Their source queries do not impose business filters. All three entities support soft deletion and have no active/archive status attribute.

`FinanceManagerRole` permits all three lists and entities. `UiMinimalRole` permits MainView without granting those permissions; access to the home page therefore does not imply access to its dashboard features. No source-defined row-level roles were found, but runtime roles must still be respected.

## 4. Layout and Visual Design

Use the existing main view's home content area. The dashboard appears after normal login to home and when returning home, including through the existing application-title home link. A direct link to another permitted view continues to open that destination. The dashboard must not remain above child list/detail views.

For a user permitted to access all three features:

1. A restrained page heading and an `Обновить` action.
2. A row of three compact count indicators, ordered Clients, Contracts, Projects.
3. A row of three larger navigation tiles in the same order.

Count indicators are informational and are not clickable. Each navigation tile has a semantic icon, title, short explanation, and an obvious opening affordance. The whole tile should provide one accessible navigation action; avoid nested interactive controls or duplicate keyboard stops for the same destination.

| Feature | Count label | Icon semantics |
| --- | --- | --- |
| Клиенты | Всего клиентов | People / customer group |
| Контракты | Всего контрактов | Document |
| Проекты | Всего проектов | Folder |

Reuse Lumo typography, color, spacing, and focus conventions. Use subtle borders, generous spacing, and restrained accents. Icons supplement readable text; color or icons alone must not convey meaning. Verify concrete icon constants against the installed Vaadin version during implementation.

On narrow screens, reduce the column count and ultimately stack cards without horizontal page scrolling. Preserve reading order and equal treatment of the three features. When permissions hide a feature, close the unused space rather than leaving a placeholder.

All actions must be keyboard operable and have visible focus. Loading and error states must have accessible text, and count updates should be announced without moving focus. Format counts as localized integers, without monetary symbols or decimal fractions.

## 5. Navigation Behavior

- Each tile opens its corresponding existing list in the same browser tab using Jmix view navigation.
- Navigation does not introduce dashboard-specific filter or pagination parameters.
- Do not clear or override the list's saved/default settings; retain the list's normal behavior.
- Totals are independent of filters selected in a list. A filtered list may show fewer records than its dashboard total.
- The existing drawer and Addendums menu entry remain available according to their current permissions.
- Preserve normal browser navigation and the existing home route.

## 6. Count Semantics and Security

A total is the number of records of the corresponding entity that the current user may read, excluding soft-deleted records. It is not a monetary amount. Include records regardless of contract dates, list filter state, or any inferred business activity status.

Show a feature's tile and indicator only when the current user has both permission to open the target list view and READ permission for the entity. Do not use role names or the presence of a menu item as substitutes for these checks. The destination view must continue to enforce its normal security.

- Hidden features must not have their counts requested or disclosed.
- Apply row-level permissions to each visible feature's count.
- A permitted feature with no accessible records displays `0` and remains navigable.
- If no feature is available, display `Нет доступных разделов` instead of empty dashboard rows.
- Permission absence is not a zero count or a query failure.
- Do not grant additional permissions as part of this feature.
- Do not cache results across users or sessions.

## 7. Refresh, Loading, and Failure States

Load counts on initial home entry and every subsequent return to home. Do not reload them merely because MainView is the parent layout of another view. Do not add polling.

`Обновить` refreshes all currently visible indicators and retries previously failed ones. Navigation tiles remain usable throughout loading.

| State | Indicator | Refresh action |
| --- | --- | --- |
| Initial load or refresh in progress | Loading placeholder with accessible loading text; no previous number or temporary zero | Disabled until the refresh finishes |
| Successful result | New localized integer, including genuine zero | Enabled once all visible indicators finish |
| Failed result | `Недоступно`; no previous number or zero | Enabled once all visible indicators finish; next refresh retries |

A failed count must not discard successful results for the other indicators or prevent navigation. Do not expose exception details to the user. Log diagnostic information server-side.

Each refresh has one active generation: do not queue duplicate refreshes, allow older results to overwrite newer ones, or update a detached/obsolete dashboard after navigation away. Returning home starts or schedules the appropriate fresh load. The implementation must keep the UI responsive and scope result delivery to the initiating user and home instance.

Counts are independent observations rather than a guaranteed transactional snapshot across three entities. Concurrent changes can occur after a count is read; the next refresh reconciles the display.

The welcome notification retains its current one-time behavior. Count refreshes must not retrigger it.

## 8. Selected Approach and Alternatives

### Selected: Existing home area plus a counting service

Populate `MainView`'s existing `initialLayout`. Keep navigation, permission-aware presentation, and loading state in the UI layer; isolate counting in a Spring service with no UI dependencies. This retains the current route and shell while keeping database logic out of MainView.

### Considered: Separate dashboard view

A dedicated routed view would provide additional UI isolation, but would require another route and navigation configuration. It was not selected because the current feature fits the existing home area.

### Considered: Combined count/navigation cards

Three combined cards would use less space, but give totals less emphasis. The selected design intentionally uses three compact indicators plus three larger navigation tiles.

## 9. Jmix Implementation Constraints

These constraints were checked against cached Jmix 3.1.0 framework source rather than relying on Jmix 2 skill examples.

### Secured counting

Use the constrained `DataManager` entity-context counting path (`getCount(LoadContext<?>)`) under the current user's authentication. Keep soft deletion enabled. Use no list-specific filters or pagination limits.

The Jmix 3.1 scalar value-query path applies JPQL row-level constraints but does not perform entity-loaded Java predicate filtering. Consequently, a scalar `loadValue("select count(...) ...")` alone is insufficient for the promised behavior when predicate policies exist. The entity-context count path can fall back to loading and filtering entities when in-memory READ constraints require it.

Prefer database counts where supported by the security constraints. Do not independently load all entities just to count them. The framework's predicate-policy fallback can be more expensive; preserve security rather than bypassing it for speed. Include this case in validation.

Handle failures per entity operation so that a failed query or transaction does not poison the other counts. Do not use `UnconstrainedDataManager`, system authentication, raw SQL, or unrestricted shared totals as shortcuts.

### Main-view lifecycle and background work

`StandardMainView` uses `initialLayout` when no child router content is present. View ready events can recur on reused instances, so a general MainView-ready handler is not by itself sufficient to distinguish home entry from child navigation.

Refresh must explicitly detect the home state and remain independent of the welcome flag. Select a verified Jmix/Vaadin mechanism for responsive loading and safe UI updates. If work runs on another thread, explicitly preserve the initiating user's authentication/security context; never substitute a system user. Respect view detachment and do not share results between users.

### Styling and localization

Use XML component declarations and theme CSS classes where appropriate. Put dashboard styles in the existing application theme, with no manual changes to generated frontend files. Keep all visible text in message bundles and use the project's Russian terminology. Do not add a charting/dashboard add-on for three counts.

### Expected artifacts

- `src/main/java/ru/stnovator/finassist/view/main/MainView.java`
- `src/main/resources/ru/stnovator/finassist/view/main/main-view.xml`
- A small dashboard count service in the application service layer.
- `frontend/themes/fin-assist/view/main-view.css` (already imported by the theme).
- `src/main/resources/ru/stnovator/finassist/messages_ru.properties`
- Focused count-service/security and main-view UI tests.

No entity changes, database migrations, new feature routes, or permission grants are expected. Existing menu, list behavior, and authentication flows remain intact.

## 10. Acceptance Criteria

1. A fully authorized user sees three indicators and three tiles in the agreed order on home.
2. Each tile opens the specified existing list in the same browser tab; indicators do not navigate.
3. Existing list filters and settings retain their normal behavior; no dashboard filter is injected.
4. Counts include all accessible records, exclude soft-deleted records, and display genuine zero correctly.
5. Both JPQL and predicate-based row restrictions are respected; restricted rows are not exposed through totals.
6. Lacking either target-view or entity READ permission hides both corresponding elements and prevents its count query.
7. Users with no available features see `Нет доступных разделов`, with normal shell/account controls retained.
8. Initial entry, return home, and manual refresh load counts; child-view navigation does not trigger dashboard queries.
9. Loading removes previous counts, shows loading text, disables duplicate refresh, and leaves navigation usable.
10. One failed count shows `Недоступно` without suppressing successful indicators; a later refresh can recover it.
11. Navigation away during a load causes no stale updates, cross-user results, or detached-UI errors.
12. Count refresh never retriggers the existing welcome notification.
13. Tiles, refresh, and focus states work with keyboard navigation; icons and count states have suitable accessible text.
14. Desktop and narrow-screen layouts are readable, retain logical order, and have no horizontal page overflow.
15. Labels and integer formatting follow the configured Russian locale and existing Lumo theme.

## 11. Verification Plan

- Inspect changed Java, XML, messages, and CSS using Jmix-aware IDE tools when available; verify new APIs and icon constants against pinned dependencies.
- Test totals with permitted, deleted, zero-result, JPQL-restricted, and predicate-restricted records under the intended user identity.
- Test independent query failure/recovery, refresh deduplication, navigation-away behavior, and security-context handling.
- Extend UI coverage for tile destinations, permission visibility, empty/loading/error states, refresh on home return, and welcome-notification preservation.
- Run `compileJava`, then the terminating full `clean test` suite against an isolated database; do not use a non-terminating bootRun as the context-load gate.
- Use the available Playwright MCP tools to verify the implemented dashboard at desktop and narrow viewport sizes, keyboard navigation, routes, responsive loading, and visible failure/zero states.
- Verify the production frontend build because the feature changes theme CSS and UI structure.

## 12. Implementation Choices Left to the Builder

The following are implementation choices rather than additional business requirements:

- Exact spacing, breakpoints, card dimensions, and verified icon constants within the agreed visual direction.
- Exact short tile descriptions and page heading, consistent with existing Russian terminology.
- The verified asynchronous-loading mechanism, bounded execution strategy, and stale-result guard.
- Service/result type names and whether UI markup warrants a small reusable fragment.
- Hiding the refresh action when no feature is available (recommended), and exact accessible loading announcements.

There are no unresolved business requirements from the interview. Implementation requires review of this written specification; no application code is changed by preparing it.

## 13. Assistant card follow-up — 2026-11-04

The approved assistant card appears below the three navigation tiles, inside the home-only initial layout. It introduces **Захар**, **Финансовый менеджер**, with the description «Помогу найти клиентов, разобраться в контрактах и графиках платежей.» and a single «Спросить Захара» button. A decorative chat icon, restrained accent background and existing Lumo theme tokens keep the card consistent with the dashboard. At narrow widths the button spans a separate row below the identity and description.

The button reuses `finassist_openAiTools` and opens the existing chat hub without sending a prompt or creating a conversation. The card is visible only with permission to open `aitls_AiChatHubView`; the action also checks permission at execution. Chat-only access suppresses the dashboard's «Нет доступных разделов» message even when none of the three business sections is available. Existing business counts, navigation, role grants and home-only layout behavior are retained. Suggested-question chips remain outside this change.

Automated coverage in `MainDashboardUiTest` checks identity and caption rendering, navigation and return, denied-access hiding, and chat-only access. The three new cases failed before the card was added and passed afterward. Final `clean test`: **146 tests, zero failures/errors/skips**, using disposable PostgreSQL and a dummy AI key.

Per-file checks: `MainView.java` and `MainDashboardUiTest.java` compiled and passed UI integration tests; `main-view.xml` parsed and its component/action bindings loaded in those tests; `messages_ru.properties` resolved the card captions with no duplicate keys; `main-view.css` passed mechanical checks and is already imported by the active theme; this specification passed content/whitespace checks. IDE semantic inspection was unavailable. A temporary app started successfully on localhost:8091, but the browser switched away during login, so visual verification was stopped without touching the other page. **Styling is not browser-verified**, including narrow-screen layout. The temporary app and database were removed afterward.

## References

- [Business Requirements Document](../business-requirements-document.md)
- Main view controller and descriptor listed above.
- Existing list controllers/descriptors under `view/customer`, `view/contract`, and `view/project`.
- Existing security roles under `src/main/java/ru/stnovator/finassist/security`.
- Jmix 3.1.0 sources: `StandardMainView`, `View`, `JpaDataStore`, `ReadEntityQueryConstraint`, `AbstractDataStore`, and `BaseDataStoreInMemoryCrudListener`.
