# FinAssist

FinAssist is a Jmix-based internal application for planning contract cash flows. The current implementation focuses on reference data, contract/project structure, base shipment and payment schedules, addenda-based schedule corrections, and XLSX reporting.

This README describes the application as it is implemented now, not the broader target vision.

## Technology

- Java 21
- Jmix 3.1.0, with Spring Boot 4.1.1 and Vaadin 25.3.0 managed by the Jmix BOM
- PostgreSQL (16 in Docker Compose and CI)
- Jmix Reports, REST API, authorization server, security, audit, data tools, and local file storage add-ons
- Gradle 9.8.0 via the repository wrapper
- pnpm for frontend dependencies; overrides are maintained in `pnpm-workspace.yaml`

The application uses the StroyNovator theme with a bundled Roboto font. The configured UI locale is Russian (`ru`).

## Current Functional Scope

The system currently supports:

- customer, contract, project, and line-of-business master data
- contract addenda with effective dates
- one base shipment schedule per project
- one base payment schedule per project
- shipment schedule corrections by addendum and project
- payment schedule corrections by addendum and project
- two XLSX reports available from the contract list view
- a home dashboard with navigation and permission-aware customer, contract, and project totals

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

### Home And Detail Editors

The home view provides customer, contract, and project navigation tiles and record totals. Counts load independently in background tasks through `DashboardCountService`, respect the current user's permissions and soft deletion, and can be refreshed. Loading is canceled when leaving the view. A welcome notification includes the authenticated user's name.

Detail editors use responsive forms, scrollable content, and persistent action footers. Required fields remain visually neutral before interaction; validation still runs on interaction and save.

### Menu And Views

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

`service/DashboardCountService` provides secured record counts for the home dashboard. Schedule editing logic remains in Flow UI controllers, and report calculations remain in report beans.

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

The report supports base mode and addendum-aware mode. In addendum-aware mode, shipment and payment corrections from the selected effective contract addendum are applied independently. Each missing correction side falls back to its base schedule. The source label identifies mixed sources; a correction replaces its corresponding schedule rather than adding to it. Older addenda are not searched separately for each side.

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
- no scheduled milestone monitoring or overdue notifications (dashboard background tasks and the welcome notification are implemented)
- report logic is implemented directly in report beans rather than in reusable domain services

Remaining work is tracked in [GitHub Issues](https://github.com/vfadeevGit/fin-assist/issues), including payment-date recalculation, duplicate-correction UX, related-record navigation, affected-project summaries, addendum reporting, and contract date validation.

## Running Locally

Install JDK 21 and provide a running PostgreSQL database. The default datasource in `src/main/resources/application.properties` points to `jdbc:postgresql://localhost/finassist`, using username `postgres` and password `admin`. Create the database before startup; Liquibase applies the schema changelogs automatically.

Run with the included Gradle wrapper:

```bash
./gradlew bootRun
```

The `bootRun` task activates the `dev` profile. Open **http://localhost:8090**; automatic browser launch is disabled. The default development login is `admin` / `admin`.

To use a different local database, set `MAIN_DATASOURCE_URL`, `MAIN_DATASOURCE_USERNAME`, and `MAIN_DATASOURCE_PASSWORD` in the process environment before running the command.

### Runtime Profiles

| Setting | Local development | Production container |
| --- | --- | --- |
| Spring profile | `dev` through `bootRun` | `prod` through Docker Compose |
| Application port | `8090` | `8080`, matching the Nginx upstream |
| Database configuration | `main.datasource.*`, overridable with `MAIN_DATASOURCE_*` | `DB_VENDOR`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` |
| File storage | Jmix default unless overridden | `/storage`, backed by a Docker volume |

Production overrides are in `src/main/resources/application-prod.properties`. The Compose configuration supplies the database variables and exposes the application through Nginx on ports 80/443.

## Testing And Production Build

The test suite includes JUnit tests, Spring Boot integration tests, and Jmix UI tests. Integration and UI tests require PostgreSQL; there is no separate test datasource configuration in the repository. Point tests at a dedicated database, for example:

```bash
export MAIN_DATASOURCE_URL=jdbc:postgresql://localhost:5432/finassist_test
export MAIN_DATASOURCE_USERNAME=postgres
export MAIN_DATASOURCE_PASSWORD='<test-database-password>'
./gradlew test
```

Create `finassist_test` first. Liquibase initializes its schema. Test reports are written to `build/reports/tests/test/index.html`.

Run an individual test class:

```bash
./gradlew test --tests 'ru.stnovator.finassist.report.ListOfContractsTest'
```

Deployment-script tests require Python 3:

```bash
python3 -m unittest discover -s scripts/tests -v
```

Build the application with the production frontend bundle:

```bash
./gradlew bootJar -Pvaadin.productionMode=true
```

The resulting JAR is under `build/libs/`. The multi-stage [Dockerfile](Dockerfile) builds the production bundle with Java 21 and runs the application as a non-root user.

## CI/CD And Deployment

The [CI and Docker delivery workflow](.github/workflows/delivery.yml) runs on pull requests, pushes to `main`, and manual dispatches:

| Trigger | Tests | Docker image | Production deployment |
| --- | --- | --- | --- |
| Pull request | Deployment scripts and application, with PostgreSQL 16 | Build only | No |
| Push to `main` | Same checks | Build and publish | No |
| Manual run on `main` | Same checks | Build and publish | Only with `deploy=true` |

Images are published to `vfadeevdocker/fin-assist` with the tag `sha-<commit SHA>`. Production deployment uses the published image digest. Manual runs also accept `no_cache=true` to rebuild Docker layers without the build cache; both manual inputs default to `false`.

To deploy from GitHub, open **Actions → CI and Docker delivery → Run workflow**, select `main`, and enable `deploy`. The workflow uses the `production` environment and the restricted SSH deployment entrypoint. It updates the application container, reloads Nginx, and verifies `/login` through local and public HTTPS checks. Deployment preserves database and storage volumes; a failed health check does not automatically roll back the database.

Required GitHub secrets:

- `DOCKERHUB_TOKEN` for image publication
- `PRODUCTION_SSH_KEY` and `PRODUCTION_KNOWN_HOSTS` for deployment

Optional variables are `DOCKERHUB_USERNAME`, `DEPLOY_HOST`, and `DEPLOY_PORT`. The workflow defines their defaults. A separate [Check Docker Hub access workflow](.github/workflows/registry-check.yml) verifies registry authentication and access to an existing image without publishing or deploying.

Production URL: **https://fin-assist.st-novator.ru**.

See [docker/DEPLOY.md](docker/DEPLOY.md) for server and certificate setup, [docker/docker-compose.yml](docker/docker-compose.yml) for runtime services, and [scripts/install-production-deploy.sh](scripts/install-production-deploy.sh) for installation of the restricted deployment entrypoint. The delivery workflow is the source of truth for automated release triggers and image tags.
