# FinAssist — Business Requirements Document

## 1. Purpose

FinAssist supports finance managers from industrial construction business domain in maintaining contract cash-flow plans. It provides a controlled record of customers, contracts, projects, planned shipments, planned payments, and amendments to those plans. Its primary business outcome is an exportable view of future planned cash movements by contract and project.

## 2. Business Objectives

The system shall enable the business to:

1. Maintain a consistent contract portfolio, including customers, projects, contract terms, and lines of business.
2. Plan shipment and payment cash flows at dated, monetary-line level for each project.
3. Preserve amended plans separately from a project's base plan and apply the appropriate plan basis in reporting.
4. Give finance users downloadable XLSX reports for calendar cash-flow analysis and contract-level plan review.
5. Limit finance data maintenance and report access to authorised users, while retaining audit information for business records.

## 3. Scope

### In scope

- Customer and line-of-business reference data.
- Contracts, their projects, and contract addenda.
- Base shipment and payment schedules.
- Shipment and payment schedule corrections associated with addenda.
- Plan-oriented XLSX reports.
- User authentication and role-based UI/API access.
- Audit fields and soft deletion for planning entities.

### Explicitly out of scope in the current release

- Recording actual shipment or payment facts.
- Automatic generation of payment schedules from shipment dates or payment delay based in the contract terms - payment and delivery conditions.
- Plan-versus-actual analysis.
- Notifications of overdue/missed milestones.
- Approval, workflow, or BPM processes.
- Automatic recalculation of contract amounts from schedules or corrections.
- Currency management, tax calculation, invoicing, and accounting postings.

## 4. Stakeholders and User Roles

| Role / stakeholder | Business need | Current access model |
| --- | --- | --- |
| Finance manager | Maintain plans and run financial reports. | `FinanceManagerRole` grants CRUD access to planning data and report UI access. |
| System administrator | Manage users and unrestricted system configuration. | `FullAccessRole` grants unrestricted application access. |

## 5. Functional Requirements

### FR-01 — Master data maintenance

Finance users shall be able to create, view, edit, and remove customers, lines of business, contracts, projects, and addenda through list and detail views.

The main navigation shall group business functions into Contract work, Plans, Corrections, and References.

### FR-02 — Contract structure management

Users shall be able to maintain a contract's projects and addenda from the contract detail view. Project records shall display their name and line of business; addenda shall display number and effective date.

### FR-03 — Base plan maintenance

Users shall be able to create or remove the shipment and payment schedule for a project. A schedule may only be created when the corresponding project has none; controls for its lines shall be unavailable while it does not exist.

Users shall be able to create, edit, and remove schedule lines in project context. The project view shall display the line count per schedule and the current sum of its amounts.

### FR-04 — Correction plan maintenance

Users shall be able to create, edit, and remove shipment and payment corrections from an addendum and from their respective list views.

When an addendum is selected for a correction, the selectable project set shall be restricted to the projects of that addendum's contract. If the selected addendum changes and the current project does not belong to the new contract, the project selection shall be cleared.

### FR-05 — Correction initialisation and clearing

For a selected project, users shall be able to populate a correction with all items from that project's corresponding base schedule. Existing correction lines with the same date shall not be duplicated. Users shall be able to clear all correction lines after a confirmation prompt.

> The copy operation is a starting point for manual editing; it does not calculate or retain a delta from the base schedule.

### FR-06 — Auditability and record lifecycle

The system shall retain creation user/date, last-modification user/date, deletion user/date, and optimistic version information for planning records. Deleting an owned aggregate (for example, a contract, project, schedule, addendum, or correction) shall cascade to its owned children. The entity model supports soft deletion.

### FR-07 — Cashflow Calendar report

The system shall provide an XLSX Cashflow Calendar report from the contract list view. Users shall be able to filter it by report date (required), customer, contract, project, and calculation mode.

The report shall show each included project as two planned rows: shipment and payment. It shall aggregate the chosen plan into monthly columns for the report year, plus before-year, after-year, and total amounts. It shall include totals across visible projects.

In **Base** mode, the report shall use base schedules only. In **Actual With Addendums** mode, it shall determine the latest addendum effective on or before the report date for each project. It shall use correction plans only if that addendum contains non-empty shipment and payment corrections for the project; otherwise it shall use both base schedules for that project.

Contract inclusion currently requires `contract.startDate < reportDate`. Once eligible, all schedule dates may contribute to the report; `reportDate` is not a schedule-item cutoff.

### FR-08 — List of Contracts report

The system shall provide an XLSX List of Contracts report from the contract list view. Users shall be able to filter it by report date (required), customer, contract, project, and calculation mode.

The report shall group its output by customer, contract, and project. For each project it shall show date-sorted shipment and payment amounts, together with totals. In addendum-aware mode, it shall select the latest contract addendum effective on or before the report date; each project uses shipment and payment corrections independently when that side has correction items for the selected addendum. A missing side uses its base schedule. Corrections replace their corresponding schedule, including zero-valued items; an empty correction header does not erase a schedule. The report identifies mixed sources explicitly and does not search older addenda separately for each side.

The report date selects the applicable addendum and is printed in the output; it does not remove cash-flow lines after that date.

## 6. Non-functional Requirements

### Security

- User authentication is required for UI access.
- Finance-manager permissions protect planning entities and report screens.
- User records are view-only for finance managers; unrestricted user administration is reserved for full-access users.
- REST endpoints under `/rest/**` require authentication.

### Data integrity and concurrency

- Database uniqueness constraints enforce the one-schedule and one-correction-per-key rules.
- Required fields are enforced at entity/database level where defined.
- Optimistic locking prevents silent overwrites of concurrent changes.

### Reporting

- Reports are generated as XLSX downloads.
- Report UI and report data use Russian localisation.
- Report access additionally requires the Jmix report-execution role.