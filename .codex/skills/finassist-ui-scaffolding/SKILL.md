---
name: finassist-ui-scaffolding
description: Use this instructions when making operations with views and view controllers
---
Actual instructions:

## 1) View class conventions (Flow UI)
- Each view has a Java controller + XML descriptor pair.
- Annotate each controller with:
  - `@Route(value = "<path>", layout = MainView.class)`
  - `@ViewController("<viewId>")`
  - `@ViewDescriptor("<descriptor-file>.xml")`
- Use base classes:
  - `StandardListView<T>` for list views.
  - `StandardDetailView<T>` for detail views.
  - `StandardView` for custom/non-CRUD views.
- Use `@EditedEntityContainer("<dcId>")` on detail views.
- Use `@LookupComponent("<componentId>")` on list views with lookup mode.
- Prefer constructor-free dependency injection with `@Autowired` and UI components via `@ViewComponent`.

## 2) View IDs, routes, and file names
- View IDs follow Bookstore style: `<module>_<Entity>.list`, `<module>_<Entity>.detail`, or `<module>_<CustomView>`.
- Route paths are kebab-case for custom views (e.g., `supplier-order-approval-form`) and plural resources for entities (e.g., `customers`, `orders/:id`).
- Descriptor file names are kebab-case and match the Java class name by meaning:
  - `CustomerListView` -> `customer-list-view.xml`
  - `OrderDetailView` -> `order-detail-view.xml`

## 3) Descriptor XML layout structure
- Keep XML sections in consistent order:
  1. `<data>`
  2. `<facets>`
  3. `<actions>`
  4. `<layout>`
- Use `dataLoadCoordinator` for views with loaders.
- Use `focusComponent` on the root `<view>` when relevant.
- Keep layout containers aligned and consistent (e.g., `vbox` for vertical stacking, `hbox` for horizontal actions).

## 4) Data components
- List views use a `collection` with `loader` and `fetchPlan`:
  - Keep queries in the descriptor when simple; use Java for complex logic.
  - Prefer stable ordering in list queries.
- Detail views use `instance` containers:
  - Define `fetchPlan` explicitly when required.
  - Use `collection` nested in `instance` for composition lists (e.g., orders of a customer).

## 5) Actions and buttons
- Use standard action types (`list_create`, `list_edit`, `list_remove`, `detail_saveClose`, `detail_close`, `list_read`, `lookup_select`, `lookup_discard`).
- Bind buttons to actions in XML with `action="..."`.
- Keep action IDs aligned with component IDs (e.g., `customersDataGrid.create`).

## 6) Main view and app shell
- `MainView` is the root shell and should remain lean (navigation, user context, notifications).
- Use `appLayout`, `navigationBar`, and `drawerLayout` for consistent layout.
- Keep menus in `src/main/resources/ru/stnovator/finassist/menu.xml` and reference view IDs.

## 7) Bookstore-inspired view patterns
- List views:
  - Combine `genericFilter` + `simplePagination` with a read-only `collection` loader.
  - Keep CRUD buttons in an `hbox` with a shared `buttons-panel` class.
  - When using tabs, keep `tabSheet` as the root of the layout content and wire URL query parameters to preserve tab selection.
- Detail views:
  - Use a top-level `vbox` for scrollable content and a bottom `detailActions` bar for save/close.
  - Group related form fields into `vbox` panels with semantic class names (e.g., `contrast-panel`, `grid-panel`).
  - Use `formLayout` for entity fields and keep nested collections in a dedicated panel with a `dataGrid`.
- Dialog views:
  - Open secondary views via `DialogWindows.view(...).build().open()` and pass required data before open.
- Map views:
  - Use a `maps:geoMap` in a dedicated tab and bind a `maps:dataVectorSource` to the same `collection` container.
  - Keep map view state in XML (`maps:mapView`) and listen for map clicks in the controller.

## 8) Messages and i18n
- Use `msg://` keys in XML for titles, labels, and texts.
- Keep view-specific message keys in `messages_*.properties` under the view package.
- Use consistent key naming: `view.<feature>/<viewName>.title` or similar existing patterns.

## 9) Styling
- Use `classNames` and `themeNames` in XML rather than inline styles.
- Put shared styles in `frontend/themes/<theme>/` and keep names semantic.

## 10) Testing
- Unit and integration tests go in `src/test/java/ru/stnovator/finassist`.
- UI tests follow the `*UiTest` naming and use `jmix-flowui-test-assist`.

## 11) General Java style
- 4-space indentation, PascalCase for classes, camelCase for methods/fields.
- Keep code organized by feature and avoid cross-feature package coupling.
