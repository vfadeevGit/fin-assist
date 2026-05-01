---
name: jmix-reports-project-scaffolding
description: Create or extend Jmix applications that use the Reports add-on. Use when Codex needs to wire report dependencies, define design-time reports with annotations, import runtime reports, add report actions to Flow UI views, create DOCX/XLSX/HTML/JRXML templates, or follow patterns from the jmix-reports-sample project.
---

# Jmix Reports Project Scaffolding

Implement report functionality in an existing Jmix app or in a freshly created Jmix project. Favor design-time report definitions in code when the report structure should be versioned with the application; favor runtime report imports when business users need editable report definitions in the database.

Load `references/report-patterns.md` when you need concrete examples for band structures, report classes, Gradle setup, runtime import, or template types.

## Workflow

1. Confirm scope.
   Decide whether the task is:
   - add Reports add-on support to a project
   - create a new design-time report
   - import or update runtime reports
   - expose report actions in Flow UI views
   - repair an existing report template or dataset

2. Inspect the project before editing.
   Check:
   - `build.gradle`
   - `src/main/resources/application.properties`
   - report package under `src/main/java/.../report`
   - report templates under `src/main/resources/.../report`
   - target list/detail views and `menu.xml`
   - security roles for report visibility

3. Use the existing Jmix skills for adjacent work.
   - Use `jmix-views` for Flow UI controller/XML changes.
   - Use `jmix-services` for non-trivial business logic or data loading extracted out of views.
   - Use `jmix-i18n` for message bundle updates.
   - Use `jmix-liquibase` if runtime report initialization needs database-backed flags or support entities.
   - Use `jmix-testing` for integration and UI test coverage.

4. Use Context7 if repository examples are not enough.
   Query `/jmix-framework/jmix-context7` for current Reports add-on APIs, annotations, and Flow UI report actions.

## Add-on Setup

Add the reporting dependencies to `build.gradle`:

```gradle
implementation 'io.jmix.reports:jmix-reports-starter'
implementation 'io.jmix.reports:jmix-reports-flowui-starter'
```

Keep the rest of the project aligned with the existing Jmix BOM/version. Do not introduce mismatched Jmix module versions.

If the project must convert DOCX or ODT templates to PDF, configure office conversion in `application.properties` and ensure the target office binary exists on the machine:

```properties
jmix.reports.use-office-for-document-conversion=true
jmix.reports.office-path=/path/to/office/binary
```

## Design-Time Reports

Create report definitions as Spring beans via Jmix report annotations:

- `@ReportGroupDef` for a reusable UI group
- `@ReportDef` for report identity, title, description, group, and optional UUID
- `@TemplateDef` for one or more output templates
- `@InputParameterDef` for external parameters
- `@BandDef` and `@DataSetDef` for report structure
- `@DataSetDelegate` for Java-backed data loading
- `@InputParameterDelegate` and `@ReportDelegate` for validations
- `@AvailableForRoles` and `@AvailableInViews` for visibility

Implementation rules:

- Always define a `Root` band with `root = true`.
- Keep dataset names stable because they are referenced by delegate methods and template placeholders.
- Prefer `DataManager` for loading entities and aggregates.
- Keep complex value formatting in helper methods so templates stay simple.
- Use constructor injection in new services and helper beans; report classes may follow existing project conventions if they already use field injection.
- Give report codes and template codes stable identifiers suitable for API use.
- Add `uuid` values when consistency across environments matters.

## Template Strategy

Choose the template type by report shape:

- `HTML` plus Freemarker for lightweight printable or browser-friendly output.
- `DOCX` for business documents and optional PDF conversion.
- `JRXML` for Jasper-based PDF layouts.
- `XLSX` for tabular exports, grouped output, charts, and cross-tabs.

Template rules:

- Keep template files in `src/main/resources/<package>/report/`.
- Keep output name patterns explicit.
- Match `outputType` to the intended generated file.
- For multi-template reports, mark one template as `isDefault = true`.
- When templates become large or format-specific, keep formatting decisions in the template and data shaping in Java.
- For XLSX templates, do not create a named region for `Root`. `Root` is a logical/system band in the Java report structure, not a spreadsheet range.
- In XLSX templates, create named regions only for the first rendered band and downstream bands, for example `Header`, `Customer`, `Projects`, `Order`, `GrandTotal`.
- The first named region in the workbook must correspond to the first child band under `Root`, not to `Root` itself.

## Data Loading Patterns

Use the simplest dataset strategy that fits the report:

- Use one flat band for simple lists or summaries.
- Use parent/child bands for master-detail reports.
- Use nested bands for grouped subtotals.
- Use `Orientation.CROSS` for cross-tab reports.
- Use `loadValues()` for aggregate rows and `KeyValueEntity`-style projections.
- Use thread-local accumulators only when a report needs streaming subtotal state across nested delegate execution; clean them up at the end of the report.

Prefer these query patterns:

- entity loading via `dataManager.load(Entity.class)...`
- scalar/aggregate loading via `dataManager.loadValue()` or `dataManager.loadValues()`
- parameterized JPQL with explicit aliases and ordering

## Runtime Reports

Use runtime report import only when the report definition must be editable in the running application. Import ZIP archives from classpath resources with `ReportImportExport`.

Implementation rules:

- Put archives in a dedicated resources folder such as `src/main/resources/.../runtime-reports/`.
- Trigger imports on startup with an authenticated listener.
- If repeat imports would be harmful, persist an initialization flag and guard import execution.
- Log missing archives and import failures clearly.

## Flow UI Integration

Expose reports where users actually work:

- add `report_runReport` actions to data grids in list views
- expose entity-scoped report actions from list and detail views
- use `@AvailableInViews` so only relevant reports appear

When modifying views, keep UI text in message bundles and follow the project’s Flow UI patterns.

## Security

Report visibility is the intersection of report annotations and normal Jmix security. When adding a report:

- confirm the right roles can open the target views
- add or update `@AvailableForRoles`
- ensure users can read the entities and attributes required by datasets
- verify menu/view permissions for any report designer or report runner screens you expose

## Validation

After implementing or changing reports:

1. Check file problems for each modified Java/XML file.
2. Run targeted tests first, then `./gradlew test`.
3. Start the app and run the report from the target view.
4. Verify parameter validation, generated file name, output format, and role visibility.
5. For runtime reports, verify import happens once and archives remain in sync with code/templates.

## Output Expectations

When using this skill to implement a request, produce:

- the required Java report classes and helper classes
- matching template files
- view XML/controller updates for report actions when needed
- message bundle updates
- security updates if visibility changed
- tests or a clear note explaining why tests were not added
