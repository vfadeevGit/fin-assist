# FinAssist

FinAssist is a Jmix-based internal application for planning contract cash flows. The current implementation focuses on reference data, contract/project structure, base shipment and payment schedules, addenda-based schedule corrections, and XLSX reporting.

This README describes the application as it is implemented now, not the broader target vision.

## Technology

- Java 21
- Jmix 2.8.1 / Spring Boot 3 / Vaadin Flow UI
- PostgreSQL
- Jmix Reports add-on
- Gradle

## Current Functional Scope

The system currently supports:

- customer, contract, project, and line-of-business master data
- contract addenda with effective dates
- one base shipment schedule per project
- one base payment schedule per project
- shipment schedule corrections by addendum and project
- payment schedule corrections by addendum and project
- two XLSX reports available from the contract list view

The system does not currently implement:

- actual shipment facts
- actual payment facts
- automatic payment schedule generation from shipment schedule and payment delay
- notifications about missed milestones
- BPM or approval workflows

## Domain Model

### Customer

Customer stores:

- name
- INN
- KPP

A customer owns multiple contracts.

### Contract

Contract stores:

- customer
- internal contract ID
- start date
- end date
- payment type
- total amount
- payment days

The payment type is represented by `ContractType` with these values:

- `DIRECT_CONSUMER`
- `GENERAL_CONTRACTOR`
- `FACTORING_CONSUMER`

A contract owns:

- projects
- addenda

The entity also exposes a derived duration in days.

### LineOfBusiness

Reference entity for the project business line.

### Project

Project stores:

- name
- contract
- line of business

Each project can own:

- one shipment schedule
- one payment schedule

### Addendum

Addendum stores:

- contract
- addendum number
- effective date

Each addendum can own:

- shipment schedule corrections
- payment schedule corrections

### Base Schedules

Base schedules are modeled as separate aggregate roots:

- `ShipmentSchedule` -> many `ShipmentScheduleItem`
- `PaymentSchedule` -> many `PaymentScheduleItem`

Schedule items store:

- item date
- amount

Both schedule headers are unique per project.

### Schedule Corrections

Corrections are also modeled as separate aggregates:

- `ShipmentScheduleCorrection` -> many `ShipmentScheduleCorrectionItem`
- `PaymentScheduleCorrection` -> many `PaymentScheduleCorrectionItem`

Each correction belongs to:

- one addendum
- one project

There is a unique constraint per `(addendum, project)` for each correction type, so for a given addendum and project there can be at most:

- one shipment correction
- one payment correction

## UI Surface

The main menu currently contains these sections:

- Contract work
- Plans
- Corrections
- References

Available list/detail views:

- Customers
- Contracts
- Projects
- Addenda
- Shipment schedules
- Payment schedules
- Shipment schedule corrections
- Payment schedule corrections
- Line of business
- Users

### Project Detail View

The project detail view contains embedded work with both base schedules:

- create or remove shipment schedule
- create or remove payment schedule
- edit schedule items directly in the project context
- keep schedule items sorted by date
- show item counts in tabs
- show total amount in grid footers

### Correction Detail Views

Both correction detail views contain helper logic:

- available projects are filtered by the selected addendum's contract
- schedule items can be copied from the current base schedule into the correction
- copied items are deduplicated by date
- correction items are sorted by date
- total amount is shown in the grid footer
- all correction items can be cleared with confirmation

## Business Logic Implemented Today

There is no dedicated application service layer yet. The implemented business logic is concentrated in Flow UI controllers and report beans.

Current logic includes:

- maintaining a strict one-schedule-per-project structure for base shipment and payment plans
- maintaining a strict one-correction-per-addendum-per-project structure for each correction type
- selecting projects for corrections only from the addendum's contract
- copying base schedule items into a correction as a starting point for editing
- choosing between base schedules and addendum corrections in reports

## Reports

Two design-time reports are implemented and available from the contract list view.

### Cashflow Calendar

`CashflowCalendar` generates an XLSX cross-tab report with:

- filters by report date, customer, contract, and project
- monthly columns for the selected year
- separate planned shipment and planned payment rows per project
- totals across all visible projects
- support for two modes:
  - base schedules only
  - actual plan with addenda, where the latest applicable addendum is used if both shipment and payment corrections exist for a project

The report also splits amounts into:

- before selected year
- inside selected year by month
- after selected year

### List Of Contracts

`ListOfContracts` generates an XLSX hierarchical report grouped by:

- customer
- contract
- project

For each project it outputs dated cash-flow rows and totals for:

- shipment amounts
- payment amounts

The report supports base mode and addendum-aware mode. In addendum-aware mode it uses correction schedules only when both shipment and payment corrections exist for the selected effective addendum; otherwise it falls back to the base schedules.

## Security

The application includes these notable roles:

- `FinanceManagerRole` for the main business UI and reports
- `UiMinimalRole` for login and main shell access
- `FullAccessRole`
- `OpenclawUserRole` for API scope

`FinanceManagerRole` grants CRUD access to the planning entities and access to report screens.

## Current Gaps And Limits

Compared with the earlier product vision, the current codebase has these important limits:

- no fact registration entities or UI
- no plan-vs-fact comparison in the data model
- no automatic schedule recalculation service
- no background jobs or notifications
- no dedicated business services package yet
- report logic is implemented directly in report beans rather than in reusable domain services

## Running Locally

Run the application in development mode:

```bash
./gradlew bootRun
```

The default development login is:

- username: `admin`
- password: `admin`

## Deployment Notes

The repository also contains:

- a production-oriented `Dockerfile`
- Docker Compose files under `docker/`
- an HTTPS deployment guide in `docker/DEPLOY.md`

For container deployment, use the Docker-specific documents rather than this README as the source of truth.
