# FinAssist — Contract Cash Flow Planning System

## Overview

**FinAssist** is an enterprise financial planning application designed for construction companies.  
The system supports **cash flow planning and control** across customers, contracts, projects, and business lines.

The primary goal of FinAssist is to provide financial directors with a **single source of truth** for:
- planned vs actual execution of construction contracts,
- cash inflows and outflows over time,
- deviations, deficits, and delays.

The system is intended to be built as a **Jmix platform-based enterprise application** with a strong domain model and extensible business logic.

---

## Business Scope

FinAssist covers the following business areas:

- Contract-based financial planning
- Project-level execution tracking
- Calendar-based cash flow analysis
- Contract change management via addenda
- Automated control of missed planned events
- Analytical reporting by date, customer, contract, project, and line of business
- All shipment and payment amounts are stored and processed in a single currency - Russian Rouble

---

## User Role

### Financial Director
The only required role for the MVP.

Responsibilities:
- maintaining contracts and projects;
- defining planned shipment and payment schedules;
- registering actual shipments and payments;
- managing contract addenda and schedule corrections;
- analyzing cash flow reports.

---

## Core Domain Model

### Customer
Represents a client (customer) with whom contracts are signed.

- A Customer can have multiple Contracts.
- A Contract belongs to exactly one Customer.

---

### Contract
Represents a construction contract.

Attributes (high level):
- contract number and date
- customer
- start and end dates
- total contract amount
- status

Relationships:
- Contract 1 → N Projects
- Contract 1 → N Addenda

---

### LineOfBusiness
Reference entity describing a business direction.

Examples (not fixed):
- General Contracting
- Subcontracting
- Design Works
- Maintenance

Relationships:
- LineOfBusiness 1 → N Projects
- Project N → 1 LineOfBusiness

---

### Project
Represents a project executed within a contract.

Important rules:
- Every Project belongs to exactly one Contract.
- Every Project belongs to exactly one LineOfBusiness.

Relationships:
- Project → Shipment Schedule
- Project → Payment Schedule
- Project → Shipment Facts
- Project → Payment Facts
- Project → Schedule Corrections (via Addenda)

---

## Planning Model (Aggregates)

### ShipmentSchedule
Represents the **planned schedule of construction works**.

This is an aggregate root.

- One ShipmentSchedule exists per Project.
- The schedule contains multiple positions (date + amount).

Structure:
- ShipmentSchedule (header)
- ShipmentScheduleItem (1 → N)

ShipmentScheduleItem:
- shipment date
- planned shipment amount in currency

---

### PaymentSchedule
Represents the **planned payment schedule**.

This is an aggregate root.

- One PaymentSchedule exists per Project.
- The schedule contains multiple positions (date + amount).

Structure:
- PaymentSchedule (header)
- PaymentScheduleItem (1 → N)

PaymentSchedule supports automatic calculation.

Initial supported algorithm:
- **Deferred payment**
    - payment date = shipment date + N days
    - payment amount = shipment amount

---

## Actual Data

### ShipmentFact
Represents an actual performed shipment (construction work execution).

Attributes:
- contract
- project
- shipment date
- actual amount in currency

---

### PaymentFact
Represents an actual received payment.

Attributes:
- contract
- project
- payment date
- actual amount in currency

---

## Contract Changes

### Addendum
Represents a contract addendum.

Rules:
- An Addendum belongs to a Contract.
- Customer is derived from the Contract.
- Addendum has an effective date (date of signing).

---

### Schedule Corrections

Addenda introduce **schedule corrections**.

Important business constraints:
- For each Addendum and each Project:
    - at most **one shipment schedule correction**
    - at most **one payment schedule correction**

Corrections are modeled as **full replacement schedules**, effective from the addendum date.

#### ShipmentScheduleCorrection
- linked to Addendum and Project
- contains corrected shipment schedule items (date + amount in currency)

#### PaymentScheduleCorrection
- linked to Addendum and Project
- contains corrected payment schedule items (date + amount in currency)

---

## Planning Rules (MVP)

When calculating planned cash flow for a given date:

- if date < addendum effective date → use base schedule
- if date ≥ addendum effective date and correction exists → use correction
- if no correction exists → use base schedule

---

## Functional Requirements (User Stories Summary)

1. Financial Director defines shipment and payment schedules per project.
2. Financial Director registers actual shipments.
3. Financial Director registers actual payments.
4. Financial Director creates addenda and corrects schedules.
5. System automatically notifies stakeholders if planned shipments are missed.
6. Financial Director generates reports with daily cash flow:
    - planned shipments
    - planned payments
    - actual shipments
    - actual payments
    - deviations and deficits

---

## Reporting Requirements

Reports must support:
- filtering by date range
- grouping by:
    - customer
    - contract
    - project
    - line of business
- daily calendar view
- plan vs fact comparison
- deviation calculation

---

## Non-Functional Requirements

- Strong domain model
- Clear aggregate boundaries
- Extensible calculation logic
- Auditability of plan changes
- Enterprise-grade security model
- Suitable for further BPM / approval workflows

---

## Expected Outcome

FinAssist provides financial directors with:
- transparent contract execution tracking,
- early detection of cash flow gaps,
- reliable financial planning across projects and contracts.

The system must be extensible for future algorithms, workflows, and integrations.

