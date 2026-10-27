# ListOfContracts Report Specification

## Source of Truth

- Report class: `src/main/java/ru/stnovator/finassist/report/ListOfContracts.java`
- Report template: `src/main/resources/ru/stnovator/finassist/report/list-of-contracts.xlsx`
- Report launch point: `src/main/resources/ru/stnovator/finassist/view/contract/contract-list-view.xml`
- Related entities:
  - `Contract`
  - `Customer`
  - `Project`
  - `Addendum`
  - `ShipmentScheduleItem`
  - `PaymentScheduleItem`
  - `ShipmentScheduleCorrectionItem`
  - `PaymentScheduleCorrectionItem`

## Report Identity

- Report code: `list-of-contracts`
- Display name: `List Of Contracts`
- Output format: `XLSX`
- Output file name: `list-of-contracts.xlsx`
- Availability:
  - available to `ReportsRunRole`
  - available from `ContractListView`

## Business Purpose

The report exports a hierarchical view of contracts grouped by customer, then by contract, then by project, with a project-level cashflow schedule.

The cashflow portion compares shipment amounts and payment amounts by date.

The report supports two user-facing calculation modes:

- `BASE`
- `ACTUAL_WITH_ADDENDUMS`

## Input Parameters

### Required

- `reportDate` (`DATE`)
  - Always required.
  - When the report input parameter dialog opens, the field is prefilled from the current system time.
  - Business logic uses the date portion of that value.
  - Used only for:
    - printing the header value
    - selecting the contract-level active addendum in `ACTUAL_WITH_ADDENDUMS`
  - Not used to filter cashflow rows by date.

- `contractListMode` (`ENUMERATION`)
  - Always required.
  - Default value: `BASE`
  - Supported values:
    - `BASE`
    - `ACTUAL_WITH_ADDENDUMS`

### Optional

- `customer` (`Customer`)
- `contract` (`Contract`)
- `project` (`Project`)

## High-Level Data Structure

The report band hierarchy is:

`Root -> Header -> Customer -> Contract -> Project -> ProjectCashflowRecord / ProjectCashflowTotal`

Spacer/header-only bands also exist for template layout, but they do not introduce additional business logic.

## Filtering Logic

### Customer level

The customer dataset is resolved as follows:

1. If `project` is provided:
   - load that project with its contract and customer
   - if the loaded project does not belong to the selected `contract` or selected `customer`, return no rows
   - otherwise return exactly one customer row: the project's contract customer
2. Else if `contract` is provided:
   - load that contract with its customer
   - if the loaded contract does not belong to the selected `customer`, return no rows
   - otherwise return exactly one customer row: the contract customer
3. Else if `customer` is provided:
   - return exactly one customer row for that customer
4. Else:
   - load all distinct customers that have at least one contract
   - sort by customer name ascending

### Contract level

For each customer row:

1. If `project` is provided:
   - load that project
   - if its customer does not match the parent customer row, return no rows
   - otherwise return exactly one contract row: the project's contract
2. Else if `contract` is provided:
   - load that contract
   - if its customer does not match the parent customer row, return no rows
   - otherwise return exactly one contract row
3. Else:
   - load all contracts for the parent customer
   - sort by `startDate desc, internalID asc`

### Project level

For each contract row:

1. If `project` is provided:
   - load that project
   - if its contract does not match the parent contract row, return no rows
   - otherwise return exactly one project row
2. Else:
   - load all projects for the parent contract
   - sort by project name ascending

## Contract Row Semantics

Each contract row exposes:

- contract id
- internal contract number
- start date
- end date
- localized payment type
- contract total amount
- selected contract-level addendum metadata, if any

### Contract-level addendum selection

An addendum is attached to the contract row only when all conditions are true:

- `contractListMode == ACTUAL_WITH_ADDENDUMS`
- `reportDate` is present and convertible to `LocalDate`
- the contract has at least one addendum with `effectiveDate <= reportDate`

When multiple addenda are eligible, the report selects exactly one using this ordering:

`effectiveDate desc, createdDate desc, number desc`

This means the report chooses the most recent effective addendum as of `reportDate`, using creation date and number as tie-breakers.

If no addendum is resolved, the addendum label, number, and effective date are emitted as empty values.

Important: addendum selection is contract-level, not project-level.

## Project Row Semantics

Each project row exposes:

- project id
- project name
- line of business, formatted through Jmix metadata formatting
- `cashflowSourceLabel`
- `cashflowSource`
- `shipmentAddendumId` and `paymentAddendumId` used independently for each schedule (null means base)

