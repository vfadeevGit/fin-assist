# Jmix Reports Patterns

This reference condenses the sample project at `~/IdeaProjects/jmix-reports-sample` and the matching Jmix docs retrieved through Context7.

## Add-on Wiring

Sample `build.gradle` uses:

```gradle
implementation 'io.jmix.reports:jmix-reports-starter'
implementation 'io.jmix.reports:jmix-reports-flowui-starter'
```

The sample also enables document conversion through LibreOffice in `application.properties`:

```properties
jmix.reports.use-office-for-document-conversion=true
jmix.reports.office-path=/Applications/LibreOffice.app/Contents/MacOS
```

## Sample Report Types

### 1. Client Profile

Files:

- `src/main/java/com/company/crm/report/ClientProfileReport.java`
- `src/main/resources/com/company/crm/report/client-profile-report.html`
- `src/main/resources/com/company/crm/report/client-profile-report.docx`
- `src/main/resources/com/company/crm/report/client-profile-report.jrxml`

Patterns:

- design-time report defined in code with multiple `@TemplateDef` entries
- master-detail structure: `Root -> Client -> Contacts`
- entity input parameter: `client`
- one report produces HTML, DOCX, PDF from DOCX, and PDF from JRXML
- available from both list and detail views

Use this pattern for:

- entity profile reports
- printable business documents
- reports that need multiple output formats from the same dataset structure

### 2. Orders by Client

File:

- `src/main/java/com/company/crm/report/OrdersByClientReport.java`

Template:

- `src/main/resources/com/company/crm/report/orders-by-client-report.xlsx`

Patterns:

- hierarchical bands: `Root -> Header`, `Root -> Client -> OrderStatus -> Order`, plus `ClientTotal` and `GrandTotal`
- aggregate state tracked across delegates with thread-local totals
- grouped XLSX output with subtotals and grand totals
- optional date range parameters
- the workbook defines named regions for rendered bands like `Header`, `Client`, `OrderStatus`, `Order`, `ClientTotal`, `GrandTotal` and does not define a named region for `Root`

Use this pattern for:

- grouped exports
- subtotal/grand total reports
- reports where template layout is spreadsheet-first

### 3. Orders by Status

File:

- `src/main/java/com/company/crm/report/OrdersByStatusReport.java`

Template:

- `src/main/resources/com/company/crm/report/orders-by-status-report.xlsx`

Patterns:

- aggregate query via `dataManager.loadValues(...)`
- mapped result rows with count and total
- dedicated chart band in the report structure
- XLSX output with chart-ready data

Use this pattern for:

- dashboards exported to Excel
- summary reports based on counts and totals
- reports driven by aggregate SQL/JPQL rather than entity trees

### 4. Revenue by Month

File:

- `src/main/java/com/company/crm/report/RevenueByMonthReport.java`

Template:

- `src/main/resources/com/company/crm/report/revenue-by-month-report.xlsx`

Patterns:

- cross-tab report using `orientation = Orientation.CROSS`
- three datasets: dynamic header, master data, and data matrix
- per-parameter validation with `@InputParameterDelegate`
- cross-parameter validation with `@ReportDelegate`
- generated month headers and client/month matrix cells

Use this pattern for:

- pivot-like reports
- period-by-period comparisons
- reports with dynamic columns

## Runtime Report Import

Sample importer:

- `src/main/java/com/company/crm/init/RuntimeReportsImporter.java`

Patterns:

- load ZIP archives from `src/main/resources/com/company/crm/runtime-reports/`
- import on startup using `ReportImportExport`
- annotate startup listener with `@Authenticated`
- iterate over a fixed archive list and log missing files

Prefer a persistent init flag if duplicate imports must be prevented across restarts or clustered nodes.

## Flow UI Integration

The official docs example uses `report_runReport` as a grid action type. Add the action to a `dataGrid` and expose it with a button in the buttons panel when users need in-context execution.

Pair that with `@AvailableInViews` on the report class so the report shows only in relevant list/detail views.

## Selection Heuristics

Choose:

- HTML when the result is browser-oriented and easy to style with Freemarker.
- DOCX when the result is a document and may also need PDF conversion.
- JRXML when Jasper-specific PDF layout is required.
- XLSX when users expect calculations, grouping, charts, or cross-tabs.

Prefer design-time reports when:

- the report must be versioned in git
- structure changes travel with code deployments
- developers maintain report definitions

Prefer runtime reports when:

- business users edit templates or datasets in the UI
- report definitions must be imported/exported independently of code

## XLSX Named Region Rule

For Jmix XLSX templates:

- define named regions only for rendered bands
- do not define a named region for `Root`
- start the workbook named regions from the first child band under `Root`

Example:

- Java band structure: `Root -> Customer -> Projects`
- XLSX named regions: `Customer`, `Projects`
- not `Root`, `Customer`, `Projects`