### Project-level source decision

Although addendum selection is contract-level, source usage is decided per project.

#### `BASE`

Every project uses:

- `ShipmentScheduleItem`
- `PaymentScheduleItem`

Project output: `cashflowSource = База`; both schedule source IDs are null.

#### `ACTUAL_WITH_ADDENDUMS`

The report selects one eligible addendum at contract level, then resolves each project's schedules independently:

| Shipment correction items | Payment correction items | Shipment source | Payment source | Source label |
|---|---|---|---|---|
| Present | Present | Selected addendum | Selected addendum | Доп. соглашение |
| Present | Absent | Selected addendum | Base | Отгрузки: доп. соглашение; оплаты: база |
| Absent | Present | Base | Selected addendum | Отгрузки: база; оплаты: доп. соглашение |
| Absent | Absent | Base | Base | База |

Without an eligible addendum, both schedules use base data. Presence means at least one non-deleted correction item for that project and selected addendum, including zero-amount items. An empty correction header does not clear the schedule. A correction replaces the entire corresponding base schedule; it is not a delta.

Different projects may use different source combinations. Source decisions are recomputed for each project dataset load so subsequent reports see edits. The report does not search older addenda separately for each schedule.

## Cashflow Computation

Cashflow is calculated for `(projectId, shipmentAddendumId, paymentAddendumId)`. The records and totals bands use this same key; the cached computation is removed after totals are emitted.

For shipments, a null `shipmentAddendumId` loads `ShipmentScheduleItem` by `schedule.project.id`; otherwise load `ShipmentScheduleCorrectionItem` by `correction.project.id` and `correction.addendum.id`.

For payments, a null `paymentAddendumId` loads `PaymentScheduleItem` by `schedule.project.id`; otherwise load `PaymentScheduleCorrectionItem` by `correction.project.id` and `correction.addendum.id`.

Both lists are ordered by `itemDate`, merged into a date-indexed map, and emitted in ascending date order.

## Cashflow Aggregation Rules

For each date:

- all shipment items on that date are summed into `shipmentSum`
- all payment items on that date are summed into `paymentSum`

Each emitted cashflow row contains:

- `cashflowDate`
- `shipmentSum`
- `paymentSum`

The totals band contains:

- `shipmentTotal`
- `paymentTotal`

Where:

- `shipmentTotal` is the sum of all shipment item amounts used in the computation
- `paymentTotal` is the sum of all payment item amounts used in the computation

## Important Actual Behaviors

### `reportDate` is not a cashflow cutoff

The report does not restrict schedule or correction items to dates on or before `reportDate`.

Actual behavior:

- when the report parameter dialog opens, `reportDate` is initialized from the current system time
- `reportDate` affects header output
- `reportDate` affects which contract-level addendum is selected in `ACTUAL_WITH_ADDENDUMS`
- cashflow rows can include dates after `reportDate`

### Addendum mode can fall back to base

In `ACTUAL_WITH_ADDENDUMS`, the report does not blindly force correction data for every project.

Actual consequences:

- a missing correction side falls back independently to its base schedule; the other correction side is still applied
- the contract header may still show the selected addendum, while the project row shows `Источник данных: База`
- contract total amount still comes from `Contract.totalAmount`; it is not recalculated from correction data

### Filter mismatch returns empty output

If provided parameters are inconsistent, the report silently returns no rows instead of raising a validation error. Examples:

- selected `project` does not belong to selected `contract`
- selected `contract` does not belong to selected `customer`
- selected `project` belongs to a different customer than the parent row

## Ordering Rules

- customers: by customer name ascending
- contracts: by contract start date descending, then internal id
- projects: by project name ascending
- cashflow rows: by cashflow date ascending

## Security and UI Entry Point

- The report is exposed in the contract list screen through the `report_runReport` action.
- Access is constrained by Jmix report execution role visibility plus normal screen/data permissions.

## Non-Goals and What the Report Does Not Do

The implemented logic does not:

- validate parameter consistency up front
- limit cashflow rows by `reportDate`
- combine base and correction items within the same schedule (shipment or payment)
- select different addenda per project
- recalculate contract amount based on addendum corrections
- calculate deltas

## Regression Acceptance Case

For contract `2026-02-15-ФАСАД`, project `Оформление фасада заводоуправления`, addendum `2026-04` effective 2026-04-01, report date 2026-10-27 in actual mode: shipment 789000 moves from 2026-03-01 to 2026-04-01; base payments remain unchanged. Shipment total is 7189000 and payment total is 30780000. Base mode retains the March shipment date.
